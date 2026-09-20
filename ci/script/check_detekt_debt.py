#!/usr/bin/env python3
"""
check_detekt_debt.py — P4-2 detekt 债务上限断言。

各模块 detekt-baseline.xml 的 CurrentIssues 条目总数必须在 ci/detekt_debt_cap.json
记录的 total 上限内,且任意单模块不得超出其模块级上限(双重围栏,禁止新增)。
按批次清理后: 重生成对应模块 baseline → 手动把 cap 文件数值调 DOWN,并说明清理内容。
绝不允许只把 cap 调 UP 而不清理(那样门禁就只是摆设)。

运行: py -3 ci/script/check_detekt_debt.py [--root ROOT] [--cap CAPFILE]
"""

import argparse
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

DEFAULT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CAP = Path(__file__).resolve().parents[1] / "detekt_debt_cap.json"


def count_baseline(baseline_file: Path) -> int:
    """统计 detekt 基线 CurrentIssues 条数;文件缺失视为 0。"""
    if not baseline_file.exists():
        return 0
    tree = ET.parse(baseline_file)
    current = tree.getroot().find("CurrentIssues")
    return len(current) if current is not None else 0


def check(root: Path, cap_file: Path) -> list[str]:
    errors: list[str] = []
    cap = json.loads(cap_file.read_text(encoding="utf-8"))
    total_cap = int(cap["total"])
    module_caps = {k: int(v) for k, v in cap.get("modules", {}).items()}

    actual: dict[str, int] = {}
    total = 0
    for module in sorted(module_caps.keys()):
        baseline = root / module / "detekt-baseline.xml"
        n = count_baseline(baseline)
        actual[module] = n
        total += n
        module_cap = module_caps.get(module)
        if module_cap is not None and n > module_cap:
            errors.append(
                f"{module}: 基线 {n} 条 > 模块上限 {module_cap} 条 —— 禁止新增,先按批次清理"
            )

    if total > total_cap:
        errors.append(f"detekt 债务总量 {total} 条 > 上限 {total_cap} 条 —— 禁止新增")

    print(f"detekt 债务实测: {total} 条 (各模块 {actual})")
    if errors:
        return errors
    print(f"在 {total_cap} 条上限内,门禁通过")
    return []


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=str(DEFAULT_ROOT))
    parser.add_argument("--cap", default=str(DEFAULT_CAP))
    args = parser.parse_args()
    errors = check(Path(args.root), Path(args.cap))
    if errors:
        print("P4-2 detekt 债务上限断言失败:")
        for err in errors:
            print(f"  - {err}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())