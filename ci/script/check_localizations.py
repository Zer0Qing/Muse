#!/usr/bin/env python3
"""
check_localizations.py — Android 字符串资源完整性校验。

对比 values/(基准)与各 values-<locale>/ 目录下 strings_*.xml 的 key 差异,
报告每个 locale 缺失的 key。CI 中用于强制保证翻译完整性。

用法:
    python ci/script/check_localizations.py [--res-dir app/src/main/res] [--strict-locales en]

退出码:
    0 — strict locales(默认 en)无缺失 key
    1 — 存在缺失 key(CI 失败)

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

# 提取 <string name="key"> 的正则
STRING_NAME_RE = re.compile(r'<string\s+name="([^"]+)"', re.IGNORECASE)

# 需要扫描的文件模式
STRING_FILE_PATTERN = "strings_*.xml"
# 默认 values 目录名(基准)
DEFAULT_VALUES_DIR = "values"
# 支持的 locale 目录前缀
LOCALE_DIR_PREFIX = "values-"


def extract_keys_from_file(file_path: Path) -> set:
    """从单个 strings_*.xml 文件中提取所有 string key。"""
    if not file_path.exists():
        return set()
    try:
        content = file_path.read_text(encoding="utf-8")
    except Exception as e:
        print(f"  WARNING: 读取 {file_path} 失败: {e}", file=sys.stderr)
        return set()
    return set(STRING_NAME_RE.findall(content))


def extract_all_keys(values_dir: Path) -> dict:
    """
    从 values 目录中提取所有 strings_*.xml 的 key。

    返回: {filename: set(keys)} 如 {"strings_ui_main.xml": {"key1", "key2"}}
    """
    result = {}
    if not values_dir.exists():
        return result
    for xml_file in sorted(values_dir.glob(STRING_FILE_PATTERN)):
        keys = extract_keys_from_file(xml_file)
        result[xml_file.name] = keys
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


def check_locale(res_dir: Path, verbose: bool = False, strict_locales: set = None) -> int:
    """
    校验所有 locale 的 key 完整性。

    返回: 缺失 key 的总数(0 表示全部完整)
    """
    default_dir = res_dir / DEFAULT_VALUES_DIR
    baseline = extract_all_keys(default_dir)

    if not baseline:
        print(f"ERROR: 基准目录 {default_dir} 无 strings_*.xml 文件", file=sys.stderr)
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

    for locale_dir in locale_dirs:
        locale_name = locale_dir.name
        locale_keys = extract_all_keys(locale_dir)

        locale_total = sum(len(keys) for keys in locale_keys.values())
        missing_by_file = {}
        extra_by_file = {}

        for filename, base_keys in baseline.items():
            loc_keys = locale_keys.get(filename, set())
            missing = base_keys - loc_keys
            extra = loc_keys - base_keys
            if missing:
                missing_by_file[filename] = missing
            if extra and verbose:
                extra_by_file[filename] = extra

        missing_count = sum(len(m) for m in missing_by_file.values())
        # v1.0.56: strict locale 缺失才累计失败;其他语言缺失仅警告
        is_strict = strict_locales and locale_name in strict_locales
        if is_strict:
            total_missing += missing_count

        if missing_count == 0:
            status = "OK"
        else:
            status = f"MISSING {missing_count} keys" + (" (FAIL)" if is_strict else " (WARN)")
        print(f"  {locale_name}: {locale_total} keys [{status}]")

        if missing_by_file:
            for filename, missing in sorted(missing_by_file.items()):
                for key in sorted(missing):
                    print(f"    MISSING: {filename} → {key}")

        if verbose and extra_by_file:
            for filename, extra in sorted(extra_by_file.items()):
                for key in sorted(extra):
                    print(f"    EXTRA:   {filename} → {key}")

    print()
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
    strict_locales = set(locale.strip() for locale in args.strict_locales.split(",") if locale.strip())

    res_dir = Path(args.res_dir).resolve()
    if not res_dir.exists():
        print(f"ERROR: res 目录不存在: {res_dir}", file=sys.stderr)
        sys.exit(2)

    exit_code = check_locale(res_dir, verbose=args.verbose, strict_locales=strict_locales)
    sys.exit(exit_code)


if __name__ == "__main__":
    main()
