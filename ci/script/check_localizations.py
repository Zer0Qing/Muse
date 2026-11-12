#!/usr/bin/env python3
"""
check_localizations.py — Android 字符串资源完整性校验。

对比 values/(基准)与各 values-<locale>/ 目录下 XML 字符串资源的 key 差异,
报告每个 locale 缺失的 key。CI 中用于强制保证翻译完整性。

用法:
    python ci/script/check_localizations.py [--res-dir app/src/main/res] [--strict-locales en]

退出码:
    0 — strict locales(默认 en)无缺失 key 且所有 locale 资源类型匹配
    1 — 存在缺失 key 或资源类型不匹配(CI 失败)

说明:
    v1.0.56: 新增 --strict-locales — 只有列出的 locale 缺失 key 才算失败;
    其他语言(es/ja/ko/pt/ru 等)缺失仅打印 WARN,不阻塞 CI。
    多语言翻译是增量工作,不应阻塞主功能发布;强制主语言(en)完整性即可。
"""

import argparse
import os
import re
import sys
from pathlib import Path

# Windows 中文控制台默认 GBK,而本脚本会打印 ✓/✗(不在 GBK 字符集内),
# 直接输出会抛 UnicodeEncodeError 并让 lanes lane 失败。统一切到 UTF-8。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # noqa: BLE001 - 环境不支持时不应影响检查本身
    pass

# 提取 <string name="key"> 的正则
STRING_NAME_RE = re.compile(r'<string\s+name="([^"]+)"', re.IGNORECASE)

# 声明为 translatable="false" 的 key 是故意不翻译的(语言自称、专有名词),
# 不该算进各 locale 的完整性缺口;开标签正则用于把属性和 name 一起认出来。
STRING_TAG_OPEN_RE = re.compile(r"<string\s[^>]*>", re.IGNORECASE)
RESOURCE_TAG_OPEN_RE = re.compile(r"<(string-array|plurals|string)\b([^>]*)>", re.IGNORECASE)
XML_COMMENT_RE = re.compile(r"<!--.*?-->", re.DOTALL)
NAME_ATTR_RE = re.compile(r'name="([^"]+)"')
NOT_TRANSLATABLE_RE = re.compile(r'translatable\s*=\s*"false"', re.IGNORECASE)

# 需要扫描的文件模式
STRING_FILE_PATTERN = "*.xml"
# 默认 values 目录名(基准)
DEFAULT_VALUES_DIR = "values"
# 支持的 locale 目录前缀
LOCALE_DIR_PREFIX = "values-"


def extract_keys_from_file(file_path: Path) -> set:
    """从单个 XML 字符串资源文件中提取所有 string key。"""
    if not file_path.exists():
        return set()
    try:
        content = file_path.read_text(encoding="utf-8")
    except Exception as e:
        print(f"  WARNING: 读取 {file_path} 失败: {e}", file=sys.stderr)
        return set()
    return set(STRING_NAME_RE.findall(content))


def extract_untranslatable_keys_from_file(file_path: Path) -> set:
    """本文件里 translatable="false" 的 string key 集合。"""
    if not file_path.exists():
        return set()
    try:
        content = file_path.read_text(encoding="utf-8")
    except Exception as e:
        print(f"  WARNING: 读取 {file_path} 失败: {e}", file=sys.stderr)
        return set()
    keys = set()
    for tag in STRING_TAG_OPEN_RE.findall(content):
        if NOT_TRANSLATABLE_RE.search(tag):
            name = NAME_ATTR_RE.search(tag)
            if name:
                keys.add(name.group(1))
    return keys


def extract_all_keys(values_dir: Path) -> dict:
    """
    从 values 目录中提取所有 XML 文件的字符串 key。

    返回: {filename: set(keys)} 如 {"strings_ui_main.xml": {"key1", "key2"}}
    """
    result = {}
    if not values_dir.exists():
        return result
    for xml_file in sorted(values_dir.glob(STRING_FILE_PATTERN)):
        keys = extract_keys_from_file(xml_file)
        result[xml_file.name] = keys
    return result


def extract_resource_kinds(values_dir: Path) -> dict:
    """Collect named text resource kinds across files, ignoring XML comments."""
    result = {}
    for xml_file in sorted(values_dir.glob(STRING_FILE_PATTERN)):
        content = XML_COMMENT_RE.sub("", xml_file.read_text(encoding="utf-8"))
        for kind, attributes in RESOURCE_TAG_OPEN_RE.findall(content):
            name = NAME_ATTR_RE.search(attributes)
            if name:
                result.setdefault(name.group(1), set()).add(kind.lower())
    return result


def find_locale_dirs(res_dir: Path) -> list:
    """找到所有 values-* 目录(排除 values-night 等非语言目录)。"""
    locales = []
    if not res_dir.exists():
        return locales
    for d in sorted(res_dir.iterdir()):
        if not d.is_dir():
            continue
        name = d.name
        if not name.startswith(LOCALE_DIR_PREFIX):
            continue
        # 排除非语言目录(values-night, values-v31 等)
        suffix = name[len(LOCALE_DIR_PREFIX):]
        if suffix.startswith("v") or suffix in ("night", "land", "port", "desk", "car", "television", "appliance", "watch", "small", "normal", "large", "xlarge", "hdpiv", "wdpiv"):
            continue
        locales.append(d)
    return locales


