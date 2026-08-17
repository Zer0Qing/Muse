# -*- coding: utf-8 -*-
"""Print detekt violations from XML report."""
import xml.etree.ElementTree as ET
import sys

path = sys.argv[1] if len(sys.argv) > 1 else r"app/build/reports/detekt/detekt.xml"
t = ET.parse(path)
root = t.getroot()
for f in root.iter("file"):
    name = f.get("name", "")
    short = name.split("java/io/zer0/muse/")[-1]
    for e in f.iter("error"):
        msg = e.get("message", "")
        rule = msg.split(":")[0]
        detail = msg[msg.find(":") + 1:].strip()[:140]
        print(f"{short}:{e.get('line')} [{rule}] {detail}")
