#!/usr/bin/env python3
"""
check_perf_hotspots.py — 性能热点的「回归哨兵」（基线式）。

## 为什么需要这条门禁

2026-11-01 的性能整改里，最值钱的几项不是"加了什么"，而是**去掉了什么**：
每行日志开关文件、环形缓冲每行 O(n) 遍历、每帧跑 BPE、每个调用新建序列化器/正则……
这些写法都是**写起来自然、review 看着无害、回退时没人发现**。

这条门禁把它们钉死：**以当前状态为基线，只报新增**。
这样存量噪声不影响判断，而"下次重构又写回去"会被立刻抓住。

## 规则

| 规则 | 匹配 | 为什么是热点 |
|---|---|---|
| `log_append_text` | `appendText(` | 每行 open+write+close 三次系统调用（应改常驻句柄） |
| `deque_size` | `ConcurrentLinkedDeque` 文件里的 `.size` 判断 | `size()` 是 O(n) 遍历 |
| `inline_json` | 非 `val x =` 形式的 `Json {` | 每次调用新建序列化器 |
| `inline_regex` | 非 `val x =` 形式的 `Regex("` | 每次调用重新编译 |
| `bpe_in_composable` | `@Composable` 邻近行出现 `TokenEstimator.estimate(` | 组合期跑 BPE |

用法：
  py -3 ci/script/check_perf_hotspots.py                  # 只报新增
  py -3 ci/script/check_perf_hotspots.py --update-baseline
  py -3 ci/script/check_perf_hotspots.py --verbose
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]
BASELINE_PATH = PROJECT_ROOT / "ci" / "baseline" / "perf_hotspots_baseline.json"

SCAN_DIRS = [
    PROJECT_ROOT / "app" / "src" / "main" / "java",
    PROJECT_ROOT / "common" / "src" / "main" / "java",
    PROJECT_ROOT / "memory" / "src" / "main" / "java",
]

# 「实现本身」不算违规
EXEMPT_FILES = {"Logger.kt", "DebugLogStore.kt"}

VAL_DECL = re.compile(r"^\s*(?:private |internal |public |protected |const )?val \w+\s*(?::[^=]+)?=")
COMPOSABLE = re.compile(r"^\s*@Composable\b")
RULES = {
    # 只认真正的 File.appendText；自定义方法名 appendText(...) 在下面按行排除
    "log_append_text": re.compile(r"\.appendText\("),
    "inline_json": re.compile(r"\bJson\s*\{"),
    "inline_regex": re.compile(r"\bRegex\(\s*\""),
    "bpe_in_composable": re.compile(r"TokenEstimator\.estimate\("),
    "deque_size": re.compile(r"\.size\b"),
}


def iter_kt_files():
    for base in SCAN_DIRS:
        if base.is_dir():
            yield from (p for p in base.rglob("*.kt") if p.name not in EXEMPT_FILES)


def scan_file(path: Path) -> dict[str, int]:
    counts: dict[str, int] = {}
    text = path.read_text(encoding="utf-8", errors="ignore")
    lines = text.splitlines()
    mentions_deque = "ConcurrentLinkedDeque" in text
    composable_window = 0

    for line in lines:
        stripped = line.strip()
        if stripped.startswith("//") or stripped.startswith("*"):
            continue

        if COMPOSABLE.match(line):
            composable_window = 12   # 组合函数体大致范围
        elif composable_window > 0:
            composable_window -= 1

        # 排除自定义方法定义/调用：`fun appendText(` 与流式缓冲里同名的 appendText(...)
        if RULES["log_append_text"].search(line) and "fun appendText" not in line and "appendText(sequenceInStream" not in line:
            counts["log_append_text"] = counts.get("log_append_text", 0) + 1

        # 声明式赋值（类/对象级 val）不算"每次调用新建"
        declared = bool(VAL_DECL.match(line))
        if not declared and RULES["inline_json"].search(line):
            counts["inline_json"] = counts.get("inline_json", 0) + 1
        if not declared and RULES["inline_regex"].search(line):
            counts["inline_regex"] = counts.get("inline_regex", 0) + 1

        if composable_window > 0 and RULES["bpe_in_composable"].search(line):
            counts["bpe_in_composable"] = counts.get("bpe_in_composable", 0) + 1

        if mentions_deque and RULES["deque_size"].search(line) and re.search(r"\b(?:if|while|for)\b", line):
            counts["deque_size"] = counts.get("deque_size", 0) + 1

    return counts


def current_counts() -> dict[str, int]:
    result: dict[str, int] = {}
    for path in sorted(iter_kt_files()):
        rel = path.relative_to(PROJECT_ROOT).as_posix()
        for rule, count in scan_file(path).items():
            result[f"{rel}|{rule}"] = count
    return result


def load_baseline() -> dict[str, int]:
    if not BASELINE_PATH.exists():
        return {}
    return json.loads(BASELINE_PATH.read_text(encoding="utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--update-baseline", action="store_true")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    current = current_counts()

    if args.update_baseline:
        BASELINE_PATH.parent.mkdir(parents=True, exist_ok=True)
        BASELINE_PATH.write_text(
            json.dumps(dict(sorted(current.items())), indent=2, ensure_ascii=False) + "\n",
            encoding="utf-8",
        )
        print(f"已写入基线：{BASELINE_PATH.relative_to(PROJECT_ROOT)}（{len(current)} 条）")
        return 0

    baseline = load_baseline()
    regressions = []
    for key, count in sorted(current.items()):
        allowed = baseline.get(key, 0)
        if count > allowed:
            regressions.append((key, allowed, count))

    if regressions:
        print("FAILED — 新增性能热点写法：")
        for key, allowed, count in regressions:
            path, rule = key.rsplit("|", 1)
            print(f"  [{rule}] {path}: {count} 处（基线 {allowed}）")
        print()
        print("对照本脚本头部注释；确属必要请说明理由后 --update-baseline。")
        return 1

    if args.verbose:
        print(f"当前共 {len(current)} 条记录，均在基线内。")
    print("OK: 未新增性能热点写法。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
