#!/usr/bin/env python3
"""Mini Maven resolver: reads root coordinates, recursively resolves POM
dependencies (compile/runtime, non-optional), downloads jars via curl.
First-seen version wins. Repos: Maven Central, then Google Maven."""
import subprocess, sys, os, re
import xml.etree.ElementTree as ET

REPOS = [
    "https://repo.maven.apache.org/maven2",
    "https://dl.google.com/dl/android/maven2",
]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
OUT = sys.argv[2] if len(sys.argv) > 2 else "libs"
os.makedirs(OUT, exist_ok=True)
_pom_cache = {}

def fetch(url):
    for _ in range(3):
        r = subprocess.run(["curl", "-sfL", "--max-time", "60", url],
                           capture_output=True)
        if r.returncode == 0:
            return r.stdout
    return None

def path_of(g, a, v, file):
    return f"{g.replace('.', '/')}/{a}/{v}/{file}"

def get_pom(g, a, v):
    key = (g, a, v)
    if key in _pom_cache:
        return _pom_cache[key]
    data = None
    for repo in REPOS:
        data = fetch(f"{repo}/{path_of(g, a, v, f'{a}-{v}.pom')}")
        if data:
            break
    root = None
    if data:
        try:
            root = ET.fromstring(data)
        except ET.ParseError:
            root = None
    _pom_cache[key] = root
    return root

def txt(el, tag):
    e = el.find(f"m:{tag}", NS) if el is not None else None
    return e.text.strip() if e is not None and e.text else None

def subst(s, props, depth=0):
    if s is None or depth > 5:
        return s
    def rep(m):
        return props.get(m.group(1), m.group(0))
    out = re.sub(r"\$\{([^}]+)\}", rep, s)
    return subst(out, props, depth + 1) if "${" in out else out

def pom_model(g, a, v):
    """Return (properties, dep_mgmt {(g,a): version}, deps [raw dicts], packaging)."""
    root = get_pom(g, a, v)
    if root is None:
        return None
    props = {"project.groupId": g, "project.artifactId": a,
             "project.version": v, "pom.groupId": g, "pom.version": v}
    packaging = txt(root, "packaging") or "jar"
    parent = root.find("m:parent", NS)
    mgmt, parent_props = {}, {}
    if parent is not None:
        pg, pa, pv = txt(parent, "groupId"), txt(parent, "artifactId"), txt(parent, "version")
        pm = pom_model(pg, pa, pv)
        if pm:
            parent_props, mgmt = pm[0], dict(pm[1])
    props.update(parent_props)
    pel = root.find("m:properties", NS)
    if pel is not None:
        for child in pel:
            tag = child.tag.split("}")[-1]
            props[tag] = (child.text or "").strip()
    props["project.version"] = v
    # dependencyManagement
    dm = root.find("m:dependencyManagement/m:dependencies", NS)
    if dm is not None:
        for d in dm.findall("m:dependency", NS):
            dg, da = txt(d, "groupId"), txt(d, "artifactId")
            dv, scope, typ = txt(d, "version"), txt(d, "scope"), txt(d, "type")
            dv = subst(dv, props)
            if scope == "import" and typ == "pom" and dv:
                bom = pom_model(dg, da, dv)
                if bom:
                    for k, val in bom[1].items():
                        mgmt.setdefault(k, val)
            elif dv:
                mgmt[(dg, da)] = dv
    deps = []
    del_ = root.find("m:dependencies", NS)
    if del_ is not None:
        for d in del_.findall("m:dependency", NS):
            dg, da = subst(txt(d, "groupId"), props), txt(d, "artifactId")
            dv = subst(txt(d, "version"), props)
            if not dv:
                dv = mgmt.get((dg, da))
            excl = set()
            ex = d.find("m:exclusions", NS)
            if ex is not None:
                for e in ex.findall("m:exclusion", NS):
                    excl.add((txt(e, "groupId"), txt(e, "artifactId")))
            deps.append({
                "g": dg, "a": da, "v": dv,
                "scope": txt(d, "scope") or "compile",
                "type": txt(d, "type") or "jar",
                "opt": txt(d, "optional") == "true",
                "excl": excl,
            })
    return props, mgmt, deps, packaging

resolved = {}   # (g,a) -> version
order = []
problems = []

def resolve(g, a, v, excl=frozenset()):
    if (g, a) in excl or v is None:
        if v is None:
            problems.append(f"NO-VERSION {g}:{a}")
        return
    if "[" in v or "(" in v:  # version range: crude - take lower bound
        v = re.split(r"[,)\]]", v.strip("[("))[0] or v
    if (g, a) in resolved:
        return
    resolved[(g, a)] = v
    order.append((g, a, v))
    model = pom_model(g, a, v)
    if model is None:
        problems.append(f"NO-POM {g}:{a}:{v}")
        return
    for d in model[2]:
        if d["opt"] or d["scope"] in ("test", "provided", "system"):
            continue
        if d["type"] not in ("jar", "bundle"):
            continue
        resolve(d["g"], d["a"], d["v"], excl | d["excl"])

roots = []
for line in open(sys.argv[1]):
    line = line.strip()
    if not line or line.startswith("#"):
        continue
    g, a, v = line.split(":")
    roots.append((g, a, v))
for g, a, v in roots:
    resolve(g, a, v)

print(f"resolved {len(order)} artifacts")
ok, fail = 0, []
for g, a, v in order:
    name = f"{a}-{v}.jar"
    dest = os.path.join(OUT, name)
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        ok += 1
        continue
    got = False
    for repo in REPOS:
        data = fetch(f"{repo}/{path_of(g, a, v, name)}")
        if data:
            open(dest, "wb").write(data)
            got = True
            break
    if got:
        ok += 1
    else:
        fail.append(f"{g}:{a}:{v}")
print(f"jars ok={ok} fail={len(fail)}")
for f in fail:
    print("  JAR-FAIL", f)
for p in problems:
    print(" ", p)
open(os.path.join(OUT, "resolved.txt"), "w").write(
    "\n".join(f"{g}:{a}:{v}" for g, a, v in order))
