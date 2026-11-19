#!/usr/bin/env python3
# 把 dsh-pet 的全部 webm 动作转成 APK 资源：frames/<id>/NNN.webp + meta.txt，另有 index.json
# 只用 ffmpeg（libwebp 编码，保留 alpha），不依赖 Pillow。
import json, os, shutil, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.environ.get("SRC", "")
OUT = os.environ.get("OUT", os.path.normpath(os.path.join(HERE, "..", "app", "assets", "frames")))
WIDTH = os.environ.get("WIDTH", "384")  # 线上素材是 384 宽；改小只会省体积、换清晰度
FPS = os.environ.get("FPS", "8")
QUALITY = os.environ.get("QUALITY", "75")
FF = os.environ.get("FFMPEG", "ffmpeg")
FFPROBE = os.environ.get("FFPROBE", "ffprobe")

if not SRC or not os.path.isdir(SRC):
    raise SystemExit("SRC 指向 dsh-pet 的 assets/webm 目录（见 tools/fetch-frames.sh）")

names = sorted(f[:-5] for f in os.listdir(SRC) if f.endswith(".webm"))
only = sys.argv[1:]
if only:
    names = [n for n in names if n in only]
if os.path.isdir(OUT):
    shutil.rmtree(OUT)
os.makedirs(OUT, exist_ok=True)

index = []
for i, name in enumerate(names):
    aid = "a%03d" % i
    d = os.path.join(OUT, aid)
    os.makedirs(d)
    src = os.path.join(SRC, name + ".webm")
    pix = ""
    try:
        p = subprocess.run([FFPROBE, "-v", "error", "-select_streams", "v:0",
                            "-show_entries", "stream=pix_fmt", "-of", "default=nw=1:nk=1", src],
                           capture_output=True, text=True)
        pix = p.stdout.strip()
    except Exception as e:
        pix = "?"
    cmd = [FF, "-v", "error", "-y", "-c:v", "libvpx-vp9", "-i", src,
           "-vf", "fps=%s,scale=%s:-1:flags=lanczos" % (FPS, WIDTH),
           "-c:v", "libwebp", "-lossless", "0", "-q:v", QUALITY,
           "-pix_fmt", "yuva420p",
           os.path.join(d, "%03d.webp")]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        print("FAIL", name, r.stderr.strip()[:120])
        shutil.rmtree(d, ignore_errors=True)
        continue
    n = len([x for x in os.listdir(d) if x.endswith(".webp")])
    if n == 0:
        print("EMPTY", name)
        shutil.rmtree(d, ignore_errors=True)
        continue
    with open(os.path.join(d, "meta.txt"), "w") as fh:
        fh.write("fps=%s\ncount=%d\n" % (FPS, n))
    size = sum(os.path.getsize(os.path.join(d, x)) for x in os.listdir(d))
    index.append({"id": aid, "name": name, "fps": int(FPS), "count": n, "bytes": size, "pix": pix})
    print("%-5s %-26s %3d f %6.0fKB %s" % (aid, name, n, size / 1024.0, pix))

# 帧内有效区域（实测全部动作在 256px 宽时的 alpha 包围盒 90% 分位），供 App 裁掉透明留白
scale = float(WIDTH) / 256.0
crop = {"x": int(round(64 * scale)), "y": int(round(22 * scale)),
        "w": int(round(125 * scale)), "h": int(round(113 * scale))}
with open(os.path.join(os.path.dirname(os.path.abspath(OUT)), "crop.json"), "w") as fh:
    json.dump(crop, fh)
    fh.write("\n")

with open(os.path.join(os.path.dirname(os.path.abspath(OUT)), "index.json"), "w") as fh:
    json.dump(index, fh, ensure_ascii=False, indent=1)

total = sum(e["bytes"] for e in index)
yuva = sum(1 for e in index if e["pix"] == "yuva420p")
print("TOTAL %d anims, %.1f MB, alpha-stream=%d" % (len(index), total / 1048576.0, yuva))
