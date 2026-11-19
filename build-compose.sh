#!/usr/bin/env bash
# 桌面宠物的实际构建链路：不做 Gradle，自己拉 AAR、合并资源、生成 R.java，
# 再 aapt(v1) + javac/kotlinc + d8 + zipalign + apksigner 一步步打出来。
#
# 两种用法：
#   1. 本机增量：跑过一次之后只走「javac/kotlinc + app.dex + 重签」，约 2–3 分钟；
#   2. CI / 第一次：先跑 compose/fetch-tools.sh 把依赖与编译器下好，再跑本脚本。
#
# 可以覆盖的环境变量：
#   ANDROID_SDK(或 ANDROID_HOME)  javac 要的 android.jar 与 aapt/zipalign/apksigner
#   COMPOSE                       依赖与工具目录（默认仓库里的 compose/）
#   OUT / CACHE                   中间产物与缓存目录（默认仓库内 out-compose / .cache）
#   KS / KSPASS                   签名密钥与其口令（CI 里由 secret 解码得到）
#   FULL=1                        忽略两级缓存，全量重打
#   SINGLE=0                      打两个 dex（默认 1：合成一个多 dex 包）
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
APP=$HERE/app
COMPOSE=${COMPOSE:-$HERE/compose}
OUT=${OUT:-$HERE/out-compose}
CACHE=${CACHE:-$HERE/.cache}
KS=${KS:-$HERE/build/pet.keystore}
KSPASS=${KSPASS:-petpet123}

# —— 找 Android SDK ——
SDK=${ANDROID_SDK:-${ANDROID_HOME:-}}
if [ -z "$SDK" ]; then
  for d in "$HOME/Android/Sdk" "$HOME/android-sdk" /usr/lib/android-sdk; do
    [ -d "$d" ] && { SDK=$d; break; }
  done
fi
[ -n "$SDK" ] || { echo "!! 找不到 Android SDK：设 ANDROID_SDK，或装 build-tools + platforms/android-34"; exit 1; }
ANDROID_SDK=$SDK
AJAR=${AJAR:-$SDK/platforms/android-34/android.jar}
[ -f "$AJAR" ] || { echo "!! 缺 $AJAR（需要 platforms;android-34）"; exit 1; }

BT=${BUILD_TOOLS:-$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)}
AAPT=${AAPT:-$( [ -n "$BT" ] && [ -x "$BT/aapt" ] && echo "$BT/aapt" || command -v aapt || true )}
ZIPALIGN=${ZIPALIGN:-$( [ -n "$BT" ] && [ -x "$BT/zipalign" ] && echo "$BT/zipalign" || command -v zipalign || true )}
APKSIGNER=${APKSIGNER:-$( [ -n "$BT" ] && [ -x "$BT/apksigner" ] && echo "$BT/apksigner" || command -v apksigner || true )}
JAVA_BIN=${JAVA_HOME:+$JAVA_HOME/bin/}java
JAVAC_BIN=${JAVA_HOME:+$JAVA_HOME/bin/}javac
JAR_BIN=${JAVA_HOME:+$JAVA_HOME/bin/}jar
for t in "$AAPT" "$ZIPALIGN" "$APKSIGNER"; do
  [ -n "$t" ] && [ -x "$t" ] || { echo "!! 缺工具：aapt/zipalign/apksigner 不在 build-tools 也不在 PATH"; exit 1; }
done

D8_JAR=${D8_JAR:-$COMPOSE/r8.jar}
CORO=$(ls "$COMPOSE"/jars/kotlinx-coroutines-core-jvm-*.jar 2>/dev/null | head -1)
KC="$COMPOSE/kc-embeddable.jar:$COMPOSE/kotlin-stdlib.jar:${CORO:-}:$COMPOSE/trove4j.jar:$COMPOSE/annotations.jar"
for f in "$D8_JAR" "$COMPOSE/kc-embeddable.jar" "$COMPOSE/compose-plugin.jar"; do
  [ -f "$f" ] || { echo "!! 缺 $f，先跑 compose/fetch-tools.sh"; exit 1; }
