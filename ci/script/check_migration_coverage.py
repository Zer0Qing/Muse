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
FACT_DB_PATH = REPO_ROOT / "memory/src/main/java/io/zer0/memory/fact/FactDb.kt"
MEMORY_DB_PATH = REPO_ROOT / "memory/src/main/java/io/zer0/memory/summary/MemoryDb.kt"
MEMORY_DB_BUILDER_PATH = REPO_ROOT / "memory/src/main/java/io/zer0/memory/MemoryModule.kt"
MEMORY_DB_TEST = REPO_ROOT / "memory/src/test/java/io/zer0/memory/summary/MemoryDbMigrationTest.kt"
APP_SOURCE_ROOT = REPO_ROOT / "app/src/main/java"

# 68 -> 74 is retained as a historical dead declaration. The live chain uses
# 68 -> 69, so registering both would make Room choose the wrong branch.
KNOWN_UNREGISTERED_MUSE_MIGRATIONS = {(68, 74)}

STATIC_DECL = re.compile(r"val\s+(MIGRATION_(\d+)_(\d+))\s*=\s*object\s*:\s*Migration")
DYNAMIC_DECL = re.compile(r"fun\s+(migrate(\d+)To(\d+))\b")
ADD_MIG_TOKEN = re.compile(r"\b(?:MIGRATION_(\d+)_(\d+)|migrate(\d+)To(\d+))\b")
TEST_REF = re.compile(r"MuseDb\.(?:MIGRATION_(\d+)_(\d+)|migrate(\d+)To(\d+))")
VERSION_RE = re.compile(r"version\s*=\s*(\d+)")
# `version = SOME_CONST` 形式(如 FactDb 的 FACT_DB_VERSION)与其常量声明
CONST_VERSION_RE = re.compile(r"version\s*=\s*([A-Za-z_][A-Za-z0-9_]*)")
MIGRATION_LIST_START = re.compile(
    r"(?m)^\s*(?:internal\s+|private\s+|public\s+)?fun\s+migrations\s*\([^)]*\)\s*"
    r":\s*List\s*<\s*Migration\s*>\s*=\s*listOf\s*\(",
)


def resolve_current_version(db_text: str) -> int | None:
    """解析数据库当前 version,支持字面量与常量两种写法。

    - 字面量:`version = 105`
    - 常量:  `version = FACT_DB_VERSION` → 回溯 `const val FACT_DB_VERSION = 14`

    早期只匹配字面量,FactDb 改用常量后会被误报"无法解析当前 version"(口径问题,非链断)。
    """
    literal = re.search(r"\bversion\s*=\s*(\d+)", db_text)
    if literal:
        return int(literal.group(1))
    ref = re.search(r"\bversion\s*=\s*([A-Za-z_][A-Za-z0-9_.]*)", db_text)
    if not ref:
        return None
    symbol = re.escape(ref.group(1).rsplit(".", 1)[-1])
    decl = re.search(rf"const\s+val\s+{symbol}\s*=\s*(\d+)", db_text)
    return int(decl.group(1)) if decl else None


def name_to_pair(name: str) -> tuple[int, int] | None:
    """'MIGRATION_68_74' -> (68, 74); 'migrate75To76' -> (75, 76)."""
    m = re.fullmatch(r"(?:MIGRATION_)?(\d+)_(\d+)", name)
    if m:
        return int(m.group(1)), int(m.group(2))
    m = re.fullmatch(r"migrate(\d+)To(\d+)", name)
    if m:
        return int(m.group(1)), int(m.group(2))
    return None


def _balanced_call_block(text: str, start: int) -> str:
    """Return a parenthesized call/list block beginning at ``start``."""
    opening = text.find("(", start)
    if opening < 0:
        return ""
    depth = 0
    for index in range(opening, len(text)):
        ch = text[index]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return text[opening : index + 1]
    return text[opening:]


def _extract_migration_tokens(block: str) -> list[tuple[int, int]]:
    """Extract migration names while ignoring Kotlin comments."""
    without_comments = re.sub(r"/\*.*?\*/", "", block, flags=re.DOTALL)
    without_comments = re.sub(r"//[^\r\n]*", "", without_comments)
    return [
        (int(a), int(b))
        for a, b, c, d in ADD_MIG_TOKEN.findall(without_comments)
        for a, b in ([(a, b)] if a else [(c, d)])
    ]


def _collect_external_static_migrations() -> set[tuple[int, int]]:
    """Collect migrations declared in app-owned schema helper objects.

    Knowledge schema migrations live beside MuseDb but are registered through
    the single MuseDb list with qualified names, so they are not declarations
    in MuseDb.kt itself. They still must be checked as valid registration
    targets, while their dedicated module tests remain responsible for test
    coverage.
    """
    pairs: set[tuple[int, int]] = set()
    if not APP_SOURCE_ROOT.is_dir():
        return pairs
    for source in APP_SOURCE_ROOT.rglob("*.kt"):
        try:
            text = source.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        pairs.update((int(a), int(b)) for _, a, b in STATIC_DECL.findall(text))
    return pairs


