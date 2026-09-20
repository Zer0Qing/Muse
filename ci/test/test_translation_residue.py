#!/usr/bin/env python3
"""
test_translation_residue.py — check_translation_residue.py 的单元测试。

运行: py -3 ci/test/test_translation_residue.py
"""

import json
import sys
import tempfile
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parents[1] / "script"
sys.path.insert(0, str(SCRIPT_DIR))

from check_translation_residue import (  # noqa: E402
    RES_REL,
    evaluate,
    locale_of,
    scan_res,
    update_baseline,
)


def make_root(files: dict[str, str]) -> Path:
    root = Path(tempfile.mkdtemp())
    for rel, text in files.items():
        p = root / RES_REL / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding="utf-8")
    return root


def strings(body: str) -> str:
    return f"<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>{body}</resources>\n"


def test_attributed_string_detected():
    """旧 PowerShell 脚本要求 `<string name="x">` 紧邻,带属性会漏报 —— 这条是回归测试。"""
    root = make_root(
        {"values-en/strings.xml": strings('<string name="x" formatted="false">中文残留</string>')}
    )
    found = scan_res(root)
    assert len(found["en"]) == 1, found


def test_translatable_false_excluded():
    root = make_root(
        {"values-en/strings.xml": strings('<string name="x" translatable="false">中文</string>')}
    )
    assert scan_res(root)["en"] == []


def test_commented_entry_excluded():
    root = make_root(
        {"values-en/strings.xml": strings('<!-- <string name="x">中文</string> -->')}
    )
    assert scan_res(root)["en"] == []


def test_plurals_item_detected():
    root = make_root(
        {
            "values-ru/strings.xml": strings(
                '<plurals name="p"><item quantity="one">一个</item>'
                '<item quantity="other">many</item></plurals>'
            )
        }
    )
    found = scan_res(root)
    assert len(found["ru"]) == 1 and found["ru"][0].name == "p[one]", found


def test_ja_and_default_values_excluded():
    root = make_root(
        {
            "values/strings.xml": strings('<string name="x">中文源</string>'),
            "values-ja/strings.xml": strings('<string name="x">画面の読み取り</string>'),
            "values-v31/themes.xml": strings('<string name="x">中文</string>'),
        }
    )
    found = scan_res(root)
    assert found == {}, found


def test_locale_of():
    assert locale_of("values-pt-rBR") == "pt-rBR"
    assert locale_of("values-en") == "en"
    assert locale_of("values-ja") is None
    assert locale_of("values-zh-rCN") is None
    assert locale_of("values-v31") is None
    assert locale_of("values") is None


def test_clean_pack_passes():
    root = make_root({"values-en/strings.xml": strings('<string name="x">hello</string>')})
    found = scan_res(root)
    failures, _ = evaluate(found, {"locales": {"en": 0}})
    assert failures == [], failures


def test_over_baseline_fails():
    root = make_root(
        {"values-en/strings.xml": strings('<string name="x" formatted="false">中文</string>')}
    )
    found = scan_res(root)
    failures, _ = evaluate(found, {"locales": {"en": 0}})
    assert any("en" in f and "> 基线" in f for f in failures), failures


def test_new_locale_over_zero_baseline_fails():
    root = make_root({"values-fr/strings.xml": strings('<string name="x">中文</string>')})
    failures, _ = evaluate(scan_res(root), {"locales": {"en": 0}})
    assert any("基线外语言包" in f for f in failures), failures


def test_update_baseline_refuses_increase():
    root = make_root(
        {"values-en/strings.xml": strings('<string name="x">中文</string>')}
    )
    found = scan_res(root)
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "baseline.json"
        path.write_text(json.dumps({"locales": {"en": 0}}), encoding="utf-8")
        code = update_baseline(path, found, allow_increase=False)
        assert code == 1, code
        assert json.loads(path.read_text(encoding="utf-8"))["locales"] == {"en": 0}


def test_broken_xml_fails_closed():
    root = make_root({"values-en/strings.xml": strings('<string name="x">中文</resources>')})
    try:
        scan_res(root)
    except ValueError:
        return
    raise AssertionError("XML 解析失败时应抛 ValueError,不能静默跳过")


if __name__ == "__main__":
    tests = [
        test_attributed_string_detected,
        test_translatable_false_excluded,
        test_commented_entry_excluded,
        test_plurals_item_detected,
        test_ja_and_default_values_excluded,
        test_locale_of,
        test_clean_pack_passes,
        test_over_baseline_fails,
        test_new_locale_over_zero_baseline_fails,
        test_update_baseline_refuses_increase,
        test_broken_xml_fails_closed,
    ]
    for t in tests:
        t()
        print(f"PASS {t.__name__}")
    print("all tests passed")
