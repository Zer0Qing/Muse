#!/usr/bin/env python3
"""
check_engineering_discipline.py — 工程纪律 CI 检查。

扫描代码 diff 中的违反 AGENTS.md 规范的模式:
  - 不安全的类型转换 (as String / as Int / as Any)
  - 无注释的非空断言 !!
  - 空 catch 块
  - 无条件 null 兜底
  - 无条件默认值兜底
  - 类型回退成 Any (val/var : Any 声明)
  - when 表达式 else -> null 联合类型兜底
  - 函数返回 Any 类型
  - TODO/FIXME 数量超限

用法:
  py -3 ci/script/check_engineering_discipline.py [--diff] [--base main] [--head HEAD]
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

# 项目根目录
PROJECT_ROOT = Path(__file__).resolve().parents[2]

# 检查规则
RULES = {
    "unsafe_cast": {
        "pattern": re.compile(r"\bas\s+(String|Int|Long|Float|Double|Boolean|Any|JsonObject)\b"),
        "severity": "warning",
        "message": "不安全的类型转换，请使用安全转换 (as? + ?: 或 if-let)",
    },
    "non_null_assertion": {
        # 匹配 !! 但允许同行后续有 // 注释说明
        # (?![^\n]*//) 表示: 如果 !! 后面同一行有 // 注释,则不标记
        "pattern": re.compile(r"!!(?![^\n]*//)"),
        "severity": "warning",
        "message": "无注释的非空断言 !!，请添加注释说明或使用 require/check",
    },
    "empty_catch": {
        "pattern": re.compile(r"catch\s*\([^)]+\)\s*\{\s*\}"),
        "severity": "error",
        "message": "空 catch 块，必须记录日志或处理异常",
    },
    "null_fallback": {
        "pattern": re.compile(r"\?:\s*null(?!\s*//)"),
        "severity": "warning",
        "message": "无条件 null 兜底，请说明原因或使用 error()",
    },
    "default_fallback": {
        "pattern": re.compile(r'\?:\s*"(?:unknown|default)"'),
        "severity": "warning",
        "message": "无条件默认值兜底，请说明原因",
    },
    # P3-5: 类型安全规则 (AGENTS.md §2.1 / §5)
    "any_type_declaration": {
        # 匹配 val/var x: Any 或 val/var x: Any? 的显式 Any 类型声明
        # 排除 Map<String, Any> 等(那是泛型参数,不是变量类型回退)
        "pattern": re.compile(r"\b(?:val|var)\s+\w+\s*:\s*Any\b(?!\s*[<,])"),
        "severity": "warning",
        "message": "变量类型回退成 Any，请使用具体类型或 sealed class (AGENTS.md §2.1)",
    },
    "any_return_type": {
        # 匹配 fun foo(...): Any 或 fun foo(...): Any? 的函数返回 Any
        "pattern": re.compile(r"\bfun\s+\w+\s*\([^)]*\)\s*:\s*Any\b(?!\s*[<,])"),
        "severity": "warning",
        "message": "函数返回 Any 类型，请使用具体类型或 sealed class (AGENTS.md §5)",
    },
    "when_else_null": {
        # 匹配 when 块中的 else -> null (联合类型兜底)
        "pattern": re.compile(r"\belse\s*->\s*null\b(?!\s*//)"),
        "severity": "warning",
        "message": "when 表达式 else -> null 联合类型兜底，请使用 error() 或 sealed class (AGENTS.md §5)",
    },
    # P3-5: 不安全的可空转换 (AGENTS.md §2.2)
    "nullable_unsafe_cast": {
        # 匹配 (value as String) 这种对可空值的不安全转换
        "pattern": re.compile(r"\(\s*\w+\s+as\s+(String|Int|Long|Float|Double|Boolean)\s*\)"),
        "severity": "warning",
        "message": "对可空值使用 as 转换，请使用 as? + ?: 或 if-let (AGENTS.md §2.2)",
    },
}

# TODO/FIXME 限制
MAX_TODO_COUNT = 10
TODO_PATTERN = re.compile(r"\b(?:TODO|FIXME)\b")

# 扫描的文件扩展名
SCAN_EXTENSIONS = {".kt", ".java"}


def get_diff_files(base: str = "main", head: str = "HEAD") -> list[Path]:
    """获取 diff 中变更的文件列表"""
    try:
        result = subprocess.run(
            ["git", "diff", "--name-only", f"{base}...{head}"],
            cwd=PROJECT_ROOT,
            capture_output=True,
            text=True,
            timeout=30,
        )
        if result.returncode != 0:
            # 回退: 用 git diff --cached 或 git status
            result = subprocess.run(
                ["git", "diff", "--name-only", "HEAD"],
                cwd=PROJECT_ROOT,
                capture_output=True,
                text=True,
                timeout=30,
            )
        files = []
        for line in result.stdout.strip().split("\n"):
            if line:
                f = PROJECT_ROOT / line
                if f.exists() and f.suffix in SCAN_EXTENSIONS:
                    files.append(f)
        return files
    except Exception:
        return []


def get_diff_lines(file_path: Path, base: str = "main") -> list[str]:
    """获取文件 diff 中新增的行"""
    try:
        rel = file_path.relative_to(PROJECT_ROOT).as_posix()
        result = subprocess.run(
            ["git", "diff", f"{base}...HEAD", "--unified=0", rel],
            cwd=PROJECT_ROOT,
            capture_output=True,
            text=True,
            timeout=30,
        )
        if result.returncode != 0:
            result = subprocess.run(
                ["git", "diff", "HEAD", "--unified=0", rel],
                cwd=PROJECT_ROOT,
                capture_output=True,
                text=True,
                timeout=30,
            )
        added_lines = []
        for line in result.stdout.split("\n"):
            if line.startswith("+") and not line.startswith("+++"):
                added_lines.append(line[1:])
        return added_lines
    except Exception:
        return []


def scan_file(file_path: Path, added_lines: list[str] | None = None) -> list[dict]:
    """扫描单个文件中的违规模式"""
    violations = []
    content = file_path.read_text(encoding="utf-8", errors="ignore")
    lines = content.split("\n")

    # 如果有 diff 行，只检查新增行；否则检查全文
    if added_lines is not None:
        check_lines = added_lines
    else:
        check_lines = lines

    for rule_name, rule in RULES.items():
        for line_num, line in enumerate(check_lines, 1):
            for match in rule["pattern"].finditer(line):
                violations.append({
                    "file": str(file_path.relative_to(PROJECT_ROOT)),
                    "line": line_num,
                    "rule": rule_name,
                    "severity": rule["severity"],
                    "message": rule["message"],
                    "match": match.group(0),
                    "code": line.strip(),
                })

    # TODO/FIXME 计数(全文)
    todo_count = len(TODO_PATTERN.findall(content))
    if todo_count > MAX_TODO_COUNT:
        violations.append({
            "file": str(file_path.relative_to(PROJECT_ROOT)),
            "line": 0,
            "rule": "todo_overflow",
            "severity": "warning",
            "message": f"TODO/FIXME 数量超限 ({todo_count} > {MAX_TODO_COUNT})",
            "match": "",
            "code": "",
        })

    return violations


def main():
    parser = argparse.ArgumentParser(description="Muse 工程纪律 CI 检查")
    parser.add_argument("--diff", action="store_true", help="只检查 diff 中的新增行")
    parser.add_argument("--base", default="main", help="diff 基准分支(默认: main)")
    parser.add_argument("--head", default="HEAD", help="diff 目标(默认: HEAD)")
    parser.add_argument("--path", default=None, help="只扫描指定路径")
    args = parser.parse_args()

    print("=" * 60)
    print("Engineering Discipline Check")
    print("=" * 60)

    # 收集要扫描的文件
    if args.diff:
        files = get_diff_files(args.base, args.head)
        print(f"Scanning {len(files)} changed files (diff mode)")
    else:
        scan_path = Path(args.path) if args.path else PROJECT_ROOT
        files = []
        for ext in SCAN_EXTENSIONS:
            files.extend(scan_path.rglob(f"*{ext}"))
        # 过滤 build 目录
        files = [f for f in files if "build" not in f.parts]
        print(f"Scanning {len(files)} files (full mode)")

    all_violations = []
    for file_path in files:
        if args.diff:
            added = get_diff_lines(file_path, args.base)
            violations = scan_file(file_path, added)
        else:
            violations = scan_file(file_path)
        all_violations.extend(violations)

    # 输出结果
    errors = [v for v in all_violations if v["severity"] == "error"]
    warnings = [v for v in all_violations if v["severity"] == "warning"]

    if all_violations:
        print(f"\nFound {len(errors)} errors, {len(warnings)} warnings:\n")
        for v in all_violations:
            icon = "✗" if v["severity"] == "error" else "⚠"
            print(f"  {icon} [{v['severity'].upper()}] {v['file']}:{v['line']}")
            print(f"    Rule: {v['rule']} — {v['message']}")
            if v["code"]:
                print(f"    Code: {v['code']}")
            print()
    else:
        print("\n✓ No violations found")

    print(f"\n{'=' * 60}")
    print(f"Summary: {len(errors)} errors, {len(warnings)} warnings")
    print(f"{'=' * 60}")

    # error 阻止合并, warning 只提醒
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
