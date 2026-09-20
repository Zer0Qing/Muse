#!/usr/bin/env python3
"""
check_ktlint_report.py — P4-1 门禁断言。

背景: AGP 9 内置 Kotlin 支持导致 ktlint-gradle 12.1.1 的 Android 源集钩子失效,
ktlintCheck 只查 .kts(假绿)。根 build.gradle.kts 因此自建 ktlintKotlinSourceCheck
任务,用 ktlint CLI 真扫各模块 Kotlin 源,并写入
build/reports/ktlint/ktlintKotlinSourceCheck.txt(内含 @linted-file-count=<N> 行)。

本脚本断言: 每个模块的该报告必须存在、非空、且已如实记录本次落 lint 的 .kt 文件数
(N > 0) —— 否则说明门禁再次退化为空跑,直接失败。

运行: py -3 ci/script/check_ktlint_report.py
"""

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# P4-1: 已接入 ktlintKotlinSourceCheck 的模块(与根 build.gradle.kts 插件列表一致)
MODULES = ["app", "common", "memory", "ai", "material3", "accessibility"]


def linted_file_count(report_text: str) -> int | None:
    """解析报告中的 @linted-file-count=<N> 行,缺失返回 None。"""
    for line in report_text.splitlines():
        line = line.strip()
        if line.startswith("@linted-file-count="):
            try:
                return int(line.split("=", 1)[1])
            except ValueError:
                return None
    return None


def check_module(module: str) -> list[str]:
    """校验单模块报告,返回错误信息列表(空列表 = 通过)。"""
    errors: list[str] = []
    report = ROOT / module / "build" / "reports" / "ktlint" / "ktlintKotlinSourceCheck.txt"
    if not report.exists():
        errors.append(f"{module}: ktlint 报告缺失 {report.relative_to(ROOT)} — ktlintKotlinSourceCheck 未执行,门禁退化为空跑")
        return errors
    text = report.read_text(encoding="utf-8", errors="replace")
    if not text.strip():
        errors.append(f"{module}: ktlint 报告为空文件 {report.relative_to(ROOT)} — 不能证明真实 lint 了 Kotlin 源")
        return errors
    count = linted_file_count(text)
    if count is None:
        errors.append(f"{module}: 报告缺少 @linted-file-count= 行 — 未证明覆盖任何 .kt 源文件")
    elif count <= 0:
        errors.append(f"{module}: @linted-file-count={count} — 落 lint 的 .kt 文件数为 0,门禁无意义")
    return errors


def main() -> int:
    all_errors: list[str] = []
    for module in MODULES:
        all_errors.extend(check_module(module))
    if all_errors:
        print("P4-1 ktlint 门禁断言失败:")
        for err in all_errors:
            print(f"  - {err}")
        return 1
    print(f"P4-1 ktlint 门禁断言通过: {len(MODULES)} 个模块报告均非空且记录了真实 lint 文件数")
    return 0


if __name__ == "__main__":
    sys.exit(main())