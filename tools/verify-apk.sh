#!/usr/bin/env bash
# 比较两个 APK 的内容是否等价：文件清单 + 每个条目的 sha256（忽略 META-INF 里的签名块），
# 再比对签名证书。用来确认「CI 构建出来的」和「手上那份」是不是同一份东西。
# 用法：bash tools/verify-apk.sh A.apk B.apk
set -u
A=${1:?第一个 APK}
B=${2:?第二个 APK}
TA=$(mktemp -d); TB=$(mktemp -d); trap 'rm -rf "$TA" "$TB"' EXIT
unzip -q "$A" -d "$TA" || { echo "解不开：$A" >&2; exit 2; }
unzip -q "$B" -d "$TB" || { echo "解不开：$B" >&2; exit 2; }

sums() { # $1=目录  输出「sha256 路径」，跳过签名块
  ( cd "$1" && find . -type f ! -path './META-INF/*' -printf '%P\0' \
      | sort -z | xargs -0 -r sha256sum )
}
sums "$TA" > "$TA.list"
sums "$TB" > "$TB.list"
NA=$(wc -l < "$TA.list"); NB=$(wc -l < "$TB.list")

echo "文件数：$NA vs $NB"
if ! diff -q "$TA.list" "$TB.list" >/dev/null; then
  echo "内容不一致，差异（左=第一个，右=第二个）："
  diff <(sort -k2 "$TA.list") <(sort -k2 "$TB.list") | head -40
  exit 1
fi
echo "内容一致：$NA 个条目逐个 sha256 相同"

certs() {
  local f=$1
  if command -v apksigner >/dev/null 2>&1; then
    apksigner verify --print-certs "$f" 2>/dev/null | grep -i "SHA-256 digest" | head -1 | awk '{print $NF}'
  elif [ -n "${ANDROID_SDK:-}" ] && [ -x "$ANDROID_SDK/build-tools/34.0.0/apksigner" ]; then
    "$ANDROID_SDK/build-tools/34.0.0/apksigner" verify --print-certs "$f" 2>/dev/null | grep -i "SHA-256 digest" | head -1 | awk '{print $NF}'
  fi
}
CA=$(certs "$A"); CB=$(certs "$B")
if [ -n "${CA:-}" ] && [ -n "${CB:-}" ]; then
  echo "签名证书：$CA vs $CB"
  [ "$CA" = "$CB" ] || { echo "签名不同——不能覆盖安装，需要卸载或换成同一把钥匙" >&2; exit 3; }
else
  echo "签名证书：没找到 apksigner，跳过比对"
fi
echo "OK：两份 APK 内容与签名一致"
