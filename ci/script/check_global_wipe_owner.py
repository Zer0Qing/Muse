#!/usr/bin/env python3
"""check_global_wipe_owner.py — 进程级"全局清理"只准有一个用户触发入口（整改计划 第 2 条的护栏）。

背景（都是仓库里已有的事实，不是我推演出来的）：
  - `WebStorage.getInstance().deleteAllData()` 与 `CookieManager.removeAllCookies()`
    是**进程级**动作：一个 WebView 实例的清理代码能把所有站点的数据一起擦掉。
  - `JsSandbox.kt` 里 P2-20 已经记过一次同样的错（"不再调用 WebStorage.deleteAllData() —
    沙箱只加载 about:blank(opaque origin)"），而 `HtmlPreviewScreen` 直到本轮还留着同一句
    （它以 `about:blank` 为 base URL 加载，本来什么都攒不下，却把 Agent 浏览器里
    所有站点的 localStorage/IndexedDB 擦了 —— 用户预览一张卡片就被登出）。

所以这里钉住唯一所有者：全仓只允许 `tools/BrowserSettings.kt` 的 `clearBrowserSiteData()`
调用这两个 API（它是设置页那颗「清除 Cookies 与站点数据」按钮的实现，用户主动触发）。
其它地方要用，就走这个函数；要新增入口，先改这条门禁并说明理由。

哨兵自证：本门禁会验证"允许文件里确实找得到这两个调用"，
否则一旦 owner 文件被改名/重构，扫描会退化成"永远通过"的空跑（这是本项目反复踩过的失效形态）。

用法：
    py -3 ci/script/check_global_wipe_owner.py
退出码：0 通过；1 出现越权的调用点或 owner 哨兵失效。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

PROJECT_ROOT = Path(__file__).resolve().parents[2]
OWNER = "tools/BrowserSettings.kt"


def discover_source_roots() -> list[Path]:
    """扫描范围靠**动态发现**，不写模块名单，也不假设源目录叫 `java`。

    为什么不列名单：我上一版硬写了 9 个模块名，结果既漏了 `miniphone`/`virtual-display-*`/
    `accessibility-provider`，又把三个**没有 java 源目录**的模块当存在列着 ——
    那种列表不会报错，只会安静地少扫一片。新增模块时更没人会想起来改这里。
    现规则：仓库里任意 `src/main/java` **或** `src/main/kotlin` 都算
    （`conversation-core`、`material3` 用的就是 kotlin 目录，只认 java 会整个漏掉）。
    排除 build 产物；测试源不在范围内：测试里可以造全局清理验证行为，那不算越权。
    """
    roots: list[Path] = []
    for src_dir in ("java", "kotlin"):
        for path in PROJECT_ROOT.rglob(f"src/main/{src_dir}"):
            if not path.is_dir():
                continue
            parts = set(path.parts)
            if {"build", ".git", ".gradle", "node_modules"} & parts:
                continue
            roots.append(path)
    return sorted(set(roots))


SRC_ROOTS = discover_source_roots()
# 一个源目录都没找到就直接失败：否则扫描退化成"永远通过"（P4-1 当初修的就是这种假绿）。
assert SRC_ROOTS, "未发现任何 src/main 源目录，门禁无法验证任何东西"
# 数量下限：发现逻辑若被改坏（少扫一片），宁可让它红，也不要安静地少判。
_SCANNED = sum(1 for root in SRC_ROOTS for _ in root.rglob("*.kt"))
assert _SCANNED >= 300, f"只扫到 {_SCANNED} 个 .kt，源目录发现逻辑多半坏了"
PATTERNS = {
    "webstorage_delete_all_data": re.compile(r"WebStorage\s*\.\s*getInstance\s*\(\s*\)\s*\.\s*deleteAllData\s*\("),
    # 注意：owner 是在 `CookieManager.getInstance().apply { removeAllCookies(null) }` 块内调用的，
    # 前面没有点号 —— 早先写成 `\.removeAllCookies` 会把自家入口漏掉（哨兵因此误报失效）。
    "cookie_remove_all": re.compile(r"\bremoveAllCookies\s*\("),
}


def strip_comments(text: str) -> str:
    """注释里提到 API 名不算调用（`HtmlPreviewScreen` 就有一段解释性注释带着这句原文）。"""
    no_block = re.sub(r"/\*.*?\*/", lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)
    lines = []
    for line in no_block.split("\n"):
        # 去掉行注释；URL 里的 // 不会被上面的模式误判，因为它不在注释起始位置
        idx = line.find("//")
        if idx != -1:
            before = line[:idx]
            if before.count('"') % 2 == 0:
                line = before
        lines.append(line)
    return "\n".join(lines)


def scan() -> list[tuple[str, int, str]]:
    hits: list[tuple[str, int, str]] = []
    for src_root in SRC_ROOTS:
        for path in sorted(src_root.rglob("*.kt")):
            # 相对到仓库根，报告里能一眼看出是哪个模块（parents[1] 会只剩 main/java/…，看不出模块）
            rel = path.relative_to(PROJECT_ROOT).as_posix()
            body = strip_comments(path.read_text(encoding="utf-8", errors="replace"))
            for line_no, line in enumerate(body.split("\n"), start=1):
                for name, pattern in PATTERNS.items():
                    if pattern.search(line):
                        hits.append((rel, line_no, name))
    return hits


def main() -> int:
    hits = scan()
    violations = [h for h in hits if not h[0].endswith(OWNER)]
    owner_hits = [h for h in hits if h[0].endswith(OWNER)]

    # 哨兵：owner 里两个 API 都必须在，否则说明入口被改名/搬走，本门禁已退化成空跑
    owner_rules = {h[2] for h in owner_hits}
    missing = set(PATTERNS) - owner_rules
    if missing:
        print(f"FAIL: 哨兵失效 — {OWNER} 里找不到 {sorted(missing)} 的调用，"
              "说明唯一入口被改名/重构，本门禁已无法验证任何东西。")
        return 1

    if violations:
        print(f"FAIL: 发现 {len(violations)} 处越权的进程级全局清理调用"
              f"（只允许 {OWNER} 的 clearBrowserSiteData 作为用户触发入口）：")
        for rel, line_no, name in violations:
            print(f"  - {rel}:{line_no} [{name}]")
        print("\n正确做法：调用 clearBrowserSiteData()（同一口径），"
              "或说明为什么需要新的独立入口后再改本门禁。")
        return 1

    print(f"OK: 进程级全局清理只有 {OWNER} 一个入口"
          f"（owner 内 {len(owner_hits)} 处，全仓越权 0 处）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
