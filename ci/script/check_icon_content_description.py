#!/usr/bin/env python3
"""
check_icon_content_description.py — 交互图标语义检查。

扫描 ui/ 目录，粗略识别“可点击容器内的 Icon 却没有 contentDescription”的情况：
  - 在 contentDescription = null 上方 8 行内出现 IconButton / clickable / combinedClickable
    / toggleable / selectable
  - 该窗口内没有其它 contentDescription = 赋值（避免父级已提供语义的误报）

Baseline 机制：存量放过、新增拦截、只降不升。

已知局限：
  - 不是 AST 级分析，跨多行的点击容器可能漏报或误报
  - 带语义的父级如果离 Icon 超过 8 行仍会误报，需人工复核

用法：
  py -3 ci/script/check_icon_content_description.py
  py -3 ci/script/check_icon_content_description.py --update-baseline
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]
UI_DIR = PROJECT_ROOT / "app" / "src" / "main" / "java" / "io" / "zer0" / "muse" / "ui"
BASELINE_PATH = PROJECT_ROOT / "ci" / "baseline" / "icon_content_description_baseline.txt"

INTERACTIVE = re.compile(
    r"IconButton\(|clickable\(|combinedClickable\(|toggleable\(|selectable\("
)
NULL_CD = re.compile(r"contentDescription\s*=\s*null")
HAS_CD = re.compile(r"contentDescription\s*=\s*(?!null)")


def scan_file(path: Path) -> list[tuple[int, str]]:
    hits: list[tuple[int, str]] = []
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeDecodeError):
        return hits
    for i, line in enumerate(lines):
        if not NULL_CD.search(line):
            continue
        start = max(0, i - 8)
        window = "\n".join(lines[start:i + 1])
        if INTERACTIVE.search(window) and not HAS_CD.search(window):
            hits.append((i + 1, line.strip()))
    return hits


def scan_all() -> dict[str, list[tuple[int, str]]]:
    result: dict[str, list[tuple[int, str]]] = {}
    for kt in sorted(UI_DIR.rglob("*.kt")):
        hits = scan_file(kt)
        if hits:
            result[str(kt.relative_to(PROJECT_ROOT)).replace("\\", "/")] = hits
    return result


def load_baseline(path: Path) -> dict[str, int]:
    baseline: dict[str, int] = {}
    if not path.exists():
        return baseline
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        file_part, sep, count_part = line.rpartition(":")
        if not sep:
            continue
        try:
            baseline[file_part.strip()] = int(count_part.strip())
        except ValueError:
            continue
    return baseline


def write_baseline(path: Path, violations: dict[str, list[tuple[int, str]]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "# icon_content_description baseline — 交互图标语义存量清单",
        "# 格式: <file>: <违规数>;新增或上升都会失败,只允许下降",
        "# 清理存量后运行: py -3 ci/script/check_icon_content_description.py --update-baseline",
        "#",
        f"# 当前共 {sum(len(v) for v in violations.values())} 处,分布 {len(violations)} 个文件",
    ]
    for file, hits in sorted(violations.items()):
        lines.append(f"{file}: {len(hits)}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="交互图标语义检查")
    parser.add_argument("--baseline", type=Path, default=BASELINE_PATH)
    parser.add_argument("--update-baseline", action="store_true")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    violations = scan_all()
    total = sum(len(v) for v in violations.values())

    if args.update_baseline:
        write_baseline(args.baseline, violations)
        print(f"baseline 已更新: {args.baseline}")
        print(f"  共 {total} 处,分布 {len(violations)} 个文件")
        return 0

    print(f"[icon-cd] 扫描 ui/ 目录: {total} 处交互图标缺 contentDescription,分布 {len(violations)} 个文件")

    if args.verbose:
        for file, hits in sorted(violations.items()):
            for lineno, text in hits:
                print(f"  {file}:{lineno} {text}")

    if not args.baseline.exists():
        print("[icon-cd] 未找到 baseline,仅报告不拦截。生成: --update-baseline")
        return 0

    baseline = load_baseline(args.baseline)
    failures: list[str] = []
    improved: list[str] = []
    for file, hits in sorted(violations.items()):
        current = len(hits)
        allowed = baseline.get(file, 0)
        if current > allowed:
            failures.append(f"  {file}: {current} 处 (baseline {allowed},新增 {current - allowed})")
        elif current < allowed:
            improved.append(f"  {file}: {allowed} → {current}")
    cleaned = [f for f in baseline if f not in violations]
    for f in cleaned:
        improved.append(f"  {f}: 已清零")

    if improved:
        print("[icon-cd] 有改善,建议 --update-baseline 收紧:")
        for line in improved:
            print(line)

    if failures:
        print("[icon-cd] FAILED — 新增交互图标缺 contentDescription:")
        for line in failures:
            print(line)
        print("修复方式: 在可点击 Icon 上补 contentDescription;装饰性图标在父级提供语义")
        return 1

    print(f"[icon-cd] PASS — 无新增交互图标语义缺失 (存量 {total} 处,按 baseline 豁免)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
