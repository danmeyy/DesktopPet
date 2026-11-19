#!/usr/bin/env python3
"""极简 Maven 解析器：只要 POM 里的 compile/runtime 依赖，按 GA 去重（版本取高），
下载 .aar/.jar 到 out/。给没有 AGP 的构建用。"""
import os, re, sys, urllib.request, xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor

REPOS = [
    "https://dl.google.com/dl/android/maven2",
    "https://repo1.maven.org/maven2",
]
import os
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.environ.get("LIBS", os.path.join(HERE, "libs"))
os.makedirs(OUT, exist_ok=True)

ROOTS = [
    ("androidx.compose.ui", "ui", "1.7.7"),
    ("androidx.compose.ui", "ui-graphics", "1.7.7"),
    ("androidx.compose.ui", "ui-text", "1.7.7"),
    ("androidx.compose.ui", "ui-unit", "1.7.7"),
    ("androidx.compose.ui", "ui-util", "1.7.7"),
    ("androidx.compose.ui", "ui-geometry", "1.7.7"),
    ("androidx.compose.foundation", "foundation", "1.7.7"),
    ("androidx.compose.foundation", "foundation-layout", "1.7.7"),
    ("androidx.compose.material3", "material3", "1.3.1"),
    ("androidx.compose.runtime", "runtime", "1.7.7"),
    ("androidx.compose.runtime", "runtime-saveable", "1.7.7"),
    ("androidx.compose.animation", "animation", "1.7.7"),
    ("androidx.compose.animation", "animation-core", "1.7.7"),
    ("androidx.activity", "activity-compose", "1.9.3"),
    ("androidx.activity", "activity", "1.9.3"),
    ("androidx.lifecycle", "lifecycle-viewmodel-compose", "2.8.6"),
    ("androidx.lifecycle", "lifecycle-runtime-compose", "2.8.6"),
    ("androidx.lifecycle", "lifecycle-runtime", "2.8.6"),
    ("androidx.core", "core", "1.13.1"),
    ("dev.chrisbanes.haze", "haze-android", "1.3.1"),
    ("org.jetbrains.kotlin", "kotlin-stdlib", "2.0.21"),
]

NS = "{http://maven.apache.org/POM/4.0.0}"


def fetch(url, timeout=40):
    req = urllib.request.Request(url, headers={"User-Agent": "dsh-build"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def path_of(g, a, v, ext):
    return f"{g.replace('.', '/')}/{a}/{v}/{a}-{v}.{ext}"


def get_pom(g, a, v):
    for base in REPOS:
        try:
            return fetch(f"{base}/{path_of(g, a, v, 'pom')}")
        except Exception:
            continue
    return None


def text(el, tag):
    c = el.find(NS + tag)
    return (c.text or "").strip() if c is not None else ""


def deps_of(pom_bytes):
    try:
        root = ET.fromstring(pom_bytes)
    except Exception:
        return []
    out = []
    parent = root.find(NS + "parent")
    parent_v = text(parent, "version") if parent is not None else ""
    dm = root.find(NS + "dependencyManagement")
    managed = {}
    if dm is not None:
        for d in dm.iter(NS + "dependency"):
            g, a = text(d, "groupId"), text(d, "artifactId")
            if g and a:
                managed[(g, a)] = text(d, "version")
    for d in root.iter(NS + "dependency"):
        g, a, v = text(d, "groupId"), text(d, "artifactId"), text(d, "version")
        scope = text(d, "scope") or "compile"
        opt = text(d, "optional")
        if scope not in ("compile", "runtime") or opt == "true":
            continue
        if not v:
            v = managed.get((g, a), "")
        if "${" in v or "${" in g or "${" in a:
            continue
        if v == "" and parent_v:
            v = parent_v
        if g and a and v:
            out.append((g, a, v))
    return out


def ver_key(v):
    return [int(x) if x.isdigit() else 0 for x in re.split(r"[.\-]", v)]


def main():
    seen = {}          # (g,a) -> version chosen
    queue = list(ROOTS)
    pom_cache = {}
    with ThreadPoolExecutor(max_workers=12) as ex:
        while queue:
            batch, queue = queue, []
            def work(dep):
                g, a, v = dep
                if (g, a) in seen:
                    return dep, None
                return dep, get_pom(g, a, v)
            for dep, pom in ex.map(work, batch):
                g, a, v = dep
                if (g, a) in seen or not pom:
                    continue
                seen[(g, a)] = v
                pom_cache[(g, a)] = pom
                queue.extend(deps_of(pom))
    print("modules:", len(seen))

    jobs = []
    for (g, a), v in sorted(seen.items()):
        pom = pom_cache.get((g, a), b"")
        packaging = ""
        try:
            r = ET.fromstring(pom)
            p = r.find(NS + "packaging")
            packaging = (p.text or "").strip() if p is not None else ""
        except Exception:
            pass
        ext = "aar" if packaging == "aar" else "jar"
        jobs.append((g, a, v, ext))

    ok, bad = 0, []

    def dl(job):
        g, a, v, ext = job
        name = f"{a}-{v}.{ext}"
        dest = os.path.join(OUT, name)
        if os.path.exists(dest) and os.path.getsize(dest) > 0:
            return True
        for base in REPOS:
            for e in ([ext, "jar"] if ext == "aar" else [ext, "aar"]):
                url = f"{base}/{path_of(g, a, v, e)}"
                try:
                    data = fetch(url, timeout=90)
                    if data[:2] == b"PK":
                        open(dest, "wb").write(data)
                        return True
                except Exception:
                    continue
        return False

    with ThreadPoolExecutor(max_workers=10) as ex:
        for job, good in zip(jobs, ex.map(dl, jobs)):
            if good:
                ok += 1
            else:
                bad.append(f"{job[0]}:{job[1]}:{job[2]}")
    print("downloaded:", ok, "failed:", len(bad))
    if bad:
        print("MISSING:", *bad[:40], sep="\n  ")
    print("size:", sum(os.path.getsize(os.path.join(OUT, f)) for f in os.listdir(OUT)) // 1024 // 1024, "MB")


if __name__ == "__main__":
    main()
