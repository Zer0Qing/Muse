#!/usr/bin/env python3
"""门禁：插件静态扫描器的规则必须逐条真的生效，且**证明它不执行被扫代码**。

三条不妥协的判据（对应 DSHGO 阶段 4 的验收）：

1. **逐规则可复核**：每个语料夹具都期望特定规则命中，并核对命中位置（文件:行号）。
   规则被写坏、模式串被改掉，这里就红 —— 而不是等到装预览里少了一栏。
2. **行为证明不执行**：`exec-probe` 里那段代码一旦被执行就会留下哨兵文件。
   扫完之后断言哨兵不存在。这里刻意**不**用"扫源码里有没有 subprocess/eval"那种结构检查：
   规则表本身就有 `\\bsubprocess\\b`、`\\beval\\s*\\(` 这些模式串，字符串检查会被自己的规则表骗到。
3. **报告自带边界**：`notCovered` 必须非空、免责文案必须写"静态扫描不等于安全认证"，
   并且报告必须声明它**不做**信任判定（签名与内容核验仍只由签名门负责）。

外加 zip 入口的路径穿越条目必须被跳过而不是写盘。

退出码：0 通过；1 红；2 环境不具备。
"""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]
SCANNER = PROJECT_ROOT / "tools" / "plugin-scan" / "scan.py"
TESTDATA = PROJECT_ROOT / "tools" / "plugin-scan" / "testdata"
SAMPLES = PROJECT_ROOT / "docs" / "plugin" / "samples"

for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

# 夹具名 → 期望命中的规则（info 级不计入风险，但仍会出现在 findings 里）
EXPECTED = {
    "network-undeclared": {"NET_OUTBOUND"},
    "network-declared": {"NET_OUTBOUND"},
    "fs-write": {"FS_WRITE"},
    "subprocess": {"SUBPROCESS", "DYNAMIC_CODE"},  # require('child_process') 既是子进程也是动态加载
    "native-and-install": {"NATIVE_CODE", "INSTALL_SCRIPT"},
    "dynamic-escape": {"DYNAMIC_CODE", "SANDBOX_ESCAPE"},
    "env-credentials": {"ENV_CREDENTIALS"},
    "clean": set(),
    "exec-probe": {"FS_WRITE"},  # 它写哨兵文件的 open(...,'w') 本身就是文件写特征
}

# 期望被扫描器**跳过**的夹具：不能因为语料里带危险字样就报缺失
CAPABILITY_GAP_EXPECTED = {
    "network-undeclared": True,   # 用了 fetch 却没声明 network
    "network-declared": False,    # 声明了就不得虚报缺口
}


def scan(path: Path) -> dict:
    completed = subprocess.run(
        [sys.executable, str(SCANNER), "--src", str(path)],
        cwd=PROJECT_ROOT,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        env={**subprocess.os.environ, "PYTHONIOENCODING": "utf-8"},
        timeout=180,
    )
    if completed.returncode != 0:
        raise RuntimeError(f"扫描器退出 {completed.returncode}: {completed.stderr[-400:]}")
    payload = completed.stdout.strip()
    start = payload.index("{")
    return json.loads(payload[start:])


def rules_hit(report: dict, *, include_info: bool = True) -> set[str]:
    return {f["rule"] for f in report["findings"] if include_info or f["severity"] != "info"}


def finding_shape_ok(report: dict, rule: str) -> bool:
    """每条命中都必须带可复核定位：包内相对路径 + 正整数行号 + 原文片段 + 规则说明。"""
    for finding in report["findings"]:
        if finding["rule"] != rule:
            continue
        if not finding["file"] or not isinstance(finding["line"], int) or finding["line"] < 1:
            return False
        if not finding["excerpt"] or not finding["why"]:
            return False
        if ".." in Path(finding["file"]).parts or Path(finding["file"]).is_absolute():
            return False
    return True


