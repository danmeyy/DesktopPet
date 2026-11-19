#!/usr/bin/env python3
# 把仓库里的 plugin/ 打成 APK 要带的那只 zip：app/assets/pet-brain-plugin.zip
# 解压后是「下载」目录里的样子：
#   pet-brain-plugin/README-安装说明.md
#   pet-brain-plugin/plugin/{package.json,cordis.patch.yml,lib/index.js}
# 改完 plugin/ 记得跑一次，否则 APK 里带的还是旧插件。
import os
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, ".."))
PLUGIN = os.path.join(ROOT, "plugin")
DOC = os.path.join(PLUGIN, "README-安装说明.md")
OUT = os.environ.get("OUT", os.path.join(ROOT, "app", "assets", "pet-brain-plugin.zip"))

if not os.path.isdir(PLUGIN):
    sys.exit("缺少 plugin/ 目录：%s" % PLUGIN)

os.makedirs(os.path.dirname(OUT), exist_ok=True)

entries = []
if os.path.isfile(DOC):
    entries.append(("pet-brain-plugin/README-安装说明.md", DOC))
for base, dirs, files in os.walk(PLUGIN):
    dirs.sort()
    for name in sorted(files):
        full = os.path.join(base, name)
        if full == DOC:          # 安装说明放在压缩包根目录，不塞进 plugin/
            continue
        rel = os.path.relpath(full, PLUGIN).replace(os.sep, "/")
        entries.append(("pet-brain-plugin/plugin/" + rel, full))

with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
    for arcname, full in entries:
        # 固定时间戳 + 固定权限：同样的源码在任何机器上打出的 zip 逐字节相同，
        # 不然 CI 每次 checkout 后 mtime 都变，仓库里这个 zip 永远显示「已修改」。
        info = zipfile.ZipInfo(arcname, date_time=(2020, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o644 << 16
        with open(full, "rb") as fh:
            z.writestr(info, fh.read())

count = len(entries)

print("wrote %s（%d 个文件，%d 字节）" % (OUT, count, os.path.getsize(OUT)))
