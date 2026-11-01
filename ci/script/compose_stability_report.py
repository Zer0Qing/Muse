#!/usr/bin/env python3
"""
compose_stability_report.py — 把 Compose 编译器的稳定性报告汇总成可核对的清单。

## 为什么需要它

`app/build.gradle.kts` 打开了 `composeCompiler { reportsDestination / metricsDestination }`，
产物是 `app/build/compose_reports/` 下三个几百 KB 的文本文件。直接读有三个麻烦：
报告按 composable 罗列、不稳定参数**不带 `unstable` 前缀**（没写 `stable` 就是不稳定）、
以及「哪些落在对话/卡片链路上」需要人工过滤。

性能整改时真正要回答的是两个问题：
  1. 有多少 composable 不可跳过（重组优化的入口条件）？
  2. 对话/卡片链路上哪些 composable 带不稳定参数？

## 用法

    # 先跑一次编译让报告生成
    .\\gradlew.bat :app:compileDebugKotlin --rerun-tasks
    python ci/script/compose_stability_report.py

输出：`build/perf/compose_report_summary.txt`，并在 stdout 打印摘要。

## 口径

- `restartable skippable ...` = 可跳过；`restartable ...`（无 skippable）= 不可跳过。
- 参数行只有带 `stable ` 前缀的才算稳定；其余按编译器口径视为不稳定。
- 「对话/卡片链路」= 包名或函数名命中 [FOCUS_PACKAGES] / [FOCUS_NAMES]，可按需扩展。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]
REPORT = PROJECT_ROOT / "app" / "build" / "compose_reports" / "app-composables.txt"
CLASSES = PROJECT_ROOT / "app" / "build" / "compose_reports" / "app-classes.txt"
OUT = PROJECT_ROOT / "build" / "perf" / "compose_report_summary.txt"

FOCUS_PACKAGES = (
    "ui.MessageBubble",
    "ui.markdown",
    "ui.chat",
    "ui.InputBar",
    "ui.ChatScreenComponents",
    "ui.common",
)
FOCUS_NAMES = (
    "MessageBubble",
    "MarkdownText",
    "RichContentCard",
    "CardRuntime",
    "ChatScreen",
    "InputBar",
    "CodeBlockView",
    "TableView",
    "MoodSection",
    "MuseCard",
)


def parse_entries(text: str) -> list[tuple[str, list[str]]]:
    """把报告切成 [(头部行, [参数行...]), ...]。"""
    entries: list[tuple[str, list[str]]] = []
    head: str | None = None
    params: list[str] = []
    for line in text.splitlines():
        if line.startswith("restartable"):
            if head is not None:
                entries.append((head, params))
            head, params = line, []
        elif head is not None:
            if line.startswith("  "):
                params.append(line.strip())
            elif not line.strip():
                entries.append((head, params))
                head, params = None, []
    if head is not None:
        entries.append((head, params))
    return entries


def main() -> None:
    if not REPORT.exists():
        print(f"[compose-stability] 报告不存在：{REPORT}", file=sys.stderr)
        print(
            "[compose-stability] 先跑一次：.\\gradlew.bat :app:compileDebugKotlin --rerun-tasks",
            file=sys.stderr,
        )
        sys.exit(1)

    entries = parse_entries(REPORT.read_text(encoding="utf-8", errors="replace"))
    total = len(entries)
    if total < 300:
        print(
            f"[compose-stability] 警告：报告只解析出 {total} 个 composable，"
            "极可能是增量编译产物（只含本次重编译的文件）。"
            "要全量清单请跑 --rerun-tasks。",
            file=sys.stderr,
        )
    skippable = [entry for entry in entries if "skippable" in entry[0].split("scheme")[0]]
    non_skippable = [entry for entry in entries if entry not in skippable]

    unstable: dict[str, list[str]] = {}
    for head, params in non_skippable:
        match = re.search(r"fun ([\w.$]+)\(", head)
        fn = match.group(1) if match else head
        bad = [param for param in params if not param.startswith("stable")]
        if bad:
            unstable[fn] = bad

    lines: list[str] = [
        f"composables total = {total}",
        f"skippable = {len(skippable)}  ({len(skippable) * 100 // max(total, 1)}%)",
        f"NOT skippable = {len(non_skippable)}",
        "",
        "=== 不可跳过、且落在对话/卡片/公共组件链路上的（按不稳定参数数量排）===",
    ]
    rows: list[tuple[int, str, list[str]]] = []
    for fn, bad in unstable.items():
        simple = fn.split(".")[-1]
        if any(key in fn for key in FOCUS_PACKAGES) or any(name in simple for name in FOCUS_NAMES):
            rows.append((len(bad), fn, bad))
    rows.sort(reverse=True)
    for count, fn, bad in rows:
        lines.append(f"- {fn}  [{count} 个不稳定参数]")
        for param in bad:
            lines.append(f"    · {param}")
    lines.append("")
    lines.append(f"（对话/卡片链路上共 {len(rows)} 个不可跳过 composable）")

    lines.extend(["", "=== 被判为不稳定的 class（去重，前 60）==="])
    if CLASSES.exists():
        names = re.findall(
            r"unstable class ([\w.$]+)",
            CLASSES.read_text(encoding="utf-8", errors="replace"),
        )
        seen: list[str] = []
        for name in names:
            if name not in seen:
                seen.append(name)
        lines.append(f"unstable class 去重后 = {len(seen)}")
        lines.extend(f"- {name}" for name in seen[:60])
    else:
        lines.append("(app-classes.txt 缺失)")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(lines), encoding="utf-8")
    print("\n".join(lines[:24]))
    print(f"\n[full] {OUT}")


if __name__ == "__main__":
    main()
