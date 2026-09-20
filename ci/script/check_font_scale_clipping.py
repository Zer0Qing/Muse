#!/usr/bin/env python3
"""
check_font_scale_clipping.py — A11Y-03 大字体裁切护栏。

扫描 app/src/main 的 UI 源码中对 `.height(<N>.dp)` 的**固定高度**用法：当
N >= MIN_RISK_DP(32) 且该 Modifier 所在容器内有文本可渲染内容（Text /
BasicText / stringResource / label / title / subtitle 等）、且不是媒体占位
（Image / AsyncImage / Canvas / Icon 容器）时计为 1 处「大字体裁切风险」。

为什么拦:
  固定高度在系统字体放大(1.3x / 1.5x,Android 无障碍「大字体」)时不会随文本
  增长,文字被裁掉或与相邻元素重叠;正确的写法是 `heightIn(min = N.dp)`
  —— 保底高度不变,文本放大时容器一起长高。项目已有大量 heightIn(min=) 用法
  (MuseChip / MuseCapsuleTab / ChatScreen 等)可作范例。

识别口径（每处计 1 次）:
  1. 形如 `.height(48.dp)` 的修饰符调用；`heightIn(` 不计,`Spacer` 行不计
     （间距不承载文本,放大字号无害）。
  2. N >= MIN_RISK_DP；再小的固定高度是图标/圆点/分割线/胶囊,不涉及文字裁切。
  3. 规则 B(同为大字体/无界高度风险):`heightIn(min = …)` **没有 max** 且其后 12 行内
     出现 `.fillMaxSize()` —— 子项会请求父级最大高度,在无界父容器(顶栏插槽、Scaffold)
     里会撑满整屏。1.3x 走查实测:聊天顶栏中岛因此长到 1608px,页面内容被挤出屏幕。
     带 max 的 heightIn(min=…, max=…) 不在此列(已限上界)。
  4. 向容器体内看 TEXT_FORWARD 行必须有文本标记（否则只是相邻组件的文字）；
     在「前 MEDIA_LOOKBACK 行 + 后 MEDIA_FORWARD 行」内出现媒体标记则按媒体
     处理（图片头图上的标题属于覆盖层、滑块/图表容器的高度由 Canvas 或图片
     决定,都不随字号撑高）。
  注释中的提及不计（strip_comments 与 check_component_convergence.py 同一套）。

基线机制:
  - 默认对比 ci/font_scale_clipping_baseline.json：逐文件处数不得高于基线,
    总数只减不升;基线文件缺失 = 失败(fail closed)。
  - --update-baseline 重写基线;默认拒绝写入上升条目,需 --allow-increase。
  - 消除一处风险后运行 --update-baseline 收紧,门禁随改造变严。

已知局限:
  - 纯词法启发式,无法识别 `LocalDensity` 换算或自定义 Layout 的裁切
  - 不覆盖 XML 布局与 material3/common 模块的设计系统实现
  - `.size(N.dp)` / `.requiredHeight(N.dp)` 不在统计内（前者多见于图标与头像）

用法:
  py -3 ci/script/check_font_scale_clipping.py
  py -3 ci/script/check_font_scale_clipping.py --verbose
  py -3 ci/script/check_font_scale_clipping.py --update-baseline [--allow-increase]
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]
BASELINE_REL = Path("ci") / "font_scale_clipping_baseline.json"
SCAN_ROOTS = [
    Path("app") / "src" / "main" / "java" / "io" / "zer0" / "muse" / "ui",
    Path("app") / "src" / "main" / "java" / "io" / "zer0" / "muse" / "automation" / "ui",
]
MIN_RISK_DP = 32
TEXT_FORWARD = 12
MEDIA_FORWARD = 40
MEDIA_LOOKBACK = 12

HEIGHT_RE = re.compile(r"\.height\(\s*([0-9]+(?:\.[0-9]+)?)\.dp\s*\)")
# 规则 B:只匹配**没有** max 的 heightIn(min=…);带 max 说明作者已限定上界
HEIGHTIN_MIN_ONLY_RE = re.compile(r"\.heightIn\(\s*min\s*=\s*[0-9.]+(?:\.dp)?\s*\)")
TEXT_RE = re.compile(
    r"\bText\(|BasicText|stringResource|label\s*=|title\s*=|subtitle\s*=|placeholder\s*="
)
MEDIA_RE = re.compile(
    r"Image\(|AsyncImage|SmartImage|SubcomposeAsyncImage|Canvas\(|Icon\(|VideoView|WebView"
)


@dataclass
class FileScan:
    """单个 UI 文件的固定高度风险统计。file 为项目根相对 posix 路径。"""

    file: str
    occurrences: int = 0
    unbounded: int = 0
    fixed_heights: int = 0
    details: list[str] = field(default_factory=list)
    unbounded_details: list[str] = field(default_factory=list)


def strip_comments(line: str, in_block_comment: bool) -> tuple[str, bool]:
    """剔除块/行注释,字符串字面量原样保留（与 check_component_convergence.py 一致）。"""
    out: list[str] = []
    i = 0
    n = len(line)
    while i < n:
        ch = line[i]
        if in_block_comment:
            if ch == "*" and i + 1 < n and line[i + 1] == "/":
                in_block_comment = False
                i += 2
                continue
            i += 1
            continue
        if ch in "\"'":
            quote = ch
            out.append(ch)
            i += 1
            while i < n:
                c = line[i]
                out.append(c)
                if c == "\\" and i + 1 < n:
                    out.append(line[i + 1])
                    i += 2
                    continue
                if c == quote:
                    i += 1
                    break
                i += 1
            continue
        if ch == "/" and i + 1 < n and line[i + 1] == "*":
            in_block_comment = True
            i += 2
            continue
        if ch == "/" and i + 1 < n and line[i + 1] == "/":
            break
        out.append(ch)
        i += 1
    return "".join(out), in_block_comment


def scan_file(path: Path, rel: str) -> FileScan:
    """统计一个 .kt 文件里「承载文本的固定高度」处数。"""
    scan = FileScan(file=rel)
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeDecodeError) as e:
        print(f"WARN: 无法读取 {path}: {e}", file=sys.stderr)
        return scan

    # 先剥注释,再统计;上下文判断同样基于剥注释后的文本,避免注释里的
    # 「Text(」或「Image(」误判。
    code_lines: list[str] = []
    in_block = False
    for line in lines:
        code, in_block = strip_comments(line, in_block)
        code_lines.append(code)

    for i, code in enumerate(code_lines):
        m = HEIGHT_RE.search(code)
        if not m:
            continue
        scan.fixed_heights += 1
        value = float(m.group(1))
        if value < MIN_RISK_DP or "Spacer" in code:
            continue
        # 细线/分隔条(width 0.5~2dp)不承载文本,高度固定无害
        nearby = "\n".join(code_lines[max(0, i - 3) : i + 1])
        if re.search(r"\.width\(\s*(?:0\.5|1|2)\.dp\s*\)", nearby):
            continue
        # 文本必须在紧邻的容器体内(前 TEXT_FORWARD 行),否则只是相邻组件里的文字;
        # 媒体窗口更宽(前 MEDIA_LOOKBACK 行 + 后 MEDIA_FORWARD 行):滑块轨道、图表
        # 容器的高度由 Canvas/图片决定,头图上的标题遮罩也属媒体覆盖层,都不随字号撑高。
        body = "\n".join(code_lines[i : i + TEXT_FORWARD])
        if not TEXT_RE.search(body):
            continue
        media = "\n".join(code_lines[max(0, i - MEDIA_LOOKBACK) : i + MEDIA_FORWARD])
        if MEDIA_RE.search(media):
            continue
        scan.occurrences += 1
        scan.details.append(f"L{i + 1}: height({m.group(1)}.dp)")

    # 规则 B:heightIn(min=…) 无上界 + 子项 fillMaxSize()
    for i, code in enumerate(code_lines):
        if not HEIGHTIN_MIN_ONLY_RE.search(code):
            continue
        if ".fillMaxSize()" in "\n".join(code_lines[i + 1 : i + 13]):
            scan.unbounded += 1
            scan.unbounded_details.append(f"L{i + 1}: heightIn(min=…) 无 max + 子项 fillMaxSize()")

    return scan


def scan_roots(root: Path) -> list[FileScan]:
    """扫描所有 UI 根目录,返回**所有含固定高度**的文件（按路径排序）。

    返回全集而非仅命中集,以便报告如实给出「固定高度总数 / 其中承载文本的风险数」;
    基线只使用 occurrences > 0 的子集。
    """
    results: list[FileScan] = []
    seen: set[str] = set()
    for rel_root in SCAN_ROOTS:
        base = root / rel_root
        if not base.is_dir():
            continue
        for kt in sorted(base.rglob("*.kt")):
            rel = kt.relative_to(root).as_posix()
            if rel in seen:
                continue
            seen.add(rel)
            scan = scan_file(kt, rel)
            if scan.fixed_heights or scan.unbounded:
                results.append(scan)
    if not seen:
        raise FileNotFoundError(
            "UI 源码目录不存在: " + ", ".join((root / r).as_posix() for r in SCAN_ROOTS)
        )
    results.sort(key=lambda s: s.file)
    return results


def load_baseline(path: Path) -> dict:
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def evaluate(current: list[FileScan], baseline: dict) -> tuple[list[str], list[str]]:
    """对比当前扫描与基线,返回 (failures, improvements)。"""
    failures: list[str] = []
    improvements: list[str] = []

    if baseline.get("min_risk_dp") is not None and int(baseline["min_risk_dp"]) != MIN_RISK_DP:
        failures.append(
            f"  基线 min_risk_dp={baseline['min_risk_dp']} 与脚本 {MIN_RISK_DP} 不一致 ——"
            f"识别口径已变化,请复核后 --update-baseline 重新落基线"
        )

    base_files = {k: int(v) for k, v in (baseline.get("files") or {}).items()}
    base_unbounded = {k: int(v) for k, v in (baseline.get("unbounded") or {}).items()}

    for scan in current:
        u_allowed = base_unbounded.get(scan.file, 0)
        if scan.unbounded > u_allowed:
            kind = "新增文件" if scan.file not in base_unbounded else "高于基线"
            failures.append(
                f"  {scan.file}: 无界 heightIn 当前 {scan.unbounded} 处 > 基线 {u_allowed} 处 "
                f"({kind}) {scan.unbounded_details}"
            )
        elif scan.unbounded < u_allowed:
            improvements.append(f"  {scan.file}: 无界 heightIn {u_allowed} → {scan.unbounded}")

        allowed = base_files.get(scan.file, 0)
        if scan.occurrences > allowed:
            kind = "新增文件" if scan.file not in base_files else "高于基线"
            failures.append(
                f"  {scan.file}: 当前 {scan.occurrences} 处 > 基线 {allowed} 处 ({kind}) "
                f"{scan.details}"
            )
        elif scan.occurrences < allowed:
            improvements.append(f"  {scan.file}: {allowed} → {scan.occurrences}")

    for gone in sorted(set(base_files) - {s.file for s in current}):
        improvements.append(f"  {gone}: 已清零")
    for gone in sorted(set(base_unbounded) - {s.file for s in current}):
        improvements.append(f"  {gone}: 无界 heightIn 已清零")

    cur_unbounded = sum(s.unbounded for s in current)
    base_unbounded_total = int((baseline.get("totals") or {}).get("unbounded", 0))
    if cur_unbounded > base_unbounded_total:
        failures.append(f"  无界 heightIn 总处数: 当前 {cur_unbounded} > 基线 {base_unbounded_total}")

    cur_total = sum(s.occurrences for s in current)
    base_total = int((baseline.get("totals") or {}).get("occurrences", 0))
    if cur_total > base_total:
        failures.append(f"  总处数: 当前 {cur_total} > 基线 {base_total}")

    return failures, improvements


def update_baseline(path: Path, current: list[FileScan], allow_increase: bool) -> int:
    """用当前扫描结果重写基线;默认拒绝写入上升条目（只降不升）。"""
    old = load_baseline(path)
    old_files = {k: int(v) for k, v in (old.get("files") or {}).items()}
    old_unbounded = {k: int(v) for k, v in (old.get("unbounded") or {}).items()}
    increases = [
        f"  {s.file}: {old_files.get(s.file, 0)} → {s.occurrences}"
        for s in current
        if s.occurrences > old_files.get(s.file, 0)
    ]
    increases += [
        f"  {s.file}: 无界 heightIn {old_unbounded.get(s.file, 0)} → {s.unbounded}"
        for s in current
        if s.unbounded > old_unbounded.get(s.file, 0)
    ]
    if increases and old and not allow_increase:
        print("[font-scale-clipping] 拒绝写回: 以下条目高于现有基线(只降不升)")
        for line in increases:
            print(line)
        print("先把固定高度改成 heightIn(min = …) 或去掉高度;确需保留请显式加 --allow-increase 并说明理由。")
        return 1

    total = sum(s.occurrences for s in current)
    payload = {
        "note": (
            "A11Y-03 大字体裁切基线。只允许下降: 基线外的新文件或处数高于本文件即失败。"
            "改造后运行 py -3 ci/script/check_font_scale_clipping.py --update-baseline 收紧"
            "(默认拒绝写入上升值,需 --allow-increase)。"
        ),
        "scan_roots": [r.as_posix() for r in SCAN_ROOTS],
        "min_risk_dp": MIN_RISK_DP,
        "text_forward": TEXT_FORWARD,
        "media_forward": MEDIA_FORWARD,
        "media_lookback": MEDIA_LOOKBACK,
        "totals": {
            "files": len(current),
            "occurrences": total,
            "unbounded": sum(s.unbounded for s in current),
        },
        "files": {s.file: s.occurrences for s in current},
        "unbounded": {s.file: s.unbounded for s in current if s.unbounded},
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        f"[font-scale-clipping] 基线已更新: {path} "
        f"({len(current)} 个文件 / {total} 处固定高度承载文本)"
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="A11Y-03 大字体裁切护栏检查")
    parser.add_argument("--root", type=Path, default=PROJECT_ROOT, help="项目根(测试可用 fixture)")
    parser.add_argument("--baseline", type=Path, default=None, help="基线 JSON 路径")
    parser.add_argument("--update-baseline", action="store_true", help="用当前扫描结果重写基线")
    parser.add_argument("--allow-increase", action="store_true", help="更新基线时允许写入上升值")
    parser.add_argument("--verbose", action="store_true", help="打印每个文件的明细")
    args = parser.parse_args()
    baseline_path = args.baseline or (args.root / BASELINE_REL)

    try:
        current = scan_roots(args.root)
    except FileNotFoundError as e:
        print(f"[font-scale-clipping] 扫描失败: {e}")
        return 1

    risky = [s for s in current if s.occurrences or s.unbounded]
    fixed = sum(s.fixed_heights for s in current)
    total = sum(s.occurrences for s in current)
    unbounded = sum(s.unbounded for s in current)
    print(
        f"[font-scale-clipping] 扫描 UI 源码: 固定高度 {fixed} 处"
        f"(承载文本且 >= {MIN_RISK_DP}dp 的 {total} 处),"
        f"无界 heightIn(min=…)+fillMaxSize 子项 {unbounded} 处,跨 {len(risky)} 个文件"
    )
    if args.verbose:
        for s in risky:
            print(f"  {s.occurrences:3d}/{s.unbounded:1d}  {s.file}  {s.details} {s.unbounded_details}")
    current = risky

    if args.update_baseline:
        return update_baseline(baseline_path, current, args.allow_increase)

    baseline = load_baseline(baseline_path)
    if not baseline:
        print(f"[font-scale-clipping] FAILED — 未找到基线 {baseline_path};生成: --update-baseline")
        return 1

    base_files = baseline.get("files") or {}
    base_total = int((baseline.get("totals") or {}).get("occurrences", 0))
    unbounded_total = sum(s.unbounded for s in current)
    base_unbounded_n = len(baseline.get("unbounded") or {})
    print(
        f"[font-scale-clipping] 当前 / 基线: 文件 {len(current)}/{len(base_files)}, "
        f"处数 {total}/{base_total}, 无界 heightIn {unbounded_total}/{base_unbounded_n}"
    )

    failures, improvements = evaluate(current, baseline)
    if improvements:
        print("[font-scale-clipping] 有改善,建议 --update-baseline 收紧:")
        for line in improvements:
            print(line)
    if failures:
        print("[font-scale-clipping] FAILED — 新增/高于基线的「固定高度承载文本」:")
        for line in failures:
            print(line)
        print(
            "修复方式: ① 固定高度 → Modifier.heightIn(min = N.dp)(保底不变、随字号增长),"
            "注意子项若用 fillMaxSize 需同时给 max 上界或把子项改为 fillMaxWidth;"
            "② 无界 heightIn + fillMaxSize 子项 → 给 max 上界,或把子项改成 fillMaxWidth。"
        )
        return 1
    print(
        f"[font-scale-clipping] PASS — 未超过基线 "
        f"(存量 {len(base_files)} 个文件 / {base_total} 处,只降不升)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
