#!/usr/bin/env python3
"""
dedup_strings.py — 检测并移除 strings_*.xml 文件间的重复 key。

问题: strings.xml 和 strings_features.xml 可能定义了相同的 key,
导致 Android 编译报 "Duplicate resources" 错误。

策略:
  1. strings.xml 优先级最高(主文件)
  2. 其余 strings_*.xml 按文件名字母序,先出现者优先
  3. 后续文件中的重复 key 被移除

用法: py -3 ci/script/dedup_strings.py [--res-dir app/src/main/res] [--dry-run]
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

STRING_TAG_PATTERN = re.compile(r'<string\s+name="([^"]+)"\s*(?:[^>]*?)>(.*?)</string>', re.DOTALL)


def extract_string_names(xml_content: str) -> list[str]:
    """提取 XML 中所有 string name"""
    return [m.group(1) for m in STRING_TAG_PATTERN.finditer(xml_content)]


def remove_string_tag(xml_content: str, name: str) -> tuple[str, bool]:
    """从 XML 中移除指定 name 的 <string> 标签(整行移除)"""
    # 匹配整行(包括前导空白和换行符)
    pattern = re.compile(
        r'\s*<string\s+name="' + re.escape(name) + r'"\s*(?:[^>]*?)>.*?</string>\s*\n?',
        re.DOTALL,
    )
    new_content, count = pattern.subn('', xml_content)
    return new_content, count > 0


def dedup_locale(locale_dir: Path, dry_run: bool = False) -> int:
    """
    对单个 locale 目录去重。
    返回移除的重复 key 总数。
    """
    # 文件优先级: strings.xml 最高,其余按文件名排序
    files = sorted(locale_dir.glob("strings_*.xml"))
    main_strings = locale_dir / "strings.xml"
    if main_strings.exists():
        files.insert(0, main_strings)

    if not files:
        return 0

    seen_keys: set[str] = set()
    total_removed = 0

    for file_path in files:
        content = file_path.read_text(encoding="utf-8")
        names = extract_string_names(content)

        duplicates = [n for n in names if n in seen_keys]
        if not duplicates:
            seen_keys.update(names)
            continue

        # 移除重复 key
        new_content = content
        for dup_name in duplicates:
            new_content, removed = remove_string_tag(new_content, dup_name)
            if removed:
                total_removed += 1

        if not dry_run:
            file_path.write_text(new_content, encoding="utf-8")

        status = "[DRY]" if dry_run else "[FIXED]"
        print(f"  {status} {file_path.name}: removed {len(duplicates)} duplicates: {', '.join(duplicates[:5])}{'...' if len(duplicates) > 5 else ''}")

        # 更新已见 key 集合(只添加非重复的)
        seen_keys.update(n for n in names if n not in duplicates)

    return total_removed


def main():
    parser = argparse.ArgumentParser(description="Android strings_*.xml 重复 key 去重")
    parser.add_argument("--res-dir", default="app/src/main/res", help="Android res 目录路径")
    parser.add_argument("--dry-run", action="store_true", help="仅检测不修改")
    args = parser.parse_args()

    res_dir = Path(args.res_dir)
    if not res_dir.exists():
        print(f"ERROR: res directory not found: {res_dir}")
        return 1

    # 查找所有 locale 目录
    locale_dirs = sorted([d for d in res_dir.iterdir() if d.is_dir() and d.name.startswith("values")])

    print(f"Found {len(locale_dirs)} locale directories")
    print(f"Mode: {'DRY RUN' if args.dry_run else 'FIX'}")
    print()

    grand_total = 0
    for locale_dir in locale_dirs:
        removed = dedup_locale(locale_dir, dry_run=args.dry_run)
        if removed > 0:
            print(f"  {locale_dir.name}: {removed} duplicates removed")
        grand_total += removed

    print()
    print(f"Total duplicates {'detected' if args.dry_run else 'removed'}: {grand_total}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
