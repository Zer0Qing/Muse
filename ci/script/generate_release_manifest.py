#!/usr/bin/env python3
"""F-05: 生成 release/manifest.json(发布单一来源)。

单一来源原则: 版本名/版本号/DB 版本/APK 校验和/发布说明引用/生成时间
全部在此产出一份 manifest, 后续发布脚本/应用内更新/人工核对都以它为准,
杜绝"版本号散落多处、发布时各读各的"导致的不一致。

用法(CI release job 在 Build release APKs 后调用):
  python3 ci/script/generate_release_manifest.py \
    --version-name 1.0.76 --version-code 175 \
    --apk app/build/outputs/apk/release/app-release.apk \
    --db-file app/src/main/java/io/zer0/muse/data/session/MuseDb.kt \
    --release-notes releases/v1.0.76/release_body.md \
    --out release/manifest.json

幂等: 同版本重复生成直接覆盖, 内容一致时字节不变。
"""

import argparse
import hashlib
import json
import re
import sys
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


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version-name", required=True, help="如 1.0.76(tag 去 v)")
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--db-file", required=True, type=Path)
    parser.add_argument("--release-notes", required=True, help="release_body.md 相对路径")
    parser.add_argument("--out", default="release/manifest.json", type=Path)
    args = parser.parse_args()

    if not args.apk.is_file():
        print(f"FATAL: APK 不存在 {args.apk}")
        return 1
    if not args.db_file.is_file():
        print(f"FATAL: DB 文件不存在 {args.db_file}")
        return 1

    manifest = {
        "versionName": args.version_name,
        "versionCode": args.version_code,
        "dbVersion": db_version_of(args.db_file),
        "apkFile": str(args.apk),
        "apkSizeBytes": args.apk.stat().st_size,
        "apkSha256": sha256_of(args.apk),
        "releaseNotesRef": args.release_notes,
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
    args.out.write_text(payload, encoding="utf-8")
    print(f"manifest 已生成: {args.out}")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
