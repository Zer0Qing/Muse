#!/usr/bin/env python3
"""Validate bundled, merged, and optional runtime release manifests."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from urllib.parse import urlparse


EXPECTED_ABIS = {"arm64-v8a", "x86_64"}
SHA256 = re.compile(r"^[a-fA-F0-9]{64}$")
REVISION = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")
ARCHIVE_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*\.tar\.gz$")
REQUIRED_BINS = {"proot", "loader", "libtalloc.so.2", "libandroid-shmem.so"}


def is_local_host(host: str) -> bool:
    host = host.lower().strip("[]")
    if host in {"localhost", "::1", "0.0.0.0", "10.0.2.2"} or host.endswith(".local"):
        return True
    if host.startswith(("127.", "10.", "192.168.")):
        return True
    parts = host.split(".")
    return len(parts) == 4 and parts[0] == "172" and parts[1].isdigit() and 16 <= int(parts[1]) <= 31


def versions_for(document: dict) -> list[dict]:
    versions = document.get("versions")
    if isinstance(versions, list) and versions:
        return versions
    if "revision" in document and isinstance(document.get("archive"), dict):
        return [
            {
                "revision": document.get("revision"),
                "installedBytes": document.get("installedBytes"),
                "bins": document.get("bins", {}),
                "archive": document["archive"],
            }
        ]
    return []


def validate_manifest(path: Path, expected_abi: str | None = None) -> list[str]:
    errors: list[str] = []
    try:
        with path.open("r", encoding="utf-8") as source:
            document = json.load(source)
    except (OSError, json.JSONDecodeError) as error:
        return [f"{path}: 无法读取 JSON: {error}"]

    if not isinstance(document, dict):
        return [f"{path}: 清单根节点必须是对象"]
    abi = document.get("abi")
    if abi not in EXPECTED_ABIS:
        errors.append(f"{path}: 不支持或缺失 ABI: {abi!r}")
    if expected_abi is not None and abi != expected_abi:
        errors.append(f"{path}: 文件名 ABI 与内容不一致，预期 {expected_abi!r}，实际 {abi!r}")
    if document.get("runtimeId") != "ubuntu-node":
        errors.append(f"{path}: runtimeId 必须是 ubuntu-node")

    versions = versions_for(document)
    if not versions:
        errors.append(f"{path}: versions 不能为空，且必须包含兼容的旧版 archive")
    for index, version in enumerate(versions):
        label = f"{path}: versions[{index}]"
        if not isinstance(version, dict):
            errors.append(f"{label} 必须是对象")
            continue
        revision = version.get("revision")
        if not isinstance(revision, str) or not REVISION.fullmatch(revision) or ".." in revision:
            errors.append(f"{label} revision 非法")
        archive = version.get("archive")
        if not isinstance(archive, dict):
            errors.append(f"{label} archive 缺失")
            continue
        name = archive.get("name")
        if not isinstance(name, str) or not ARCHIVE_NAME.fullmatch(name):
            errors.append(f"{label} archive name 非法")
        size = archive.get("sizeBytes")
        if not isinstance(size, int) or size <= 0:
            errors.append(f"{label} sizeBytes 必须大于 0")
        digest = archive.get("sha256")
        if not isinstance(digest, str) or not SHA256.fullmatch(digest):
            errors.append(f"{label} sha256 非法")
        installed = version.get("installedBytes")
        if not isinstance(installed, int) or installed <= 0:
            errors.append(f"{label} installedBytes 必须大于 0")

        urls = archive.get("urls")
        if not isinstance(urls, list) or not urls:
            errors.append(f"{label} urls 不能为空")
        else:
            for url in urls:
                parsed = urlparse(url) if isinstance(url, str) else None
                if parsed is None or parsed.scheme != "https" or not parsed.hostname or is_local_host(parsed.hostname):
                    errors.append(f"{label} URL 必须是公开 HTTPS 地址: {url!r}")
            last = urlparse(urls[-1]) if isinstance(urls[-1], str) else None
            if not last or last.hostname != "github.com" or "/releases/download/" not in last.path:
                errors.append(f"{label} 最后一个 URL 必须是 GitHub Release 原始地址")

        bins = version.get("bins")
        if not isinstance(bins, dict):
            errors.append(f"{label} bins 缺失")
        else:
            for binary in REQUIRED_BINS:
                binary_hash = bins.get(binary)
                if not isinstance(binary_hash, str) or not SHA256.fullmatch(binary_hash):
                    errors.append(f"{label} bins[{binary}] sha256 缺失或非法")
    return errors


def manifests_in(directory: Path) -> list[Path]:
    return sorted(
        path
        for path in directory.rglob("*.json")
        if path.is_file() and (
            path.name.startswith("manifest-") or
            (path.name == "manifest.json" and path.parent.name in EXPECTED_ABIS)
        )
    )


def validate_directory(directory: Path, require_both_abis: bool) -> list[str]:
    if not directory.is_dir():
        return [f"目录不存在: {directory}"]
    paths = manifests_in(directory)
    if not paths:
        return [f"没有找到 manifest-*.json: {directory}"]
    errors: list[str] = []
    seen_abis: set[str] = set()
    for path in paths:
        expected = path.stem.removeprefix("manifest-") if path.name.startswith("manifest-") else path.parent.name
        if expected not in EXPECTED_ABIS:
            errors.append(f"{path}: 无法从文件名或父目录判定 ABI")
            expected = None
        errors.extend(validate_manifest(path, expected))
        if expected is not None:
            seen_abis.add(expected)
    if require_both_abis and seen_abis != EXPECTED_ABIS:
        errors.append(f"{directory}: ABI 集合应为 {sorted(EXPECTED_ABIS)}，实际 {sorted(seen_abis)}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", required=True, type=Path, help="Android 仓库根目录")
    parser.add_argument("--merged-assets", type=Path, help="AGP mergeDebugAssets 输出目录")
    parser.add_argument("--release-dir", type=Path, help="可选 runtime release 产物目录")
    arguments = parser.parse_args()

    repo = arguments.repo.resolve()
    asset_dir = repo / "app" / "src" / "main" / "assets" / "runtime"
    errors = validate_directory(asset_dir, require_both_abis=True)
    if arguments.merged_assets:
        errors.extend(validate_directory(arguments.merged_assets.resolve(), require_both_abis=True))
    if arguments.release_dir:
        errors.extend(validate_directory(arguments.release_dir.resolve(), require_both_abis=True))
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print("runtime manifest checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