done

VERSION=$(grep -o 'android:versionName="[^"]*"' "$APP/AndroidManifest.xml" | head -1 | cut -d'"' -f2)
PKG=${PKG:-pet-${VERSION}-compose.apk}
mkdir -p "$OUT" "$CACHE" "$HERE/build"
T0=$(date +%s)

# 有帧却没有动作索引：装上去宠物不会动，宁可在构建时就停住
if [ -d app/assets/frames ] && [ -n "$(ls -A app/assets/frames 2>/dev/null)" ] && [ ! -f app/assets/index.json ]; then
  echo "!! 有 frames 但缺 app/assets/index.json（动作索引），这样打出来的包装上宠物不会动" >&2
  echo "   先跑：bash tools/fetch-frames.sh（或把 index.json 一起拷回来）" >&2
  exit 1
fi

echo "== 1/7 资源合并 =="
LIBS="$COMPOSE/libs" JARS="$COMPOSE/jars" UNPACKED="$COMPOSE/unpacked" \
  RES_OUT="$COMPOSE/res-app" APP_RES="$APP/res" python3 "$COMPOSE/merge_res.py" >/dev/null
RES_HASH=$(cd "$COMPOSE/res-app" && find . -type f -print0 | sort -z | xargs -0 md5sum | md5sum | cut -d' ' -f1)
ASSETS_SIG=$(find "$APP/assets" -type f | wc -l)$(du -sb "$APP/assets" | cut -f1)
MANIFEST_HASH=$(md5sum "$APP/AndroidManifest.xml" | cut -d' ' -f1)
BASE_KEY=$(printf '%s|%s|%s' "$RES_HASH" "$ASSETS_SIG" "$MANIFEST_HASH" | md5sum | cut -d' ' -f1)
BASE_APK="$CACHE/base-$BASE_KEY.apk"
R_JAVA_CACHE="$CACHE/rjava-$BASE_KEY"
if [ -f "$BASE_APK" ] && [ -d "$R_JAVA_CACHE" ] && [ "${FULL:-0}" != "1" ]; then
  echo "   资源包命中缓存 $BASE_KEY（跳过 aapt）"
  cp "$BASE_APK" "$OUT/base.apk"
  rm -rf "$OUT/rjava"
  cp -r "$R_JAVA_CACHE" "$OUT/rjava"
else
  echo "   资源包新建 $BASE_KEY"
  rm -rf "$OUT/rjava"
  mkdir -p "$OUT/rjava"
  # -0 webp/png/jpg：帧图本来就是压缩格式，再 deflate 一遍纯属浪费时间（实测 240s → 89s）
  "$AAPT" package -f -M "$APP/AndroidManifest.xml" -S "$COMPOSE/res-app" -A "$APP/assets" \
      -I "$AJAR" --min-sdk-version 26 --target-sdk-version 34 \
      -0 webp -0 png -0 jpg \
      -F "$OUT/base.apk" -J "$OUT/rjava" >/dev/null
  cp "$OUT/base.apk" "$BASE_APK"
  rm -rf "$R_JAVA_CACHE"
  cp -r "$OUT/rjava" "$R_JAVA_CACHE"
fi

echo "== 2/7 生成各库 R.java =="
APP_R="$OUT/rjava/R.java" UNPACKED="$COMPOSE/unpacked" R_OUT="$COMPOSE/rjava" \
  python3 "$COMPOSE/gen_r.py" | tail -1
R_SRCS=$(find "$COMPOSE/rjava" -name "R.java" | tr '\n' ' ')

echo "== 3/7 依赖 fat jar =="
LIBS_KEY=$(cd "$COMPOSE/jars" && md5sum *.jar | md5sum | cut -d' ' -f1)
LIBS_CACHE="$CACHE/libs-$LIBS_KEY.jar"
if [ -f "$LIBS_CACHE" ] && [ "${FULL:-0}" != "1" ]; then
  cp "$LIBS_CACHE" "$OUT/libs-fat.jar"
  echo "   依赖 jar 命中缓存"
