#!/usr/bin/env python3
"""Validate a generated release manifest against the local release inputs.

The generator records the build provenance and APK digest; this independent
check prevents a mismatched manifest, APK, DB version, tag, or commit from
being uploaded by the release job.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

COMMIT_RE = re.compile(r"^[0-9a-fA-F]{7,64}$")


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def db_version_of(path: Path) -> int:
    match = re.search(r"version\s*=\s*(\d+)", path.read_text(encoding="utf-8"))
    if not match:
        raise ValueError(f"无法从 {path} 解析 DB version")
    return int(match.group(1))


def validate_manifest(
    manifest_path: Path,
    apk_path: Path | list[Path],
    db_path: Path,
    tag: str,
    commit_sha: str,
) -> list[str]:
    errors: list[str] = []
    apk_paths = [apk_path] if isinstance(apk_path, Path) else apk_path
    if not manifest_path.is_file():
        return [f"manifest 不存在: {manifest_path}"]
    missing_apks = [path for path in apk_paths if not path.is_file()]
    if missing_apks:
        errors.extend(f"APK 不存在: {path}" for path in missing_apks)
    if not db_path.is_file():
        errors.append(f"DB 源文件不存在: {db_path}")
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        return [f"manifest 无法解析: {error}"]
    if not isinstance(manifest, dict):
        return ["manifest 顶层必须是 JSON object"]

    expected_version = tag.strip()[1:] if tag.strip().lower().startswith("v") else tag.strip()
    if manifest.get("versionName") != expected_version:
        errors.append("versionName 与 tag 不一致")
    if manifest.get("sourceRef") != tag.strip():
        errors.append("sourceRef 与 tag 不一致")
    normalized_commit = commit_sha.strip().lower()
    if not COMMIT_RE.fullmatch(normalized_commit):
        errors.append("传入 commit SHA 格式非法")
    elif manifest.get("sourceCommit") != normalized_commit:
        errors.append("sourceCommit 与构建 commit 不一致")

    required = ("versionCode", "dbVersion", "apkSizeBytes", "apkSha256", "releaseNotesRef")
    for key in required:
        if key not in manifest:
            errors.append(f"manifest 缺少字段: {key}")
    if not isinstance(manifest.get("versionCode"), int) or manifest.get("versionCode", 0) <= 0:
        errors.append("versionCode 必须是正整数")
    if not isinstance(manifest.get("releaseNotesRef"), str) or not manifest.get("releaseNotesRef", "").strip():
        errors.append("releaseNotesRef 不能为空")

    primary_apk = apk_paths[0] if apk_paths else None
    if primary_apk is not None and primary_apk.is_file():
        actual_size = primary_apk.stat().st_size
        actual_hash = sha256_of(primary_apk)
        if manifest.get("apkSizeBytes") != actual_size:
            errors.append("apkSizeBytes 与实际 APK 不一致")
        if manifest.get("apkSha256") != actual_hash:
            errors.append("apkSha256 与实际 APK 不一致")

    # 新 manifest 覆盖发布 job 上传的全部 ABI APK；旧 manifest 缺少该字段时保留兼容。
    declared_artifacts = manifest.get("apkArtifacts")
    if declared_artifacts is not None:
        if not isinstance(declared_artifacts, list) or len(declared_artifacts) != len(apk_paths):
            errors.append("apkArtifacts 与发布 APK 数量不一致")
        else:
            declared_by_name = {
                item.get("name"): item
                for item in declared_artifacts
                if isinstance(item, dict) and isinstance(item.get("name"), str)
            }
            for apk in apk_paths:
                if not apk.is_file():
                    continue
                item = declared_by_name.get(apk.name)
                if item is None:
                    errors.append(f"manifest 缺少 APK 条目: {apk.name}")
                    continue
                if item.get("sizeBytes") != apk.stat().st_size:
                    errors.append(f"apkArtifacts 大小与实际 APK 不一致: {apk.name}")
                if item.get("sha256") != sha256_of(apk):
                    errors.append(f"apkArtifacts 摘要与实际 APK 不一致: {apk.name}")

    if db_path.is_file():
        try:
            actual_db_version = db_version_of(db_path)
            if manifest.get("dbVersion") != actual_db_version:
                errors.append("dbVersion 与当前数据库源代码不一致")
        except ValueError as error:
            errors.append(str(error))
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--apk", required=True, action="append", type=Path)
    parser.add_argument("--db-file", required=True, type=Path)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--commit-sha", required=True)
    args = parser.parse_args()
    errors = validate_manifest(args.manifest, args.apk, args.db_file, args.tag, args.commit_sha)
    if errors:
        print("release manifest validation FAIL:")
        for error in errors:
            print(f"  - {error}")
        return 1
    print("release manifest validation PASS: version, DB, APK digest and provenance match")
    return 0


if __name__ == "__main__":
    sys.exit(main())
