#!/usr/bin/env python3
"""
delete_unused_resources.py — 按 verify 结果删除确认无引用的资源。

## 安全约束
- 只删 verify_unused_resources.py 的 safe_to_delete 条目。
- **全语言目录同名 key 一起删**（values + values-xx），否则触发 MissingTranslation。
- drawable/ 下的独立文件直接删文件。
- values 下的 <string>/<plurals>/<array> 删对应行。
- 删前打印清单，删后报告。

用法:
  python ci/script/delete_unused_resources.py --verified E:/tmp/unused-verified.json [--apply]
  不带 --apply 只做 dry-run。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from xml.etree import ElementTree as ET

PROJECT_ROOT = Path(__file__).resolve().parents[2]
RES = PROJECT_ROOT / "app" / "src" / "main" / "res"

# 所有语言目录
LOCALES = ["values", "values-en", "values-es", "values-ja", "values-ko", "values-pt-rBR", "values-ru"]


def collect_names(verified: Path) -> tuple[set[str], set[str]]:
    """返回 (strings+plurals+array 的名字集合, drawable 名字集合)。"""
    d = json.loads(verified.read_text(encoding="utf-8"))
    safe = d["safe_to_delete"]
    value_names = {e["name"] for e in safe if e["type"] in ("string", "plurals", "array")}
    drawables = {e["name"] for e in safe if e["type"] == "drawable"}
    return value_names, drawables


def delete_drawables(drawables: set[str], apply: bool) -> list[str]:
    removed = []
    for name in sorted(drawables):
        for p in RES.rglob(f"{name}.xml"):
            # 只删 drawable* 目录下的
            if "drawable" in p.parent.name:
                if apply:
                    p.unlink()
                removed.append(str(p.relative_to(PROJECT_ROOT)))
    return removed


def delete_value_entries(value_names: set[str], apply: bool) -> list[str]:
    """删 values*/**.xml 里对应的 <string>/<plurals>/<array> 条目（含多行条目）。

    重要：源文件里可能存在**同一行多个资源**（历史格式遗留），
    例如：
        <string name="a">..</string>    <string name="b">..</string>
    因此不能“删整行”，而必须按 name **只删目标片段**，保留同行其他内容。
    """
    removed = []
    tag_re = re.compile(
        r'<(string|plurals|array)\s+name="([^"]+)"[^>]*>.*?</\1>',
        re.DOTALL,
    )
    for loc in LOCALES:
        d = RES / loc
        if not d.is_dir():
            continue
        for p in d.rglob("*.xml"):
            text = p.read_text(encoding="utf-8")
            changed = 0

            def _sub(m: re.Match) -> str:
                nonlocal changed
                if m.group(2) in value_names:
                    changed += 1
                    removed.append(f"{p.relative_to(PROJECT_ROOT)}: {m.group(2)}")
                    return ""  # 只删该片段
                return m.group(0)

            new_text = tag_re.sub(_sub, text)
            if changed and apply:
                p.write_text(new_text, encoding="utf-8")
    return removed


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--verified", default="E:/tmp/unused-verified.json")
    ap.add_argument("--apply", action="store_true")
    args = ap.parse_args()

    value_names, drawables = collect_names(Path(args.verified))
    print(f"待删: values 条目 {len(value_names)}, drawable {len(drawables)}")
    print("模式:", "APPLY（真删）" if args.apply else "DRY-RUN")

    dw = delete_drawables(drawables, args.apply)
    vl = delete_value_entries(value_names, args.apply)

    print(f"\ndrawable 删除 {len(dw)} 个文件")
    print(f"values 删除 {len(vl)} 条（跨语言）")
    if not args.apply:
        print("\n（dry-run，未改动文件；加 --apply 执行）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
