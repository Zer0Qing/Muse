#!/usr/bin/env python3
"""插件包**静态**风险特征扫描（DSHGO 阶段 4 原型）。

## 它是什么
对**离线包**（目录或 `.muse-plugin`）里的文本文件做规则匹配，产出一份可复核的报告：
每条命中都带 `文件:行号`、规则号、严重度、原文片段与**规则说明**。

## 它不是什么（这条比它能查出什么更重要）
- **不执行任何第三方代码**：只用 `zipfile`/文本读取 + 正则，不 import 插件、不跑它、不装它。
  本文件的自检（`check_plugin_scan.py`）会验证源码里没有 `subprocess`/`exec`/`eval`。
- **不是安全认证**：扫过 ≠ 安全。签名与内容核验仍然只由 `market-signer` / App 的签名门负责，
  这里既不绕过也不替代；扫描通过不会让任何包获得信任。
- **不覆盖运行时行为**：动态分发、压缩/混淆代码、网络目的地址、实际写入路径、
  依赖树与原生库内部都在覆盖范围外，报告里以 `notCovered` 原样列出。

## 为什么值得做
安装前唯一能拿到的"这东西会碰什么"的信号就是静态特征 + 能力声明。
把两者**对照**才有用：代码里出现 `host.http.get` 而清单没声明 `network`，
说明装上新版后会静默少一块功能（能力总线会在运行时拒绝），而不是"这个插件有害"。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import zipfile
from pathlib import Path

SCANNER_VERSION = "0.1.0"
MAX_EXCERPT_CHARS = 88
TEXT_SUFFIXES = {".js", ".mjs", ".cjs", ".ts", ".py", ".json", ".md", ".sh", ".ps1", ".bat", ".txt"}

# (规则号, 类别, 严重度, 判定, 说明)
# 说明里必须写"为什么这值得用户知道"，而不只是"匹配到了什么"——
# 报告是要给人读的，一条没有解释的命中只会让人以为插件有害。
RULES: list[dict] = [
    {
        "id": "NET_OUTBOUND",
        "category": "network",
        "severity": "medium",
        "patterns": [r"\bfetch\s*\(", r"XMLHttpRequest", r"\bhost\.http\b", r"\busocket\b", r"\bsocket\b",
                     r"\burllib\b", r"\brequests\.", r"\baiohttp\b"],
        "why": "会向设备外部发数据。能力 `network` 未声明时运行期会被能力总线拒绝；声明了就等于允许它把会话内容送出去。",
    },
    {
        "id": "NET_LOCAL",
        "category": "network",
        "severity": "low",
        "patterns": [r"127\.0\.0\.1", r"localhost", r"::1"],
        "why": "访问本机地址：常用于连本地服务，可能读到同一台机器上其它服务的数据。",
    },
    {
        "id": "FS_WRITE",
        "category": "file",
        "severity": "medium",
        "patterns": [r"\bfs_write\b", r"\bfs\.writeFile", r"\bfs\.appendFile", r"\bfs\.unlink",
                     r"\bopen\s*\(.*['\"][wax][^'\"]*['\"]", r"\bshutil\.", r"\bos\.remove\b",
                     r"\bos\.rename\b", r"\.writeText\b", r"\bmkdir\b"],
        "why": "会写或删除文件。对应能力 `resource.write`；误用可能覆盖用户工作区里的真实文件。",
    },
    {
        "id": "FS_READ",
        "category": "file",
        "severity": "low",
        "patterns": [r"\bfs_read\b", r"\bfs\.readFile", r"\bfs\.readdir", r"\bglob\b", r"\bos\.listdir\b",
                     r"os\.walk"],
        "why": "会读目录内容。对应能力 `resource.read`；读到的内容可能包含凭据或私人文件。",
    },
    {
        "id": "SUBPROCESS",
        "category": "subprocess",
        "severity": "high",
        "patterns": [r"\bchild_process\b", r"\bsubprocess\b", r"\bos\.system\b", r"\bPopen\b",
                     r"\bexecSync\b", r"\bos\.popen\b", r"\bos\.exec\b"],
        "why": "会起子进程执行系统命令，能力边界之外还能碰到设备上任何该用户可访问的东西。",
    },
    {
        "id": "NATIVE_CODE",
        "category": "native",
        "severity": "high",
        "patterns": [r"\.so\b", r"\.node\b", r"\.dll\b", r"\bctypes\b", r"\bcffi\b", r"\bdlopen\b",
                     r"\badd_library\b"],
        "why": "包含或加载原生代码。静态扫描无法判断其中的机器码做什么，必须人工看来源。",
    },
    {
        "id": "INSTALL_SCRIPT",
        "category": "installScript",
        "severity": "high",
        "patterns": [r'"postinstall"\s*:', r'"preinstall"\s*:', r'"prepare"\s*:', r"\bsetup\.py\b",
                     r"\bpip install\b", r"\bnpm install\b"],
        "why": "安装期就会自动跑代码，早于任何用户授权动作。",
    },
    {
        "id": "DYNAMIC_CODE",
        "category": "dynamic",
        "severity": "medium",
        "patterns": [r"\beval\s*\(", r"\bnew Function\b", r"\bimportScripts\b", r"\b__import__\b",
                     r"\brequire\s*\(", r"\bFunction\s*\(\s*['\"]"],
        "why": "运行时拼代码，前面的静态特征判定对它不可靠：真正执行的内容可能是扫描时看不到的字符串。",
    },
    {
        "id": "SANDBOX_ESCAPE",
        "category": "sandboxEscape",
        "severity": "medium",
        "patterns": [r"\bdocument\.", r"\bwindow\.", r"\blocalStorage\b", r"\bglobalThis\s*\["],
        "why": "试图碰宿主页面/全局对象。沙盒会拦，但出现这种写法说明作者在找边界。",
    },
    {
        "id": "ENV_CREDENTIALS",
        "category": "credentials",
        "severity": "medium",
        "patterns": [r"\bprocess\.env\b", r"\bos\.environ\b", r"\bgetenv\b", r"api[_-]?key\s*[:=]",
                     r"token\s*[:=]"],
        "why": "读取环境变量或写死密钥：环境里可能有别的服务的凭据；写死的密钥会随包外泄。",
    },
    {
        "id": "HOST_BRIDGE_USE",
        "category": "capabilityMediated",
        "severity": "info",
        "patterns": [r"\bhost\.([a-zA-Z_]+)\.([a-zA-Z_][a-zA-Z0-9_]*)\b"],
        "why": "走宿主能力总线的调用。这类本身不是风险，但必须与清单声明的能力对上（对照结果在 capabilityGaps 里）。",
    },
]

for _rule in RULES:
    _rule["compiled"] = [re.compile(_p) for _p in _rule["patterns"]]


# 直接特征 → App 能力名（只有能对上一格的才参与对照，避免把"起子进程"编造成某个不存在的能力）
CAPABILITY_FOR_DIRECT = {
    "NET_OUTBOUND": "network",
    "FS_WRITE": "resource.write",
    "FS_READ": "resource.read",
}

CAPABILITY_FOR_BRIDGE = {
    "http": "network",
    "fs": "resource.read",
    "clipboard": "clipboard",
    "notify": "notify",
    "device": "device.info",
    "storage": "storage",
    "chat": "chat.read",
    "ui": "ui.interactive",
}

NOT_COVERED = [
    "运行时行为与真实数据流：只看静态文本，不执行、不追踪赋值",
    "动态拼接的代码（见 DYNAMIC_CODE）在扫描期不可见其内容",
    "压缩 / 混淆过的源码：规则匹配会被编码绕过",
    "网络目的地址、端口与实际传输内容（只看是否具备出网能力）",
    "原生库内部（.so/.node/.dll 只看存在，不看机器码）",
    "依赖树与间接引入的代码（只扫包内文件）",
    "提示词层面的社会工程（描述文案骗人不属于本扫描特征）",
]

SEVERITY_ORDER = {"info": 0, "low": 1, "medium": 2, "high": 3}


def load_package_texts(root: Path) -> tuple[dict[str, str], list[str]]:
    """把包（目录或 .muse-plugin）里的文本文件读成 {包内相对路径: 文本}。

    刻意**不落盘**：解 zip 写文件会引入路径穿越（`../` 条目）与"把被扫代码放到磁盘上"的
    额外风险，而扫描只需要内容。zip 里越界或可疑的条目直接跳过并记录。
    """
    texts: dict[str, str] = {}
    skipped: list[str] = []
    if root.is_file():
        with zipfile.ZipFile(root) as archive:
            for info in archive.infolist():
                name = info.filename
                if info.is_dir():
                    continue
                if Path(name).suffix.lower() not in TEXT_SUFFIXES:
                    continue
                parts = Path(name).parts
                if ".." in parts or name.startswith(("/", "\\")) or ":" in "".join(parts[:1]):
                    skipped.append(f"{name} (可疑条目名，未读取)")
                    continue
                texts[name] = archive.read(name).decode("utf-8", "replace")
        return texts, skipped

    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.suffix.lower() not in TEXT_SUFFIXES:
            continue
        texts[str(path.relative_to(root)).replace("\\", "/")] = path.read_text(encoding="utf-8", errors="replace")
    return texts, skipped


def manifest_capabilities(texts: dict[str, str], skipped: list[str]) -> list[str]:
    for rel, text in texts.items():
        if rel.endswith("manifest.json"):
            try:
                return list(json.loads(text).get("capabilities") or [])
            except json.JSONDecodeError:
                skipped.append(f"{rel} (manifest.json 解析失败)")
                return []
    return []


def scan_package(root: Path) -> dict:
    """扫描一个包。返回可复核的报告字典。"""
    findings: list[dict] = []
    texts, skipped = load_package_texts(root)

    for rel, text in texts.items():
        for index, line in enumerate(text.splitlines(), start=1):
            for rule in RULES:
                if any(pattern.search(line) for pattern in rule["compiled"]):
                    findings.append({
                        "rule": rule["id"],
                        "category": rule["category"],
                        "severity": rule["severity"],
                        "file": rel,
                        "line": index,
                        "excerpt": line.strip()[:MAX_EXCERPT_CHARS],
                        "why": rule["why"],
                    })
                    # 不 break：一行可以同时命中多条规则（`require('child_process')`
                    # 既是子进程也是动态加载），只记第一条会把风险说小。

    capabilities = manifest_capabilities(texts, skipped)

    # 能力对照：代码实际用的动作 vs 清单声明的能力。
    # 命中不一致不代表"危险"，代表"装上后会静默少一块功能"——这才是用户装前想知道的。
    bridge_used = {
        CAPABILITY_FOR_BRIDGE[m.group(1)]
        for finding in findings if finding["rule"] == "HOST_BRIDGE_USE"
        for m in [re.match(r"host\.([a-zA-Z_]+)\.", finding["excerpt"])] if m and m.group(1) in CAPABILITY_FOR_BRIDGE
    }
    # 直接特征同样要算：第一版只对照总线调用，于是裸 `fetch()` 未声明 network 竟被报告成"无缺口"——
    # 那是把漏报写成了"干净"，比不报更坏。
    direct_used = {
        CAPABILITY_FOR_DIRECT[f["rule"]]
        for f in findings if f["rule"] in CAPABILITY_FOR_DIRECT
    }
    used_capabilities = sorted(bridge_used | direct_used)
    gaps = [
        {
            "bridge": cap,
            "declared": False,
            "meaning": "代码调用了它，但清单没声明同名能力：装上新版后该动作会在运行期被能力总线拒绝，功能会静默缺一块",
        }
        for cap in used_capabilities
        if not any(cap == declared or declared.startswith(cap + ".") or declared.startswith(cap)
                   for declared in capabilities)
    ]

    risk = [f for f in findings if f["severity"] != "info"]
    return {
        "scannerVersion": SCANNER_VERSION,
        "target": str(root),
        "execution": "none",          # 本扫描器不执行、不导入、不安装被扫代码
        "signatureCheck": "notPerformed",  # 信任判定只在签名门，这里不掺和
        "filesScanned": len(texts),
        "skippedFiles": skipped,
        "manifestCapabilities": sorted(capabilities),
        "bridgeCapabilitiesUsed": used_capabilities,
        "capabilityGaps": gaps,
        "countsBySeverity": {
            level: sum(1 for f in risk if f["severity"] == level)
            for level in ("low", "medium", "high")
        },
        "findings": sorted(findings, key=lambda f: (-SEVERITY_ORDER[f["severity"]], f["file"], f["line"])),
        "notCovered": NOT_COVERED,
        "disclaimer": "静态扫描不等于安全认证：它只列出可见特征，既不能证明插件无害，"
                      "也不会因为通过扫描而授予任何信任。签名无效或内容变化仍由签名门拒绝。",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="插件包静态风险特征扫描（不执行被扫代码）")
    parser.add_argument("--src", required=True, help="包目录或 .muse-plugin")
    parser.add_argument("--out", help="报告 JSON 输出路径（缺省打印到 stdout）")
    parser.add_argument("--fail-on", choices=list(SEVERITY_ORDER), default=None,
                        help="命中达到该严重度即返回非零（供 CI 用；默认不失败，扫描结论不是门禁）")
    args = parser.parse_args()

    report = scan_package(Path(args.src))
    payload = json.dumps(report, ensure_ascii=False, indent=2)
    if args.out:
        Path(args.out).write_text(payload, encoding="utf-8")
    else:
        print(payload)

    print(
        "扫描 {files} 个文件：高 {high} / 中 {medium} / 低 {low}，能力缺口 {gaps} 处（{execution}执行第三方代码）".format(
            files=report["filesScanned"], high=report["countsBySeverity"]["high"],
            medium=report["countsBySeverity"]["medium"], low=report["countsBySeverity"]["low"],
            gaps=len(report["capabilityGaps"]),
            execution="未",
        ),
        file=sys.stderr,
    )
    if args.fail_on:
        threshold = SEVERITY_ORDER[args.fail_on]
        if any(f["severity"] != "info" and SEVERITY_ORDER[f["severity"]] >= threshold for f in report["findings"]):
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
