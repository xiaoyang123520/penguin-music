#!/usr/bin/env python3
"""Generate R.kt + Strings.kt from app/src/main/res/values/strings.xml.
Desktop replacement for Android resources: R.string.<name> -> stable Int id,
androidx.compose.ui.res.stringResource shim resolves ids via Strings.byId."""
import sys, xml.etree.ElementTree as ET

src, outdir = sys.argv[1], sys.argv[2]
tree = ET.parse(src)
items = []
for el in tree.getroot():
    if el.tag != "string":
        continue
    name = el.get("name")
    text = "".join(el.itertext())
    # Android escapes: \' \" \n \t and &quot; etc. are handled by the XML parser
    # except backslash escapes, which we normalize here.
    text = text.replace("\\'", "'").replace('\\"', '"').replace("\\n", "\n").replace("\\t", "\t")
    items.append((name, text))

def kt_str(s):
    out = (s.replace("\\", "\\\\").replace('"', '\\"')
            .replace("\n", "\\n").replace("\t", "\\t").replace("$", "\\$"))
    return f'"{out}"'

r_lines = ["package com.jussicodes.music", "", "object R {", "    object string {"]
s_lines = ["package com.jussicodes.music", "",
           "object Strings {", "    private val map: Map<Int, String> = mapOf("]
for i, (name, text) in enumerate(items, start=1000):
    r_lines.append(f"        const val {name}: Int = {i}")
    s_lines.append(f"        {i} to {kt_str(text)},")
r_lines += ["    }", "}", ""]
s_lines += ["    )", "",
            "    fun byId(id: Int): String = map[id] ?: \"\"", "}", ""]
open(f"{outdir}/R.kt", "w").write("\n".join(r_lines))
open(f"{outdir}/Strings.kt", "w").write("\n".join(s_lines))
print(f"generated {len(items)} strings")
