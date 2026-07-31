"""
sync_missing_keys.py — 同步 locale 缺失 key。

从基准 values/ 目录读取所有 strings_*.xml 的 key,
对每个 values-<locale>/ 目录,补充缺失的 key(用基准中文值作为占位符)。

用法:
    py -3 ci/script/sync_missing_keys.py [--res-dir app/src/main/res]
"""

import re
import sys
from pathlib import Path

# 只提取 name 属性(与 check_localizations.py 一致)
NAME_RE = re.compile(r'<string\s+name="([^"]+)"', re.IGNORECASE)
# 提取完整的 <string ...>...</string> 元素(支持多行内容)
FULL_STRING_RE = re.compile(r'<string\s+name="([^"]+)"[^>]*>.*?</string>', re.IGNORECASE | re.DOTALL)
LOCALE_DIR_PREFIX = "values-"
NON_LOCALE_SUFFIXES = ("v31", "night", "land", "port", "desk", "car", "television", "appliance", "watch")


def extract_names(file_path: Path) -> set:
    """从文件提取所有 string name(只返回 key 集合)。"""
    if not file_path.exists():
        return set()
    content = file_path.read_text(encoding="utf-8")
    return set(NAME_RE.findall(content))


def extract_full_elements(file_path: Path) -> dict:
    """从文件提取 {key: full_xml_element} 映射(支持多行)。"""
    if not file_path.exists():
        return {}
    content = file_path.read_text(encoding="utf-8")
    result = {}
    for match in FULL_STRING_RE.finditer(content):
        key = match.group(1)
        result[key] = match.group(0)
    return result


def sync_locale(locale_dir: Path, baseline_by_file: dict) -> int:
    """同步单个 locale 目录,返回新增 key 数量。"""
    added = 0
    for filename, baseline_strings in baseline_by_file.items():
        locale_file = locale_dir / filename
        locale_names = extract_names(locale_file)

        missing_keys = set(baseline_strings.keys()) - locale_names
        if not missing_keys:
            continue

        if locale_file.exists():
            content = locale_file.read_text(encoding="utf-8")
        else:
            content = '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n'

        # 在 </resources> 前插入缺失的 key
        insert_lines = []
        for key in sorted(missing_keys):
            if key in baseline_strings:
                insert_lines.append(f"    {baseline_strings[key]}")
                added += 1

        if not insert_lines:
            continue

        if "</resources>" in content:
            new_content = content.replace(
                "</resources>",
                "\n".join(insert_lines) + "\n</resources>"
            )
        else:
            new_content = content + "\n" + "\n".join(insert_lines) + "\n"

        locale_file.write_text(new_content, encoding="utf-8")

    return added


def main():
    res_dir = Path("app/src/main/res").resolve()
    if not res_dir.exists():
        print(f"ERROR: res 目录不存在: {res_dir}", file=sys.stderr)
        sys.exit(2)

    # 1. 读取基准 values/ 的所有 strings(完整元素)
    default_dir = res_dir / "values"
    baseline_by_file = {}
    for xml_file in sorted(default_dir.glob("strings_*.xml")):
        baseline_by_file[xml_file.name] = extract_full_elements(xml_file)

    total_baseline = sum(len(v) for v in baseline_by_file.values())
    print(f"基准目录: {default_dir}, 文件数: {len(baseline_by_file)}, key 总数: {total_baseline}")

    # 2. 找到所有 locale 目录
    locale_dirs = []
    for d in sorted(res_dir.iterdir()):
        if not d.is_dir() or not d.name.startswith(LOCALE_DIR_PREFIX):
            continue
        suffix = d.name[len(LOCALE_DIR_PREFIX):]
        if suffix.startswith("v") or suffix in NON_LOCALE_SUFFIXES:
            continue
        locale_dirs.append(d)

    # 3. 同步每个 locale
    for locale_dir in locale_dirs:
        added = sync_locale(locale_dir, baseline_by_file)
        print(f"  {locale_dir.name}: 新增 {added} 个缺失 key")

    print("\nDone.")


if __name__ == "__main__":
    main()
