#!/usr/bin/env python3
"""Regression tests for strict locale matching."""

import contextlib
import io
import sys
import tempfile
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parents[1] / "script"
sys.path.insert(0, str(SCRIPT_DIR))

from check_localizations import check_locale


def write_resource(path: Path, key: str | None) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    body = f'<string name="{key}">value</string>' if key else ""
    path.write_text(f"<resources>{body}</resources>\n", encoding="utf-8")


def test_plain_locale_codes_match_android_resource_dirs():
    with tempfile.TemporaryDirectory() as tmp:
        res_dir = Path(tmp)
        write_resource(res_dir / "values" / "strings.xml", "required_key")
        write_resource(res_dir / "values-en" / "strings.xml", None)
        write_resource(res_dir / "values-es" / "strings.xml", None)

        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            assert check_locale(res_dir, strict_locales={"en"}) == 1
            assert check_locale(res_dir, strict_locales={"es"}) == 1
            assert check_locale(res_dir, strict_locales={"values-es"}) == 1


def test_unselected_locale_remains_warning_only():
    with tempfile.TemporaryDirectory() as tmp:
        res_dir = Path(tmp)
        write_resource(res_dir / "values" / "strings.xml", "required_key")
        write_resource(res_dir / "values-en" / "strings.xml", "required_key")
        write_resource(res_dir / "values-es" / "strings.xml", None)

        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            assert check_locale(res_dir, strict_locales={"en"}) == 0


def test_strings_outside_strings_named_files_are_checked():
    with tempfile.TemporaryDirectory() as tmp:
        res_dir = Path(tmp)
        write_resource(res_dir / "values" / "automation_tasker_strings.xml", "tasker_title")
        write_resource(res_dir / "values-es" / "strings.xml", None)

        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            assert check_locale(res_dir, strict_locales={"es"}) == 1
            write_resource(res_dir / "values-es" / "automation_tasker_strings.xml", "tasker_title")
            assert check_locale(res_dir, strict_locales={"es"}) == 0


def test_untranslatable_keys_are_not_required_in_other_locales():
    """语言自称、专有名词这类 translatable="false" 的 key 只写在 values/，不该逼每个 locale 复制一遍。"""
    with tempfile.TemporaryDirectory() as tmp:
        res_dir = Path(tmp)
        (res_dir / "values").mkdir(parents=True, exist_ok=True)
        (res_dir / "values" / "strings.xml").write_text(
            "<resources>"
            '<string name="needs_translation">value</string>'
            '<string name="endonym" translatable="false">日本語</string>'
            "</resources>\n",
            encoding="utf-8",
        )
        (res_dir / "values-en" / "strings.xml").parent.mkdir(parents=True, exist_ok=True)
        write_resource(res_dir / "values-en" / "strings.xml", "needs_translation")

        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            assert check_locale(res_dir, strict_locales={"en"}) == 0


def write_typed_resource(path: Path, kind: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    body = {
        "string": "value",
        "plurals": '<item quantity="other">values</item>',
        "string-array": "<item>value</item>",
    }[kind]
    path.write_text(f'<resources><{kind} name="shared_key">{body}</{kind}></resources>\n', encoding="utf-8")


def test_matching_resource_kinds_across_files_pass():
    for kind in ("string", "plurals", "string-array"):
        with tempfile.TemporaryDirectory() as tmp:
            res_dir = Path(tmp)
            write_typed_resource(res_dir / "values" / "baseline.xml", kind)
            write_typed_resource(res_dir / "values-es" / "translated.xml", kind)
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                assert check_locale(res_dir, strict_locales={"es"}) == 0


def test_wrong_resource_kinds_fail_even_for_unselected_locales():
    for expected in ("string", "plurals", "string-array"):
        for actual in ("string", "plurals", "string-array"):
            if expected == actual:
                continue
            with tempfile.TemporaryDirectory() as tmp:
                res_dir = Path(tmp)
                write_typed_resource(res_dir / "values" / "baseline.xml", expected)
                write_typed_resource(res_dir / "values-es" / "translated.xml", actual)
                output = io.StringIO()
                with contextlib.redirect_stdout(output), contextlib.redirect_stderr(io.StringIO()):
                    assert check_locale(res_dir, strict_locales={"en"}) == 1
                assert f"TYPE MISMATCH: shared_key: expected {expected}; found {actual}" in output.getvalue()


def test_commented_resource_kinds_do_not_override_plural():
    with tempfile.TemporaryDirectory() as tmp:
        res_dir = Path(tmp)
        write_typed_resource(res_dir / "values" / "baseline.xml", "plurals")
        path = res_dir / "values-es" / "translated.xml"
        write_typed_resource(path, "plurals")
        path.write_text(path.read_text().replace("</resources>", '<!-- <string name="shared_key">old</string> --></resources>'))
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            assert check_locale(res_dir, strict_locales={"es"}) == 0


if __name__ == "__main__":
    test_plain_locale_codes_match_android_resource_dirs()
    test_unselected_locale_remains_warning_only()
    test_strings_outside_strings_named_files_are_checked()
    test_untranslatable_keys_are_not_required_in_other_locales()
    test_matching_resource_kinds_across_files_pass()
    test_wrong_resource_kinds_fail_even_for_unselected_locales()
    test_commented_resource_kinds_do_not_override_plural()
    print("all localization tests passed")
