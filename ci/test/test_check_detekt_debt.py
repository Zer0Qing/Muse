#!/usr/bin/env python3
"""
test_check_detekt_debt.py — check_detekt_debt.py 的单元测试。

运行: py -3 ci/test/test_check_detekt_debt.py
"""

import json
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parents[1] / "script"
sys.path.insert(0, str(SCRIPT_DIR))

from check_detekt_debt import check  # noqa: E402

BASELINE_TMPL = """<?xml version="1.0" ?><SmellBaseline><ManuallySuppressedIssues/><CurrentIssues>{ids}</CurrentIssues></SmellBaseline>"""
ID = "<ID>ComplexCondition:X.kt$X$a &amp;&amp; b</ID>"


def make_env(module_counts: dict[str, int], total_cap: int, module_caps: dict[str, int]):
    root = Path(tempfile.mkdtemp())
    for module, n in module_counts.items():
        mod_dir = root / module
        mod_dir.mkdir(parents=True, exist_ok=True)
        ids = ID * n
        (mod_dir / "detekt-baseline.xml").write_text(
            BASELINE_TMPL.format(ids=ids), encoding="utf-8"
        )
    cap = Path(tempfile.mkdtemp()) / "cap.json"
    cap.write_text(
        json.dumps({"total": total_cap, "modules": module_caps}), encoding="utf-8"
    )
    return root, cap


def test_within_cap_passes():
    root, cap = make_env({"app": 10, "common": 3}, 20, {"app": 10, "common": 5})
    assert check(root, cap) == []


def test_total_over_cap_fails():
    root, cap = make_env({"app": 10, "common": 5}, 13, {"app": 10, "common": 5})
    errors = check(root, cap)
    assert len(errors) == 1 and "总量" in errors[0]


def test_module_over_cap_fails():
    root, cap = make_env({"app": 8, "common": 9}, 20, {"app": 10, "common": 5})
    errors = check(root, cap)
    assert any("common" in e and "模块上限" in e for e in errors)


def test_missing_baseline_counts_zero():
    root, cap = make_env({"app": 4}, 10, {"app": 4})
    assert check(root, cap) == []


if __name__ == "__main__":
    tests = [
        test_within_cap_passes,
        test_total_over_cap_fails,
        test_module_over_cap_fails,
        test_missing_baseline_counts_zero,
    ]
    for t in tests:
        t()
        print(f"PASS {t.__name__}")
    print("all tests passed")