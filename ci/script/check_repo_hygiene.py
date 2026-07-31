#!/usr/bin/env python3
"""
check_repo_hygiene.py — 仓库卫生检查。

快速检查仓库的基本卫生状况:
  - 冲突标记 (<<<<<<< / >>>>>>>)
  - 行尾空白
  - 文件末尾换行
  - JSON/XML/YAML 语法
  - 大文件检测

用法: py -3 ci/script/check_repo_hygiene.py [--path .]
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from xml.etree import ElementTree as ET

PROJECT_ROOT = Path(__file__).resolve().parents[2]

# 冲突标记
CONFLICT_PATTERN = re.compile(r"^(<{7}|={7}|>{7})", re.MULTILINE)
# 行尾空白
TRAILING_WHITESPACE = re.compile(r"[ \t]+$", re.MULTILINE)

# 检查的文件类型
CHECK_EXTENSIONS = {
    ".kt", ".java", ".xml", ".json", ".yaml", ".yml", ".py", ".md", ".txt", ".gradle", ".kts",
}

# 忽略的目录
IGNORE_DIRS = {"build", ".gradle", ".idea", ".git", "node_modules", "__pycache__"}

# 大文件阈值 (500KB)
MAX_FILE_SIZE = 500 * 1024


def check_conflict_markers(file_path: Path, content: str) -> list[str]:
    """检查 Git 冲突标记"""
    issues = []
    for match in CONFLICT_PATTERN.finditer(content):
        line_num = content[:match.start()].count("\n") + 1
        issues.append(f"冲突标记: {file_path.name}:{line_num}")
    return issues


def check_trailing_whitespace(file_path: Path, content: str) -> list[str]:
    """检查行尾空白"""
    issues = []
    for match in TRAILING_WHITESPACE.finditer(content):
        line_num = content[:match.start()].count("\n") + 1
        issues.append(f"行尾空白: {file_path.name}:{line_num}")
    return issues


def check_final_newline(file_path: Path, content: str) -> list[str]:
    """检查文件末尾换行"""
    if content and not content.endswith("\n"):
        return [f"缺少末尾换行: {file_path.name}"]
    return []


def check_json_syntax(file_path: Path, content: str) -> list[str]:
    """检查 JSON 语法"""
    try:
        json.loads(content)
    except json.JSONDecodeError as e:
        return [f"JSON 语法错误: {file_path.name}:{e.lineno}: {e.msg}"]
    return []


def check_xml_syntax(file_path: Path, content: str) -> list[str]:
    """检查 XML 语法"""
    try:
        ET.fromstring(content)
    except ET.ParseError as e:
        return [f"XML 语法错误: {file_path.name}: {str(e)}"]
    return []


def check_file_size(file_path: Path) -> list[str]:
    """检查大文件"""
    size = file_path.stat().st_size
    if size > MAX_FILE_SIZE:
        size_kb = size / 1024
        return [f"大文件警告 ({size_kb:.0f}KB): {file_path.name}"]
    return []


def main():
    parser = argparse.ArgumentParser(description="Muse 仓库卫生检查")
    parser.add_argument("--path", default=".", help="检查路径(默认: 项目根目录)")
    args = parser.parse_args()

    scan_path = PROJECT_ROOT / args.path if not Path(args.path).is_absolute() else Path(args.path)

    print("=" * 60)
    print("Repository Hygiene Check")
    print("=" * 60)

    all_issues = []
    file_count = 0

    for file_path in scan_path.rglob("*"):
        if not file_path.is_file():
            continue
        if any(part in IGNORE_DIRS for part in file_path.parts):
            continue
        if file_path.suffix not in CHECK_EXTENSIONS:
            continue

        file_count += 1
        content = file_path.read_text(encoding="utf-8", errors="ignore")

        # 通用检查
        all_issues.extend(check_conflict_markers(file_path, content))
        all_issues.extend(check_trailing_whitespace(file_path, content))
        all_issues.extend(check_final_newline(file_path, content))
        all_issues.extend(check_file_size(file_path))

        # 类型特定检查
        if file_path.suffix == ".json":
            all_issues.extend(check_json_syntax(file_path, content))
        elif file_path.suffix == ".xml":
            all_issues.extend(check_xml_syntax(file_path, content))

    # 输出结果
    errors = [i for i in all_issues if "错误" in i or "冲突" in i]
    warnings = [i for i in all_issues if "警告" in i or "空白" in i or "换行" in i]

    if all_issues:
        print(f"\nScanned {file_count} files, found {len(errors)} errors, {len(warnings)} warnings:\n")
        for issue in all_issues:
            icon = "✗" if "错误" in issue or "冲突" in issue else "⚠"
            print(f"  {icon} {issue}")
    else:
        print(f"\n✓ Scanned {file_count} files, no issues found")

    print(f"\n{'=' * 60}")
    print(f"Summary: {len(errors)} errors, {len(warnings)} warnings")
    print(f"{'=' * 60}")

    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
