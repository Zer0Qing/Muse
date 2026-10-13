#!/usr/bin/env python3
"""
test_repo_hygiene.py — check_repo_hygiene.py 的单元测试。

重点回归 v2.6.1 收紧的冲突标记正则:
  - 真实 Git 冲突标记(恰好 7 个字符 + 行尾/空格)必须命中;
  - markdown 的 `====` 分隔线、`----` 下划线等不应被误判(旧写法 `^={7}` 会假红)。

运行: py -3 ci/test/test_repo_hygiene.py
"""

import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parents[1] / "script"
sys.path.insert(0, str(SCRIPT_DIR))

from check_repo_hygiene import CONFLICT_PATTERN


def _has_conflict(text: str) -> bool:
    """给定文本是否含冲突标记。"""
    return bool(CONFLICT_PATTERN.search(text))


def test_real_conflict_markers_detected():
    """三种真实冲突标记都应命中。"""
    assert _has_conflict("<<<<<<< HEAD")
    assert _has_conflict("=======")
    assert _has_conflict(">>>>>>> feature/branch")
    # 冲突标记行首 + 后续内容
    assert _has_conflict("<<<<<<< HEAD\nsome code\n=======\nother\n>>>>>>> other")


def test_conflict_marker_with_trailing_space_detected():
    """标记后可跟空格(部分工具会补尾随空格)。"""
    assert _has_conflict("<<<<<<< HEAD   ")


def test_markdown_separators_not_detected():
    """markdown 分隔线不应被误判为冲突标记(v2.6.1 修复的假红)。"""
    # release_verification.txt 标题下划线这类
    assert not _has_conflict("Muse v2.6.1 构建校验\n============================\n")
    assert not _has_conflict("标题\n====\n")
    assert not _has_conflict("标题\n==========\n")


def test_dashes_and_other_runs_not_detected():
    """其它连续符号(非 < / = / > 的 7 连)不应命中。"""
    assert not _has_conflict("--------")
    assert not _has_conflict("########")
    assert not _has_conflict("*" * 20)


def test_shorter_or_longer_runs_not_detected():
    """少于 7 个不算;多于 7 个且后接非空白也不算(需紧跟空格/行尾)。"""
    assert not _has_conflict("<<<<<<")
    # 8 个 < 后接文字 —— 不是标准标记形态
    assert not _has_conflict("<<<<<<<<x")


def test_embedded_marker_not_detected():
    """行中出现的符号序列不应命中(必须行首)。"""
    assert not _has_conflict("code = a <<<<<<< b")


def test_each_pattern_kind_detected():
    """三种前缀各自独立命中。"""
    for marker in ("<<<<<<<", "=======", ">>>>>>>"):
        assert _has_conflict(marker), f"应命中: {marker}"


def run_all_tests():
    """运行所有测试并报告结果。"""
    tests = [
        test_real_conflict_markers_detected,
        test_conflict_marker_with_trailing_space_detected,
        test_markdown_separators_not_detected,
        test_dashes_and_other_runs_not_detected,
        test_shorter_or_longer_runs_not_detected,
        test_embedded_marker_not_detected,
        test_each_pattern_kind_detected,
    ]

    passed = 0
    failed = 0
    for test in tests:
        try:
            test()
            print(f"  [PASS] {test.__name__}")
            passed += 1
        except AssertionError as e:
            print(f"  [FAIL] {test.__name__}: {e}")
            failed += 1
        except Exception as e:
            print(f"  [ERROR] {test.__name__}: {e}")
            failed += 1

    print(f"\n{'='*50}")
    print(f"Total: {passed + failed}, Passed: {passed}, Failed: {failed}")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(run_all_tests())
