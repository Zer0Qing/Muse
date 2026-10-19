"""CI 门禁：禁止把 % 占位符字面量当格式化参数传给字符串资源。

背景（v2.6.2 线上崩溃）：
    stringResource(R.string.settings_sticker_deleted_toast, "%1$d")
`stringResource(id, args...)` 内部就是 `Resources.getString(id, args...)`，
会立即执行 `String.format`。资源模板是 `已删除 %1$d 个表情包`（%1$d 要整数），
实参却是字符串字面量 "%1$d" → 运行期抛
`IllegalFormatConversionException: d != java.lang.String`，整页组合期崩溃。

这种写法类型上完全合法，lint 与编译器都抓不到，只有真机组合期才炸。
若本意是「先取模板原文、事后再 replace」，必须用**无参** `stringResource(id)`。

本脚本只认「第一个实参就是 % 字面量」这一种确定错误形态，避免误报。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java"
RES = ROOT / "app/src/main/res"

# 资源名 -> 模板原文（只取 values/ 默认语言）
STR_RE = re.compile(r'<string\s+name="([^"]+)"[^>]*>(.*?)</string>', re.S)
strings: dict[str, str] = {}
for f in RES.glob("values/*.xml"):
    text = f.read_text(encoding="utf-8", errors="ignore")
    for m in STR_RE.finditer(text):
        strings.setdefault(m.group(1), m.group(2))

INT_SPEC = re.compile(r"%(?:\d+\$)?[dix]")

# R.string.xxx , "%1$d" ...   （第一个实参即 % 字面量）
BAD_CALL = re.compile(
    r"(?:stringResource|getString)\(\s*(?:androidx\.compose\.ui\.res\.)?R\.string\.(\w+)\s*,\s*\"%\d*\$?[sdifx]"
)

problems: list[str] = []
for kt in JAVA.rglob("*.kt"):
    src = kt.read_text(encoding="utf-8", errors="ignore")
    for m in BAD_CALL.finditer(src):
        name = m.group(1)
        template = strings.get(name, "")
        # 模板里没有整型占位符时，传字符串字面量是合法的（如 tool_alarm_set 全 %s）。
        if not INT_SPEC.search(template):
            continue
        line = src.count("\n", 0, m.start()) + 1
        end = src.find("\n", m.start())
        frag = src[m.start(): end if end > 0 else len(src)]
        problems.append(
            f"{kt.relative_to(ROOT)}:{line}\n"
            f"    资源 {name} = {template!r}\n"
            f"    {frag.strip()}"
        )

if problems:
    print("发现把 % 字面量当格式化参数的调用（会在组合期崩溃）：\n")
    for p in problems:
        print(p)
    print("\n修法：去掉参数 —— stringResource(R.string.xxx)（无参不格式化，返回模板原文）")
    sys.exit(1)

print("OK: 未发现 % 字面量当格式化参数的调用")
