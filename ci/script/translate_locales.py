#!/usr/bin/env python3
"""
translate_locales.py — 使用 Google Translate 免费 API 逐条翻译 strings_*.xml 文件。

修复版: 逐条翻译(不批量) + 多线程并发, 避免批量翻译结果错位问题。
占位符保护: 使用 HTML 注释标记, 翻译后正则还原。

工作流程:
  1. 读取 values-en/ 下所有 strings_*.xml 作为英文基准
  2. 对每个目标 locale (es/ja/ko/ru/pt-rBR):
     a. 读取该 locale 已有翻译(保留人工翻译过的字符串)
     b. 对未翻译(仍是英文或中文占位符)的字符串逐条调用 Google Translate
     c. 写回翻译后的文件
  3. 保留 XML 结构、注释、格式占位符(%1$s 等)
"""

from __future__ import annotations

import json
import re
import sys
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

# 项目根目录
PROJECT_ROOT = Path(__file__).resolve().parents[2]
RES_DIR = PROJECT_ROOT / "app" / "src" / "main" / "res"
EN_DIR = RES_DIR / "values-en"

# 目标语言映射: Android locale → Google Translate language code
TARGET_LOCALES = {
    "values-es": "es",
    "values-ja": "ja",
    "values-ko": "ko",
    "values-ru": "ru",
    "values-pt-rBR": "pt",
}

# 并发线程数
MAX_WORKERS = 15
# 请求间隔(秒),每个线程独立控制
REQUEST_DELAY = 0.2
# 重试次数
MAX_RETRIES = 3

# 中文字符检测正则
CJK_PATTERN = re.compile(r"[\u4e00-\u9fff]")
# 字符串标签正则(匹配 <string name="...">...</string>)
STRING_TAG_PATTERN = re.compile(r'<string\s+name="([^"]+)"\s*(?:[^>]*?)>(.*?)</string>', re.DOTALL)
# 格式占位符正则(%1$s, %1$d, \n, \", XML 实体等)
# 使用 HTML 注释标记保护,避免翻译引擎破坏
PLACEHOLDER_PATTERN = re.compile(r"%\d+\$[sd]|\\n|&amp;|&lt;|&gt;|&quot;|&apos;")


def log(msg: str):
    """带 flush 的日志输出"""
    print(msg, flush=True)


def has_chinese(text: str) -> bool:
    """检测字符串是否包含中文字符"""
    return bool(CJK_PATTERN.search(text))


def google_translate_single(text: str, target_lang: str, source_lang: str = "en") -> str | None:
    """
    调用 Google Translate 免费 API 翻译单条文本。
    逐条翻译,避免批量翻译的分割错位问题。
    """
    url = (
        "https://translate.googleapis.com/translate_a/single"
        f"?client=gtx&sl={source_lang}&tl={target_lang}&dt=t&q={urllib.parse.quote(text)}"
    )

    for attempt in range(MAX_RETRIES):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(req, timeout=15) as resp:
                data = json.loads(resp.read().decode("utf-8"))
            # data[0] 是翻译结果数组,每个元素 [translated_text, original_text, ...]
            # 逐条翻译时 data[0] 只有一个元素
            if data and data[0] and data[0][0] and data[0][0][0]:
                return data[0][0][0]
            return None
        except Exception as e:
            if attempt < MAX_RETRIES - 1:
                wait = (attempt + 1) * 2
                time.sleep(wait)
            else:
                return None
    return None


def protect_placeholders(text: str) -> tuple[str, list[tuple[str, str]]]:
    """
    保护格式占位符(%1$s 等),替换为 HTML 注释标记,翻译后还原。
    HTML 注释不会被翻译引擎修改。
    """
    placeholders: list[tuple[str, str]] = []
    counter = [0]

    def replace(match):
        token = f"<!--PH{counter[0]:04d}-->"
        placeholders.append((token, match.group(0)))
        counter[0] += 1
        return token

    protected = PLACEHOLDER_PATTERN.sub(replace, text)
    return protected, placeholders


def restore_placeholders(text: str, placeholders: list[tuple[str, str]]) -> str:
    """还原被保护的占位符"""
    for token, original in placeholders:
        text = text.replace(token, original)
        # 兜底: 翻译引擎可能破坏注释格式(如添加空格、小写)
        # 从 token 提取编号,用正则匹配变体
        m = re.search(r"PH(\d{4})", token)
        if m:
            num = m.group(1)
            pattern = re.compile(
                r"<!--\s*[Pp][Hh]\s*" + num + r"\s*-->",
                re.IGNORECASE,
            )
            text = pattern.sub(original, text)
    return text


def extract_strings(xml_content: str) -> list[tuple[str, str, str]]:
    """
    从 XML 内容中提取所有 <string> 标签。
    返回 [(name, value, full_match), ...]
    """
    results = []
    for match in STRING_TAG_PATTERN.finditer(xml_content):
        name = match.group(1)
        value = match.group(2).strip()
        results.append((name, value, match.group(0)))
    return results


