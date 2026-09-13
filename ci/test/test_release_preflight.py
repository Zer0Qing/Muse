#!/usr/bin/env python3
"""Tests for release version and signing preflight helpers."""

import base64
import sys
import tempfile
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parents[1] / "script"
sys.path.insert(0, str(SCRIPT_DIR))

from resolve_release_version import (
    VERSION_CODE_LINE_RE,
    VERSION_NAME_LINE_RE,
    resolve,
)
from validate_release_signing import decode_keystore, normalize_text
from generate_release_manifest import main as generate_manifest_main
from validate_release_manifest import validate_manifest
from validate_release_apks import expand_apks


ROOT = Path(__file__).resolve().parents[2]


def _read_gradle_defaults(gradle_file: Path) -> tuple[str, int]:
    """按 resolve_release_version 的同源规则读当前默认版本,测试不随版本号过时。"""
    lines = gradle_file.read_text(encoding="utf-8").splitlines()
    inside = False
    version_code: int | None = None
    for line in lines:
        stripped = line.strip()
        if stripped.startswith("versionCode ="):
            inside = True
            continue
        if inside and stripped.startswith("versionName ="):
            break
        if inside:
            match = VERSION_CODE_LINE_RE.fullmatch(line)
            if match:
                version_code = int(match.group(1))
    assert version_code is not None, "default versionCode not found in build.gradle.kts"
    inside = False
    version_name: str | None = None
    for line in lines:
        stripped = line.strip()
        if stripped.startswith("versionName ="):
            inside = True
            continue
        if inside and stripped.startswith("buildTypes"):
            break
        if inside:
            match = VERSION_NAME_LINE_RE.fullmatch(line)
            if match:
                version_name = match.group(1)
    assert version_name is not None, "default versionName not found in build.gradle.kts"
    return version_name, version_code


def test_release_version_matches_current_gradle_defaults():
    expected_name, expected_code = _read_gradle_defaults(ROOT / "app" / "build.gradle.kts")
    version_name, version_code = resolve(
        f"v{expected_name}",
        ROOT / "app" / "build.gradle.kts",
    )
    assert version_name == expected_name
    assert version_code == expected_code


def test_release_version_rejects_tag_drift():
    try:
        resolve("v9.9.9", ROOT / "app" / "build.gradle.kts")
    except ValueError as error:
        assert "does not match Gradle default" in str(error)
    else:
        raise AssertionError("tag/version drift should fail before building")


def test_release_signing_never_falls_back_to_debug_keystore():
    gradle_text = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    assert 'signingConfigs.getByName("debug")' not in gradle_text
    assert "缺失时不再回退到 debug keystore" in gradle_text


def test_secret_text_removes_bom_and_transport_whitespace():
    assert normalize_text("\ufeffï»¿ muse\r\n", "KEY_ALIAS") == "muse"


def test_keystore_base64_decoder_ignores_transport_whitespace():
    payload = b"valid keystore bytes"
    encoded = base64.b64encode(payload).decode("ascii")
    assert decode_keystore("\ufeff" + encoded[:5] + "\r\n" + encoded[5:]) == payload


def test_release_manifest_requires_and_records_provenance(tmp_path, monkeypatch):
    apk = tmp_path / "app-release.apk"
    secondary_apk = tmp_path / "app-arm64-release.apk"
    apk.write_bytes(b"apk")
    secondary_apk.write_bytes(b"arm64-apk")
    db = tmp_path / "MuseDb.kt"
    db.write_text("val version = 42", encoding="utf-8")
    notes = tmp_path / "release_body.md"
    notes.write_text("notes", encoding="utf-8")
    output = tmp_path / "release" / "manifest.json"
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "generate_release_manifest.py",
            "--version-name", "1.2.3",
            "--version-code", "7",
            "--apk", str(apk),
            "--apk", str(secondary_apk),
            "--db-file", str(db),
            "--release-notes", str(notes),
            "--commit-sha", "A" * 40,
            "--source-ref", "v1.2.3",
            "--out", str(output),
        ],
    )
    assert generate_manifest_main() == 0
    manifest = __import__("json").loads(output.read_text(encoding="utf-8"))
    assert manifest["sourceCommit"] == "a" * 40
    assert manifest["sourceRef"] == "v1.2.3"
    assert [item["name"] for item in manifest["apkArtifacts"]] == [
        "app-release.apk",
        "app-arm64-release.apk",
    ]
    assert all("file" not in item for item in manifest["apkArtifacts"])


