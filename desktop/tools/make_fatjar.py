#!/usr/bin/env python3
"""Merge app/ncmapi/proto + all dependency jars + app assets into one fat jar.

- First occurrence wins for duplicate paths, except META-INF/services/* which
  are concatenated (Coil/Ktor/coroutines service registrations).
- Jar signature files (*.SF/*.RSA/*.DSA) are dropped so the merged jar stays
  loadable.
"""
import os, sys, zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
D = os.path.join(ROOT, "desktop")
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(D, "build", "jussichords-all.jar")
MAIN_CLASS = "com.jussicodes.music.desktop.DesktopMainKt"

sources = [
    os.path.join(D, "build", "app.jar"),
    os.path.join(D, "build", "ncmapi.jar"),
    os.path.join(D, "build", "proto.jar"),
] + sorted(
    os.path.join(D, "libs", f) for f in os.listdir(os.path.join(D, "libs"))
    if f.endswith(".jar")
)

written = {}
services = {}

def add_bytes(zout, name, data):
    if name.startswith("META-INF/") and name.endswith((".SF", ".RSA", ".DSA")):
        return
    if name == "META-INF/MANIFEST.MF":
        return
    if name.startswith("META-INF/services/"):
        services.setdefault(name, []).append(data)
        return
    if name in written:
        return
    written[name] = True
    zout.writestr(name, data)

with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zout:
    manifest = (
        "Manifest-Version: 1.0\r\n"
        f"Main-Class: {MAIN_CLASS}\r\n"
        "Implementation-Title: jussichords-desktop\r\n"
        "Implementation-Version: 2.2.0\r\n\r\n"
    )
    zout.writestr("META-INF/MANIFEST.MF", manifest)
    total = 0
    for src in sources:
        with zipfile.ZipFile(src) as zin:
            for info in zin.infolist():
                if info.is_dir():
                    continue
                add_bytes(zout, info.filename, zin.read(info.filename))
                total += 1
    for name, chunks in services.items():
        merged = b"\n".join(c.rstrip(b"\n") for c in chunks) + b"\n"
        zout.writestr(name, merged)
    # app assets (lx/lx-builtin.js) under assets/
    assets_root = os.path.join(ROOT, "app", "src", "main", "assets")
    for dirpath, _dirs, files in os.walk(assets_root):
        for f in files:
            full = os.path.join(dirpath, f)
            rel = os.path.relpath(full, assets_root).replace(os.sep, "/")
            with open(full, "rb") as fh:
                add_bytes(zout, f"assets/{rel}", fh.read())
    # desktop resources (2.1.3: donate.jpg 赞赏码等) at jar root,
    # loaded via classloader getResourceAsStream.
    res_root = os.path.join(D, "src", "main", "resources")
    if os.path.isdir(res_root):
        for dirpath, _dirs, files in os.walk(res_root):
            for f in files:
                full = os.path.join(dirpath, f)
                rel = os.path.relpath(full, res_root).replace(os.sep, "/")
                with open(full, "rb") as fh:
                    add_bytes(zout, rel, fh.read())

print(f"fat jar written: {OUT} ({os.path.getsize(OUT) // (1024*1024)} MB, {len(written)} entries)")
