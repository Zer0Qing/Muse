#!/usr/bin/env python3
"""门禁：作者侧的 commands / schedules 判据必须真的会拒。

为什么需要：App 端对这些限制是**静默**处理的 —— 超过 20 条命令被 `take()` 截掉、
间隔低于 15 分钟被 `coerceAtLeast` 抬高、名字非法的那条整条不注册。
作者在打包机上什么都看不见，到设备上才发现"命令少了两条""任务怎么 15 分钟才跑一次"。
`sign.py` 现在把这些判据镜像过来了，但"镜像了"不等于"生效了"，所以逐条构造违规输入去撞它。

不依赖 openssl：违规用例在签名前就被拒；对照用例（合法清单）要求它**越过校验阶段**——
如果本机没有 openssl，报错应当是 openssl 相关，那同样证明校验通过了。

退出码：0 通过；1 红；2 环境不具备（找不到 python/工具）。
"""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]
SIGN = PROJECT_ROOT / "tools" / "market-signer" / "sign.py"

for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

ENTRY_JS = """export function demo(args) {
  return { ok: true, valueJson: JSON.stringify(args ?? {}) };
}
"""

BASE_MANIFEST = {
    "id": "lint-probe",
    "name": "判据探针",
    "version": "1.0.0",
    "description": "用于校验器自测",
    "author": "ci",
    "entry": "main.js",
    "runtime": "node",
    "kind": "tool",
    "trust": "sandboxed",
    "capabilities": [],
    "activationEvents": [],
    "tools": [
        {
            "name": "demo",
            "description": "演示",
            "parametersJson": "{}",
            "requiredJson": "[]",
            "functionName": "demo",
        }
    ],
    "commands": [
        {"name": "hello", "description": "打个招呼", "handler": "onHello", "kind": "tool"}
    ],
    "schedules": [{"name": "daily", "handler": "onDaily", "intervalMinutes": 720}],
}

# (用例名, 对 manifest 的改动, 期望出现在错误里的片段)
CASES = [
    (
        "命令数超上限",
        lambda m: m.update(commands=[
            {"name": f"c{i}", "description": "x", "handler": f"h{i}", "kind": "tool"} for i in range(21)
        ]),
        "commands 超过上限 20",
    ),
    (
        "命令名非法（大写）",
        lambda m: m.update(commands=[{"name": "Bad_Name", "description": "x", "handler": "h", "kind": "tool"}]),
        "命令名 'Bad_Name' 不合法",
    ),
    (
        "命令名重复",
        lambda m: m.update(commands=[
            {"name": "dup", "description": "x", "handler": "h1", "kind": "tool"},
            {"name": "dup", "description": "y", "handler": "h2", "kind": "tool"},
        ]),
        "命令名重复: dup",
    ),
    (
        "命令缺 handler",
        lambda m: m.update(commands=[{"name": "nohandler", "description": "x", "handler": "", "kind": "tool"}]),
        "命令 /nohandler 缺 handler",
    ),
    (
        "命令说明超长",
        lambda m: m.update(commands=[{"name": "longdesc", "description": "说" * 121, "handler": "h", "kind": "tool"}]),
        "超过上限 120",
    ),
    (
        "定时任务间隔低于下限",
        lambda m: m.update(schedules=[{"name": "fast", "handler": "h", "intervalMinutes": 5}]),
        "低于下限 15",
    ),
    (
        "定时任务重名",
        lambda m: m.update(schedules=[
            {"name": "same", "handler": "a", "intervalMinutes": 60},
            {"name": "same", "handler": "b", "intervalMinutes": 90},
        ]),
        "定时任务名重复: same",
    ),
    (
        "定时任务间隔不是整数",
        lambda m: m.update(schedules=[{"name": "weird", "handler": "h", "intervalMinutes": "60"}]),
        "必须是整数",
    ),
]


def write_plugin(root: Path, manifest: dict) -> Path:
    src = root / "plugin"
    src.mkdir(parents=True, exist_ok=True)
    (src / "main.js").write_text(ENTRY_JS, encoding="utf-8")
    (src / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return src


def run_pack(src: Path, out: Path, key: Path) -> tuple[int, str]:
    env = {**subprocess.os.environ, "PYTHONIOENCODING": "utf-8"}
    completed = subprocess.run(
        [
            sys.executable, str(SIGN), "pack",
            "--src", str(src),
            "--key", str(key),
            "--publisher-id", "ci",
            "--out-dir", str(out),
        ],
        cwd=PROJECT_ROOT,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        env=env,
        timeout=180,
    )
    return completed.returncode, (completed.stdout or "") + (completed.stderr or "")


def main() -> int:
    if not SIGN.is_file():
        print(f"FAIL: 找不到 {SIGN}")
        return 1
    openssl = subprocess.os.environ.get("MUSE_OPENSSL") or shutil.which("openssl")
    with tempfile.TemporaryDirectory(prefix="muse-lint-gate-") as tmp:
        root = Path(tmp)
        # 密钥只要文件存在即可通过前置检查：用一个占位私钥，
        # 违规用例走不到签名；对照用例若没有真密钥/openssl，会以签名或 openssl 错误结束——
        # 那同样说明「校验阶段通过了」，所以判定条件只看错误内容里是否出现校验类文案。
        key = root / "publisher.pem"
        key.write_text("PLACEKEY\n", encoding="utf-8")
        failures: list[str] = []

        for name, mutate, expected in CASES:
            manifest = json.loads(json.dumps(BASE_MANIFEST))
            mutate(manifest)
            src = write_plugin(root / f"case-{len(failures)}-{name[:8]}", manifest)
            code, output = run_pack(src, root / "out", key)
            if code == 0 or expected not in output:
                failures.append(f"{name}: 期望被拒（含 {expected!r}），实际 exit={code}\n{output[-300:]}")

        # 对照：合法清单必须**越过**这些校验（不能因为我的新规则被误拒）
        src = write_plugin(root / "control", json.loads(json.dumps(BASE_MANIFEST)))
        code, output = run_pack(src, root / "out", key)
        guard_hits = [text for text in ("commands 超过上限", "命令名", "命令 /", "定时任务") if text in output]
        if code == 0:
            print("  对照用例：合法清单打包成功 ✓")
        elif guard_hits:
            failures.append(f"对照用例被误拒（合法清单却撞上判据）：{guard_hits}\n{output[-400:]}")
        else:
            stage = "openssl/签名阶段" if (openssl is None or "openssl" in output or "私钥" in output) else "签名之后的其它阶段"
            print(f"  对照用例：已越过 commands/schedules 校验（停在 {stage}）✓")

        if failures:
            print("FAIL: commands/schedules 判据未按预期生效：")
            for failure in failures:
                print(f"  - {failure}")
            return 1

    print(f"OK: {len(CASES)} 条违规全部被拒 + 合法对照未被误杀。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
