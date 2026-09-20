#!/usr/bin/env python3
"""
check_horizontal_inset.py — 水平内边距（贴边）护栏。

规则来源: `UI组件库维护规范.md` 的「水平内边距」一节。
文字/卡片顶到屏幕边缘几乎都是同一个原因: 容器的水平内边距被显式压成 0。
本脚本拦住这类显式清零:

  - horizontalPadding = 0.dp        (MuseBottomSheet 等内容面板)
  - padding(horizontal = 0.dp)
  - PaddingValues(horizontal = 0.dp)
  - contentPadding = 0.dp

需要贴边的极少数场景(全屏媒体、横向滚动画廊)在行尾加
`// inset-guard: allow` 显式豁免。

用法:
  py -3 ci/script/check_horizontal_inset.py
  py -3 ci/script/check_horizontal_inset.py --root app/src/main/java/io/zer0/muse/ui
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

PATTERNS = [
    (re.compile(r"horizontalPadding\s*=\s*0(?:\.\w+)?\b"), "面板水平内边距被压成 0"),
    (re.compile(r"padding\(\s*horizontal\s*=\s*0(?:\.\w+)?\b"), "Modifier.padding(horizontal = 0)"),
    (re.compile(r"PaddingValues\(\s*horizontal\s*=\s*0(?:\.\w+)?\b"), "PaddingValues(horizontal = 0)"),
    (re.compile(r"contentPadding\s*=\s*0(?:\.\w+)?\b"), "contentPadding 为 0"),
]
ALLOW_MARK = "inset-guard: allow"


def scan(root: Path) -> list[tuple[Path, int, str, str]]:
    hits: list[tuple[Path, int, str, str]] = []
    for path in sorted(root.rglob("*.kt")):
        for lineno, line in enumerate(path.read_text(encoding="utf-8", errors="ignore").splitlines(), 1):
            if ALLOW_MARK in line:
                continue
            stripped = re.sub(r'"[^"]*"', '""', line)
            if stripped.lstrip().startswith("//"):
                continue
            for pattern, reason in PATTERNS:
                if pattern.search(stripped):
                    hits.append((path, lineno, reason, line.strip()))
                    break
    return hits


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default="app/src/main/java/io/zer0/muse/ui")
    args = parser.parse_args()

    root = Path(args.root)
    if not root.is_dir():
        print(f"[horizontal-inset] 目录不存在: {root}")
        return 0

    hits = scan(root)
    if not hits:
        print("[horizontal-inset] 扫描 ui/ 完成: 0 处水平内边距被清零")
        print("[horizontal-inset] PASS — 没有内容贴到屏幕边缘")
        return 0

    print(f"[horizontal-inset] 发现 {len(hits)} 处水平内边距被清零(内容会顶到屏幕边):")
    for path, lineno, reason, snippet in hits:
        print(f"  {path}:{lineno}  {reason}\n      {snippet}")
    print("[horizontal-inset] FAIL — 请改用 MusePaddings.screen，")
    print("                  确需贴边时在行尾加 `// inset-guard: allow` 并说明原因")
    return 1


if __name__ == "__main__":
    sys.exit(main())
