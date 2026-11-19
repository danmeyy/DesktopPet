#!/usr/bin/env bash
# 生成 app/assets/{frames,index.json,crop.json}。
#
# 帧素材来自 npm 上的 dsh-pet 包（本来是这个 App 依赖的动作资产，版权归其作者，
# 所以不随仓库分发）。没有它们 App 也能编译、能装，只是桌面上不会动。
# 想要完整效果就先跑这个脚本，再跑 build-compose.sh。
#
# 依赖：npm（或 node）、ffmpeg、ffprobe、python3
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/.." && pwd)
WORK=${WORK:-$ROOT/.cache/frames}
PKG=${DSH_PET_VERSION:-0.4.5}

command -v ffmpeg >/dev/null || { echo "!! 需要 ffmpeg"; exit 1; }
command -v ffprobe >/dev/null || { echo "!! 需要 ffprobe"; exit 1; }

mkdir -p "$WORK"
if [ ! -d "$WORK/package/assets/webm" ]; then
  echo "== 下载 dsh-pet@$PKG =="
  ( cd "$WORK" && npm pack "dsh-pet@$PKG" >/dev/null && tar xzf dsh-pet-*.tgz )
fi

echo "== 转帧（约 8800 张 webp，几分钟） =="
SRC="$WORK/package/assets/webm" \
OUT="$ROOT/app/assets/frames" \
  python3 "$ROOT/tools/extract.py" | tail -3

echo "== 完成 =="
du -sh "$ROOT/app/assets/frames"