def translate_one(name: str, en_value: str, target_lang: str) -> tuple[str, str]:
    """
    翻译单条字符串。返回 (name, translated_value)。
    失败时返回 (name, en_value) 保留英文。
    """
    # 保护占位符
    protected, placeholders = protect_placeholders(en_value)

    # 翻译
    translated = google_translate_single(protected, target_lang)
    if translated is None:
        return (name, en_value)

    # 还原占位符
    restored = restore_placeholders(translated, placeholders)

    # 清理: 去除首尾空白
    restored = restored.strip()

    # 如果翻译结果为空,保留英文
    if not restored:
        return (name, en_value)

    return (name, restored)


def translate_xml_file(
    en_content: str,
    existing_content: str | None,
    target_lang: str,
    file_name: str,
) -> str | None:
    """
    翻译单个 strings_*.xml 文件。
    """
    en_strings = extract_strings(en_content)
    if not en_strings:
        return None

    # 解析已有翻译
    existing_map: dict[str, str] = {}
    if existing_content:
        for name, value, _ in extract_strings(existing_content):
            existing_map[name] = value

    # 分类: 需要翻译 vs 保留
    to_translate: list[tuple[str, str]] = []  # (name, english_value)
    final_values: dict[str, str] = {}

    for name, en_value, _ in en_strings:
        existing = existing_map.get(name)

        if existing is not None:
            if existing == en_value:
                # 值与英文相同 → 需要翻译
                to_translate.append((name, en_value))
            elif has_chinese(existing):
                # 中文占位符 → 需要翻译
                to_translate.append((name, en_value))
            else:
                # 已有真实翻译 → 保留
                final_values[name] = existing
        else:
            # 没有已有翻译 → 需要翻译
            to_translate.append((name, en_value))

    if not to_translate:
        log(f"  [{file_name}] all strings already translated, skipping")
        return existing_content

    log(f"  [{file_name}] translating {len(to_translate)} strings to {target_lang}...")

    # 多线程并发翻译
    with ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
        futures = {
            executor.submit(translate_one, name, en_val, target_lang): name
            for name, en_val in to_translate
        }
        done_count = 0
        for future in as_completed(futures):
            name, translated = future.result()
            final_values[name] = translated
            done_count += 1
            if done_count % 50 == 0:
                log(f"    [{file_name}] {done_count}/{len(to_translate)} done")

    # 重建 XML: 以英文基准为模板,替换值
    result_content = en_content
    for name, en_value, full_match in en_strings:
        translated = final_values.get(name, en_value)
        # XML 转义(保留 & < > 的转义)
        # 注意: 翻译结果可能已经包含 XML 实体(如 &amp;),需要避免双重转义
        # 策略: 先反转义英文基准中的实体,再根据翻译结果重新转义
        # 但这里简化处理: 如果翻译结果包含 & 开头的实体,不再转义
        if "&" in translated and not translated.startswith("&amp;"):
            # 检查是否已有 XML 实体
            has_entity = bool(re.search(r"&\w+;", translated))
            if not has_entity:
                translated = translated.replace("&", "&amp;")
        translated = translated.replace("<", "&lt;").replace(">", "&gt;")
        new_tag = f'<string name="{name}">{translated}</string>'
        result_content = result_content.replace(full_match, new_tag, 1)

    return result_content


def main():
    # 启用行缓冲
    try:
        sys.stdout.reconfigure(line_buffering=True)
        sys.stderr.reconfigure(line_buffering=True)
    except Exception:
        pass

    log("=" * 60)
    log("Muse Locale Translator (v2 - per-item translation)")
    log("=" * 60)

    if not EN_DIR.exists():
        log(f"ERROR: English baseline directory not found: {EN_DIR}")
        return 1

    # 收集所有 strings_*.xml 文件
    en_files = sorted(EN_DIR.glob("strings_*.xml"))
    main_strings = EN_DIR / "strings.xml"
    if main_strings.exists():
        en_files.append(main_strings)

    log(f"Found {len(en_files)} English baseline files")
    log(f"Target locales: {', '.join(TARGET_LOCALES.keys())}")
    log(f"Concurrency: {MAX_WORKERS} threads")

    total_translated = 0
    total_skipped = 0
    total_failed = 0

    for locale_dir_name, gtx_lang in TARGET_LOCALES.items():
        locale_dir = RES_DIR / locale_dir_name
        log(f"\n{'─' * 40}")
        log(f"Processing locale: {locale_dir_name} (gtx: {gtx_lang})")
        log(f"{'─' * 40}")

        locale_dir.mkdir(parents=True, exist_ok=True)

        for en_file in en_files:
            file_name = en_file.name
            target_file = locale_dir / file_name

            en_content = en_file.read_text(encoding="utf-8")
            existing_content = None
            if target_file.exists():
                existing_content = target_file.read_text(encoding="utf-8")

            try:
                result = translate_xml_file(en_content, existing_content, gtx_lang, file_name)
                if result is not None:
                    target_file.write_text(result, encoding="utf-8")
                    total_translated += 1
                    log(f"  ✓ {file_name}")
                else:
                    total_skipped += 1
                    log(f"  - {file_name} (skipped)")
            except Exception as e:
                total_failed += 1
                log(f"  ✗ {file_name} (ERROR: {e})")

    log(f"\n{'=' * 60}")
    log(f"Translation complete:")
    log(f"  Translated: {total_translated}")
    log(f"  Skipped:    {total_skipped}")
    log(f"  Failed:     {total_failed}")
    log(f"{'=' * 60}")

    return 0 if total_failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
