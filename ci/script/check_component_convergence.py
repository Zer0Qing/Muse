#!/usr/bin/env python3
"""
check_component_convergence.py — CMP-11 组件收敛护栏。

扫描 app/src/main/java/io/zer0/muse/ui/** 下的 Kotlin 文件对 Material3
交互控件的直接引用，统计文件数与出现次数，与基线
ci/component_convergence_baseline.json 比较：新增或高于基线即失败（只降不升）。

为什么拦:
  Muse 的统一交互组件在 ui/common（MuseDialog / MuseFormDialog /
  MuseBottomSheet / MuseSelectionSheet / MuseTextField 等，见
  UI组件库维护规范.md）。业务页面直接使用 M3 原生 Button/Card/IconButton 会重新
  引入不一致的圆角、间距、触摸尺寸与弹窗行为；本脚本既是收敛度量（存量只降），
  也是「新增 UI 文件不得直接用 M3 控件」的 CI 闸门。

识别口径（每个引用计 1 次）:
  1. `import androidx.compose.material3.<控件>`（兼容 `as` 别名写法）
  2. `import androidx.compose.material3.*` 通配导入：按 1 次计入该文件，同时进入
     「通配导入文件集合」，该集合只减不增（通配导入会让逐符号计数失效，新增即
     失败，避免用 `.*` 绕过统计）
  3. 代码中的全限定引用 `androidx.compose.material3.<控件>`，如
     `androidx.compose.material3.Button(...)`（同一行多次出现按多次计）
  只扫以上三种直接引用；注释中的提及不计（词法剥离逻辑与
  ci/script/check_hardcoded_cjk.py 的 strip_comments 保持一致）。空白 UI 文件 /
  无命中文件不进入基线。

控件清单: 见 SYMBOLS，至少覆盖计划要求的最低集合 Button / OutlinedButton /
  TextButton / IconButton / Card / FilterChip / CircularProgressIndicator /
  DropdownMenu，另含 AlertDialog / ModalBottomSheet / 输入类 / 选择类控件。
  清单快照写入基线 JSON，清单一旦变化会强制重新复核基线。

基线机制:
  - 默认对比 ci/component_convergence_baseline.json：逐文件出现次数不得高于基线
    （基线外的新文件视为 0），总数与通配导入集合同样只减不增。
  - 基线文件缺失 = 失败(fail closed),避免删掉基线绕过门禁。
  - --update-baseline 重写基线；默认拒绝写入「上升」的条目，需 --allow-increase
    才强制写入。迁移清理后运行 --update-baseline 收紧，让门禁随 CMP 迁移变严。

已知局限:
  - 通过包装函数、别名变量或反射间接使用 M3 控件不在识别范围
  - 只覆盖 app 的 ui/**；material3/、common/ 等模块的设计系统实现不在此列

用法:
  py -3 ci/script/check_component_convergence.py
  py -3 ci/script/check_component_convergence.py --baseline <path>
  py -3 ci/script/check_component_convergence.py --update-baseline
  py -3 ci/script/check_component_convergence.py --allow-increase --update-baseline
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
UI_REL = Path("app") / "src" / "main" / "java" / "io" / "zer0" / "muse" / "ui"
BASELINE_REL = Path("ci") / "component_convergence_baseline.json"

# CMP-11 覆盖的 M3 交互控件（含变体；变更本清单需 --update-baseline 重新落基线）
SYMBOLS = [
    "AlertDialog",
    "AssistChip",
    "BasicAlertDialog",
    "Button",
    "Card",
    "Checkbox",
    "CircularProgressIndicator",
    "DropdownMenu",
    "DropdownMenuItem",
    "ElevatedButton",
    "ElevatedCard",
    "FilterChip",
    "FloatingActionButton",
    "IconButton",
    "InputChip",
    "LinearProgressIndicator",
    "ModalBottomSheet",
    "OutlinedButton",
    "OutlinedCard",
    "OutlinedTextField",
    "RadioButton",
    "Slider",
    "SuggestionChip",
    "Switch",
    "TextButton",
    "TextField",
]
SYMBOL_SET = frozenset(SYMBOLS)

M3_IMPORT_RE = re.compile(
    r"^\s*import\s+androidx\.compose\.material3\.([A-Za-z_][A-Za-z0-9_]*|\*)"
    r"(?:\s+as\s+[A-Za-z_][A-Za-z0-9_]*)?\s*;?\s*$"
)
M3_FQ_RE = re.compile(r"androidx\.compose\.material3\.([A-Za-z_][A-Za-z0-9_]*)")


@dataclass
class FileScan:
    """单个 UI 文件的直接引用统计。file 为项目根相对 posix 路径。"""

    file: str
    occurrences: int = 0
    wildcard: int = 0
    symbols: Counter[str] = field(default_factory=Counter)


def strip_comments(line: str, in_block_comment: bool) -> tuple[str, bool]:
    """剔除块/行注释,字符串字面量原样保留(不把 "https://x" 里的 // 当注释)。

    与 ci/script/check_hardcoded_cjk.py 的 strip_comments 同一套逻辑。
    """
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
            break  # 行注释,丢弃剩余
        out.append(ch)
        i += 1
    return "".join(out), in_block_comment


def scan_file(path: Path, rel: str) -> FileScan:
    """统计一个 .kt 文件里的 M3 直接引用（import / 通配 import / 全限定引用）。"""
    scan = FileScan(file=rel)
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeDecodeError) as e:
        print(f"WARN: 无法读取 {path}: {e}", file=sys.stderr)
        return scan
    in_block = False
    for line in lines:
        code, in_block = strip_comments(line, in_block)
        if not code.strip():
            continue
        m = M3_IMPORT_RE.match(code)
        if m:
            name = m.group(1)
            if name == "*":
                scan.wildcard += 1
                scan.occurrences += 1
            elif name in SYMBOL_SET:
                scan.occurrences += 1
                scan.symbols[name] += 1
            continue  # import 行已处理,不再做全限定匹配
        for hit in M3_FQ_RE.finditer(code):
            name = hit.group(1)
            if name in SYMBOL_SET:
                scan.occurrences += 1
                scan.symbols[name] += 1
    return scan


def scan_ui(root: Path) -> list[FileScan]:
    """扫描 root/ui/** 下所有 .kt，返回有直接引用的文件（按路径排序）。"""
    ui_dir = root / UI_REL
    if not ui_dir.is_dir():
        raise FileNotFoundError(f"UI 目录不存在: {ui_dir}")
    results: list[FileScan] = []
    for kt in sorted(ui_dir.rglob("*.kt")):
        scan = scan_file(kt, kt.relative_to(root).as_posix())
        if scan.occurrences:
            results.append(scan)
    return results


def load_baseline(path: Path) -> dict:
    """读取基线 JSON;不存在返回空 dict(等价于「只报告不拦截」)。"""
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def evaluate(current: list[FileScan], baseline: dict) -> tuple[list[str], list[str]]:
    """对比当前扫描与基线,返回 (failures, improvements) 两组人读消息。"""
    failures: list[str] = []
    improvements: list[str] = []

    base_symbols = baseline.get("symbols")
    if base_symbols is not None and list(base_symbols) != SYMBOLS:
        failures.append(
            "  基线 symbols 快照与脚本 SYMBOLS 不一致 —— 控件清单已变化,"
            "请复核影响面后 --update-baseline 重新落基线"
        )

    base_files = {k: int(v) for k, v in (baseline.get("files") or {}).items()}
    base_wild = {k: int(v) for k, v in (baseline.get("wildcard_imports") or {}).items()}

    for scan in current:
        allowed = base_files.get(scan.file, 0)
        if scan.occurrences > allowed:
            kind = "新增文件" if scan.file not in base_files else "高于基线"
            failures.append(
                f"  {scan.file}: 当前 {scan.occurrences} 处 > 基线 {allowed} 处 ({kind}) "
                f"{dict(scan.symbols)}"
            )
        elif scan.occurrences < allowed:
            improvements.append(f"  {scan.file}: {allowed} → {scan.occurrences}")
        w_allowed = base_wild.get(scan.file, 0)
        if scan.wildcard > w_allowed:
            failures.append(
                f"  {scan.file}: 通配导入 androidx.compose.material3.* 当前 {scan.wildcard} 处 "
                f"> 基线 {w_allowed} 处 —— 禁止用通配导入绕过逐控件统计"
            )
        elif scan.wildcard < w_allowed:
            improvements.append(f"  {scan.file}: 通配导入 {w_allowed} → {scan.wildcard}")

    for gone in sorted(set(base_files) - {s.file for s in current}):
        improvements.append(f"  {gone}: 已清零")

    cur_total = sum(s.occurrences for s in current)
    base_total = int((baseline.get("totals") or {}).get("occurrences", 0))
    if cur_total > base_total:
        failures.append(f"  总出现次数: 当前 {cur_total} > 基线 {base_total}")

    return failures, improvements


def update_baseline(path: Path, current: list[FileScan], allow_increase: bool) -> int:
    """用当前扫描结果重写基线;默认拒绝写入上升条目(CMP-11 只降不升)。"""
    old = load_baseline(path)
    old_files = {k: int(v) for k, v in (old.get("files") or {}).items()}
    old_wild = {k: int(v) for k, v in (old.get("wildcard_imports") or {}).items()}
    increases = [
        f"  {s.file}: {old_files.get(s.file, 0)} → {s.occurrences}"
        for s in current
        if s.occurrences > old_files.get(s.file, 0)
    ]
    increases += [
        f"  {s.file}: 通配导入 {old_wild.get(s.file, 0)} → {s.wildcard}"
        for s in current
        if s.wildcard > old_wild.get(s.file, 0)
    ]
    if increases and old and not allow_increase:
        print("[component-convergence] 拒绝写回: 以下条目高于现有基线(只降不升)")
        for line in increases:
            print(line)
        print("先按 CMP 迁移清理这些引用;确需保留请显式加 --allow-increase 并说明理由。")
        return 1

    total = sum(s.occurrences for s in current)
    wild = {s.file: s.wildcard for s in current if s.wildcard}
    payload = {
        "note": (
            "CMP-11 组件收敛基线。只允许下降: 基线外的新文件或出现次数高于本文件即失败。"
            "清理存量后运行 py -3 ci/script/check_component_convergence.py --update-baseline 收紧"
            "(默认拒绝写入上升值,需 --allow-increase)。"
        ),
        "ui_root": UI_REL.as_posix(),
        "symbols": SYMBOLS,
        "totals": {
            "files": len(current),
            "occurrences": total,
            "wildcard_files": len(wild),
        },
        "wildcard_imports": wild,
        "files": {s.file: s.occurrences for s in current},
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(
        f"[component-convergence] 基线已更新: {path}"
        f" ({len(current)} 个文件 / {total} 处 / 通配导入 {len(wild)} 个文件)"
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="CMP-11 M3 组件收敛护栏检查")
    parser.add_argument("--root", type=Path, default=PROJECT_ROOT, help="项目根(测试可用 fixture)")
    parser.add_argument("--baseline", type=Path, default=None, help="基线 JSON 路径")
    parser.add_argument("--update-baseline", action="store_true", help="用当前扫描结果重写基线")
    parser.add_argument("--allow-increase", action="store_true", help="更新基线时允许写入上升值")
    parser.add_argument("--verbose", action="store_true", help="打印每个文件的明细")
    args = parser.parse_args()
    baseline_path = args.baseline or (args.root / BASELINE_REL)

    try:
        current = scan_ui(args.root)
    except FileNotFoundError as e:
        print(f"[component-convergence] 扫描失败: {e}")
        return 1

    total = sum(s.occurrences for s in current)
    wild_files = [s for s in current if s.wildcard]
    print(
        f"[component-convergence] 扫描 {UI_REL.as_posix()}: "
        f"{len(current)} 个文件 / {total} 处直接引用 M3 交互控件"
        f" (通配导入 {len(wild_files)} 个文件), 控件清单 {len(SYMBOLS)} 个"
    )
    if args.verbose:
        for s in current:
            print(f"  {s.occurrences:3d}  {s.file}  {dict(s.symbols)}")

    if args.update_baseline:
        return update_baseline(baseline_path, current, args.allow_increase)

    baseline = load_baseline(baseline_path)
    if not baseline:
        # 基线缺失 = 门禁失效,必须失败(删掉基线文件不能成为绕过手段)
        print(f"[component-convergence] FAILED — 未找到基线 {baseline_path};"
              f"生成: --update-baseline")
        return 1

    base_files = baseline.get("files") or {}
    base_wild = baseline.get("wildcard_imports") or {}
    base_total = int((baseline.get("totals") or {}).get("occurrences", 0))
    print(
        f"[component-convergence] 当前 / 基线: 文件 {len(current)}/{len(base_files)}, "
        f"出现 {total}/{base_total}, 通配导入文件 {len(wild_files)}/{len(base_wild)}"
    )

    failures, improvements = evaluate(current, baseline)
    if improvements:
        print("[component-convergence] 有改善,建议 --update-baseline 收紧:")
        for line in improvements:
            print(line)
    if failures:
        print("[component-convergence] FAILED — 新增/高于基线直接使用 M3 控件:")
        for line in failures:
            print(line)
        print("修复方式: 优先复用 ui/common 的统一组件(MuseDialog/MuseBottomSheet/MuseSelectionSheet/"
              "MuseTextField 等);确需新增存量请先清理等量引用,而不是调高基线。")
        return 1
    print(f"[component-convergence] PASS — 未超过基线 (存量 {len(base_files)} 个文件 / {base_total} 处,只降不升)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
