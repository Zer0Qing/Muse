#!/usr/bin/env python3
"""test_hardcoded_cjk.py — check_hardcoded_cjk.py 的单元测试。

运行: py -3 ci/test/test_hardcoded_cjk.py
"""

import sys
import tempfile
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parents[1] / "script"
sys.path.insert(0, str(SCRIPT_DIR))

from check_hardcoded_cjk import scan_file


def test_hardcoded_cjk_in_text_detected():
    with tempfile.TemporaryDirectory() as tmp:
        p = Path(tmp) / "Sample.kt"
        p.write_text(
            'Text("你好，世界")\n'
            'val label = "中文"\n',
            encoding="utf-8",
        )
        vs = scan_file(p)
        assert len(vs) == 2, vs


def test_comment_with_cjk_ignored():
    with tempfile.TemporaryDirectory() as tmp:
        p = Path(tmp) / "Sample.kt"
        p.write_text(
            "// 注释里的中文\n"
            "/* 块注释：中文 */\n"
            "val x = 1 // 行尾中文\n",
            encoding="utf-8",
        )
        vs = scan_file(p)
        assert vs == [], vs


def test_string_resource_not_detected():
    with tempfile.TemporaryDirectory() as tmp:
        p = Path(tmp) / "Sample.kt"
        p.write_text(
            'Text(stringResource(R.string.hello))\n',
            encoding="utf-8",
        )
        vs = scan_file(p)
        assert vs == [], vs


def test_mixed_line_only_reports_string_cjk():
    with tempfile.TemporaryDirectory() as tmp:
        p = Path(tmp) / "Sample.kt"
        p.write_text(
            'Text("你好") // 注释里也有中文\n',
            encoding="utf-8",
        )
        vs = scan_file(p)
        assert len(vs) == 1, vs


def test_logger_cjk_ignored():
    with tempfile.TemporaryDirectory() as tmp:
        p = Path(tmp) / "Sample.kt"
        p.write_text(
            'Logger.w(TAG, "加载失败: $msg")\n'
            'Text("请重试")\n',
            encoding="utf-8",
        )
        vs = scan_file(p)
        assert len(vs) == 1, vs

if __name__ == "__main__":
    test_hardcoded_cjk_in_text_detected()
    test_comment_with_cjk_ignored()
    test_string_resource_not_detected()
    test_mixed_line_only_reports_string_cjk()
    test_logger_cjk_ignored()
    print("test_hardcoded_cjk OK")
