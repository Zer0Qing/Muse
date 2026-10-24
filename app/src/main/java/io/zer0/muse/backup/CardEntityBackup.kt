package io.zer0.muse.backup

import io.zer0.common.Logger
import io.zer0.muse.data.session.MuseDb

/**
 * `card_entities` 的备份读写收口。
 *
 * ## 为什么这里没有列清单
 *
 * 上一版走的是手写 SQL + 手写列清单（`CardEntityTable`），原因是 DAO 缺全量查询与清空。
 * 代价是实体加列时若忘了同步列清单，新列会在备份里静默丢失，只能靠反射测试拦。
 * 现在 [io.zer0.muse.data.card.CardEntityDao] 补齐了 `getAll()` / `insertAll()` /
 * `deleteAll()` / `count()`，读写全部回到 Room 生成的映射：列清单只有一个真源
 * （`CardEntity` 的 `@ColumnInfo`），加字段不需要再改备份侧任何文件。
 *
 * 这里只留三件 Room 给不了的事：批量与线格式之间的转换、停靠槽位预校验（让坏备份
 * 报出可读原因而不是 `UNIQUE constraint failed`）、以及恢复后的行数对账。
 *
 * ## 事务
 *
 * 全部 suspend，调用方负责包在 `db.withTransaction { }` 里（与其它实体的恢复路径同口径）。
 * 走 DAO 而不是 `openHelper.writableDatabase`：Room 会把 DAO 调用调度到事务的
 * 那条连接上，也照常触发表级失效通知，恢复后停靠区的 Flow 订阅会刷新。
 */

/**
 * 恢复后对卡片做一次核对：行数不足即视为失败并抛出，让整个恢复事务回滚。
 *
 * 与 `BackupService` 的 B-34 对账分工：那边按 meta 声明做通用核对，这里只补一条
 * 卡片特有的、能直接指出问题来源的信息（停靠卡是否真落库）。
 * 取数失败不阻断导入，只记日志（与既有对账同口径）。
 */
internal suspend fun verifyCardEntitiesRestored(db: MuseDb, expected: Int) {
    if (expected <= 0) return
    val actual = runCatching { db.cardEntityDao().count() }
        .onFailure { Logger.w("BackupService", "卡片入库对账取数失败: ${it.message}", it) }
        .getOrNull() ?: return
    check(actual >= expected) {
        "备份入库数不一致: cardEntities 声明 $expected, 实际入库 $actual"
    }
}

/**
 * 整表替换（单 JSON 恢复路径用）：先校验槽位，再清空，最后批量写回。
 *
 * 校验放在清空之前 —— 事务回滚虽然也能兜住，但「先确认数据没坏再动手删」能让
 * 失败路径的日志更直接指向备份文件本身。清空是必须的：恢复语义是整体替换，
 * 不合并（与其它所有实体一致），否则旧卡会与恢复出来的卡共存在停靠区。
 *
 * @return 实际写回的卡片数（供对账使用）。
 */
internal suspend fun MuseDb.replaceCardEntities(records: List<CardEntityRecord>): Int {
    val entities = records.map { it.toEntity() }
    requireNoDuplicateDockSlots(entities)
    cardEntityDao().deleteAll()
    if (entities.isNotEmpty()) cardEntityDao().insertAll(entities)
    return entities.size
}

/**
 * 追加写回一批卡片（NDJSON 流式恢复用，调用方已在恢复起点清空整表）。
 *
 * 每批单独校验槽位：唯一索引在 SQLite 层会拒绝重复槽位，但那条错误只写
 * `UNIQUE constraint failed`，排障时看不出是备份数据坏了。跨批次重复也能被挡住，
 * 因为校验基于该批次的行，而跨批次重复会在插入时撞唯一索引 —— 那种情况属于
 * 备份文件自相矛盾，报约束错误比悄悄覆盖一张卡更好。
 */
internal suspend fun MuseDb.appendCardEntities(records: List<CardEntityRecord>) {
    if (records.isEmpty()) return
    val entities = records.map { it.toEntity() }
    requireNoDuplicateDockSlots(entities)
    cardEntityDao().insertAll(entities)
}
