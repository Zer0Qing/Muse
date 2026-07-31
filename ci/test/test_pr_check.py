#!/usr/bin/env python3
"""
test_pr_check.py — pr_check.py 的单元测试。

测试作用域分类与 Lane 路由逻辑的正确性。
运行: py -3 ci/test/test_pr_check.py
"""

import sys
import os
from pathlib import Path

# 将 ci/script 加入 path
SCRIPT_DIR = Path(__file__).resolve().parents[1] / "script"
sys.path.insert(0, str(SCRIPT_DIR))

from pr_check import classify_file, compute_lanes


def test_classify_kotlin_file():
    """Kotlin 源码应分类为 android_jvm。"""
    assert classify_file("app/src/main/java/io/zer0/muse/MainActivity.kt") == "android_jvm"
    assert classify_file("memory/src/main/java/io/zer0/memory/MemoryService.kt") == "android_jvm"


def test_classify_java_file():
    """Java 源码应分类为 android_jvm。"""
    assert classify_file("app/src/main/java/io/zer0/muse/Legacy.java") == "android_jvm"


def test_classify_localization_file():
    """values-* 资源文件应分类为 localization。"""
    assert classify_file("app/src/main/res/values-zh/strings.xml") == "localization"
    assert classify_file("app/src/main/res/values-ja/strings.xml") == "localization"
    assert classify_file("app/src/main/res/values/strings.xml") == "localization"


def test_classify_resource_file():
    """res/ 下的非 values-* 资源应分类为 android_resources。"""
    assert classify_file("app/src/main/res/layout/activity_main.xml") == "android_resources"
    assert classify_file("app/src/main/res/drawable/ic_launcher.png") == "android_resources"
    assert classify_file("app/src/main/res/xml/accessibility_service_config.xml") == "android_resources"


def test_classify_build_config():
    """build.gradle.kts 应分类为 android_full。"""
    assert classify_file("app/build.gradle.kts") == "android_full"
    assert classify_file("build.gradle.kts") == "android_full"
    assert classify_file("settings.gradle.kts") == "android_full"
    assert classify_file("gradle.properties") == "android_full"
    assert classify_file("gradle/libs.versions.toml") == "android_full"


def test_classify_manifest():
    """AndroidManifest.xml 应分类为 android_full。"""
    assert classify_file("app/src/main/AndroidManifest.xml") == "android_full"


def test_classify_script():
    """ci/ 目录下的脚本应分类为 script。"""
    assert classify_file("ci/script/pr_check.py") == "script"
    assert classify_file("ci/script/check_repo_hygiene.py") == "script"


def test_classify_docs():
    """Markdown 文件应分类为 docs。"""
    assert classify_file("README.md") == "docs"
    assert classify_file("docs/ACCESSIBILITY.md") == "docs"
    assert classify_file("AGENTS.md") == "docs"


def test_classify_config():
    """配置文件应分类为 config。"""
    assert classify_file(".editorconfig") == "config"
    assert classify_file("app/src/main/res/values/themes.xml") == "localization"  # values/ 下的 xml 归 localization


def test_classify_unknown():
    """未匹配的文件归为 other。"""
    assert classify_file("random_file.txt") == "other"
    assert classify_file("data.json") == "other"


def test_compute_lanes_empty():
    """无作用域时只运行 quick_checks。"""
    lanes = compute_lanes(set())
    assert lanes["quick_checks"] is True
    assert lanes["localization"] is False
    assert lanes["android_jvm"] is False
    assert lanes["android_full"] is False


def test_compute_lanes_docs_only():
    """仅文档变更时只运行 quick_checks。"""
    lanes = compute_lanes({"docs"})
    assert lanes["quick_checks"] is True
    assert lanes["localization"] is False
    assert lanes["android_jvm"] is False
    assert lanes["android_full"] is False


def test_compute_lanes_android_full():
    """android_full 应触发所有 Android 相关 Lane。"""
    lanes = compute_lanes({"android_full"})
    assert lanes["android_full"] is True
    assert lanes["android_jvm"] is True
    assert lanes["android_resources"] is True
    assert lanes["localization"] is False


def test_compute_lanes_mixed():
    """混合作用域应正确触发对应 Lane。"""
    lanes = compute_lanes({"android_jvm", "localization"})
    assert lanes["quick_checks"] is True
    assert lanes["android_jvm"] is True
    assert lanes["localization"] is True
    assert lanes["android_full"] is False
    assert lanes["android_resources"] is False


def test_compute_lanes_script():
    """script 作用域只触发 script lane。"""
    lanes = compute_lanes({"script"})
    assert lanes["quick_checks"] is True
    assert lanes["script"] is True
    assert lanes["android_jvm"] is False


def run_all_tests():
    """运行所有测试并报告结果。"""
    tests = [
        test_classify_kotlin_file,
        test_classify_java_file,
        test_classify_localization_file,
        test_classify_resource_file,
        test_classify_build_config,
        test_classify_manifest,
        test_classify_script,
        test_classify_docs,
        test_classify_config,
        test_classify_unknown,
        test_compute_lanes_empty,
        test_compute_lanes_docs_only,
        test_compute_lanes_android_full,
        test_compute_lanes_mixed,
        test_compute_lanes_script,
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
