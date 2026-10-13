import sys
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent.parent
sys.path.insert(0, str(SCRIPT_DIR))

from resolve_release_version import resolve


class ReleaseVersionBaselineTest(unittest.TestCase):
    def test_v260_tag_matches_active_gradle_baseline(self):
        self.assertEqual(
            ("2.6.0", 260),
            resolve("v2.6.0", REPO_ROOT / "app" / "build.gradle.kts"),
        )


if __name__ == "__main__":
    unittest.main()