def normalize_locale_name(name: str) -> str:
    """Accept either Android resource directory names or plain locale codes."""
    return name[len(LOCALE_DIR_PREFIX):] if name.startswith(LOCALE_DIR_PREFIX) else name


def check_locale(res_dir: Path, verbose: bool = False, strict_locales: set = None) -> int:
    """
    校验所有 locale 的 key 完整性。

    返回: 0 表示严格语言完整且所有语言资源类型匹配,否则返回 1
    """
    default_dir = res_dir / DEFAULT_VALUES_DIR
    baseline = extract_all_keys(default_dir)
    baseline_kinds = extract_resource_kinds(default_dir)
    # 自称/专有名词类 key 只存在于 values/,各 locale 不复制它们,所以不参与缺失比对。
    baseline_untranslatable = set()
    for xml_file in sorted(default_dir.glob(STRING_FILE_PATTERN)):
        baseline_untranslatable |= extract_untranslatable_keys_from_file(xml_file)
    strict_locale_codes = {
        normalize_locale_name(locale) for locale in (strict_locales or set())
    }

    if not baseline:
        print(f"ERROR: 基准目录 {default_dir} 无 XML 字符串资源文件", file=sys.stderr)
        return 1

    total_baseline_keys = sum(len(keys) for keys in baseline.values())
    print(f"基准目录: {default_dir}")
    print(f"基准文件数: {len(baseline)}, 基准 key 总数: {total_baseline_keys}")
    print()

    locale_dirs = find_locale_dirs(res_dir)
    if not locale_dirs:
        print("WARNING: 未找到任何 values-* locale 目录", file=sys.stderr)
        return 0

    total_missing = 0
    total_type_mismatches = 0

    for locale_dir in locale_dirs:
        locale_name = locale_dir.name
        locale_keys = extract_all_keys(locale_dir)
        locale_kinds = extract_resource_kinds(locale_dir)
        type_mismatches = {
            key: (baseline_kinds[key], kinds)
            for key, kinds in locale_kinds.items()
            if key in baseline_kinds and kinds != baseline_kinds[key]
        }
        total_type_mismatches += len(type_mismatches)

        locale_total = sum(len(keys) for keys in locale_keys.values())
        # v1.0.56: 改为跨文件 key 集合比对 — key 分布在不同 strings_*.xml 是合法的
        #   (如 onboarding_* 中文在 strings_features.xml、英文在 strings.xml),
        #   资源合并只看 key 唯一性;按文件比对会误报缺失并误导补翻译。
        baseline_all = set().union(*baseline.values())
        locale_all = set().union(*locale_keys.values())
        missing_keys = (baseline_all - baseline_untranslatable) - locale_all
        extra_keys = locale_all - baseline_all if verbose else set()

        missing_count = len(missing_keys)
        # v1.0.56: strict locale 缺失才累计失败;其他语言缺失仅警告
        is_strict = normalize_locale_name(locale_name) in strict_locale_codes
        if is_strict:
            total_missing += missing_count

        if missing_count == 0:
            status = "OK"
        else:
            status = f"MISSING {missing_count} keys" + (" (FAIL)" if is_strict else " (WARN)")
        if type_mismatches:
            status += f"; TYPE MISMATCH {len(type_mismatches)} (FAIL)"
        print(f"  {locale_name}: {locale_total} keys [{status}]")
        for key, (expected, actual) in sorted(type_mismatches.items()):
            print(
                f"    TYPE MISMATCH: {key}: expected {', '.join(sorted(expected))}; "
                f"found {', '.join(sorted(actual))}"
            )

        if missing_keys:
            for key in sorted(missing_keys):
                print(f"    MISSING: {key}")

        if verbose and extra_keys:
            for key in sorted(extra_keys):
                print(f"    EXTRA:   {key}")

    print()
    if total_type_mismatches:
        print(f"✗ Resource kind mismatches: {total_type_mismatches}", file=sys.stderr)
        if total_missing == 0:
            return 1
    if total_missing == 0:
        print(f"✓ strict locales({', '.join(sorted(strict_locales))}) 翻译完整(0 缺失)")
        return 0
    else:
        print(f"✗ 翻译不完整: {total_missing} 个 key 缺失(strict locales: {', '.join(sorted(strict_locales))})", file=sys.stderr)
        return 1


def main():
    parser = argparse.ArgumentParser(description="Android 字符串资源完整性校验")
    parser.add_argument(
        "--res-dir",
        default="app/src/main/res",
        help="Android res 目录路径(默认: app/src/main/res)",
    )
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="显示额外信息(包括 locale 中多余的 key)",
    )
    parser.add_argument(
        "--strict-locales",
        default="en",
        help="缺失 key 会导致失败的 locale(逗号分隔,默认: en);其他语言缺失仅警告",
    )
    args = parser.parse_args()
    strict_locales = {
        normalize_locale_name(locale.strip())
        for locale in args.strict_locales.split(",")
        if locale.strip()
    }

    res_dir = Path(args.res_dir).resolve()
    if not res_dir.exists():
        print(f"ERROR: res 目录不存在: {res_dir}", file=sys.stderr)
        sys.exit(2)

    exit_code = check_locale(res_dir, verbose=args.verbose, strict_locales=strict_locales)
    sys.exit(exit_code)


if __name__ == "__main__":
    main()
