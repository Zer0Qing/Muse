#!/usr/bin/env python3
"""
check_row_text_width.py — Row 内文本未取宽（逐字换行 / 文本列塌陷）检查。

## 为什么需要这条门禁

历史反复出现同一类线上问题：某行里的文本在放大字号（系统"显示大小 / 字体大小"）
或窄窗口下被压成一列，每行只剩 1~4 个字，撑成几十行后被卡片裁掉
（例：MCP 服务器列表行 `McpServerRow`、动作面板行 `MuseActionSheetRow`）。

根因不是字号，而是结构：
  - `Row` 的定宽子项（开关 / 按钮 / 图标 / 徽章）按自己需要的宽度优先测量；
  - 承载文本的子项没有 `Modifier.weight(1f)`（或 `fillMaxWidth()`）→ 只拿到
    "最小可用宽度"（中文最窄可到 1 个字），剩余宽度被定宽子项吃光；
  - 文本又没有 `maxLines`/`overflow` 兜底，于是无限换行。

判定：**行内有宽度竞争（≥2 个子项）时，承载文本的子项必须显式取宽**
（`Modifier.weight(...)` / `fillMaxWidth()` / `fillMaxSize()` / 显式 `width(...)`），
或至少有 `maxLines` 兜底（限行 + 省略号，不会撑成几十行）。

豁免：单子项 Row（无宽度竞争）、FlowRow（本身就是流式换行容器）、
槽位参数里的 Text（`label = { Text(...) }`，宽度由容器决定）。

用法：
  py -3 ci/script/check_row_text_width.py
  py -3 ci/script/check_row_text_width.py --update-baseline
  py -3 ci/script/check_row_text_width.py --verbose
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

from baseline_utils import require_baseline

PROJECT_ROOT = Path(__file__).resolve().parents[2]
BASELINE_PATH = PROJECT_ROOT / "ci" / "baseline" / "row_text_width_baseline.txt"
SCAN_ROOTS = ("app/src/main/java/io/zer0/muse/ui",)

# 永久豁免：这些文件渲染的是**用户正文本身**（聊天 markdown 流式文本），
# 多行是设计意图，加 maxLines 会截断用户内容。文件内的 UI 装饰（标题/标签）
# 已在各自调用处自行限行，不再由本规则约束。
EXEMPT_FILES = frozenset(
    {
        "app/src/main/java/io/zer0/muse/ui/markdown/MarkdownText.kt",
    }
)

# 行式容器（会开启一个 Row 作用域）。
# 注意：`\bRow\s*\(` 不匹配 `MuseActionSheetRow(`（`n` 与 `R` 之间无词边界）；
# 且必须排除声明与限定调用——`fun Row(` / `fun XxxRow(` / `.Row(` 都不是容器调用。
ROW_OPEN = re.compile(r"(?<!fun )(?<![\w.])(?<!fun \w)Row\s*\(|\bRowScope\b")
# 自带横向 padding 的叶子行组件：作为子项时宽度由调用方给定，不再向内判定。
LEAF_ROW = re.compile(r"\bMuseActionSheetRow\s*\(")
# 承载文本的组件
TEXT_CHILD = re.compile(
    r"\b(?:Text|BasicText|MuseActionSheetRow|MuseListItem|SettingsItemRow|"
    r"SettingsSwitchRow|SettingsItemRow)\s*\("
)
# 槽位参数里的 Text：不是行内直接子项
SLOT_LAMBDA = re.compile(r"[A-Za-z_]\w*\s*=\s*\{\s*(?:Text|BasicText)\s*\(")
# 任何 Composable 调用（用于统计行内子项数）
CALL_OPEN = re.compile(r"\b([A-Z]\w*)\s*\(")
# 取宽 / 限行写法
WIDTH_SAFE = re.compile(
    r"\.weight\s*\(|\.fillMaxWidth\s*\(|\.fillMaxSize\s*\(|"
    r"\.width\s*\(|\.widthIn\s*\(|\.requiredWidth\s*\("
)
LINES_SAFE = re.compile(r"\bmaxLines\s*=")

# 非子项的关键字（语言结构 / 修饰符），不参与子项计数
NON_CHILD_CALLS = frozenset(
    {
        "Row", "Column", "Box", "FlowRow", "Spacer", "Surface", "Card", "when", "if",
        "else", "for", "while", "return", "require", "check", "let", "also", "apply",
        "run", "with", "remember", "rememberSaveable", "rememberCoroutineScope",
        "LaunchedEffect", "DisposableEffect", "AnimatedVisibility", "Modifier",
    }
)


def _strip_comment(line: str) -> str:
    idx = line.find("//")
    return line[:idx] if idx >= 0 else line


def _extract_call(lines: list[str], start: int, max_lines: int = 40) -> tuple[str, int]:
    """抽出一个完整调用（括号配平），返回 (文本, 最后一行下标)。"""
    buf: list[str] = []
    depth = 0
    seen_open = False
    i = start
    while i < len(lines) and i - start < max_lines:
        line = _strip_comment(lines[i])
        buf.append(line)
        for ch in line:
            if ch == "(":
                depth += 1
                seen_open = True
            elif ch == ")":
                depth -= 1
        if seen_open and depth <= 0:
            return "\n".join(buf), i
        i += 1
    return "\n".join(buf), i


def scan_file(path: Path) -> list[tuple[int, str]]:
    """返回 [(行号, 行内容)]，每项是一处「行内有宽度竞争但文本未取宽」。"""
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeDecodeError):
        return []

    findings: list[tuple[int, str]] = []
    # 每个 Row 体一个条目：[体起始深度, 子项数, 未取宽文本, 已取宽但无限行的文本]
    scopes: list[list] = []
    depth = 0
    pending_open = 0  # `Row(` 已出现、其体 `{` 未出现的括号层数
    pending_row = False
    i = 0

    while i < len(lines):
        raw = lines[i]
        line = _strip_comment(raw)
        opens = line.count("{")
        closes = line.count("}")

        row_here = bool(ROW_OPEN.search(line))
        leaf_here = bool(LEAF_ROW.search(line))
        if row_here and not leaf_here:
            pending_open += line.count("(") - line.count(")")
            pending_row = True
        elif pending_row:
            # Row 调用跨行：只累计后续行的括号差（不可重复统计首行的 `Row(`）
            pending_open += line.count("(") - line.count(")")

        created_scope = False
        if pending_row:
            if "{" in line and pending_open <= 0:
                # 体深度 = `{` 之后的深度（子项所在层级）
                scopes.append([depth + 1, 0, [], []])
                created_scope = True
                pending_row = False
            elif opens > 0 and pending_open <= 0:
                # `Row(...) {` 同行：`(` 已在本行闭合
                scopes.append([depth + 1, 0, [], []])
                created_scope = True
                pending_row = False

        cur = scopes[-1] if scopes else None
        in_row_body = cur is not None and depth == cur[0]

        if in_row_body and not created_scope and CALL_OPEN.search(line):
            m = TEXT_CHILD.search(line)
            is_slot = bool(SLOT_LAMBDA.search(line))
            if m and not is_slot:
                cur[1] += 1  # 文本子项
                # 这是保守的启发式：显式 weight 的跨行 Text 可能把 modifier
                # 放在后续行；先抽完整个调用再判断，避免漏报。
                call_text, end_line = _extract_call(lines, i)
                has_width = bool(WIDTH_SAFE.search(call_text))
                has_lines = bool(LINES_SAFE.search(call_text))
                if not has_width and not has_lines:
                    # 既没取宽也没限行：放大字号时会被压成一列并撑高
                    cur[2].append((i + 1, raw.strip()))
                elif has_width and not has_lines:
                    # 取了宽但无限行：剩余宽度极小时仍会逐字换行
                    cur[3].append((i + 1, raw.strip()))
                # 跳过整个调用体，但必须把体内花括号计入深度，否则后续作用域错位
                skipped = 0
                for x in lines[i : end_line + 1]:
                    sx = _strip_comment(x)
                    skipped += sx.count("{") - sx.count("}")
                depth += skipped
                while scopes and depth < scopes[-1][0] and scopes[-1] is not cur:
                    scope = scopes.pop()
                    if scope[1] >= 2:
                        findings.extend(scope[2])
                    findings.extend(scope[3])
                i = end_line + 1
                continue
            for name in CALL_OPEN.findall(line):
                if name not in NON_CHILD_CALLS:
                    cur[1] += 1
                    break

        depth += opens - closes
        # 本行刚创建的作用域不能在本行被弹出（`{` 已计入 depth）
        if not created_scope:
            while scopes and depth <= scopes[-1][0]:
                scope = scopes.pop()
                # 未取宽：需行内有宽度竞争（子项 ≥2）才算风险
                if scope[1] >= 2:
                    findings.extend(scope[2])
                # 已取宽但无限行：任何情况都可能被压成一列，一律计风险
                findings.extend(scope[3])
        i += 1

    while scopes:
        scope = scopes.pop()
        if scope[1] >= 2:
            findings.extend(scope[2])
        findings.extend(scope[3])

    findings.sort(key=lambda x: x[0])
    return findings


def scan_all() -> dict[str, list[tuple[int, str]]]:
    result: dict[str, list[tuple[int, str]]] = {}
    for rel_root in SCAN_ROOTS:
        base = PROJECT_ROOT / rel_root
        if not base.is_dir():
            continue
        for kt in sorted(base.rglob("*.kt")):
            rel = kt.relative_to(PROJECT_ROOT).as_posix()
            if rel in EXEMPT_FILES:
                continue
            hits = scan_file(kt)
            if hits:
                result[rel] = hits
    return result


def load_baseline(path: Path) -> dict[str, int]:
    baseline: dict[str, int] = {}
    if not path.exists():
        return baseline
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        file_part, sep, count_part = line.rpartition(":")
        if not sep:
            continue
        try:
            baseline[file_part] = int(count_part)
        except ValueError:
            continue
    return baseline


def write_baseline(path: Path, violations: dict[str, list[tuple[int, str]]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    out = [
        "# Row 内文本未取宽基线（只降不升）",
        "# 格式: <文件相对路径>:<允许处数>",
        "# 收紧: py -3 ci/script/check_row_text_width.py --update-baseline",
        "# 说明: 空基线 = 全仓零容忍。用户正文（ui/markdown/MarkdownText.kt）由脚本 EXEMPT_FILES 永久豁免，",
        "#       卡片装饰（DataCardRenderer / RichContentCard 的标题与语言标签）已在调用处限行。",
        "",
    ]
    for file, hits in sorted(violations.items()):
        out.append(f"{file}:{len(hits)}")
    path.write_text("\n".join(out) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Row 内文本未取宽检查")
    parser.add_argument("--baseline", type=Path, default=BASELINE_PATH)
    parser.add_argument("--update-baseline", action="store_true")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    violations = scan_all()
    total = sum(len(v) for v in violations.values())

    if args.update_baseline:
        write_baseline(args.baseline, violations)
        print(f"[row-text-width] baseline 已更新: {args.baseline}")
        print(f"  共 {total} 处，分布 {len(violations)} 个文件")
        return 0

    print(f"[row-text-width] 扫描 ui/: {total} 处 Row 内未取宽文本，分布 {len(violations)} 个文件")
    if args.verbose:
        for file, hits in sorted(violations.items()):
            print(f"  {file}")
            for ln, text in hits:
                print(f"    L{ln}: {text[:110]}")

    if not require_baseline(args.baseline, "row-text-width"):
        return 2

    baseline = load_baseline(args.baseline)
    failures: list[str] = []
    improved: list[str] = []
    for file, hits in sorted(violations.items()):
        current = len(hits)
        allowed = baseline.get(file, 0)
        if current > allowed:
            failures.append(f"  {file}: {current} 处 (baseline {allowed}, 新增 {current - allowed})")
        elif current < allowed:
            improved.append(f"  {file}: {allowed} → {current}")
    for file in baseline:
        if file not in violations:
            improved.append(f"  {file}: 已清零")

    if improved:
        print("[row-text-width] 有改善，建议 --update-baseline 收紧:")
        for line in improved:
            print(line)

    if failures:
        print("[row-text-width] FAILED — 新增「Row 内文本未取宽」写法:")
        for line in failures:
            print(line)
        print(
            "修复方式: 给承载文本的子项加 Modifier.weight(1f)（取剩余宽度）或 Modifier.fillMaxWidth()；"
            "必要时补 maxLines + overflow",
        )
        return 1

    print(f"[row-text-width] PASS — 无新增（存量 {total} 处按 baseline 豁免）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
