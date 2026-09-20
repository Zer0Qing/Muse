#!/usr/bin/env python3
"""
test_component_convergence.py — check_component_convergence.py 的单元测试。

运行: py -3 ci/test/test_component_convergence.py
"""

import json
import sys
import tempfile
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parents[1] / "script"
sys.path.insert(0, str(SCRIPT_DIR))

from check_component_convergence import (  # noqa: E402
    SYMBOLS,
    evaluate,
    scan_ui,
    update_baseline,
)

UI_REL = "app/src/main/java/io/zer0/muse/ui"
REQUIRED = (
    "Button",
    "OutlinedButton",
    "TextButton",
    "IconButton",
    "Card",
    "FilterChip",
    "CircularProgressIndicator",
    "DropdownMenu",
)


def make_root(files: dict[str, str]) -> Path:
    root = Path(tempfile.mkdtemp())
    ui = root / UI_REL
    ui.mkdir(parents=True)
    for name, src in files.items():
        (ui / name).write_text(src, encoding="utf-8")
    return root


def baseline_from(scans) -> dict:
    return {
        "symbols": list(SYMBOLS),
        "files": {s.file: s.occurrences for s in scans},
        "wildcard_imports": {s.file: s.wildcard for s in scans if s.wildcard},
        "totals": {"occurrences": sum(s.occurrences for s in scans)},
    }


def test_required_symbols_covered():
    for name in REQUIRED:
        assert name in SYMBOLS, name


def test_import_counted():
    root = make_root({"A.kt": "import androidx.compose.material3.IconButton\n"})
    scans = scan_ui(root)
    assert len(scans) == 1 and scans[0].occurrences == 1, scans
    assert scans[0].file == f"{UI_REL}/A.kt", scans[0].file


def test_fq_usage_counted():
    root = make_root({"B.kt": "        androidx.compose.material3.TextButton(\n"})
    scans = scan_ui(root)
    assert len(scans) == 1 and scans[0].occurrences == 1, scans


def test_comment_mention_not_counted():
    root = make_root(
        {
            "C.kt": "// import androidx.compose.material3.Button\n"
            "/* androidx.compose.material3.Card( */\n"
        }
    )
    assert scan_ui(root) == []


def test_wildcard_import_flagged():
    root = make_root({"D.kt": "import androidx.compose.material3.*\n"})
    scans = scan_ui(root)
    assert len(scans) == 1 and scans[0].wildcard == 1, scans


def test_within_baseline_passes():
    root = make_root({"A.kt": "import androidx.compose.material3.Card\n"})
    scans = scan_ui(root)
    failures, _ = evaluate(scans, baseline_from(scans))
    assert failures == [], failures


def test_new_file_over_baseline_fails():
    root = make_root(
        {
            "Old.kt": "import androidx.compose.material3.Card\n",
            "New.kt": "import androidx.compose.material3.Button\n",
        }
    )
    scans = scan_ui(root)
    old_only = [s for s in scans if s.file.endswith("Old.kt")]
    failures, _ = evaluate(scans, baseline_from(old_only))
    assert any("New.kt" in f and "新增文件" in f for f in failures), failures


def test_wildcard_growth_fails():
    root = make_root({"A.kt": "import androidx.compose.material3.*\n"})
    scans = scan_ui(root)
    failures, _ = evaluate(scans, baseline_from([]))
    assert any("通配导入" in f for f in failures), failures


def test_symbols_snapshot_mismatch_fails():
    root = make_root({"A.kt": "import androidx.compose.material3.Card\n"})
    scans = scan_ui(root)
    baseline = baseline_from(scans)
    baseline["symbols"] = ["Card"]
    failures, _ = evaluate(scans, baseline)
    assert any("symbols" in f for f in failures), failures


def test_update_baseline_refuses_increase():
    root = make_root({"A.kt": "import androidx.compose.material3.Card\n"})
    scans = scan_ui(root)
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "baseline.json"
        path.write_text(json.dumps(baseline_from([])), encoding="utf-8")
        code = update_baseline(path, scans, allow_increase=False)
        assert code == 1, code
        assert json.loads(path.read_text(encoding="utf-8"))["files"] == {}, "基线不应被写入"


def test_missing_ui_dir_fails():
    root = Path(tempfile.mkdtemp())
    try:
        scan_ui(root)
    except FileNotFoundError:
        return
    raise AssertionError("UI 目录缺失时应抛 FileNotFoundError")


if __name__ == "__main__":
    tests = [
        test_required_symbols_covered,
        test_import_counted,
        test_fq_usage_counted,
        test_comment_mention_not_counted,
        test_wildcard_import_flagged,
        test_within_baseline_passes,
        test_new_file_over_baseline_fails,
        test_wildcard_growth_fails,
        test_symbols_snapshot_mismatch_fails,
        test_update_baseline_refuses_increase,
        test_missing_ui_dir_fails,
    ]
    for t in tests:
        t()
        print(f"PASS {t.__name__}")
    print("all tests passed")
