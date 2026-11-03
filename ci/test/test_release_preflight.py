#!/usr/bin/env python3
"""Tests for release version and signing preflight helpers."""

import base64
import sys
import tempfile
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

SCRIPT_DIR = Path(__file__).resolve().parents[1] / "script"
sys.path.insert(0, str(SCRIPT_DIR))

from resolve_release_version import (
    VERSION_CODE_LINE_RE,
    VERSION_NAME_LINE_RE,
    resolve,
)
from validate_release_signing import decode_keystore, normalize_text
from generate_release_manifest import main as generate_manifest_main
from generate_release_manifest import db_version_of
from validate_release_manifest import validate_manifest
from validate_release_apks import (
    EXPECTED_SIGNING_CERT_SHA256,
    expand_apks,
    verify_apk,
)


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


def test_db_version_accepts_companion_schema_constant(tmp_path):
    db_file = tmp_path / "MuseDb.kt"
    db_file.write_text(
        "abstract class MuseDb {\n"
        "    companion object {\n"
        "        const val LATEST_SCHEMA_VERSION = 124\n"
        "    }\n"
        "}\n"
        "@Database(version = MuseDb.LATEST_SCHEMA_VERSION)\n",
        encoding="utf-8",
    )

    assert db_version_of(db_file) == 124


def test_manifest_validator_uses_the_same_schema_constant_resolution(tmp_path):
    apk = tmp_path / "app-arm64-v8a-release.apk"
    apk.write_bytes(b"apk")
    db = tmp_path / "MuseDb.kt"
    db.write_text(
        "abstract class MuseDb {\n"
        "    companion object {\n"
        "        const val LATEST_SCHEMA_VERSION = 124\n"
        "    }\n"
        "}\n"
        "@Database(version = MuseDb.LATEST_SCHEMA_VERSION)\n",
        encoding="utf-8",
    )
    notes = tmp_path / "release_body.md"
    notes.write_text("notes", encoding="utf-8")
    manifest = tmp_path / "manifest.json"
    manifest.write_text(
        __import__("json").dumps(
            {
                "versionName": "2.6.21",
                "versionCode": 284,
                "dbVersion": 124,
                "apkSizeBytes": 3,
                "apkSha256": __import__("hashlib").sha256(b"apk").hexdigest(),
                "releaseNotesRef": notes.name,
                "sourceCommit": "a" * 40,
                "sourceRef": "v2.6.21",
            },
        ),
        encoding="utf-8",
    )

    assert validate_manifest(manifest, apk, db, "v2.6.21", "A" * 40) == []


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


def test_release_apk_verification_requires_pinned_certificate():
    with tempfile.TemporaryDirectory() as raw_dir:
        root = Path(raw_dir)
        apk = root / "app-release.apk"
        apk.write_bytes(b"apk")
        apksigner = root / "apksigner"
        output = f"Signer #1 certificate SHA-256 digest: {EXPECTED_SIGNING_CERT_SHA256}\n"
        with patch(
            "validate_release_apks.subprocess.run",
            return_value=SimpleNamespace(returncode=0, stdout=output, stderr=""),
        ) as run:
            verify_apk(apk, apksigner)
        assert "--print-certs" in run.call_args.args[0]


def test_release_apk_verification_rejects_unexpected_certificate():
    with tempfile.TemporaryDirectory() as raw_dir:
        root = Path(raw_dir)
        apk = root / "app-release.apk"
        apk.write_bytes(b"apk")
        apksigner = root / "apksigner"
        output = "Signer #1 certificate SHA-256 digest: " + ("0" * 64)
        with patch(
            "validate_release_apks.subprocess.run",
            return_value=SimpleNamespace(returncode=0, stdout=output, stderr=""),
        ):
            try:
                verify_apk(apk, apksigner)
            except RuntimeError as error:
                assert "证书与发布证书不一致" in str(error)
            else:
                raise AssertionError("unexpected release certificate must fail")


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


def test_release_workflow_publishes_only_distribution_apks():
    workflow = (ROOT / ".github" / "workflows" / "ci.yml").read_text(encoding="utf-8")
    manifest_start = workflow.index("      - name: Generate release manifest")
    artifact_start = workflow.index("      - name: Upload release build artifacts", manifest_start)
    publish_start = workflow.index("      - name: Publish GitHub Release", artifact_start)
    manifest_step = workflow[manifest_start:artifact_start]
    artifact_step = workflow[artifact_start:publish_start]
    publish_step = workflow[publish_start:]
    distribution_arches = ("arm64-v8a", "armeabi-v7a", "universal")

    for architecture in distribution_arches:
        source_name = f"app-{architecture}-release.apk"
        assert source_name in manifest_step
        assert f"Architecture = '{architecture}'" in manifest_step

    assert "Muse_$($env:GITHUB_REF_NAME)_$($mapping.Architecture).apk" in manifest_step
    assert "release/Muse_*.apk" in artifact_step
    assert "release/Muse_*.apk" in publish_step
    assert "app/build/outputs/apk/release/*.apk" not in manifest_step
    assert "app/build/outputs/apk/release/*.apk" not in publish_step

    assert "app-x86_64-release.apk" not in manifest_step
    assert "app-x86_64-release.apk" not in artifact_step
    assert "app-x86_64-release.apk" not in publish_step
    assert "*.apk" not in manifest_step
    assert artifact_step.count("release/Muse_*.apk") == 1
    assert publish_step.count("release/Muse_*.apk") == 1


if __name__ == "__main__":
    test_release_version_matches_current_gradle_defaults()
    test_release_version_rejects_tag_drift()
    test_release_signing_never_falls_back_to_debug_keystore()
    test_secret_text_removes_bom_and_transport_whitespace()
    test_keystore_base64_decoder_ignores_transport_whitespace()
    test_release_apk_glob_expansion_deduplicates_and_filters()
    test_release_apk_verification_requires_pinned_certificate()
    test_release_apk_verification_rejects_unexpected_certificate()
    test_release_manifest_multi_apk_round_trip_without_pytest_fixtures()
    test_release_workflow_publishes_only_distribution_apks()
    print("test_release_preflight OK")
