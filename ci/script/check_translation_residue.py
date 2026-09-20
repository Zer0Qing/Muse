#!/usr/bin/env python3
"""
check_translation_residue.py — I18N-02 非中文语言包 CJK 残留校验。

用 XML 解析（而不是「<string name="x"> 必须紧邻」的正则）扫描各
values-<locale>/ 语言包里值含 CJK 的字符串条目，统计条数并与基线
ci/translation_residue_baseline.json 比较：高于基线即失败（只降不升）。
带 formatted/tools 等属性的 <string> 不会再漏报（旧 PowerShell 脚本
scripts/i18n_cjk_guard.ps1 的缺陷，该脚本已随本次整改废弃删除）。

口径（写死在脚本里，避免第二个脚本各说各话）:
  - 扫描对象: 路径形如 values-<语言>[-r<地区>] 的目录（en/es/ko/pt-rBR/ru…）
    下所有 .xml；条目 = <string>，以及 <plurals>/<string-array> 里的 <item>
    （用户可见的字符串值一个都不漏）。
  - 排除 values（默认包 = 中文源，本来就该是中文）。
  - 排除 values-ja: 日文汉字合法落在 CJK 统一表意文字区间，字符区间无法与简体
    中文区分，故不做正则拦截（ja 的残留由翻译评审兜底；当前 ja 仍有一条
    settings_agent_send_probability_hint 是中文，见整改报告）。
  - 排除 zh* 语言包与纯限定符目录（values-v31 等）。
  - 只统计「值」: 元素的文本内容（XML 解析天然覆盖任意属性写法）；
    XML 注释不是元素，天然不计。
  - translatable="false" 的条目不计（本就不参与翻译，允许保留原文）。
  - CJK 判定区间: [\\u4e00-\\u9fff\\u3400-\\u4dbf]（与 check_hardcoded_cjk.py 同域）。
  - 每个条目只计 1 次（按条目数，不按字符数）。

基线机制:
  - 默认对比 ci/translation_residue_baseline.json（各语言包条数，当前全 0）。
  - 高于基线、或基线外的新语言包出现残留 → 失败。
  - 基线文件缺失 = 失败(fail closed),避免删掉基线绕过门禁。
  - --update-baseline 重写基线；默认拒绝写入「上升」的条目，需 --allow-increase。
  - 补完翻译后把基线保持 0（而不是调高），门禁才是真的。

用法:
  py -3 ci/script/check_translation_residue.py
  py -3 ci/script/check_translation_residue.py --baseline <path>
  py -3 ci/script/check_translation_residue.py --update-baseline
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]
RES_REL = Path("app") / "src" / "main" / "res"
BASELINE_REL = Path("ci") / "translation_residue_baseline.json"

LOCALE_DIR_RE = re.compile(r"^values-([a-z]{2,3}(?:-r[A-Z]{2})?)$")
CJK_RE = re.compile(r"[\u4e00-\u9fff\u3400-\u4dbf]")
EXCLUDED_LOCALES = frozenset({"ja"})
EXCLUDED_LOCALE_PREFIXES = ("zh",)
STRING_CONTAINERS = ("plurals", "string-array")


@dataclass(frozen=True)
class Residue:
    """一条 CJK 残留。file 为 res/ 下相对 posix 路径。"""

    locale: str
    file: str
    name: str
    value: str


def locale_of(dir_name: str) -> str | None:
    """values-en → en;values-pt-rBR → pt-rBR;不扫描的目录返回 None。"""
    m = LOCALE_DIR_RE.match(dir_name)
    if not m:
        return None
    locale = m.group(1)
    if locale in EXCLUDED_LOCALES or locale.startswith(EXCLUDED_LOCALE_PREFIXES):
        return None
    return locale


def iter_string_entries(root: ET.Element):
    """产出 (name, text):<string> 及其容器内 <item>,跳过 translatable="false"。"""
    for el in root.iter():
        if el.tag == "string":
            if el.get("translatable") == "false":
                continue
            yield (el.get("name") or "?"), "".join(el.itertext())
        elif el.tag in STRING_CONTAINERS:
            if el.get("translatable") == "false":
                continue
            for item in el.findall("item"):
                quantity = item.get("quantity")
                base = el.get("name") or "?"
                yield (f"{base}[{quantity}]" if quantity else base), "".join(item.itertext())


def scan_res(root: Path) -> dict[str, list[Residue]]:
    """扫描所有受检语言包,返回 locale → 残留列表(含 0 条的语言包)。"""
    res_dir = root / RES_REL
    if not res_dir.is_dir():
        raise FileNotFoundError(f"资源目录不存在: {res_dir}")
    found: dict[str, list[Residue]] = {}
    for d in sorted(res_dir.glob("values-*")):
        if not d.is_dir():
            continue
        locale = locale_of(d.name)
        if locale is None:
            continue
        hits: list[Residue] = []
        for xml in sorted(d.glob("*.xml")):
            try:
                tree = ET.parse(xml)
            except ET.ParseError as e:
                # 解析失败必须失败退出:静默跳过会让门禁假绿
                raise ValueError(f"{xml} 解析失败: {e}") from e
            for name, text in iter_string_entries(tree.getroot()):
                if CJK_RE.search(text):
                    hits.append(
                        Residue(
                            locale=locale,
                            file=f"{d.name}/{xml.name}",
                            name=name,
                            value=text.strip()[:80],
                        )
                    )
        found[locale] = hits
    return found


def load_baseline(path: Path) -> dict:
    """读取基线 JSON;不存在返回空 dict(等价于「只报告不拦截」)。"""
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def evaluate(current: dict[str, list[Residue]], baseline: dict) -> tuple[list[str], list[str]]:
    """对比当前残留与基线,返回 (failures, improvements) 两组人读消息。"""
    failures: list[str] = []
    improvements: list[str] = []
    base = {k: int(v) for k, v in (baseline.get("locales") or {}).items()}

    for locale in sorted(current):
        hits = current[locale]
        allowed = base.get(locale, 0)
        if len(hits) > allowed:
            new_locale = " (基线外语言包)" if locale not in base else ""
            failures.append(
                f"  {locale}: 当前 {len(hits)} 条 > 基线 {allowed} 条{new_locale}"
            )
            for h in hits[:20]:
                failures.append(f"      {h.file}: {h.name} = {h.value}")
            if len(hits) > 20:
                failures.append(f"      … 其余 {len(hits) - 20} 条省略,可用 --verbose 查看")
        elif len(hits) < allowed:
            improvements.append(f"  {locale}: {allowed} → {len(hits)}")

    for locale in sorted(set(base) - set(current)):
        improvements.append(f"  {locale}: 语言包已移除")

    return failures, improvements


def update_baseline(
    path: Path, current: dict[str, list[Residue]], allow_increase: bool
) -> int:
    """用当前扫描结果重写基线;默认拒绝写入上升条目(只降不升)。"""
    old = load_baseline(path)
    old_locales = {k: int(v) for k, v in (old.get("locales") or {}).items()}
    increases = [
        f"  {locale}: {old_locales.get(locale, 0)} → {len(hits)}"
        for locale, hits in sorted(current.items())
        if len(hits) > old_locales.get(locale, 0)
    ]
    if increases and old and not allow_increase:
        print("[translation-residue] 拒绝写回: 以下语言包高于现有基线(只降不升)")
        for line in increases:
            print(line)
        print("先把这些条目翻译成本地语言;确需豁免请显式加 --allow-increase 并说明理由。")
        return 1

    payload = {
        "note": (
            "I18N-02 非中文语言包 CJK 残留基线。只允许下降: 高于本文件或出现基线外"
            "语言包的残留即失败。补完翻译后运行 "
            "py -3 ci/script/check_translation_residue.py --update-baseline 收紧"
            "(默认拒绝写入上升值,需 --allow-increase)。"
        ),
        "measure": "每个语言包中值含 CJK 的 string/plurals-item/string-array-item 条目数",
        "excluded": ["values(默认/中文源)", "values-ja(日文汉字合法)", "values-zh*(中文变体)", "values-vNN(纯限定符)"],
        "locales": {locale: len(hits) for locale, hits in sorted(current.items())},
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    total = sum(len(v) for v in current.values())
    print(f"[translation-residue] 基线已更新: {path} ({len(current)} 个语言包 / 共 {total} 条)")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="I18N-02 非中文语言包 CJK 残留检查")
    parser.add_argument("--root", type=Path, default=PROJECT_ROOT, help="项目根(测试可用 fixture)")
    parser.add_argument("--baseline", type=Path, default=None, help="基线 JSON 路径")
    parser.add_argument("--update-baseline", action="store_true", help="用当前扫描结果重写基线")
    parser.add_argument("--allow-increase", action="store_true", help="更新基线时允许写入上升值")
    parser.add_argument("--verbose", action="store_true", help="打印每条残留明细")
    args = parser.parse_args()
    baseline_path = args.baseline or (args.root / BASELINE_REL)

    try:
        current = scan_res(args.root)
    except (FileNotFoundError, ValueError) as e:
        print(f"[translation-residue] 扫描失败: {e}")
        return 1

    total = sum(len(v) for v in current.values())
    excluded_note = "排除 values(中文源) / values-ja(日文汉字) / values-zh*, "
    print(
        f"[translation-residue] 扫描 {RES_REL.as_posix()} ({excluded_note}"
        f"受检 {len(current)} 个语言包): 共 {total} 条 CJK 残留"
    )
    if args.verbose:
        for locale, hits in sorted(current.items()):
            for h in hits:
                print(f"  {locale}  {h.file}: {h.name} = {h.value}")

    if args.update_baseline:
        return update_baseline(baseline_path, current, args.allow_increase)

    baseline = load_baseline(baseline_path)
    if not baseline:
        # 基线缺失 = 门禁失效,必须失败(删掉基线文件不能成为绕过手段)
        print(f"[translation-residue] FAILED — 未找到基线 {baseline_path};"
              f"生成: --update-baseline")
        return 1

    base = baseline.get("locales") or {}
    pairs = ", ".join(
        f"{locale} {len(current.get(locale, []))}/{int(base.get(locale, 0))}"
        for locale in sorted(set(current) | set(base))
    )
    print(f"[translation-residue] 当前 / 基线: {pairs}")

    failures, improvements = evaluate(current, baseline)
    if improvements:
        print("[translation-residue] 有改善,建议 --update-baseline 收紧:")
        for line in improvements:
            print(line)
    if failures:
        print("[translation-residue] FAILED — 非中文语言包出现 CJK 残留:")
        for line in failures:
            print(line)
        print("修复方式: 把条目翻译成对应语言的值(values-<locale>/strings*.xml);"
              "不要用 --allow-increase 调高基线。")
        return 1
    print(f"[translation-residue] PASS — 所有受检语言包残留数为 0 或在基线内 (共 {total} 条)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
