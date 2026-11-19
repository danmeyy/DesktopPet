#!/usr/bin/env bash
# 把所有「构建需要、但不在仓库里」的东西下到 compose/ 下面：
#   libs/     Compose 运行时与 AndroidX（resolve.py 从 Maven 拉）
#   jars/     aar 里的 classes.jar（unpack.py）
#   unpacked/ aar 里的 res 与 R.txt（unpack.py）
#   *.jar     kotlinc / Compose 编译器插件 / R8 / trove4j
# 已经有就不重复下（CI 里靠 cache 命中）。
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
COMPOSE="$HERE"
GOOGLE=https://dl.google.com/dl/android/maven2
CENTRAL=https://repo1.maven.org/maven2
KOTLIN=2.0.21

get() { # get <url> <dest>
  [ -s "$2" ] && { echo "   已有 $(basename "$2")"; return; }
  echo "   下载 $(basename "$2")"
  curl -fsSL --retry 3 -o "$2.part" "$1"
  mv "$2.part" "$2"
}

echo "== libs / jars / unpacked =="
python3 "$COMPOSE/resolve.py"
LIBS="$COMPOSE/libs" JARS="$COMPOSE/jars" UNPACKED="$COMPOSE/unpacked" python3 "$COMPOSE/unpack.py"

echo "== 编译器与工具 =="
get "$CENTRAL/org/jetbrains/kotlin/kotlin-compiler-embeddable/$KOTLIN/kotlin-compiler-embeddable-$KOTLIN.jar" \
    "$COMPOSE/kc-embeddable.jar"
get "$CENTRAL/org/jetbrains/kotlin/kotlin-stdlib/$KOTLIN/kotlin-stdlib-$KOTLIN.jar" \
    "$COMPOSE/kotlin-stdlib.jar"
get "$CENTRAL/org/jetbrains/kotlin/kotlin-compose-compiler-plugin-embeddable/$KOTLIN/kotlin-compose-compiler-plugin-embeddable-$KOTLIN.jar" \
    "$COMPOSE/compose-plugin.jar"
get "$CENTRAL/org/jetbrains/kotlin/kotlin-daemon-embeddable/$KOTLIN/kotlin-daemon-embeddable-$KOTLIN.jar" \
    "$COMPOSE/kotlin-daemon.jar"
get "$GOOGLE/com/android/tools/r8/8.7.18/r8-8.7.18.jar" "$COMPOSE/r8.jar"
get "$CENTRAL/org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar" \
    "$COMPOSE/trove4j.jar"
get "$CENTRAL/org/jetbrains/annotations/24.0.1/annotations-24.0.1.jar" "$COMPOSE/annotations.jar"

echo "   完成：$(du -sh "$COMPOSE/libs" | cut -f1) libs、$(ls "$COMPOSE"/jars | wc -l) 个 jar"
