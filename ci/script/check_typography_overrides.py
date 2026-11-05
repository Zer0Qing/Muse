#!/usr/bin/env python3
"""check_typography_overrides.py — 字重/字距/行高的**就地覆盖**棘轮检查（整改计划 第 3 条）。

量测（本轮实测，不是印象）：
  - `ui/`（除 `theme/`）420 个文件里，`fontWeight =` 353 处、`letterSpacing =` 10 处、`lineHeight =` 6 处；
  - 其中形状高度集中：`copy(fontWeight = FontWeight.SemiBold)` 47 处、`Medium` 20 处、`Bold` 3 处。
  - 主题本身（`theme/Type.kt`）已经把 15 个角色的字号/字重/字距都定好了，
    所以问题不是"没规范"，而是**任何页面都能就地改一笔，改完全站就不一致了**。

因此这条检查做的是棘轮而不是清零：
  - 每个文件按规则计数，超过基线即失败（新增覆盖必须写明理由或复用角色）；
  - 低于基线时提示 `--update-baseline` 收紧，防止改善成果被后来者悄悄用回去。
不做的是一键批量替换：`copy(fontWeight=…)` 分散在 119 个文件，基角色各不相同
（`bodySmall` 的 SemiBold 与 `titleSmall` 的 SemiBold 是两个不同东西），
机械合并会改坏视觉，这一步必须逐屏看真机效果。

用法：
    py -3 ci/script/check_typography_overrides.py [--update-baseline] [--verbose]
退出码：0 通过；1 有新增覆盖。
"""

from __future__ import annotations

import argparse
import re
import sys
from collections import Counter
from pathlib import Path

# Windows 中文控制台默认 GBK，打印 ✓/✗ 会抛 UnicodeEncodeError 让门禁假死
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

PROJECT_ROOT = Path(__file__).resolve().parents[2]
UI_ROOT = PROJECT_ROOT / "app" / "src" / "main" / "java" / "io" / "zer0" / "muse" / "ui"
BASELINE_PATH = PROJECT_ROOT / "ci" / "baseline" / "typography_overrides_baseline.txt"

# 规则名 → 匹配式。都只抓「就地覆盖」这一动作，不抓主题自己的定义。
RULES: dict[str, re.Pattern[str]] = {
    "inline_font_weight": re.compile(r"\bfontWeight\s*="),
    "inline_letter_spacing": re.compile(r"\bletterSpacing\s*="),
    "inline_line_height": re.compile(r"\blineHeight\s*="),
}


def scan_file(path: Path) -> Counter:
    hits: Counter = Counter()
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError:
        return hits
    for line in lines:
        stripped = line.strip()
        if stripped.startswith("//") or stripped.startswith("*"):
            # 注释里提到 `fontWeight =` 不算覆盖（曾经被 KDoc 举例骗过一次，别再来一遍）
            continue
        for rule, pattern in RULES.items():
            if pattern.search(line):
                hits[rule] += 1
    return hits


def scan_all() -> dict[str, Counter]:
    result: dict[str, Counter] = {}
    for path in sorted(UI_ROOT.rglob("*.kt")):
        if "\\theme\\" in str(path) or "/theme/" in str(path):
            continue
        hits = scan_file(path)
        if not hits:
            continue
        # 注意：`Path.replace()` 是**重命名**语义，不是字符串替换 —— 必须先转成字符串。
        relative = str(path.relative_to(PROJECT_ROOT)).replace("\\", "/")
        result[relative] = hits
    return result


def load_baseline() -> dict[str, dict[str, int]]:
    if not BASELINE_PATH.is_file():
        return {}
    baseline: dict[str, dict[str, int]] = {}
    current_file = ""
    for raw in BASELINE_PATH.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.endswith(":"):
            current_file = line[:-1]
            baseline[current_file] = {}
            continue
        parts = line.split()
        if len(parts) == 2 and current_file:
            baseline[current_file][parts[0]] = int(parts[1])
    return baseline


def write_baseline(scans: dict[str, Counter]) -> None:
    lines = [
        "# 就地覆盖字重/字距/行高的基线（棘轮：只准降不准升）。",
        "# 生成：py -3 ci/script/check_typography_overrides.py --update-baseline",
        "# 每段第一行是文件路径，缩进行是 `规则名 次数`。",
    ]
    for file_name in sorted(scans):
        lines.append(f"{file_name}:")
        for rule in sorted(scans[file_name]):
            lines.append(f"  {rule} {scans[file_name][rule]}")
    BASELINE_PATH.parent.mkdir(parents=True, exist_ok=True)
    BASELINE_PATH.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


def main() -> int:
    parser = argparse.ArgumentParser(description="字重/字距/行高就地覆盖棘轮")
    parser.add_argument("--update-baseline", action="store_true", help="把当前计数写为新基线（收紧时用）")
    parser.add_argument("--verbose", action="store_true", help="打印总量与 Top 文件")
    args = parser.parse_args()

    scans = scan_all()
    if args.update_baseline:
        write_baseline(scans)
        total = sum(sum(c.values()) for c in scans.values())
        print(f"已写入基线：{len(scans)} 个文件、共 {total} 处就地覆盖。")
        return 0

    baseline = load_baseline()
    if not baseline:
        print("FAIL: 缺基线文件，先跑 --update-baseline 生成。")
        return 1

    regressions: list[str] = []
    improvements: list[str] = []
    for file_name, hits in scans.items():
        allowed = baseline.get(file_name, {})
        for rule in RULES:
            now = hits.get(rule, 0)
            was = allowed.get(rule, 0)
            if now > was:
                regressions.append(f"{file_name}: [{rule}] 现在 {now} 处，基线允许 {was} 处")
            elif now < was:
                improvements.append(f"{file_name}: [{rule}] {was} → {now}")

    # 基线里已消失的文件也要算改善（整屏收敛了）
    for file_name, allowed in baseline.items():
        if file_name in scans:
            continue
        improvements.append(f"{file_name}: 已清零（原 {sum(allowed.values())} 处）")

    if args.verbose:
        total_now = sum(sum(c.values()) for c in scans.values())
        print(f"当前共 {total_now} 处就地覆盖，涉及 {len(scans)} 个文件。")

    if regressions:
        print(f"FAIL: 新增就地覆盖 {len(regressions)} 处（字型应当复用 theme/Type.kt 的角色）：")
        for item in regressions:
            print(f"  - {item}")
        print("\n确属必要时（如品牌数字、等宽对齐）请说明理由后再 --update-baseline。")
        return 1

    total_now = sum(sum(c.values()) for c in scans.values())
    if improvements:
        print(f"OK（有改善待收紧）：当前 {total_now} 处就地覆盖；下列文件比基线更少，跑 --update-baseline 锁住：")
        for item in improvements[:10]:
            print(f"  - {item}")
        return 0

    print(f"OK: 就地覆盖未增长（当前 {total_now} 处 / {len(scans)} 个文件，均在基线内）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
