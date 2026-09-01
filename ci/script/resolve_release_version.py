#!/usr/bin/env python3
"""Resolve and validate the release version used by GitHub Actions.

The tag supplies versionName. The default versionCode is read from the same
Gradle file that developers update for a release. The script fails before any
long Android build when the tag and Gradle default version disagree.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

TAG_RE = re.compile(r"^v(\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?)$")
VERSION_CODE_LINE_RE = re.compile(r"^\s*\?:\s*(\d+)\s*$")
VERSION_NAME_LINE_RE = re.compile(r"^\s*\?:\s*\"([^\"]+)\"\s*$")


def resolve(tag: str, gradle_file: Path) -> tuple[str, int]:
    match = TAG_RE.fullmatch(tag.strip())
    if not match:
        raise ValueError(f"release tag must look like v1.2.3, got {tag!r}")
    version_name = match.group(1)
    lines = gradle_file.read_text(encoding="utf-8").splitlines()

    def find_default_value(
        declaration: str,
        value_pattern: re.Pattern[str],
        stop_declaration: str,
    ) -> str:
        inside = False
        for line in lines:
            stripped = line.strip()
            if stripped.startswith(declaration):
                inside = True
                continue
            if inside and stripped.startswith(stop_declaration):
                break
            if inside:
                value_match = value_pattern.fullmatch(line)
                if value_match:
                    return value_match.group(1)
        raise ValueError(f"default {declaration.removesuffix(' =')} not found in {gradle_file}")

    configured_code = find_default_value(
        "versionCode =",
        VERSION_CODE_LINE_RE,
        "versionName =",
    )
    configured_name = find_default_value(
        "versionName =",
        VERSION_NAME_LINE_RE,
        "buildTypes",
    )
    if configured_name != version_name:
        raise ValueError(
            f"tag versionName {version_name} does not match Gradle default {configured_name}; "
            "update app/build.gradle.kts before creating the tag",
        )
    return version_name, int(configured_code)


def append_output(output_file: Path, version_name: str, version_code: int) -> None:
    with output_file.open("a", encoding="utf-8", newline="\n") as handle:
        handle.write(f"version_name={version_name}\n")
        handle.write(f"version_code={version_code}\n")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", required=True)
    parser.add_argument("--gradle-file", type=Path, required=True)
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()
    try:
        version_name, version_code = resolve(args.tag, args.gradle_file)
    except (OSError, ValueError) as error:
        print(f"release version preflight failed: {error}", file=sys.stderr)
        return 1
    if args.github_output:
        append_output(args.github_output, version_name, version_code)
    print(f"release version preflight PASS: {version_name} / versionCode={version_code}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
