#!/usr/bin/env python3
"""
check_design_tokens.py — 设计令牌 CI 检查。

扫描 ui/ 目录下的 Jetpack Compose 代码,拦截绕过设计令牌体系的硬编码:
  - Color(0x...) 裸色字面量 (error)
  - Color(255, 0, 0) RGB 裸色 (error)
  - 硬编码 .dp / .sp 统计 (info,只报告数量不拦截)

豁免目录:
  - ui/theme/ — Color.kt / PresetTheme.kt / CustomTheme.kt / DesignTokens.kt 等,
    这里是颜色定义的唯一合法位置。

Baseline 机制 (存量放过,新增拦截,只降不升):
  - CI 模式 (--baseline ci/baseline/design_tokens_baseline.txt):
    逐文件对比违规数,多于 baseline 或出现新违规文件则失败;少于 baseline 通过。
  - 更新 baseline (--update-baseline):清理存量后重新生成。

用法:
  py -3 ci/script/check_design_tokens.py                      # 全量扫描并打印违规
  py -3 ci/script/check_design_tokens.py --baseline <path>    # CI 对比模式
  py -3 ci/script/check_design_tokens.py --update-baseline    # 重新生成 baseline
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]

UI_DIR = PROJECT_ROOT / "app" / "src" / "main" / "java" / "io" / "zer0" / "muse" / "ui"

# 豁免:主题定义目录,颜色字面量的唯一合法位置
EXEMPT_DIRS = {"theme"}

BASELINE_PATH = PROJECT_ROOT / "ci" / "baseline" / "design_tokens_baseline.txt"

# 规则定义
RULES = {
    "hardcoded_hex_color": {
        "pattern": re.compile(r"\bColor\(\s*0[xX][0-9a-fA-F]{6,8}"),
        "severity": "error",
        "message": "硬编码裸色 Color(0x...),请使用 MaterialTheme.colorScheme 或 theme/ 中定义的令牌",
    },
    "hardcoded_rgb_color": {
        # Color(255, 0, 0) / Color(red = 255, ...) 形式
        "pattern": re.compile(r"\bColor\(\s*(?:red\s*=\s*)?\d{1,3}\s*,"),
        "severity": "error",
        "message": "硬编码 RGB 裸色 Color(r, g, b),请使用 MaterialTheme.colorScheme 或 theme/ 中定义的令牌",
    },
    # copy(alpha=...) 的 Color.XXX.copy 不属于硬编码,不拦截
}

# 信息统计规则(只计数,不影响通过/失败)
INFO_RULES = {
    "hardcoded_dp": re.compile(r"\b\d+(?:\.\d+)?\.dp\b"),
    "hardcoded_sp": re.compile(r"\b\d+(?:\.\d+)?\.sp\b"),
}


@dataclass
class Violation:
    file: Path
    line: int
    rule: str
    message: str
    code: str


def is_exempt(path: Path) -> bool:
    """theme/ 目录下的文件是颜色定义区,豁免检查。"""
    try:
        rel = path.relative_to(UI_DIR)
    except ValueError:
        return False
    return rel.parts[0] in EXEMPT_DIRS


def scan_file(path: Path) -> list[Violation]:
    violations: list[Violation] = []
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeDecodeError) as e:
        print(f"WARN: 无法读取 {path}: {e}", file=sys.stderr)
        return violations
    in_block_comment = False
    for lineno, line in enumerate(lines, 1):
        # 粗略剔除块注释与行注释,避免注释里的示例代码误报
        code = line
        if in_block_comment:
            if "*/" in code:
                code = code.split("*/", 1)[1]
                in_block_comment = False
            else:
                continue
        while "/*" in code:
            before, _, after = code.partition("/*")
            if "*/" in after:
                code = before + after.split("*/", 1)[1]
            else:
                code = before
                in_block_comment = True
        code = code.split("//", 1)[0]
        if not code.strip():
            continue
        for rule_name, rule in RULES.items():
            if rule["pattern"].search(code):
                violations.append(
                    Violation(path, lineno, rule_name, rule["message"], line.strip())
                )
                break  # 一行只记一次,避免双规则重复计数
    return violations


def scan_all() -> dict[str, list[Violation]]:
    """返回 {相对路径: [违规列表]},只包含有违规的文件。"""
    result: dict[str, list[Violation]] = {}
    for kt in sorted(UI_DIR.rglob("*.kt")):
        if is_exempt(kt):
            continue
        vs = scan_file(kt)
        if vs:
            result[str(kt.relative_to(PROJECT_ROOT)).replace("\\", "/")] = vs
    return result


def count_info_stats() -> tuple[int, int]:
    """统计全 ui/ 目录的硬编码 .dp / .sp 数量(含 theme/,仅供参考)。"""
    dp = sp = 0
    for kt in UI_DIR.rglob("*.kt"):
        try:
            text = kt.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        dp += len(INFO_RULES["hardcoded_dp"].findall(text))
        sp += len(INFO_RULES["hardcoded_sp"].findall(text))
    return dp, sp


def load_baseline(path: Path) -> dict[str, int]:
    """baseline 格式: 每行 'file/path.kt: <count>'。"""
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
        "# design_tokens baseline — 存量硬编码裸色豁免清单",
        "# 格式: <file>: <违规数>;CI 只允许违规数下降,新增或上升都会失败",
        "# 清理存量后运行: py -3 ci/script/check_design_tokens.py --update-baseline",
        "#",
        f"# 当前共 {sum(len(v) for v in violations.values())} 处,分布 {len(violations)} 个文件",
    ]
    for file, vs in sorted(violations.items()):
        lines.append(f"{file}: {len(vs)}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="设计令牌硬编码检查")
    parser.add_argument("--baseline", type=Path, default=BASELINE_PATH, help="baseline 文件路径")
    parser.add_argument("--update-baseline", action="store_true", help="用当前扫描结果重写 baseline")
    parser.add_argument("--verbose", action="store_true", help="打印每一处违规明细")
    args = parser.parse_args()

    violations = scan_all()
    total = sum(len(v) for v in violations.values())
    dp_count, sp_count = count_info_stats()

    if args.update_baseline:
        write_baseline(args.baseline, violations)
        print(f"baseline 已更新: {args.baseline}")
        print(f"  共 {total} 处裸色,分布 {len(violations)} 个文件")
        return 0

    print(f"[design-tokens] 扫描 ui/ 目录: {total} 处硬编码裸色,分布 {len(violations)} 个文件")
    print(f"[design-tokens] 参考统计: {dp_count} 处 .dp / {sp_count} 处 .sp 硬编码")

    if args.verbose:
        for file, vs in sorted(violations.items()):
            for v in vs:
                print(f"  {file}:{v.line} [{v.rule}] {v.message}")
                print(f"    {v.code}")

    if not args.baseline.exists():
        # 无 baseline 文件:只报告不失败(首次接入前的过渡态)
        print("[design-tokens] 未找到 baseline,仅报告不拦截。生成: --update-baseline")
        return 0
    # baseline 文件存在即为生效状态(空清单 = 零容忍,任何新增都失败)
    baseline = load_baseline(args.baseline)

    # 对比模式:只降不升
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
        print(f"[design-tokens] 有改善,建议运行 --update-baseline 收紧:")
        for line in improved:
            print(line)

    if failures:
        print(f"[design-tokens] FAILED — 新增硬编码裸色 (AGENTS.md 设计令牌规范):")
        for line in failures:
            print(line)
        print("修复方式: 使用 MaterialTheme.colorScheme / theme/ 令牌;若为存量请清理后 --update-baseline")
        return 1

    print(f"[design-tokens] PASS — 无新增裸色 (存量 {total} 处,按 baseline 豁免)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
