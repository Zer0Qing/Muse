#!/usr/bin/env python3
"""
check_touch_target.py — 触摸目标检查。

粗略扫描 IconButton / clickable / combinedClickable 上被显式压到 48dp 以下
的 Modifier.size(...)。IconButton 只检查按钮自身的 modifier（进入 { 之前），
避免把内部图标尺寸误判为触摸目标。

Baseline 机制：存量放过、新增拦截、只降不升。

用法：
  py -3 ci/script/check_touch_target.py
  py -3 ci/script/check_touch_target.py --update-baseline
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]
UI_DIR = PROJECT_ROOT / "app" / "src" / "main" / "java" / "io" / "zer0" / "muse" / "ui"
BASELINE_PATH = PROJECT_ROOT / "ci" / "baseline" / "touch_target_baseline.txt"

SIZE_MODIFIER = re.compile(r"Modifier\.size\((\d+(?:\.\d+)?)\.dp\)")


def scan_file(path: Path) -> list[tuple[int, str]]:
    hits: list[tuple[int, str]] = []
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeDecodeError):
        return hits
    for i, line in enumerate(lines):
        if re.search(r"IconButton\(", line):
            end = min(len(lines), i + 6)
            for j in range(i, end):
                if "{" in lines[j]:
                    end = j
                    break
            window = "\n".join(lines[i:end])
            sizes = [float(x) for x in SIZE_MODIFIER.findall(window)]
            if sizes and min(sizes) < 48:
                hits.append((i + 1, line.strip()))
        elif re.search(r"clickable\(|combinedClickable\(", line):
            sizes = [float(x) for x in SIZE_MODIFIER.findall(line)]
            if sizes and min(sizes) < 48:
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
        "# touch_target baseline — 小触摸目标存量清单",
        "# 格式: <file>: <违规数>;新增或上升都会失败,只允许下降",
        "# 清理存量后运行: py -3 ci/script/check_touch_target.py --update-baseline",
        "#",
        f"# 当前共 {sum(len(v) for v in violations.values())} 处,分布 {len(violations)} 个文件",
    ]
    for file, hits in sorted(violations.items()):
        lines.append(f"{file}: {len(hits)}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="触摸目标检查")
    parser.add_argument("--baseline", type=Path, default=BASELINE_PATH)
    parser.add_argument("--update-baseline", action="store_true")
    args = parser.parse_args()

    violations = scan_all()
    total = sum(len(v) for v in violations.values())

    if args.update_baseline:
        write_baseline(args.baseline, violations)
        print(f"baseline 已更新: {args.baseline}")
        print(f"  共 {total} 处,分布 {len(violations)} 个文件")
        return 0

    print(f"[touch-target] 扫描 ui/ 目录: {total} 处小触摸目标,分布 {len(violations)} 个文件")

    if not args.baseline.exists():
        print("[touch-target] 未找到 baseline,仅报告不拦截。生成: --update-baseline")
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
        print("[touch-target] 有改善,建议 --update-baseline 收紧:")
        for line in improved:
            print(line)

    if failures:
        print("[touch-target] FAILED — 新增小触摸目标:")
        for line in failures:
            print(line)
        print("修复方式: IconButton/clickable 使用 Modifier.size(48.dp) 或 minimumInteractiveComponentSize")
        return 1

    print(f"[touch-target] PASS — 无新增小触摸目标 (存量 {total} 处,按 baseline 豁免)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
