#!/usr/bin/env python3
"""把 libs/ 里的 aar 解开：classes.jar 收进 jars/，res/ 与 R.txt 收进 unpacked/<artifact>/。

后续 merge_res.py 合并 res、gen_r.py 按 R.txt 生成各库的 R.java，都从这里取。
"""
import os
import shutil
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
LIBS = os.environ.get("LIBS", os.path.join(HERE, "libs"))
JARS = os.environ.get("JARS", os.path.join(HERE, "jars"))
UNPACKED = os.environ.get("UNPACKED", os.path.join(HERE, "unpacked"))


def main():
    os.makedirs(JARS, exist_ok=True)
    os.makedirs(UNPACKED, exist_ok=True)
    aars = jars = 0
    for name in sorted(os.listdir(LIBS)):
        path = os.path.join(LIBS, name)
        if name.endswith(".jar"):
            shutil.copy2(path, os.path.join(JARS, name))
            jars += 1
            continue
        if not name.endswith(".aar"):
            continue
        stem = name[:-4]
        dst = os.path.join(UNPACKED, stem)
        os.makedirs(dst, exist_ok=True)
        with zipfile.ZipFile(path) as z:
            names = z.namelist()
            if "classes.jar" in names:
                with z.open("classes.jar") as src, open(os.path.join(JARS, stem + ".jar"), "wb") as out:
                    shutil.copyfileobj(src, out)
            for n in names:
                if n == "classes.jar" or n.endswith("/"):
                    continue
                if n.startswith("res/") or n == "R.txt" or n == "AndroidManifest.xml":
                    z.extract(n, dst)
        aars += 1
    print("解包 %d 个 aar、复制 %d 个 jar → %s / %s" % (aars, jars, JARS, UNPACKED))
    return 0


if __name__ == "__main__":
    sys.exit(main())
