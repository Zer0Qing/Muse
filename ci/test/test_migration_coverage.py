#!/usr/bin/env python3
"""Regression tests for extra Room database migration coverage."""

import sys
import tempfile
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parents[1] / "script"
sys.path.insert(0, str(SCRIPT_DIR))

from check_migration_coverage import check_extra_db_static_chain


DB_SOURCE = """
@Database(version = 3)
abstract class MemoryDb {
    val MIGRATION_1_2 = object : Migration(1, 2) {}
    val MIGRATION_2_3 = object : Migration(2, 3) {}
}
"""


def write(path: Path, text: str) -> Path:
    path.write_text(text, encoding="utf-8")
    return path


def test_memory_database_registration_and_test_coverage():
    with tempfile.TemporaryDirectory() as raw_dir:
        root = Path(raw_dir)
        db = write(root / "MemoryDb.kt", DB_SOURCE)
        builder = write(
            root / "MemoryModule.kt",
            ".addMigrations(MemoryDb.MIGRATION_1_2, MemoryDb.MIGRATION_2_3)",
        )
        test = write(
            root / "MemoryDbMigrationTest.kt",
            "MemoryDb.MIGRATION_1_2\nMemoryDb.MIGRATION_2_3",
        )

        failures = [0]
        check_extra_db_static_chain(
            db,
            "MemoryDb",
            1,
            failures,
            registration_path=builder,
            test_path=test,
        )
        assert failures == [0], failures


def test_memory_database_missing_registration_fails():
    with tempfile.TemporaryDirectory() as raw_dir:
        root = Path(raw_dir)
        db = write(root / "MemoryDb.kt", DB_SOURCE)
        builder = write(root / "MemoryModule.kt", ".addMigrations(MemoryDb.MIGRATION_2_3)")
        test = write(root / "MemoryDbMigrationTest.kt", "MemoryDb.MIGRATION_2_3")

        failures = [0]
        check_extra_db_static_chain(
            db,
            "MemoryDb",
            1,
            failures,
            registration_path=builder,
            test_path=test,
        )
        assert failures[0] > 0


def test_memory_database_missing_test_reference_fails():
    with tempfile.TemporaryDirectory() as raw_dir:
        root = Path(raw_dir)
        db = write(root / "MemoryDb.kt", DB_SOURCE)
        builder = write(
            root / "MemoryModule.kt",
            ".addMigrations(MemoryDb.MIGRATION_1_2, MemoryDb.MIGRATION_2_3)",
        )
        test = write(root / "MemoryDbMigrationTest.kt", "MemoryDb.MIGRATION_2_3")

        failures = [0]
        check_extra_db_static_chain(
            db,
            "MemoryDb",
            1,
            failures,
            registration_path=builder,
            test_path=test,
        )
        assert failures[0] > 0


def test_memory_database_companion_version_constant_resolves():
    with tempfile.TemporaryDirectory() as raw_dir:
        root = Path(raw_dir)
        db = write(
            root / "MemoryDb.kt",
            """
            @Database(version = MemoryDb.MEMORY_DB_VERSION)
            abstract class MemoryDb {
                companion object {
                    const val MEMORY_DB_VERSION = 3
                }
                val MIGRATION_1_2 = object : Migration(1, 2) {}
                val MIGRATION_2_3 = object : Migration(2, 3) {}
            }
            """,
        )
        builder = write(
            root / "MemoryModule.kt",
            ".addMigrations(MemoryDb.MIGRATION_1_2, MemoryDb.MIGRATION_2_3)",
        )
        test = write(
            root / "MemoryDbMigrationTest.kt",
            "MemoryDb.MIGRATION_1_2\nMemoryDb.MIGRATION_2_3",
        )

        failures = [0]
        check_extra_db_static_chain(
            db,
            "MemoryDb",
            1,
            failures,
            registration_path=builder,
            test_path=test,
        )
        assert failures == [0], failures


if __name__ == "__main__":
    test_memory_database_registration_and_test_coverage()
    test_memory_database_missing_registration_fails()
    test_memory_database_missing_test_reference_fails()
    test_memory_database_companion_version_constant_resolves()
    print("all migration coverage tests passed")
