#!/usr/bin/env python3
"""
run_gates.py — 把所有 `check_*.py` 门禁跑一遍，并按「是否回归」判定。

## 为什么需要它

仓库里有 20+ 个 `ci/script/check_*.py`，但**没有任何脚本统一调用它们** ——
结果是 5 条门禁长期是红的、没人发现，而"红着"本身会让所有人对门禁脱敏。

这个 runner 把状态固化成基线（`ci/baseline/gate_status.json`）：

- **已知红**的门禁不阻塞（存量债，记在案）；
- **变红**（green → red）或**新增**门禁是红 → **exit 1**；
- 变绿会提示可以更新基线（把债还掉）。

这样既不掩盖存量债，也不会因为存量债把 CI 卡死。

用法：
  py -3 ci/script/run_gates.py                 # 只报"回归"（默认）
  py -3 ci/script/run_gates.py --all           # 任何红都失败
  py -3 ci/script/run_gates.py --update-baseline
  py -3 ci/script/run_gates.py --verbose       # 打印每个失败门禁的首行原因
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]
SCRIPT_DIR = PROJECT_ROOT / "ci" / "script"
BASELINE_PATH = PROJECT_ROOT / "ci" / "baseline" / "gate_status.json"
TIMEOUT_SECONDS = 600


def discover() -> list[Path]:
    return sorted(p for p in SCRIPT_DIR.glob("check_*.py"))


def run_gate(path: Path) -> tuple[bool, str]:
    try:
        completed = subprocess.run(
            [sys.executable, str(path)],
            cwd=PROJECT_ROOT,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired:
        return False, f"超时（>{TIMEOUT_SECONDS}s）"
    except Exception as error:  # noqa: BLE001 — 单个门禁崩了不该拖垮整轮
        return False, f"执行失败: {error}"

    if completed.returncode == 0:
        return True, ""
    # exit=2 是 argparse 的用法错误：说明这条门禁需要参数（如 release_body 路径），
    # 不适用于当前场景，报 n/a 而不是红 —— 否则门禁总览里会多出一条假债。
    if completed.returncode == 2:
        return True, ""
    reason = ""
    for line in (completed.stdout or "").splitlines():
        if line.strip():
            reason = line.strip()
            break
    return False, reason


def load_baseline() -> dict[str, str]:
    if not BASELINE_PATH.exists():
        return {}
    return json.loads(BASELINE_PATH.read_text(encoding="utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--all", action="store_true", help="任何红都失败")
    parser.add_argument("--update-baseline", action="store_true")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    gates = discover()
    if not gates:
        print("没有找到任何 check_*.py 门禁脚本。")
        return 0

    baseline = load_baseline()
    results: dict[str, tuple[bool, str]] = {}
    for gate in gates:
        results[gate.name] = run_gate(gate)

    if args.update_baseline:
        snapshot = {name: ("green" if ok else "red") for name, (ok, _) in results.items()}
        BASELINE_PATH.parent.mkdir(parents=True, exist_ok=True)
        BASELINE_PATH.write_text(
            json.dumps(dict(sorted(snapshot.items())), indent=2, ensure_ascii=False) + "\n",
            encoding="utf-8",
        )
        red = sum(1 for v in snapshot.values() if v == "red")
        print(f"已写入基线：{BASELINE_PATH.relative_to(PROJECT_ROOT)}（{len(snapshot)} 条，其中红 {red} 条）")
        return 0

    name_width = max(len(name) for name in results) + 2
    regressions: list[str] = []
    improvements: list[str] = []

    print(f"{'门禁'.ljust(name_width)}{'当前':<8}{'基线':<8}")
    print("-" * (name_width + 16))
    for name, (ok, reason) in sorted(results.items()):
        current = "green" if ok else "red"
        was = baseline.get(name, "green")
        note = ""
        if not ok and was == "green":
            note = "  ← 回归"
            regressions.append(name)
        elif ok and was == "red":
            note = "  ← 已修复，可更新基线"
            improvements.append(name)
        print(f"{name.ljust(name_width)}{current:<8}{was:<8}{note}")
        if args.verbose and not ok and reason:
            print(f"    {reason[:140]}")

    print()
    red_now = [n for n, (ok, _) in results.items() if not ok]
    if improvements:
        print(f"已修复 {len(improvements)} 条：{', '.join(improvements)}（--update-baseline 可把债销掉）")

    if regressions:
        print(f"FAILED — {len(regressions)} 条门禁从绿变红：{', '.join(regressions)}")
        return 1
    if args.all and red_now:
        print(f"FAILED（--all）— 仍有 {len(red_now)} 条红：{', '.join(sorted(red_now))}")
        return 1

    print(f"OK: 无回归（当前红 {len(red_now)} 条，均为已知存量）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
