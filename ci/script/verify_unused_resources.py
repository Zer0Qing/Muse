#!/usr/bin/env python3
"""
verify_unused_resources.py — 严格校验 lint 报的 UnusedResources 是否真的无引用。

## 为什么需要它

lint 的 UnusedResources 会误报（实测 920 个里至少 25 个仍被 Kotlin 引用）。
手工删资源前必须用比 lint 更严的口径复核，否则会删掉在用字符串 →
界面空白/崩溃。

## 校验口径（任一命中即视为"仍被使用"，不可删）

1. Kotlin/XML 源码中出现的 `R.<type>.<name>`（Kotlin）
2. XML 中的 `@<type>/<name>`
3. 源码里带引号的资源名字面量（保守，防字符串拼接）；
   但排除设置项 key 声明（DataStore 键名与资源同名属无关）。

## 性能

一次性扫描全部源码，建「被引用资源名」集合，再对每条待删项做集合查询
（O(n+m)，不做逐条正则回溯）。

## 用法

  python ci/script/verify_unused_resources.py --baseline app/lint-baseline.xml \
      --out E:/tmp/unused-verified.json
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from xml.etree import ElementTree as ET

PROJECT_ROOT = Path(__file__).resolve().parents[2]
MAIN = PROJECT_ROOT / "app" / "src" / "main"

MSG_RE = re.compile(r"R\.(?P<type>\w+)\.(?P<name>\w+)")
# Kotlin: R.<type>.<name>
KT_REF_RE = re.compile(r"\bR\.(?P<type>\w+)\.(?P<name>\w+)\b")
# XML: @<type>/<name>
XML_REF_RE = re.compile(r"@(?P<type>\w+)/(?P<name>\w+)\b")


def load_unused(baseline: Path) -> list[dict]:
    """从 lint baseline 抽取 UnusedResources 条目。

    重要：lint 会把**同一行里的多个资源**合并成一条记录，且只在 message 里
    写第一个名字。因此必须同时解析 errorLine1 里的**全部** name=，
    否则会漏掉同行后续资源（它们可能仍在被引用）。
    """
    tree = ET.parse(baseline)
    out = []
    for issue in tree.getroot().findall("issue"):
        if issue.get("id") != "UnusedResources":
            continue
        msg = issue.get("message") or ""
        m = MSG_RE.search(msg)
        if not m:
            continue
        locs = issue.findall("location")
        loc = locs[0] if locs else None
        res_type = m.group("type")
        names = [m.group("name")]

        # 补：errorLine1 里同行的其他 name=（lint 合并记录时漏报的）
        line1 = issue.get("errorLine1") or ""
        for nm in re.findall(r'name="([^"]+)"', line1):
            if nm not in names:
                names.append(nm)

        for n in names:
            out.append(
                {
                    "type": res_type,
                    "name": n,
                    "file": loc.get("file") if loc is not None else "",
                    "line": loc.get("line") if loc is not None else "",
                }
            )
    return out


def build_index() -> tuple[set[str], set[str], set[str]]:
    """返回 (kt_refs, xml_refs, kt_literals)：资源名集合。"""
    kt_refs: set[str] = set()
    xml_refs: set[str] = set()
    kt_literals: set[str] = set()

    for p in MAIN.rglob("*.kt"):
        text = p.read_text(encoding="utf-8", errors="ignore")
        for m in KT_REF_RE.finditer(text):
            kt_refs.add(m.group("name"))
        # 字面量：排除设置 key 声明行
        for line in text.splitlines():
            if re.search(r"KEY_[A-Z_]+|private val [A-Z_]+ =", line):
                continue
            for m in re.finditer(r"[\"']([a-z][a-z0-9_]{3,})[\"']", line):
                kt_literals.add(m.group(1))

    for p in MAIN.rglob("*.xml"):
        text = p.read_text(encoding="utf-8", errors="ignore")
        for m in XML_REF_RE.finditer(text):
            xml_refs.add(m.group("name"))

    return kt_refs, xml_refs, kt_literals


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--baseline", default=str(PROJECT_ROOT / "app" / "lint-baseline.xml"))
    ap.add_argument("--out", default="")
    args = ap.parse_args()

    unused = load_unused(Path(args.baseline))
    kt_refs, xml_refs, kt_literals = build_index()

    safe, used = [], []
    for e in unused:
        n = e["name"]
        ev = []
        if n in kt_refs:
            ev.append(f"kotlin R.{e['type']}.{n}")
        if n in xml_refs:
            ev.append(f"xml @{e['type']}/{n}")
        if n in kt_literals:
            ev.append(f'literal "{n}" in kotlin')
        if ev:
            used.append({**e, "evidence": ev})
        else:
            safe.append(e)

    result = {"safe_to_delete": safe, "still_used": used}
    print(f"UnusedResources 总计: {len(unused)}")
    print(f"  确认无引用（可删）: {len(safe)}")
    print(f"  仍被引用（保留）  : {len(used)}")
    if used:
        print("\n仍被引用的全部条目:")
        for e in used:
            print(f"  {e['type']}.{e['name']}  <- {', '.join(e['evidence'])}")

    if args.out:
        Path(args.out).write_text(
            json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        print(f"\n已写出: {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
