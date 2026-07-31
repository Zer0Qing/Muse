#!/usr/bin/env python3
"""
pr_check.py — PR 作用域分类与 Lane 路由。

基于 PR 变更文件路径,分类为不同的检查作用域(scope),输出应运行的 Lane。
GitHub Actions 通过 output 控制哪些 job 实际执行,避免纯文档 PR 触发 Android 构建。

作用域分类:
  - localization: values-* 资源文件变更
  - android_resources: res/ 下的 XML/drawable 等资源变更
  - android_jvm: Kotlin/Java 源码变更(需编译 + 单测)
  - android_full: build.gradle/kts 或 manifest 变更(需完整 assemble)
  - script: ci/script/ 下的 Python 脚本变更
  - docs: 仅 .md 文件变更(只需快速检查)
  - config: gradle/config 文件变更

用法:
  # 本地测试(对比 HEAD 与 main)
  py -3 ci/script/pr_check.py --base origin/main

  # CI 中使用(GitHub Actions)
  py -3 ci/script/pr_check.py --base origin/${{ github.base_ref }} --output json

输出(JSON):
  {
    "scopes": ["localization", "android_jvm"],
    "lanes": {
      "quick_checks": true,
      "localization": true,
      "android_resources": false,
      "android_jvm": true,
      "android_full": false,
      "script": false
    },
    "changed_files": 42,
    "classification": {"android_jvm": 15, "localization": 5, "docs": 22}
  }
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]

# ── 作用域分类规则 ──────────────────────────────────────────────────────────

# 文件路径 → 作用域的匹配规则(按优先级从高到低)
SCOPE_RULES = [
    # localization: values-* 目录下的资源文件
    (
        "localization",
        lambda p: (
            "/values-" in p
            or p.startswith("app/src/main/res/values-")
            or (p.startswith("app/src/main/res/values/") and p.endswith(".xml"))
        ),
    ),
    # script: ci/ 目录下的脚本
    ("script", lambda p: p.startswith("ci/")),
    # android_full: 构建配置或 manifest 变更
    (
        "android_full",
        lambda p: (
            p.endswith("build.gradle.kts")
            or p.endswith("build.gradle")
            or p.endswith("AndroidManifest.xml")
            or p.startswith("gradle/")
            or p == "settings.gradle.kts"
            or p == "gradle.properties"
        ),
    ),
    # android_resources: res/ 下的资源文件(非 values-* 的 XML/drawable 等)
    (
        "android_resources",
        lambda p: (
            ("/res/" in p and p.endswith(".xml"))
            or ("/res/" in p and any(p.endswith(ext) for ext in (".png", ".webp", ".jpg", ".svg")))
            or "/res/" in p
        ),
    ),
    # android_jvm: Kotlin/Java 源码
    (
        "android_jvm",
        lambda p: p.endswith(".kt") or p.endswith(".java"),
    ),
    # docs: Markdown 文件
    ("docs", lambda p: p.endswith(".md")),
    # config: 配置文件
    (
        "config",
        lambda p: any(
            p.endswith(ext)
            for ext in (".properties", ".toml", ".yml", ".yaml", ".editorconfig", ".gitignore")
        ),
    ),
]


def classify_file(filepath: str) -> str:
    """将单个文件路径分类到作用域。"""
    for scope, matcher in SCOPE_RULES:
        if matcher(filepath):
            return scope
    # 未匹配的文件归为 "other"
    return "other"


def get_changed_files(base: str) -> list[str]:
    """获取相对于 base 分支的变更文件列表。"""
    try:
        result = subprocess.run(
            ["git", "diff", "--name-only", f"{base}...HEAD"],
            capture_output=True,
            text=True,
            cwd=PROJECT_ROOT,
            check=True,
        )
        files = [f.strip() for f in result.stdout.strip().split("\n") if f.strip()]
        return files
    except subprocess.CalledProcessError as e:
        print(f"Error getting changed files: {e.stderr}", file=sys.stderr)
        sys.exit(1)
    except FileNotFoundError:
        print("Error: git not found in PATH", file=sys.stderr)
        sys.exit(1)


def compute_lanes(scopes: set[str]) -> dict[str, bool]:
    """根据作用域集合计算应运行的 Lane。"""
    # quick_checks 始终运行(卫生检查 + 工程纪律)
    lanes = {
        "quick_checks": True,
        "localization": "localization" in scopes,
        "android_resources": "android_resources" in scopes or "android_full" in scopes,
        "android_jvm": "android_jvm" in scopes or "android_full" in scopes,
        "android_full": "android_full" in scopes,
        "script": "script" in scopes,
    }
    return lanes


def main() -> int:
    parser = argparse.ArgumentParser(description="PR 作用域分类与 Lane 路由")
    parser.add_argument(
        "--base",
        default="origin/main",
        help="对比的 base 分支(默认: origin/main)",
    )
    parser.add_argument(
        "--output",
        choices=["json", "text"],
        default="text",
        help="输出格式(默认: text)",
    )
    parser.add_argument(
        "--files",
        help="直接传入文件列表(用换行分隔),跳过 git diff",
    )
    args = parser.parse_args()

    # 获取变更文件
    if args.files:
        changed_files = [f.strip() for f in args.files.split("\n") if f.strip()]
    else:
        changed_files = get_changed_files(args.base)

    if not changed_files:
        if args.output == "json":
            print(json.dumps({
                "scopes": [],
                "lanes": {"quick_checks": True, "localization": False, "android_resources": False,
                          "android_jvm": False, "android_full": False, "script": False},
                "changed_files": 0,
                "classification": {},
            }, indent=2))
        else:
            print("No changed files detected.")
        return 0

    # 分类
    classification: dict[str, int] = defaultdict(int)
    scopes: set[str] = set()
    for filepath in changed_files:
        scope = classify_file(filepath)
        classification[scope] += 1
        scopes.add(scope)

    # 计算 Lane
    lanes = compute_lanes(scopes)

    # 输出
    if args.output == "json":
        result = {
            "scopes": sorted(scopes),
            "lanes": lanes,
            "changed_files": len(changed_files),
            "classification": dict(classification),
        }
        print(json.dumps(result, indent=2))
    else:
        print(f"Changed files: {len(changed_files)}")
        print(f"\nScope classification:")
        for scope in sorted(classification.keys()):
            print(f"  {scope}: {classification[scope]} files")
        print(f"\nLanes to run:")
        for lane, should_run in lanes.items():
            status = "RUN" if should_run else "skip"
            print(f"  [{status}] {lane}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