def test_release_manifest_validator_accepts_matching_inputs(tmp_path):
    apk = tmp_path / "app-release.apk"
    secondary_apk = tmp_path / "app-arm64-release.apk"
    apk.write_bytes(b"apk")
    secondary_apk.write_bytes(b"arm64-apk")
    db = tmp_path / "MuseDb.kt"
    db.write_text("val version = 42", encoding="utf-8")
    manifest = tmp_path / "manifest.json"
    import hashlib
    manifest.write_text(
        __import__("json").dumps(
            {
                "versionName": "1.2.3",
                "versionCode": 7,
                "dbVersion": 42,
                "apkSizeBytes": 3,
                "apkSha256": hashlib.sha256(b"apk").hexdigest(),
                "apkArtifacts": [
                    {
                        "name": "app-release.apk",
                        "sizeBytes": 3,
                        "sha256": hashlib.sha256(b"apk").hexdigest(),
                    },
                    {
                        "name": "app-arm64-release.apk",
                        "sizeBytes": 9,
                        "sha256": hashlib.sha256(b"arm64-apk").hexdigest(),
                    },
                ],
                "releaseNotesRef": "notes.md",
                "sourceCommit": "a" * 40,
                "sourceRef": "v1.2.3",
            }
        ),
        encoding="utf-8",
    )
    assert validate_manifest(manifest, [apk, secondary_apk], db, "v1.2.3", "A" * 40) == []


def test_release_manifest_validator_rejects_apk_drift(tmp_path):
    apk = tmp_path / "app-release.apk"
    apk.write_bytes(b"changed")
    db = tmp_path / "MuseDb.kt"
    db.write_text("val version = 42", encoding="utf-8")
    manifest = tmp_path / "manifest.json"
    manifest.write_text(
        __import__("json").dumps(
            {
                "versionName": "1.2.3",
                "versionCode": 7,
                "dbVersion": 42,
                "apkSizeBytes": 3,
                "apkSha256": "0" * 64,
                "releaseNotesRef": "notes.md",
                "sourceCommit": "a" * 40,
                "sourceRef": "v1.2.3",
            }
        ),
        encoding="utf-8",
    )
    errors = validate_manifest(manifest, apk, db, "v1.2.3", "A" * 40)
    assert "apkSizeBytes 与实际 APK 不一致" in errors
    assert "apkSha256 与实际 APK 不一致" in errors


def test_release_apk_glob_expansion_deduplicates_and_filters():
    with tempfile.TemporaryDirectory() as raw_dir:
        tmp_path = Path(raw_dir)
        first = tmp_path / "app-arm64.apk"
        second = tmp_path / "app-universal.apk"
        ignored = tmp_path / "notes.txt"
        first.write_bytes(b"apk")
        second.write_bytes(b"apk")
        ignored.write_text("not an apk", encoding="utf-8")

        result = expand_apks([str(tmp_path / "*"), str(first)])

        assert result == [first, second]


def test_release_manifest_multi_apk_round_trip_without_pytest_fixtures():
    with tempfile.TemporaryDirectory() as raw_dir:
        root = Path(raw_dir)
        apk = root / "app-universal-release.apk"
        secondary_apk = root / "app-arm64-v8a-release.apk"
        db = root / "MuseDb.kt"
        notes = root / "release_body.md"
        output = root / "release" / "manifest.json"
        apk.write_bytes(b"universal-apk")
        secondary_apk.write_bytes(b"arm64-apk")
        db.write_text("val version = 96", encoding="utf-8")
        notes.write_text("notes", encoding="utf-8")
        old_argv = sys.argv
        try:
            sys.argv = [
                "generate_release_manifest.py",
                "--version-name", "1.0.86",
                "--version-code", "186",
                "--apk", str(apk),
                "--apk", str(secondary_apk),
                "--db-file", str(db),
                "--release-notes", str(notes),
                "--commit-sha", "4" * 40,
                "--source-ref", "v1.0.86",
                "--out", str(output),
            ]
            assert generate_manifest_main() == 0
        finally:
            sys.argv = old_argv
        assert validate_manifest(
            output,
            [apk, secondary_apk],
            db,
            "v1.0.86",
            "4" * 40,
        ) == []


def test_release_manifest_rejects_invalid_provenance(tmp_path, monkeypatch):
    apk = tmp_path / "app-release.apk"
    apk.write_bytes(b"apk")
    db = tmp_path / "MuseDb.kt"
    db.write_text("val version = 42", encoding="utf-8")
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "generate_release_manifest.py",
            "--version-name", "1.2.3",
            "--version-code", "7",
            "--apk", str(apk),
            "--db-file", str(db),
            "--release-notes", "notes.md",
            "--commit-sha", "not-a-sha",
            "--source-ref", "v1.2.3",
            "--out", str(tmp_path / "manifest.json"),
        ],
    )
    assert generate_manifest_main() == 1


if __name__ == "__main__":
    test_release_version_matches_current_gradle_defaults()
    test_release_version_rejects_tag_drift()
    test_release_signing_never_falls_back_to_debug_keystore()
    test_secret_text_removes_bom_and_transport_whitespace()
    test_keystore_base64_decoder_ignores_transport_whitespace()
    test_release_apk_glob_expansion_deduplicates_and_filters()
    test_release_manifest_multi_apk_round_trip_without_pytest_fixtures()
    print("test_release_preflight OK")
