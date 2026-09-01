#!/usr/bin/env python3
"""Tests for release version and signing preflight helpers."""

import base64
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parents[1] / "script"
sys.path.insert(0, str(SCRIPT_DIR))

from resolve_release_version import (
    VERSION_CODE_LINE_RE,
    VERSION_NAME_LINE_RE,
    resolve,
)
from validate_release_signing import decode_keystore, normalize_text


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


def test_secret_text_removes_bom_and_transport_whitespace():
    assert normalize_text("\ufeffï»¿ muse\r\n", "KEY_ALIAS") == "muse"


def test_keystore_base64_decoder_ignores_transport_whitespace():
    payload = b"valid keystore bytes"
    encoded = base64.b64encode(payload).decode("ascii")
    assert decode_keystore("\ufeff" + encoded[:5] + "\r\n" + encoded[5:]) == payload


if __name__ == "__main__":
    test_release_version_matches_current_gradle_defaults()
    test_release_version_rejects_tag_drift()
    test_secret_text_removes_bom_and_transport_whitespace()
    test_keystore_base64_decoder_ignores_transport_whitespace()
    print("test_release_preflight OK")
