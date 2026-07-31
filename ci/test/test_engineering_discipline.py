#!/usr/bin/env python3
"""
test_engineering_discipline.py — check_engineering_discipline.py 的单元测试。

测试各项工程纪律规则的正则匹配正确性。
运行: py -3 ci/test/test_engineering_discipline.py
"""

import sys
from pathlib import Path

# 将 ci/script 加入 path
SCRIPT_DIR = Path(__file__).resolve().parents[1] / "script"
sys.path.insert(0, str(SCRIPT_DIR))

from check_engineering_discipline import RULES


def _matches(rule_name: str, code: str) -> bool:
    """检查给定代码是否触发指定规则。"""
    pattern = RULES[rule_name]["pattern"]
    return bool(pattern.search(code))


def test_unsafe_cast_string():
    """as String 应被识别为不安全转换。"""
    assert _matches("unsafe_cast", "val name = data as String")
    assert _matches("unsafe_cast", "val x = obj as Int")


def test_unsafe_cast_not_triggered_on_safe_cast():
    """as? String 安全转换不应触发 unsafe_cast 规则。"""
    # 注意: as? 中 as 后跟 ?,正则 \bas\s+ 要求 as 后是空白再跟类型
    # as?String 之间无空格,不会匹配;as? String 中 ? 在 as 后,也不会匹配 \bas\s+
    assert not _matches("unsafe_cast", "val name = data as? String")
    assert not _matches("unsafe_cast", "val name = data as? String ?: \"\"")


def test_non_null_assertion():
    """无注释的 !! 应被识别。"""
    assert _matches("non_null_assertion", "val name = user!!.name")
    assert _matches("non_null_assertion", "val x = foo!!")


def test_non_null_assertion_with_comment_allowed():
    """带注释的 !! 不应触发(已在契约中允许)。"""
    assert not _matches("non_null_assertion", "val name = user!!.name // 已确认非空")
    assert not _matches("non_null_assertion", "val x = foo!! // by contract")


def test_empty_catch():
    """空 catch 块应被识别为 error。"""
    assert _matches("empty_catch", "try { foo() } catch (e: Exception) {}")
    assert _matches("empty_catch", "catch (e: Throwable) { }")
    assert RULES["empty_catch"]["severity"] == "error"


def test_non_empty_catch_not_triggered():
    """非空 catch 块不应触发。"""
    assert not _matches("empty_catch", "try { foo() } catch (e: Exception) { Logger.w(e) }")


def test_null_fallback():
    """无注释的 ?: null 应被识别。"""
    assert _matches("null_fallback", "val name = user?.name ?: null")
    assert _matches("null_fallback", "val x = getValue() ?: null")


def test_null_fallback_with_comment_allowed():
    """带注释的 ?: null 不应触发。"""
    assert not _matches("null_fallback", "val name = user?.name ?: null // 可能未初始化")


def test_default_fallback():
    """?: "unknown" / ?: "default" 应被识别。"""
    assert _matches("default_fallback", 'val name = user?.name ?: "unknown"')
    assert _matches("default_fallback", 'val type = obj?.type ?: "default"')


def test_any_type_declaration():
    """val/var x: Any 显式 Any 类型应被识别。"""
    assert _matches("any_type_declaration", "val data: Any = parseResponse()")
    assert _matches("any_type_declaration", "var cache: Any = Object()")
    assert _matches("any_type_declaration", "val payload: Any? = null")


def test_any_type_declaration_not_triggered_on_generic():
    """Map<String, Any> 等泛型参数不应触发(那是合法用法)。"""
    # 正则中的 (?!\s*[<,]) 确保不会在 Any 后跟 < 或 , 时匹配
    assert not _matches("any_type_declaration", "val map: Map<String, Any> = emptyMap()")
    assert not _matches("any_type_declaration", "val list: List<Any> = emptyList()")


def test_any_return_type():
    """fun foo(): Any 函数返回 Any 应被识别。"""
    assert _matches("any_return_type", "fun getData(): Any { return 42 }")
    assert _matches("any_return_type", "fun parse(): Any? { return null }")


def test_any_return_type_not_triggered_on_generic():
    """返回 Map<String, Any> 等泛型不应触发。"""
    assert not _matches("any_return_type", "fun getMap(): Map<String, Any> { return emptyMap() }")


def test_when_else_null():
    """when 块 else -> null 应被识别为联合类型兜底。"""
    assert _matches("when_else_null", "else -> null")
    assert _matches("when_else_null", "    else -> null")


def test_when_else_null_with_comment_allowed():
    """带注释的 else -> null 不应触发。"""
    assert not _matches("when_else_null", "else -> null // 兼容旧版本")
    assert not _matches("when_else_null", "else -> null // business fallback")


def test_when_else_other_value_not_triggered():
    """else -> 其他值不应触发 when_else_null。"""
    assert not _matches("when_else_null", "else -> 0")
    assert not _matches("when_else_null", 'else -> ""')
    assert not _matches("when_else_null", "else -> error(\"unknown\")")


def test_nullable_unsafe_cast():
    """(value as String) 括号包裹的不安全转换应被识别。"""
    assert _matches("nullable_unsafe_cast", "val processed = (value as String).trim()")
    assert _matches("nullable_unsafe_cast", "val n = (obj as Int).toLong()")


def test_nullable_unsafe_cast_not_triggered_on_safe():
    """(value as? String) 安全转换不应触发。"""
    assert not _matches("nullable_unsafe_cast", "val processed = (value as? String)?.trim()")


def test_all_rules_have_required_fields():
    """所有规则必须包含 pattern / severity / message 三个字段。"""
    for name, rule in RULES.items():
        assert "pattern" in rule, f"规则 {name} 缺少 pattern"
        assert "severity" in rule, f"规则 {name} 缺少 severity"
        assert "message" in rule, f"规则 {name} 缺少 message"
        assert rule["severity"] in ("error", "warning"), f"规则 {name} 的 severity 必须是 error 或 warning"


def test_error_rules_block_merge():
    """error 级别的规则必须能阻止合并。"""
    error_rules = [n for n, r in RULES.items() if r["severity"] == "error"]
    assert "empty_catch" in error_rules, "empty_catch 必须是 error 级别"


def run_all_tests():
    """运行所有测试并报告结果。"""
    tests = [
        test_unsafe_cast_string,
        test_unsafe_cast_not_triggered_on_safe_cast,
        test_non_null_assertion,
        test_non_null_assertion_with_comment_allowed,
        test_empty_catch,
        test_non_empty_catch_not_triggered,
        test_null_fallback,
        test_null_fallback_with_comment_allowed,
        test_default_fallback,
        test_any_type_declaration,
        test_any_type_declaration_not_triggered_on_generic,
        test_any_return_type,
        test_any_return_type_not_triggered_on_generic,
        test_when_else_null,
        test_when_else_null_with_comment_allowed,
        test_when_else_other_value_not_triggered,
        test_nullable_unsafe_cast,
        test_nullable_unsafe_cast_not_triggered_on_safe,
        test_all_rules_have_required_fields,
        test_error_rules_block_merge,
    ]

    passed = 0
    failed = 0
    for test in tests:
        try:
            test()
            print(f"  [PASS] {test.__name__}")
            passed += 1
        except AssertionError as e:
            print(f"  [FAIL] {test.__name__}: {e}")
            failed += 1
        except Exception as e:
            print(f"  [ERROR] {test.__name__}: {e}")
            failed += 1

    print(f"\n{'='*50}")
    print(f"Total: {passed + failed}, Passed: {passed}, Failed: {failed}")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(run_all_tests())
