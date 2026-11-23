#!/usr/bin/env python3
"""Regression tests for runtime manifest discovery."""

import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parents[1] / "script"
sys.path.insert(0, str(SCRIPT_DIR))

from check_runtime_manifests import manifests_in


class RuntimeManifestDiscoveryTest(unittest.TestCase):
    def test_ignores_unrelated_manifest_json(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "runtime").mkdir()
            (root / "licenses").mkdir()
            (root / "runtime" / "manifest-arm64-v8a.json").touch()
            (root / "runtime" / "manifest-x86_64.json").touch()
            (root / "licenses" / "manifest.json").touch()

            found = manifests_in(root)

            self.assertEqual(
                {"manifest-arm64-v8a.json", "manifest-x86_64.json"},
                {path.name for path in found},
            )

    def test_accepts_abi_scoped_release_manifests(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for abi in ("arm64-v8a", "x86_64"):
                directory = root / abi
                directory.mkdir()
                (directory / "manifest.json").touch()

            found = manifests_in(root)

            self.assertEqual(2, len(found))
            self.assertEqual({"arm64-v8a", "x86_64"}, {path.parent.name for path in found})


if __name__ == "__main__":
    unittest.main()
