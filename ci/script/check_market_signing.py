#!/usr/bin/env python3
"""门禁：官方示例必须能被自家签名工具一路签到底。

为什么需要这条：`MarketSigningFixtureTest` 里的四个用例都以 `assumeTrue` 开头，
产物不在就**静默跳过** —— 于是 `sign.py` 的入口函数探测只认行首 `function NAME(`，
把三个官方示例（写的是 ESM `export function noteSave(...)`）全部判"未定义工具函数"，
这件事在测试里完全没有暴露：绿是"没跑"的绿。

这条门禁不依赖开发者机器上的密钥：它把密钥生成到一次性目录里，跑完就丢，
所以任何人 clone 下来都能跑，也不碰 `~/.muse-market`。

退出码约定（与 run_gates.py 一致）：0 通过；1 红；2 环境不具备（缺 openssl，报 n/a 而非假绿）。
"""

from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

# 中文 Windows 的默认控制台编码是 GBK，一条含破折号的失败原因会让门禁**崩在打印上**
# （实测过：结果是 exit=1 但看不到 FAIL 文案）。失败信息看不见 = 门禁等于没有。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):  # 被重定向到非文本对象时直接放过
        pass

PROJECT_ROOT = Path(__file__).resolve().parents[2]
SIGN = PROJECT_ROOT / "tools" / "market-signer" / "sign.py"
SAMPLES_DIR = PROJECT_ROOT / "docs" / "plugin" / "samples"
SAMPLES = ("hello", "notes", "form")
PUBLISHER_ID = "museai"
CATALOG_KEY_ID = "museai-catalog-1"
BASE_URL = "https://example.invalid/muse-market/packages"

# openssl 是外部依赖：找不到就 n/a，不能算通过（否则又变成一条静默绿）
COMMON_WINDOWS_PATHS = (
    Path(r"C:/Program Files/Git/usr/bin/openssl.exe"),
    Path(r"C:/Program Files/Git/mingw64/bin/openssl.exe"),
)


def openssl_or_none() -> str | None:
    env = (subprocess.os.environ.get("MUSE_OPENSSL") or "").strip()
    if env and Path(env).is_file():
        return env
    found = shutil.which("openssl")
    if found:
        return found
    for candidate in COMMON_WINDOWS_PATHS:
        if candidate.is_file():
            return str(candidate)
    return None


def run(args: list[str]) -> tuple[int, str]:
    # 子进程也固定 UTF-8 输出：否则中文 Windows 上失败原因会被解码成乱码，
    # 门禁红是红了，但读不出为什么红。
    child_env = {**subprocess.os.environ, "PYTHONIOENCODING": "utf-8"}
    completed = subprocess.run(
        [sys.executable, str(SIGN)] + args,
        cwd=PROJECT_ROOT,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        env=child_env,
        timeout=300,
    )
    return completed.returncode, (completed.stdout or "") + (completed.stderr or "")


def main() -> int:
    if not SIGN.is_file():
        print(f"FAIL: 找不到签名工具 {SIGN}")
        return 1
    missing_samples = [s for s in SAMPLES if not (SAMPLES_DIR / s / "manifest.json").is_file()]
    if missing_samples:
        print(f"FAIL: 官方示例缺失：{missing_samples}（门禁失去意义）")
        return 1

    openssl = openssl_or_none()
    if openssl is None:
        print("n/a: 本机找不到 openssl（可设 MUSE_OPENSSL 指向可执行文件）—— 报 n/a，不算通过")
        return 2

    with tempfile.TemporaryDirectory(prefix="muse-market-gate-") as tmp:
        root = Path(tmp)
        env_backup = subprocess.os.environ.get("MUSE_OPENSSL")
        subprocess.os.environ["MUSE_OPENSSL"] = openssl
        try:
            publisher_key = root / "publisher.pem"
            catalog_key = root / "catalog.pem"
            for key in (publisher_key, catalog_key):
                code, out = run(["keygen", "--key", str(key)])
                if code != 0:
                    print(f"FAIL: keygen 失败（{key.name}）\n{out.strip()[:400]}")
                    return 1

            out_dir = root / "packages"
            for sample in SAMPLES:
                code, out = run(
                    [
                        "pack",
                        "--src", str(SAMPLES_DIR / sample),
                        "--key", str(publisher_key),
                        "--publisher-id", PUBLISHER_ID,
                        "--out-dir", str(out_dir),
                    ]
                )
                if code != 0:
                    tail = "\n".join(line for line in out.splitlines() if line.strip())[-400:]
                    print(f"FAIL: 官方示例 sample-{sample} 签不出来：\n  {tail}")
                    print("  （签名工具比 App 严 = 作者照文档写却被自家工具拒，必须修工具侧）")
                    return 1

            packages = sorted(out_dir.glob("*.muse-plugin")) if out_dir.exists() else []
            if len(packages) != len(SAMPLES):
                print(f"FAIL: 期望 {len(SAMPLES)} 个插件包，实际 {len(packages)} 个：{[p.name for p in packages]}")
                return 1

            code, out = run(
                [
                    "catalog",
                    "--packages-dir", str(out_dir),
                    "--key", str(catalog_key),
                    "--key-id", CATALOG_KEY_ID,
                    "--base-url", BASE_URL,
                    "--catalog-id", PUBLISHER_ID + "-off",
                    "--out", str(root / "signed-catalog.json"),
                ]
            )
            if code != 0:
                print(f"FAIL: catalog 生成失败\n{out.strip()[-400:]}")
                return 1
            if not (root / "signed-catalog.json").is_file():
                print("FAIL: catalog 命令成功却没写出目录文件")
                return 1
        finally:
            if env_backup is None:
                subprocess.os.environ.pop("MUSE_OPENSSL", None)
            else:
                subprocess.os.environ["MUSE_OPENSSL"] = env_backup

    print(f"OK: {len(SAMPLES)} 个官方示例签名与目录生成全程通过（openssl={Path(openssl).name}）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
