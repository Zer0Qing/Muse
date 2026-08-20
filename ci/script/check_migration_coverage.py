#!/usr/bin/env python3
"""F-02: 数据库迁移 expected/actual 硬校验。

历史事故: 曾出现"迁移断链但门禁全绿"——迁移链有缺口(某版本无出发迁移)而 CI
不校验,升级路径在真机崩溃。本脚本把"迁移链完整性"变成 CI 硬门禁。

四重校验:
  A. 静态注册一致: addMigrations 引用的每个 MIGRATION_x_y 都必须有对应声明;
     声明的静态迁移(from < 当前版本)必须全部注册进 addMigrations。
  B. 链完整: addMigrations 的出发版本(from)集合必须覆盖 1..N-1(N=当前 version),
     每个 to 都大于 from 且不大于 N——任意版本断链即失败。
  C. 动态迁移显式测试: migrate75To76 / migrate76To77 等函数式迁移必须被
     MuseDbMigrationTest 显式引用(静态迁移由 ManualChain 反射枚举覆盖)。
  D. 关键回归测试存在: MuseDbManualChainMigrationTest 必须存在且仍包含
     declaredFields 反射枚举(防误删该测试导致静态迁移失去执行覆盖)。

自测(构造删迁移场景验证脚本必 FAIL):
  python3 ci/script/check_migration_coverage.py --mutate-delete 89,90
  python3 ci/script/check_migration_coverage.py --mutate-delete 90,91
"""

import argparse
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DB_PATH = REPO_ROOT / "app/src/main/java/io/zer0/muse/data/session/MuseDb.kt"
TEST_MIGRATION = REPO_ROOT / "app/src/test/java/io/zer0/muse/data/session/MuseDbMigrationTest.kt"
TEST_MANUAL_CHAIN = REPO_ROOT / "app/src/test/java/io/zer0/muse/data/session/MuseDbManualChainMigrationTest.kt"

STATIC_DECL = re.compile(r"val\s+(MIGRATION_(\d+)_(\d+))\s*=\s*object\s*:\s*Migration")
DYNAMIC_DECL = re.compile(r"fun\s+(migrate(\d+)To(\d+))\b")
ADD_MIG_TOKEN = re.compile(r"\b(?:MIGRATION_(\d+)_(\d+)|migrate(\d+)To(\d+))\b")
TEST_REF = re.compile(r"MuseDb\.(?:MIGRATION_(\d+)_(\d+)|migrate(\d+)To(\d+))")
VERSION_RE = re.compile(r"version\s*=\s*(\d+)")


def name_to_pair(name: str) -> tuple[int, int] | None:
    """'MIGRATION_68_74' -> (68, 74); 'migrate75To76' -> (75, 76)."""
    m = re.fullmatch(r"(?:MIGRATION_)?(\d+)_(\d+)", name)
    if m:
        return int(m.group(1)), int(m.group(2))
    m = re.fullmatch(r"migrate(\d+)To(\d+)", name)
    if m:
        return int(m.group(1)), int(m.group(2))
    return None


def extract_add_migrations(text: str) -> list[tuple[int, int]]:
    """从 .addMigrations( 到匹配闭合括号的区间内提取全部 (from, to)。"""
    start = text.find(".addMigrations(")
    if start < 0:
        return []
    depth = 0
    for i in range(start, len(text)):
        ch = text[i]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                block = text[start : i + 1]
                break
    else:
        block = text[start:]
    return [
        (int(a), int(b))
        for a, b, c, d in ADD_MIG_TOKEN.findall(block)
        for a, b in ([(a, b)] if a else [(c, d)])
    ]