def extract_add_migrations(text: str) -> list[tuple[int, int]]:
    """从 Room 注册入口提取全部迁移。

    旧代码把迁移对象直接列在 ``addMigrations(...)`` 参数中；当前代码先
    构造 ``migrations(context): List<Migration> = listOf(...)``，再把包装后的
    vararg 传入 Room。两种写法都必须被门禁识别，否则真实注册链会被误报为空。
    """
    start = text.find(".addMigrations(")
    direct = _extract_migration_tokens(_balanced_call_block(text, start)) if start >= 0 else []
    if direct:
        return direct

    migration_list = MIGRATION_LIST_START.search(text)
    if migration_list is None:
        return []
    return _extract_migration_tokens(_balanced_call_block(text, migration_list.end() - 1))


def fail(msg: str, count: list[int]) -> None:
    count[0] += 1
    print(f"  FAIL: {msg}")


def check_extra_db_static_chain(
    db_path: Path,
    db_name: str,
    min_version: int,
    failures: list[int],
    registration_path: Path | None = None,
    test_path: Path | None = None,
) -> None:
    """对其他数据库(如 FactDb、MemoryDb)做校验 A+B(静态注册一致 + 链完整)。

    从 min_version 起校验链完整性:部分库(如 FactDb v1/v2)是历史遗留版本,
    由业务层兜底(archiveLegacyOrCorruptDatabase)处理,不在迁移链内,故不判为断链。
    """
    print(f"\n===== {db_name} 迁移链校验 =====")
    initial_failure_count = failures[0]
    if not db_path.is_file():
        fail(f"{db_name}: 未找到 {db_path}", failures)
        return
    db_text = db_path.read_text(encoding="utf-8")
    current_version = resolve_current_version(db_text)
    if current_version is None:
        fail(f"{db_name}: 无法解析当前 version", failures)
        return
    declared_static = {
        (int(a), int(b)): name for name, a, b in STATIC_DECL.findall(db_text)
    }
    registration_file = registration_path or db_path
    if not registration_file.is_file():
        fail(f"{db_name}: 未找到 migration builder {registration_file}", failures)
        return
    registration_text = registration_file.read_text(encoding="utf-8")
    registered = extract_add_migrations(registration_text)
    registered_set = set(registered)
    reachable_static = {
        (f, t)
        for (f, t) in declared_static
        if f < current_version and (f, t) not in KNOWN_UNREGISTERED_MUSE_MIGRATIONS
    }
    print(f"{db_name} version = {current_version}, 注册迁移数 = {len(registered)} (min 版本 {min_version})")

    missing_decl = sorted(registered_set - set(declared_static))
    for pair in missing_decl:
        fail(f"{db_name} addMigrations 引用了未声明迁移 {pair}", failures)
    unregistered = sorted(reachable_static - registered_set)
    for pair in unregistered:
        fail(f"{db_name} 静态迁移 {pair} 已声明但未注册进 addMigrations", failures)

    froms = {f for f, t in registered}
    missing_from = [v for v in range(min_version, current_version) if v not in froms]
    for v in missing_from:
        fail(f"{db_name} 版本 {v} 没有出发迁移(断链)", failures)
    for f, t in sorted(registered):
        if not (f < t <= current_version):
            fail(f"{db_name} 迁移 {f}->{t} 非法: 需满足 from < to <= {current_version}", failures)

    if test_path is not None:
        if not test_path.is_file():
            fail(f"{db_name}: 未找到迁移测试 {test_path}", failures)
        else:
            test_text = test_path.read_text(encoding="utf-8")
            tested = {
                (int(from_version), int(to_version))
                for from_version, to_version in re.findall(
                    rf"\b{re.escape(db_name)}\.MIGRATION_(\d+)_(\d+)\b",
                    test_text,
                )
            }
            for pair in sorted(registered_set & set(declared_static)):
                if pair not in tested:
                    fail(f"{db_name} 迁移 {pair} 未被 {test_path.name} 显式引用", failures)

    if failures[0] == initial_failure_count:
        print(f"  PASS: {db_name} 静态迁移链完整(无断链)")


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

    current_version = resolve_current_version(db_text)
    if current_version is None:
        print("FATAL: 无法从 MuseDb.kt 解析当前 version")
        return 1

    declared_static = {
        (int(a), int(b)): name for name, a, b in STATIC_DECL.findall(db_text)
    }
    external_static = _collect_external_static_migrations() - set(declared_static)
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
    reachable_static = {
        (f, t)
        for (f, t) in declared_static
        if f < current_version and (f, t) not in KNOWN_UNREGISTERED_MUSE_MIGRATIONS
    }

    print("\n[校验 A] 静态注册一致")
    for pair in sorted(registered_set & set(declared_dynamic)):
        pass  # 动态迁移见校验 C
    missing_decl = sorted(registered_set - set(declared_static) - external_static - set(declared_dynamic))
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

    check_extra_db_static_chain(FACT_DB_PATH, "FactDb", 3, failures)
    check_extra_db_static_chain(
        MEMORY_DB_PATH,
        "MemoryDb",
        1,
        failures,
        registration_path=MEMORY_DB_BUILDER_PATH,
        test_path=MEMORY_DB_TEST,
    )

    print()
    if failures[0] == 0:
        print(f"PASS: 迁移链硬校验通过(version {current_version}, {len(registered)} 迁移)")
        return 0
    print(f"FAIL: {failures[0]} 项迁移链校验失败")
    return 1


if __name__ == "__main__":
    sys.exit(main())
