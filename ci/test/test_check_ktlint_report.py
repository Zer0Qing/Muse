#!/usr/bin/env python3
"""
test_check_ktlint_report.py — check_ktlint_report.py 的单元测试。

运行: py -3 ci/test/test_check_ktlint_report.py
"""

import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parents[1] / "script"
sys.path.insert(0, str(SCRIPT_DIR))

from check_ktlint_report import linted_file_count  # noqa: E402

SAMPLE_WITH_COUNT = """src/main/java/io/zer0/muse/X.kt:1:1: standard:import-ordering

@linted-file-count=1063
"""


def test_linted_file_count_positive():
    assert linted_file_count(SAMPLE_WITH_COUNT) == 1063


def test_linted_file_count_zero():
    assert linted_file_count("@linted-file-count=0\n") == 0


def test_linted_file_count_missing():
    assert linted_file_count("some report without count\n") is None


def test_linted_file_count_invalid():
    assert linted_file_count("@linted-file-count=abc\n") is None


def test_linted_file_count_more_than_one_line():
    assert linted_file_count("a\n@linted-file-count=42\nb\n") == 42


if __name__ == "__main__":
    tests = [
        test_linted_file_count_positive,
        test_linted_file_count_zero,
        test_linted_file_count_missing,
        test_linted_file_count_invalid,
        test_linted_file_count_more_than_one_line,
    ]
    for t in tests:
        t()
        print(f"PASS {t.__name__}")
    print("all tests passed")