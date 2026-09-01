#!/usr/bin/env python3
"""
check_hardcoded_cjk.py — UI 硬编码中文字面量检查。

扫描 ui/ 目录下的 Jetpack Compose 代码，拦截绕过 stringResource 的
硬编码中文字符串（存量放过、新增拦截、只降不升）。

识别范围：
  - 同一行双引号字符串内包含 CJK 字面量，如 Text("你好")
  - 同一行包含双引号且包含 CJK 的兜底识别（覆盖 raw string 起始/结束行）
  - 自动剔除行注释与块注释，避免文档示例误报

已知局限：
  - 跨越多行的 raw string 中不含引号的续行不会识别
  - 更严格的 Kotlin 词法解析留给 IDE/Lint 插件，本脚本用于 CI 快速闸门

Baseline 机制：
  - CI 模式（--baseline ci/baseline/hardcoded_cjk_baseline.txt）：
    逐文件对比违规数，多于 baseline 或出现新违规文件则失败
  - 更新 baseline（--update-baseline）：清理存量后重新生成

用法：
  py -3 ci/script/check_hardcoded_cjk.py
  py -3 ci/script/check_hardcoded_cjk.py --baseline <path>
  py -3 ci/script/check_hardcoded_cjk.py --update-baseline
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]
UI_DIR = PROJECT_ROOT / "app" / "src" / "main" / "java" / "io" / "zer0" / "muse" / "ui"
BASELINE_PATH = PROJECT_ROOT / "ci" / "baseline" / "hardcoded_cjk_baseline.txt"

# 同一行双引号字符串内包含 CJK
CJK_IN_QUOTED_STRING = re.compile(r'"([^"\n]*[\u4e00-\u9fff][^"\n]*)"')
# 兜底：行内含双引号且含 CJK（覆盖 raw string 的边界行）
CJK_WITH_QUOTE = re.compile(r'[\u4e00-\u9fff]')


@dataclass
class Violation:
    file: Path
    line: int
    code: str


def strip_comments(line: str, in_block_comment: bool) -> tuple[str, bool]:
    """剔除块/行注释,并跳过字符串字面量(不把 "text/*" 里的 /* 当块注释,避免吞掉其后代码)。"""
    out: list[str] = []
    i = 0
    n = len(line)
    while i < n:
        ch = line[i]
        if in_block_comment:
            if ch == "*" and i + 1 < n and line[i + 1] == "/":
                in_block_comment = False
                i += 2
                continue
            i += 1
            continue
        if ch in "\"'":
            # 字符串字面量:原样 emit,跳过其中的 /* 与 //(含转义)
            quote = ch
            out.append(ch)
            i += 1
            while i < n:
                c = line[i]
                out.append(c)
                if c == "\\" and i + 1 < n:
                    out.append(line[i + 1])
                    i += 2
                    continue
                if c == quote:
                    i += 1
                    break
                i += 1
            continue
        if ch == "/" and i + 1 < n and line[i + 1] == "*":
            in_block_comment = True
            i += 2
            continue
        if ch == "/" and i + 1 < n and line[i + 1] == "/":
            break  # 行注释,丢弃剩余
        out.append(ch)
        i += 1
    return "".join(out), in_block_comment


def scan_file(path: Path) -> list[Violation]:
    violations: list[Violation] = []
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeDecodeError) as e:
        print(f"WARN: 无法读取 {path}: {e}", file=sys.stderr)
        return violations
    in_block_comment = False
    for lineno, line in enumerate(lines, 1):
        code, in_block_comment = strip_comments(line, in_block_comment)
        if not code.strip():
            continue
        # 日志调用属于内部诊断,不纳入 UI 文案 i18n 检查
        if re.search(r'\b(Logger|Log)\.[A-Za-z]+\(.*[\u4e00-\u9fff]', code):
            continue
        if CJK_IN_QUOTED_STRING.search(code):
            violations.append(Violation(path, lineno, line.strip()))
        elif '"' in code and CJK_WITH_QUOTE.search(code):
            violations.append(Violation(path, lineno, line.strip()))
    return violations


def scan_all() -> dict[str, list[Violation]]:
    result: dict[str, list[Violation]] = {}
    for kt in sorted(UI_DIR.rglob("*.kt")):
        vs = scan_file(kt)
        if vs:
            result[str(kt.relative_to(PROJECT_ROOT)).replace("\\", "/")] = vs
    return result


def load_baseline(path: Path) -> dict[str, int]:
    baseline: dict[str, int] = {}
    if not path.exists():
        return baseline
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        file_part, sep, count_part = line.rpartition(":")
        if not sep:
            continue
        try:
            baseline[file_part.strip()] = int(count_part.strip())
        except ValueError:
            continue
    return baseline


def write_baseline(path: Path, violations: dict[str, list[Violation]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "# hardcoded_cjk baseline — 存量硬编码中文字面量豁免清单",
        "# 格式: <file>: <违规数>;新增或上升都会失败,只允许下降",
        "# 清理存量后运行: py -3 ci/script/check_hardcoded_cjk.py --update-baseline",
        "#",
        f"# 当前共 {sum(len(v) for v in violations.values())} 处,分布 {len(violations)} 个文件",
    ]
    for file, vs in sorted(violations.items()):
        lines.append(f"{file}: {len(vs)}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="UI 硬编码中文字面量检查")
    parser.add_argument("--baseline", type=Path, default=BASELINE_PATH, help="baseline 文件路径")
    parser.add_argument("--update-baseline", action="store_true", help="用当前扫描结果重写 baseline")
    parser.add_argument("--verbose", action="store_true", help="打印每一处违规明细")
    args = parser.parse_args()

    violations = scan_all()
    total = sum(len(v) for v in violations.values())

    if args.update_baseline:
        write_baseline(args.baseline, violations)
        print(f"baseline 已更新: {args.baseline}")
        print(f"  共 {total} 处硬编码中文字面量,分布 {len(violations)} 个文件")
        return 0

    print(f"[hardcoded-cjk] 扫描 ui/ 目录: {total} 处硬编码中文字面量,分布 {len(violations)} 个文件")

    if args.verbose:
        for file, vs in sorted(violations.items()):
            for v in vs:
                print(f"  {file}:{v.line} {v.code}")

    if not args.baseline.exists():
        print("[hardcoded-cjk] 未找到 baseline,仅报告不拦截。生成: --update-baseline")
        return 0

    baseline = load_baseline(args.baseline)
    failures: list[str] = []
    improved: list[str] = []
    for file, vs in sorted(violations.items()):
        current = len(vs)
        allowed = baseline.get(file, 0)
        if current > allowed:
            delta = current - allowed
            failures.append(f"  {file}: {current} 处 (baseline {allowed},新增 {delta})")
        elif current < allowed:
            improved.append(f"  {file}: {allowed} → {current} (清理了 {allowed - current} 处)")
    cleaned_files = [f for f in baseline if f not in violations]
    for f in cleaned_files:
        improved.append(f"  {f}: 已清零")

    if improved:
        print("[hardcoded-cjk] 有改善,建议运行 --update-baseline 收紧:")
        for line in improved:
            print(line)

    if failures:
        print("[hardcoded-cjk] FAILED — 新增硬编码中文字面量:")
        for line in failures:
            print(line)
        print("修复方式: 使用 stringResource(R.string.xxx);若为存量请清理后 --update-baseline")
        return 1

    print(f"[hardcoded-cjk] PASS — 无新增硬编码中文字面量 (存量 {total} 处,按 baseline 豁免)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
