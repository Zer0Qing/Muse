#!/usr/bin/env python3
"""F-05: 生成 release/manifest.json(发布单一来源)。

单一来源原则: 版本名/版本号/DB 版本/APK 校验和/发布说明引用/源码 provenance/生成时间
全部在此产出一份 manifest, 后续发布脚本/应用内更新/人工核对都以它为准,
杜绝"版本号散落多处、发布时各读各的"导致的不一致。

用法(CI release job 在 Build release APKs 后调用):
  python3 ci/script/generate_release_manifest.py \
    --version-name 1.0.76 --version-code 175 \
    --apk app/build/outputs/apk/release/app-arm64-v8a-release.apk \
    --apk app/build/outputs/apk/release/app-universal-release.apk \
    --db-file app/src/main/java/io/zer0/muse/data/session/MuseDb.kt \
    --release-notes releases/v1.0.76/release_body.md \
    --out release/manifest.json

幂等: 同版本重复生成直接覆盖, 内容一致时字节不变。
"""

import argparse
import hashlib
import json
import re
import os
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def db_version_of(db_file: Path) -> int:
    text = db_file.read_text(encoding="utf-8")
    m = re.search(r"version\s*=\s*(\d+)", text)
    if not m:
        raise SystemExit(f"FATAL: 无法从 {db_file} 解析 DB version")
    return int(m.group(1))


def atomic_write_text(path: Path, payload: str) -> None:
    """Write a release index beside the target, then replace it atomically."""
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temp_name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as output:
            output.write(payload)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temp_name, path)
    except BaseException:
        try:
            os.unlink(temp_name)
        except FileNotFoundError:
            pass
        raise


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version-name", required=True, help="如 1.0.76(tag 去 v)")
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--apk", required=True, action="append", type=Path)
    parser.add_argument("--db-file", required=True, type=Path)
    parser.add_argument("--release-notes", required=True, help="release_body.md 相对路径")
    parser.add_argument(
        "--commit-sha",
        required=True,
        help="构建来源 commit SHA，通常传入 GitHub Actions 的 GITHUB_SHA",
    )
    parser.add_argument(
        "--source-ref",
        required=True,
        help="构建来源 ref/tag，通常传入 GitHub Actions 的 GITHUB_REF_NAME",
    )
    parser.add_argument("--out", default="release/manifest.json", type=Path)
    args = parser.parse_args()
    commit_sha = args.commit_sha.strip()
    source_ref = args.source_ref.strip()
    if not re.fullmatch(r"[0-9a-fA-F]{7,64}", commit_sha):
        print("FATAL: --commit-sha 必须是 7-64 位十六进制 commit SHA")
        return 1
    if not source_ref:
        print("FATAL: --source-ref 不能为空")
        return 1

    missing_apks = [apk for apk in args.apk if not apk.is_file()]
    if missing_apks:
        print(f"FATAL: APK 不存在 {missing_apks}")
        return 1
    if not args.db_file.is_file():
        print(f"FATAL: DB 文件不存在 {args.db_file}")
        return 1
    release_notes_path = Path(args.release_notes)
    if not release_notes_path.is_file():
        print(f"FATAL: 发布说明不存在 {args.release_notes}")
        return 1
    if ".." in release_notes_path.parts:
        print("FATAL: --release-notes 不能包含目录穿越")
        return 1
    release_notes_ref = release_notes_path.name if release_notes_path.is_absolute() else release_notes_path.as_posix()

    apk_artifacts = [
        {
            # 只发布资产名，不把 CI runner 的本地绝对路径写入公开 manifest。
            "name": apk.name,
            "sizeBytes": apk.stat().st_size,
            "sha256": sha256_of(apk),
        }
        for apk in args.apk
    ]
    primary_apk = args.apk[0]
    manifest = {
        "versionName": args.version_name,
        "versionCode": args.version_code,
        "dbVersion": db_version_of(args.db_file),
        # 保留首个 APK 的旧字段，使用资产名避免公开 CI runner 本地路径；完整集合见 apkArtifacts。
        "apkFile": primary_apk.name,
        "apkSizeBytes": primary_apk.stat().st_size,
        "apkSha256": sha256_of(primary_apk),
        "apkArtifacts": apk_artifacts,
        "releaseNotesRef": release_notes_ref,
        "sourceCommit": commit_sha.lower(),
        "sourceRef": source_ref,
        "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
    }

    args.out.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
    # 幂等判定忽略 generatedAt: 同一版本重复生成时其余字段必须逐字节一致,
    # 仅时间戳更新视为"无实质变化",避免 CI 重跑产生无意义 diff。
    stable = json.dumps({k: v for k, v in manifest.items() if k != "generatedAt"}, ensure_ascii=False, sort_keys=True)
    if args.out.exists():
        existing = json.loads(args.out.read_text(encoding="utf-8"))
        existing_stable = json.dumps(
            {k: v for k, v in existing.items() if k != "generatedAt"},
            ensure_ascii=False, sort_keys=True,
        )
        if existing_stable == stable:
            print(f"manifest 未变化(幂等): {args.out}")
            return 0
    atomic_write_text(args.out, payload)
    # 替换后立即回读，避免把写入异常误报为成功。
    json.loads(args.out.read_text(encoding="utf-8"))
    print(f"manifest 已生成: {args.out}")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
