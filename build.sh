#!/usr/bin/env bash
# 注意：这条链路只编译 Java 代码，界面迁到 Compose 之后它已经打不出能用的包了。
# 现在请用 build-compose.sh（README 的「编译」一节有说明）。保留此文件只作历史参考。
# 构建桌面宠物 APK —— 不用 Gradle：aapt(v1) + javac + d8 + zipalign + apksigner
#
# 需要：
#   JDK 17+            (javac / keytool / java 在 PATH 里)
#   Android SDK        platform-34 的 android.jar + build-tools（提供 d8 / apksigner）
# 环境变量：
#   ANDROID_SDK        默认 ~/android-sdk
#   BUILD_TOOLS        默认 $ANDROID_SDK/build-tools/34.0.0
#   KS                 签名库，默认 ./pet.keystore（不存在会自动生成）
#   KSPASS             签名口令，默认 petpet123（**发布前请改**）
#
# 注意：Google 官方 build-tools 的 aapt / zipalign 是 x86_64 二进制，
#      在 arm64 Linux（比如手机上的 proot）里跑不起来，改用它发行版的：
#        Debian/Ubuntu: apt install aapt zipalign apksigner
#      此时 aapt/zipalign 从 PATH 取，d8/apksigner 仍可用 build-tools 里的。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP="$ROOT/app"
SDK="${ANDROID_SDK:-$HOME/android-sdk}"
BT="${BUILD_TOOLS:-$SDK/build-tools/34.0.0}"
AJAR="$SDK/platforms/android-34/android.jar"
D8="$BT/d8"
OUT="$ROOT/out"
KS="${KS:-$ROOT/pet.keystore}"
KSPASS="${KSPASS:-petpet123}"
APK_OUT="${APK_OUT:-$ROOT/pet.apk}"

[ -f "$AJAR" ] || { echo "缺少 android.jar: $AJAR"; exit 1; }

# aapt / zipalign：PATH 里有（发行版装的 arm64 版本）就用 PATH 的，
# 否则退回 build-tools 里的（x86_64 机器，比如 CI）
if [ -z "${AAPT:-}" ]; then
  if command -v aapt >/dev/null 2>&1; then AAPT=aapt; else AAPT="$BT/aapt"; fi
fi
if [ -z "${ZIPALIGN:-}" ]; then
  if command -v zipalign >/dev/null 2>&1; then ZIPALIGN=zipalign; else ZIPALIGN="$BT/zipalign"; fi
fi
if [ -z "${APKSIGNER:-}" ]; then
  if command -v apksigner >/dev/null 2>&1; then APKSIGNER=apksigner; else APKSIGNER="$BT/apksigner"; fi
fi
[ -x "$D8" ] || { echo "缺少 d8: $D8"; exit 1; }
[ -d "$APP/assets/frames" ] || { echo "缺少帧素材：先跑 tools/extract.py（见 README）"; exit 1; }

rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex"

# 可选：VERSION_CODE / VERSION_NAME 覆盖清单里的版本号（CI 用 tag 注入）
MANIFEST="$APP/AndroidManifest.xml"
if [ -n "${VERSION_NAME:-}" ] || [ -n "${VERSION_CODE:-}" ]; then
  # 只给了其中一个时，另一个沿用清单里的值，别把版本号改成 1
  CUR_CODE=$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' "$APP/AndroidManifest.xml" | head -1)
  CUR_NAME=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$APP/AndroidManifest.xml" | head -1)
  MANIFEST="$OUT/AndroidManifest.xml"
  sed -e "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"${VERSION_CODE:-$CUR_CODE}\"/" \
      -e "s/android:versionName=\"[^\"]*\"/android:versionName=\"${VERSION_NAME:-$CUR_NAME}\"/" \
      "$APP/AndroidManifest.xml" > "$MANIFEST"
fi

echo "== aapt package (manifest + res + assets)"
"$AAPT" package -f \
  -M "$MANIFEST" \
  -S "$APP/res" \
  -A "$APP/assets" \
  -I "$AJAR" \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  -F "$OUT/base.apk"

echo "== javac"
find "$APP/src" -name '*.java' > "$OUT/sources.txt"
javac -source 8 -target 8 -nowarn -bootclasspath "$AJAR" -d "$OUT/classes" @"$OUT/sources.txt"

echo "== d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$D8" --lib "$AJAR" --release --min-api 26 --output "$OUT/dex" @"$OUT/classes.txt"
test -s "$OUT/dex/classes.dex" || { echo "FATAL: classes.dex 没生成（多半是 javac 静默失败了）"; exit 1; }

echo "== 打包 classes.dex"
( cd "$OUT/dex" && "$AAPT" add -f "$OUT/base.apk" classes.dex )
# 注意：不能用 `unzip -l | grep -q`——grep -q 提前退出会让 unzip 吃 SIGPIPE，
# 在 set -o pipefail 下这条管道会被误判为失败。
unzip -l "$OUT/base.apk" > "$OUT/list.txt"
grep -q classes.dex "$OUT/list.txt" || { echo "FATAL: dex 没进 APK"; exit 1; }

echo "== zipalign"
"$ZIPALIGN" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "== 签名"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias pet -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KSPASS" -keypass "$KSPASS" -dname "CN=dsh-pet"
fi
"$APKSIGNER" sign --ks "$KS" --ks-pass "pass:$KSPASS" --key-pass "pass:$KSPASS" \
  --out "$APK_OUT" "$OUT/aligned.apk"
"$APKSIGNER" verify --print-certs "$APK_OUT" | head -3
ls -l "$APK_OUT"