def fail(msg: str, count: list[int]) -> None:
    count[0] += 1
    print(f"  FAIL: {msg}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db-path", type=Path, default=DB_PATH, help="覆盖 MuseDb.kt 路径(自测用)")
    parser.add_argument("--test-path", type=Path, default=TEST_MIGRATION, help="覆盖迁移测试路径(自测用)")
    parser.add_argument(
        "--mutate-delete",
        metavar="FROM,TO",
        default=None,
        help="自测: 模拟从 addMigrations 删除指定迁移(逗号分隔 from,to),脚本必须 FAIL",
    )
    args = parser.parse_args()

    db_path = args.db_path
    test_path = args.test_path
    failures: list[int] = [0]

    if not db_path.is_file():
        print(f"FATAL: 未找到 {db_path}")
        return 1
    db_text = db_path.read_text(encoding="utf-8")

    version_match = VERSION_RE.search(db_text)
    if not version_match:
        print("FATAL: 无法从 MuseDb.kt 解析当前 version")
        return 1
    current_version = int(version_match.group(1))

    declared_static = {
        (int(a), int(b)): name for name, a, b in STATIC_DECL.findall(db_text)
    }
    declared_dynamic = {
        (int(a), int(b)): name for name, a, b in DYNAMIC_DECL.findall(db_text)
    }
    registered = extract_add_migrations(db_text)

    # 自测模式: 模拟删除指定迁移。
    if args.mutate_delete:
        f, t = (int(x) for x in args.mutate_delete.split(","))
        registered = [p for p in registered if p != (f, t)]
        print(f"[自测] 已模拟从 addMigrations 删除迁移 {f}->{t}")

    print(f"MuseDb version = {current_version}")
    print(f"addMigrations 注册迁移数 = {len(registered)}")
    print(f"静态声明迁移数 = {len(declared_static)} (含 from >= {current_version} 的不可达声明)")
    print(f"动态迁移声明数 = {len(declared_dynamic)}")

    registered_set = set(registered)
    reachable_static = {(f, t) for (f, t) in declared_static if f < current_version}

    print("\n[校验 A] 静态注册一致")
    for pair in sorted(registered_set & set(declared_dynamic)):
        pass  # 动态迁移见校验 C
    missing_decl = sorted(registered_set - set(declared_static) - set(declared_dynamic))
    for pair in missing_decl:
        fail(f"addMigrations 引用了未声明/未定义的迁移 {pair}", failures)
    unregistered = sorted(reachable_static - registered_set)
    for pair in unregistered:
        fail(f"静态迁移 {pair} 已声明但未注册进 addMigrations", failures)
    if not missing_decl and not unregistered:
        print("  PASS: addMigrations 引用全部有声明, 可达静态迁移全部已注册")

    print("\n[校验 B] 链完整(from 覆盖 1..N-1)")
    froms = {f for f, t in registered}
    missing_from = [v for v in range(1, current_version) if v not in froms]
    for v in missing_from:
        fail(f"版本 {v} 没有出发迁移(断链), 升级路径从 {v} 起不可达", failures)
    for f, t in sorted(registered):
        if not (f < t <= current_version):
            fail(f"迁移 {f}->{t} 非法: 需满足 from < to <= {current_version}", failures)
    if not missing_from:
        print(f"  PASS: from 覆盖 1..{current_version - 1} 全链, 无断链版本")

    print("\n[校验 C] 动态迁移显式测试")
    test_text = test_path.read_text(encoding="utf-8") if test_path.is_file() else ""
    tested = {
        (int(a), int(b)) if a else (int(c), int(d))
        for a, b, c, d in TEST_REF.findall(test_text)
    }
    dynamic_registered = sorted(set(registered) & set(declared_dynamic))
    for pair in dynamic_registered:
        if pair not in tested:
            fail(f"动态迁移 {pair} 已注册但 MuseDbMigrationTest 未显式引用", failures)
    if not dynamic_registered:
        print("  PASS: 无动态迁移注册(空校验)")
    elif all(p in tested for p in dynamic_registered):
        print(f"  PASS: {len(dynamic_registered)} 个动态迁移均有显式测试引用")

    print("\n[校验 D] 手动链回归测试存在")
    if TEST_MANUAL_CHAIN.is_file():
        chain_text = TEST_MANUAL_CHAIN.read_text(encoding="utf-8")
        if "declaredFields" in chain_text and "MIGRATION_" in chain_text:
            print("  PASS: MuseDbManualChainMigrationTest 存在且含反射枚举")
        else:
            fail("MuseDbManualChainMigrationTest 缺失 declaredFields 反射枚举", failures)
    else:
        fail("MuseDbManualChainMigrationTest 不存在, 静态迁移失去执行覆盖", failures)

    print()
    if failures[0] == 0:
        print(f"PASS: 迁移链硬校验通过(version {current_version}, {len(registered)} 迁移)")
        return 0
    print(f"FAIL: {failures[0]} 项迁移链校验失败")
    return 1


if __name__ == "__main__":
    sys.exit(main())
