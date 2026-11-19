#!/usr/bin/env python3
"""把依赖 jar 合并成一个 fat jar，重复的 class 只保留先出现的那个。

依赖图里同时存在 collection-jvm 1.4.0 与 collection-ktx 1.2.0 这类重叠，
d8 直接喂会报 "defined multiple times"。
"""
import os
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.environ.get("JARS", os.path.join(HERE, "jars"))
OUT = os.environ.get("FAT_OUT", os.path.join(HERE, "libs-fat.jar"))
SKIP_DIRS = ("META-INF/",)


def main():
    seen = set()
    dups = 0
    files = sorted(f for f in os.listdir(SRC) if f.endswith(".jar"))
    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED, compresslevel=1) as out:
        for name in files:
            with zipfile.ZipFile(os.path.join(SRC, name)) as z:
                for info in z.infolist():
                    if info.is_dir():
                        continue
                    n = info.filename
                    if n.startswith(SKIP_DIRS):
                        continue
                    if not n.endswith(".class"):
                        continue
                    if n in seen:
                        dups += 1
                        continue
                    seen.add(n)
                    out.writestr(n, z.read(n))
    print("fat jar: %d 个 class，丢弃重复 %d 个" % (len(seen), dups))


if __name__ == "__main__":
    sys.exit(main())
