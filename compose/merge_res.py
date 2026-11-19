#!/usr/bin/env python3
"""把各 AAR 的 res 合并进 app 的 res 副本。

values*/ 下的 XML 按子节点合并（同名同类型只保留先出现的那个），
其它目录（drawable/color/layout…）按文件名平铺，重名跳过。
"""
import os
import shutil
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
APP_RES = os.environ.get("APP_RES", os.path.join(ROOT, "app", "res"))
UNPACKED = os.environ.get("UNPACKED", os.path.join(HERE, "unpacked"))
OUT = os.environ.get("RES_OUT", os.path.join(HERE, "res-app"))

def key(el):
    return (el.tag, el.get("name"))


# aapt2 才认的标签/写法，aapt v1 会直接报错
DROP_TAGS = {"public", "java-symbol", "overlayable", "staging-public-group",
             "add-resource", "private-symbols", "symbols"}


def normalize(el):
    """把 aapt2 风格的写法改成 aapt v1 能接受的；返回 None 表示丢弃。"""
    if el.tag in DROP_TAGS:
        return None
    if el.tag == "id":
        # <id name="x"/>  →  <item type="id" name="x"/>
        el.tag = "item"
        el.set("type", "id")
    return el

def main():
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    shutil.copytree(APP_RES, OUT)

    seen = {}          # (folder, tag, name) -> True
    values = {}        # folder -> list of elements
    skipped = 0
    copied = 0
    empty_values = 0

    for aar in sorted(os.listdir(UNPACKED)):
        res = os.path.join(UNPACKED, aar, "res")
        if not os.path.isdir(res):
            continue
        for folder in sorted(os.listdir(res)):
            src_dir = os.path.join(res, folder)
            if not os.path.isdir(src_dir):
                continue
            if folder.startswith("values"):
                bucket = values.setdefault(folder, [])
                for name in sorted(os.listdir(src_dir)):
                    if not name.endswith(".xml"):
                        continue
                    try:
                        root = ET.parse(os.path.join(src_dir, name)).getroot()
                    except ET.ParseError as e:
                        print("  跳过无法解析的", src_dir, name, e)
                        continue
                    for child in list(root):
                        if normalize(child) is None:
                            continue
                        k = key(child)
                        if k in seen:
                            continue
                        seen[k] = True
                        bucket.append(child)
            else:
                dst_dir = os.path.join(OUT, folder)
                os.makedirs(dst_dir, exist_ok=True)
                for name in sorted(os.listdir(src_dir)):
                    src = os.path.join(src_dir, name)
                    if not os.path.isfile(src):
                        continue
                    dst = os.path.join(dst_dir, name)
                    if os.path.exists(dst):
                        skipped += 1
                        continue
                    shutil.copy2(src, dst)
                    copied += 1

    for folder, items in values.items():
        dst_dir = os.path.join(OUT, folder)
        os.makedirs(dst_dir, exist_ok=True)
        dst = os.path.join(dst_dir, "libs_merged.xml")
        if not items:
            empty_values += 1
            continue
        with open(dst, "w", encoding="utf-8") as f:
            f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n')
            for el in items:
                f.write(ET.tostring(el, encoding="unicode"))
                f.write("\n")
            f.write("</resources>\n")
        print("  %-14s %5d 项" % (folder, len(items)))

    print("非 values 资源：复制 %d 个，重名跳过 %d 个" % (copied, skipped))
    print("输出：", OUT)

if __name__ == "__main__":
    sys.exit(main())
