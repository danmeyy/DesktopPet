#!/usr/bin/env python3
"""按各 AAR 的 R.txt 生成它自己的 R.java。

符号表来自 aapt 生成的 app R.java（t/rjava/R.java）——同一个资源在最终
资源表里只有一个 id，各库的 R 类必须指向这个 id，否则运行时找不到。
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
APP_R = os.environ.get("APP_R", "")
UNPACKED = os.environ.get("UNPACKED", os.path.join(HERE, "unpacked"))
# classes.jar 由 unpack.py 收在 jars/<artifact>.jar 里，unpacked 只剩 res 与 R.txt；
# 两处都找，避免 unpacked 里没有 classes.jar 时整批 R.java 都生成不出来
JARS = os.environ.get("JARS", os.path.join(HERE, "jars"))
OUT = os.environ.get("R_OUT", os.path.join(HERE, "rjava"))
if not APP_R:
    raise SystemExit("gen_r.py 需要 APP_R 环境变量，指向 aapt 生成的 app R.java")

FIELD = re.compile(r"public static final int (\w+)\s*=\s*(0x[0-9a-fA-F]+);")
CLASS = re.compile(r"public static final class (\w+) \{")


def load_app_table():
    """返回 {(type, name): id} 与 {id: (type, name)}。"""
    by_name = {}
    by_id = {}
    cur = None
    for line in open(APP_R, encoding="utf-8"):
        m = CLASS.search(line)
        if m:
            cur = m.group(1)
            continue
        m = FIELD.search(line)
        if m and cur:
            name, val = m.group(1), int(m.group(2), 16)
            by_name[(cur, name)] = val
            by_id[val] = (cur, name)
    return by_name, by_id


def find_classes_jar(aar_dir, aar):
    """AAR 编译产物：unpacked 里可能没有，按约定在 jars/<artifact>.jar。"""
    local = os.path.join(aar_dir, "classes.jar")
    if os.path.isfile(local):
        return local
    packed = os.path.join(JARS, aar + ".jar")
    return packed if os.path.isfile(packed) else None


def package_from_jar(jar):
    """AAR 的 manifest 里没有 package 了，从 classes.jar 里对自身 R 的引用反推包名。"""
    if not jar or not os.path.isfile(jar):
        return None
    import zipfile, collections
    cand = collections.Counter()
    try:
        with zipfile.ZipFile(jar) as z:
            for n in z.namelist():
                if not n.endswith(".class"):
                    continue
                data = z.read(n)
                for m in re.finditer(rb"([a-z][\w/]{2,60})/R\$(\w+)", data):
                    cand[m.group(1).decode("ascii", "ignore")] += 1
    except Exception:
        return None
    if not cand:
        return None
    prefix, _ = cand.most_common(1)[0]
    if prefix in ("android", "java", "javax"):
        return None
    return prefix.replace("/", ".")


def parse_r_txt(path):
    """返回 [(type, name, id_or_None)]，数组额外返回依赖。"""
    simple = []
    arrays = []            # (name, [hex...])
    array_children = {}    # name -> [(suffix, hex)]
    for line in open(path, encoding="utf-8", errors="ignore"):
        line = line.strip()
        if not line:
            continue
        m = re.match(r"int\[\] styleable (\w+) \{([^}]*)\}", line)
        if m:
            ids = [int(x.strip(), 16) for x in m.group(2).split(",") if x.strip()]
            arrays.append((m.group(1), ids))
            continue
        m = re.match(r"int (\w+) (\w+) (0x[0-9a-fA-F]+)", line)
        if m:
            typ, name, hexv = m.group(1), m.group(2), int(m.group(3), 16)
            if typ == "styleable" and "_" in name:
                prefix, suffix = name.rsplit("_", 1)
                array_children.setdefault(prefix, []).append((suffix, hexv))
            else:
                simple.append((typ, name, hexv))
    return simple, arrays, array_children


def emit(pkg, simple, arrays, array_children, by_name, by_id):
    groups = {}
    for typ, name, hexv in simple:
        if (typ, name) not in by_name:
            continue
        groups.setdefault(typ, []).append(name)

    arr_fields = []
    child_names = {}
    for i, (name, ids) in enumerate(arrays):
        vals = []
        for j, hexv in enumerate(ids):
            suffix = None
            for (sname, shex) in array_children.get(name, []):
                if shex == hexv:
                    suffix = sname
                    break
            if suffix is None or ("attr", suffix) not in by_name:
                vals = []
                break
            vals.append(by_name[("attr", suffix)])
        if vals:
            arr_fields.append((name, vals))

    lines = ["package %s;" % pkg, "", "public final class R {"]
    for typ in sorted(groups):
        lines.append("    public static final class %s {" % typ)
        for name in groups[typ]:
            lines.append("        public static final int %s=%d;" % (name, by_name[(typ, name)]))
        lines.append("    }")
    if arr_fields:
        lines.append("    public static final class styleable {")
        for name, vals in arr_fields:
            lines.append("        public static final int[] %s = {%s};"
                         % (name, ",".join(str(v) for v in vals)))
        lines.append("    }")
    lines.append("}")
    return "\n".join(lines) + "\n"


def main():
    by_name, by_id = load_app_table()
    print("app 符号表：%d 项" % len(by_name))
    os.makedirs(OUT, exist_ok=True)
    # 同一个包可能由多个 AAR 提供（core 1.5.0 与 1.13.1 都落在 androidx.core），
    # 先按包并起来再写，否则后写的 R.java 会把先写的字段覆盖掉，运行时又少 id
    pkgs = {}
    for aar in sorted(os.listdir(UNPACKED)):
        d = os.path.join(UNPACKED, aar)
        rtxt = os.path.join(d, "R.txt")
        if not os.path.isfile(rtxt):
            continue
        pkg = package_from_jar(find_classes_jar(d, aar))
        if not pkg:
            continue
        simple, arrays, array_children = parse_r_txt(rtxt)
        cur = pkgs.setdefault(pkg, {"simple": {}, "arrays": {}, "children": {}})
        for typ, name, hexv in simple:
            cur["simple"].setdefault((typ, name), hexv)
        for name, ids in arrays:
            cur["arrays"].setdefault(name, ids)
        for name, kids in array_children.items():
            cur["children"].setdefault(name, []).extend(kids)
    made = 0
    for pkg, data in sorted(pkgs.items()):
        simple = [(t, n, v) for (t, n), v in data["simple"].items()]
        arrays = list(data["arrays"].items())
        src = emit(pkg, simple, arrays, data["children"], by_name, by_id)
        if src.count("public static final int "):
            path = os.path.join(OUT, pkg.replace(".", "/"))
            os.makedirs(path, exist_ok=True)
            open(os.path.join(path, "R.java"), "w", encoding="utf-8").write(src)
            made += 1
    print("生成 R.java：%d 个" % made)


if __name__ == "__main__":
    sys.exit(main())