def main() -> int:
    if not SCANNER.is_file() or not TESTDATA.is_dir():
        print("n/a: 找不到扫描器或语料")
        return 2

    failures: list[str] = []

    for name, expected in EXPECTED.items():
        fixture = TESTDATA / name
        if not fixture.is_dir():
            failures.append(f"夹具缺失：{name}")
            continue
        report = scan(fixture)
        hit = rules_hit(report)
        missing = expected - hit
        if missing:
            failures.append(f"{name}: 期望规则未命中 {sorted(missing)}（实际 {sorted(hit)}）")
        for rule in expected:
            if not finding_shape_ok(report, rule):
                failures.append(f"{name}: {rule} 的命中缺少可复核定位（file/line/excerpt/why）")

        # clean 夹具必须零风险命中——这是"扫描器不是一律报危险"的对照
        if name == "clean":
            risk = [f for f in report["findings"] if f["severity"] != "info"]
            if risk:
                failures.append(f"clean 夹具却报了风险特征：{[(f['rule'], f['file']) for f in risk]}")

        want_gap = CAPABILITY_GAP_EXPECTED.get(name)
        if want_gap is not None:
            has_gap = bool(report["capabilityGaps"])
            if has_gap != want_gap:
                failures.append(
                    f"{name}: 能力缺口判定应为 gap={want_gap}，实际 gap={has_gap}（报告={report['capabilityGaps']}）"
                )

    # 哨兵：证明不执行第三方代码
    sentinel = TESTDATA / "exec-probe" / "SENTINEL-RAN.txt"
    if sentinel.exists():
        failures.append("扫描器执行了被扫代码（哨兵文件被创建）")

    # 报告自带边界
    sample_report = scan(TESTDATA / "dynamic-escape")
    if not sample_report.get("notCovered"):
        failures.append("报告没有 notCovered 清单，读者会以为扫描=全面判定")
    disclaimer = sample_report.get("disclaimer", "")
    if "不等于安全认证" not in disclaimer:
        failures.append(f"免责文案缺失：{disclaimer!r}")
    if sample_report.get("execution") != "none":
        failures.append("报告没有声明 execution=none")
    if sample_report.get("signatureCheck") != "notPerformed":
        failures.append("报告没有声明它不做签名/信任判定")

    # 真实官方示例必须仍在覆盖内且不被误报为危险
    for sample in sorted(p.name for p in SAMPLES.iterdir() if p.is_dir()):
        report = scan(SAMPLES / sample)
        risky = [f for f in report["findings"] if f["severity"] in ("high", "medium")]
        if risky:
            failures.append(f"官方示例 {sample} 被报为危险特征（误报）：{[(f['rule'], f['file']) for f in risky]}")

    # zip 入口：路径穿越条目必须跳过且不写盘
    with tempfile.TemporaryDirectory(prefix="muse-scan-zip-") as tmp:
        archive_path = Path(tmp) / "traversal.muse-plugin"
        with zipfile.ZipFile(archive_path, "w") as archive:
            archive.writestr("main.js", "export function run(){ return fetch('https://x'); }\n")
            archive.writestr("../ESCAPED.js", "eval('danger')\n")
            archive.writestr("manifest.json", json.dumps({"id": "t", "capabilities": []}))
        report = scan(archive_path)
        escaped_files = [f["file"] for f in report["findings"] if "ESCAPED" in f["file"]]
        if escaped_files:
            failures.append(f"zip 穿越条目被当正常文件扫了：{escaped_files}")
        if not any(".." in item for item in report["skippedFiles"]):
            failures.append(f"可疑条目未记录到 skippedFiles：{report['skippedFiles']}")
        if Path(tmp).joinpath("ESCAPED.js").exists():
            failures.append("扫描器把 zip 条目写到了磁盘上")
        if "NET_OUTBOUND" not in rules_hit(report):
            failures.append("zip 输入没有被正常扫描（NET_OUTBOUND 未命中）")

    if failures:
        print("FAIL: 插件静态扫描器不达判据：")
        for failure in failures:
            print(f"  - {failure}")
        return 1

    print(
        "OK: {} 个夹具逐规则命中、clean 对照零风险、能力缺口判定正确、"
        "哨兵证明未执行第三方代码、zip 穿越条目被跳过且未写盘。".format(len(EXPECTED))
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