else
  JARS="$COMPOSE/jars" FAT_OUT="$OUT/libs-fat.jar" python3 "$COMPOSE/fatjar.py" | tail -1
  cp "$OUT/libs-fat.jar" "$LIBS_CACHE"
fi
JARS=$(ls "$COMPOSE"/jars/*.jar | tr '\n' ':')

echo "== 4/7 javac =="
mkdir -p "$OUT/classes" "$OUT/kt" "$OUT/d8app"
rm -rf "$OUT/d8app"; mkdir -p "$OUT/d8app"
find "$APP/src" -name "*.java" > "$OUT/java.list"
JAVA_KEY=$( { find "$APP/src" -name '*.java' -print0 | sort -z | xargs -0 md5sum
              find "$COMPOSE/rjava" -name 'R.java' -print0 | sort -z | xargs -0 md5sum
              echo "$BASE_KEY"; } | md5sum | cut -d' ' -f1 )
if [ -f "$OUT/javac.key" ] && [ "$(cat "$OUT/javac.key")" = "$JAVA_KEY" ] \
   && [ -n "$(find "$OUT/classes" -name '*.class' -print -quit)" ] && [ "${FULL:-0}" != "1" ]; then
  echo "   Java 源码没变，跳过 javac"
else
  rm -rf "$OUT/classes"; mkdir -p "$OUT/classes"
  if ! "$JAVAC_BIN" -nowarn -source 8 -target 8 -bootclasspath "$AJAR" -classpath "$JARS" \
      -d "$OUT/classes" @"$OUT/java.list" "$OUT/rjava/R.java" $R_SRCS > "$OUT/javac.log" 2>&1; then
    echo "!! javac 失败"; grep -E "error:" "$OUT/javac.log" | head -15; exit 1
  fi
  printf '%s' "$JAVA_KEY" > "$OUT/javac.key"
fi

echo "== 5/7 kotlinc（Compose） =="
find "$APP/src" -name "*.kt" > "$OUT/kt.list"
if [ -s "$OUT/kt.list" ]; then
  KT_KEY=$(find "$APP/src" -name '*.kt' -print0 | sort -z | xargs -0 md5sum | md5sum | cut -d' ' -f1)
  if [ -f "$OUT/kt.key" ] && [ "$(cat "$OUT/kt.key")" = "$KT_KEY" ] \
     && [ -n "$(find "$OUT/kt" -name '*.class' -print -quit)" ] && [ "${FULL:-0}" != "1" ]; then
    echo "   Kotlin 源码没变，跳过 kotlinc"
  else
    rm -rf "$OUT/kt"; mkdir -p "$OUT/kt"
    if ! "$JAVA_BIN" -Xmx3g -cp "$KC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
        -no-stdlib -jvm-target 1.8 -Xplugin="$COMPOSE/compose-plugin.jar" \
        -classpath "$OUT/classes:$COMPOSE/kotlin-stdlib.jar:$JARS$AJAR" \
        -d "$OUT/kt" @"$OUT/kt.list" > "$OUT/kotlinc.log" 2>&1; then
      echo "!! kotlinc 失败"; grep -E "error:" "$OUT/kotlinc.log" | head -15; exit 1
    fi
    printf '%s' "$KT_KEY" > "$OUT/kt.key"
  fi
fi

echo "== 6/7 d8 =="
DEXCACHE="$CACHE/libdex-$LIBS_KEY"
if [ -d "$DEXCACHE" ] && [ "${FULL:-0}" != "1" ]; then
  echo "   依赖 dex 命中缓存（$(ls "$DEXCACHE"/classes*.dex | wc -l) 个）"
else
  echo "   依赖 dex 首次生成（这一步最慢，之后复用）"
  rm -rf "$OUT/d8libs" && mkdir -p "$OUT/d8libs"
  echo "$OUT/libs-fat.jar" > "$OUT/d8libs.list"
  "$JAVA_BIN" -Xmx4g -cp "$D8_JAR" com.android.tools.r8.D8 \
      --lib "$AJAR" --release --min-api 26 --output "$OUT/d8libs" @"$OUT/d8libs.list"
  rm -rf "$DEXCACHE"; mkdir -p "$DEXCACHE"
  cp "$OUT"/d8libs/classes*.dex "$DEXCACHE"/
fi

echo "   打 app 的 dex"
(cd "$OUT/classes" && "$JAR_BIN" cf "$OUT/app-classes.jar" .)
echo "$OUT/app-classes.jar" > "$OUT/d8app.list"
if [ -d "$OUT/kt" ] && [ -n "$(find "$OUT/kt" -name '*.class' -print -quit)" ]; then
  (cd "$OUT/kt" && "$JAR_BIN" cf "$OUT/kt-classes.jar" .)
  echo "$OUT/kt-classes.jar" >> "$OUT/d8app.list"
fi
# 用 .class 内容的哈希当键：jar 里有时间戳，直接哈希 jar 每次都不一样
APP_KEY=$( { find "$OUT/classes" -name '*.class' -print0 | sort -z | xargs -0 md5sum
              if [ -d "$OUT/kt" ]; then find "$OUT/kt" -name '*.class' -print0 | sort -z | xargs -0 md5sum; fi
              echo "$LIBS_KEY"; } | md5sum | cut -d' ' -f1 )
APPDEX_CACHE="$CACHE/appdex-$APP_KEY"
if [ -d "$APPDEX_CACHE" ] && [ "${FULL:-0}" != "1" ]; then
  echo "   app dex 命中缓存"
  cp "$APPDEX_CACHE"/classes*.dex "$OUT/d8app"/
else
  "$JAVA_BIN" -Xmx3g -cp "$D8_JAR" com.android.tools.r8.D8 \
      --lib "$AJAR" --release --min-api 26 --classpath "$OUT/libs-fat.jar" \
      --output "$OUT/d8app" @"$OUT/d8app.list"
  rm -rf "$APPDEX_CACHE"; mkdir -p "$APPDEX_CACHE"
  cp "$OUT"/d8app/classes*.dex "$APPDEX_CACHE"/
fi

# 缓存不要无限长：每种留最新两份（base 包一个就 72MB）
prune() { ls -1dt $1 2>/dev/null | tail -n +3 | while read -r f; do rm -rf "$f"; done; }
prune "$CACHE/base-*.apk"; prune "$CACHE/libdex-*"; prune "$CACHE/appdex-*"

echo "== 7/7 打包签名 =="
rm -rf "$OUT/pack" && mkdir -p "$OUT/pack"
i=0
APP_N=0
for f in "$OUT"/d8app/classes*.dex; do
  i=$((i+1)); APP_N=$((APP_N+1))
  if [ $i -eq 1 ]; then cp "$f" "$OUT/pack/classes.dex"; else cp "$f" "$OUT/pack/classes$i.dex"; fi
done
for f in "$DEXCACHE"/classes*.dex; do
  i=$((i+1))
  if [ $i -eq 1 ]; then cp "$f" "$OUT/pack/classes.dex"; else cp "$f" "$OUT/pack/classes$i.dex"; fi
done
echo "   打进 $i 个 dex（app $APP_N 个在前，依赖 $((i-APP_N)) 个在后）"
# 用 zip 一次性塞进去：aapt add 每加一个 dex 都要重写整个 72MB 的包，5 个 dex 就是 5 遍
( cd "$OUT/pack" && zip -q -1 -X "$OUT/base.apk" classes*.dex )

"$ZIPALIGN" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"
[ -f "$KS" ] || { echo "!! 缺签名密钥 $KS（设 KS 指向 keystore）"; exit 1; }
"$APKSIGNER" sign --ks "$KS" --ks-pass "pass:$KSPASS" --out "$HERE/build/$PKG" "$OUT/aligned.apk"
"$APKSIGNER" verify --print-certs "$HERE/build/$PKG" | head -2
ls -l "$HERE/build/$PKG"
echo "耗时 $(( $(date +%s) - T0 )) 秒"
