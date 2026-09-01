#!/usr/bin/env python3
"""F-06: 可重入的 GitHub Release 发布脚本(断点续发 + 指数退避 + 远端核对)。

历史问题: 发布依赖一次性的 ncipollo action, 网络抖动/中途失败后重跑会
重建 Release 或重复上传资产, 且无法核对远端最终状态。本脚本用 gh CLI
(CI runner 预装) 实现幂等发布:

  1. 断点续发: Release 已存在则复用(不重建), 资产已存在且大小一致则跳过;
  2. 指数退避: 上传失败按 2s/4s/8s 退避重试(上限 3 次);
  3. 远端核对: 发布完成后用 gh release view 对比本地资产清单, 缺失即失败。

用法(CI release job):
  python3 ci/script/publish_release.py \
    --tag v1.0.76 \
    --body-file releases/v1.0.76/release_body.md \
    --artifacts "app/build/outputs/apk/release/*.apk" \
    --artifacts release/manifest.json

依赖: gh CLI 已认证(GitHub Actions 中 GITHUB_TOKEN 自动生效)。
"""

import argparse
import glob
import hashlib
import json
import os
import subprocess
import sys
import time
from pathlib import Path

MAX_UPLOAD_RETRIES = 3
BACKOFF_BASE_SECONDS = 2


def run(cmd: list[str], check: bool = True) -> subprocess.CompletedProcess:
    """执行命令, 失败时抛出带输出上下文的异常。"""
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if check and proc.returncode != 0:
        raise RuntimeError(
            f"命令失败: {' '.join(cmd)}\nstdout: {proc.stdout[-2000:]}\nstderr: {proc.stderr[-2000:]}",
        )
    return proc


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def release_exists(tag: str) -> bool:
    proc = run(["gh", "release", "view", tag, "--json", "id"], check=False)
    return proc.returncode == 0


def fetch_remote_assets(tag: str) -> list[dict]:
    """返回远端资产 [{name, size, digest}]。"""
    proc = run(["gh", "release", "view", tag, "--json", "assets"])
    assets = json.loads(proc.stdout).get("assets", [])
    return [
        {
            "name": asset["name"],
            "size": asset["size"],
            "digest": asset.get("digest") or "",
        }
        for asset in assets
    ]


def create_or_reuse_release(tag: str, body_file: Path) -> None:
    if release_exists(tag):
        # 已存在时也同步正文，避免手动创建的临时 Release 留下过期说明。
        cmd = ["gh", "release", "edit", tag, "--title", f"Muse {tag}"]
        if body_file.is_file():
            cmd += ["--notes-file", str(body_file)]
        run(cmd)
        print(f"[断点续发] Release {tag} 已存在, 复用并同步正文")
        return
    cmd = ["gh", "release", "create", tag, "--title", f"Muse {tag}"]
    if body_file.is_file():
        cmd += ["--notes-file", str(body_file)]
    run(cmd)
    print(f"[发布] Release {tag} 已创建")


def upload_with_retry(tag: str, asset: Path, remote_assets: dict[str, dict]) -> None:
    remote = remote_assets.get(asset.name)
    if remote is not None:
        local_digest = f"sha256:{sha256_of(asset)}"
        if remote["size"] == asset.stat().st_size and remote["digest"] == local_digest:
            print(f"[断点续发] 资产已存在且摘要一致, 跳过上传: {asset.name}")
            return
        print(f"[校正] 远端资产与本地不一致, 重新上传: {asset.name}")
    last_err: Exception | None = None
    for attempt in range(1, MAX_UPLOAD_RETRIES + 1):
        try:
            run(["gh", "release", "upload", tag, str(asset), "--clobber"])
            print(f"[上传] {asset.name} 成功(第 {attempt} 次尝试)")
            return
        except RuntimeError as e:
            last_err = e
            if attempt < MAX_UPLOAD_RETRIES:
                wait = BACKOFF_BASE_SECONDS * (2 ** (attempt - 1))
                print(f"[重试] {asset.name} 失败(第 {attempt} 次), {wait}s 后重试")
                time.sleep(wait)
    raise RuntimeError(f"上传 {asset.name} 连续 {MAX_UPLOAD_RETRIES} 次失败: {last_err}")


def verify_remote(tag: str, local_assets: list[Path]) -> None:
    """完成后核对远端: 名称、大小和 GitHub digest 必须全部一致。"""
    remote = {asset["name"]: asset for asset in fetch_remote_assets(tag)}
    missing = []
    for asset in local_assets:
        remote_asset = remote.get(asset.name)
        if remote_asset is None:
            missing.append(f"{asset.name} (远端缺失)")
            continue
        expected_size = asset.stat().st_size
        expected_digest = f"sha256:{sha256_of(asset)}"
        if remote_asset["size"] != expected_size:
            missing.append(
                f"{asset.name} (大小不符: 本地 {expected_size} vs 远端 {remote_asset['size']})",
            )
        if remote_asset["digest"] != expected_digest:
            missing.append(
                f"{asset.name} (摘要不符: 本地 {expected_digest} vs 远端 {remote_asset['digest'] or '缺失'})",
            )
    if missing:
        raise RuntimeError(f"远端核对失败:\n" + "\n".join(f"  - {item}" for item in missing))
    print(f"[核对] 远端资产 {len(local_assets)} 项名称、大小和 SHA-256 全部一致")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True, help="如 v1.0.76")
    parser.add_argument("--body-file", required=True, type=Path, help="release_body.md 路径")
    parser.add_argument("--artifacts", action="append", required=True, help="资产 glob(可多次)")
    args = parser.parse_args()

    local_assets: list[Path] = []
    for pattern in args.artifacts:
        local_assets.extend(Path(p) for p in sorted(glob.glob(pattern)))
    local_assets = [a for a in local_assets if a.is_file()]
    if not local_assets:
        print("FATAL: 没有匹配到任何资产文件")
        return 1
    print(f"[发布] tag={args.tag}, 资产 {len(local_assets)} 项: {[a.name for a in local_assets]}")

    try:
        create_or_reuse_release(args.tag, args.body_file)
        remote_assets = {asset["name"]: asset for asset in fetch_remote_assets(args.tag)}
        for asset in local_assets:
            upload_with_retry(args.tag, asset, remote_assets)
            # 让同一轮执行中的重复路径也能看到刚上传的资产状态。
            remote_assets.pop(asset.name, None)
        verify_remote(args.tag, local_assets)
    except RuntimeError as e:
        print(f"FAIL: {e}")
        return 1
    print(f"PASS: Release {args.tag} 发布完成且已核对")
    return 0


if __name__ == "__main__":
    sys.exit(main())
