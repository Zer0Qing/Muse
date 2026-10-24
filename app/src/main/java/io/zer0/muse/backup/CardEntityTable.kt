package io.zer0.muse.backup

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteStatement
import io.zer0.common.Logger
import io.zer0.muse.data.card.CardEntity
import io.zer0.muse.data.session.MuseDb

/**
 * `card_entities` 的备份读写通道。
 *
 * ## 为什么不用 DAO
 *
 * [io.zer0.muse.data.card.CardEntityDao] 只有按 id / 按来源消息查询、`upsert` 与槽位更新，
 * 没有全量 `getAll()`，也没有 `deleteAll()` —— 而备份导出要全量、恢复要先清空。
 * 本轮卡片域正在并行改造（`CardEntity.kt` 属于别人的改动面），不在这里加 DAO 方法，
 * 避免两个改动方在同一个文件上打架。
 *
 * 代价要说清：这里是**手写列清单**，不是 Room 生成的映射。实体加列时若忘了同步
 * [COLUMNS]，新列会在备份里静默丢失 —— 所以 `BackupCardEntitiesTest` 用反射比对
 * `CardEntity` 的字段与 [COLUMNS]，漏一列就红。 *
 * ## 事务与失效通知
 *
 * 三个写/读函数都是阻塞式、非挂起，调用方必须在 `db.withTransaction { }` 里调用。
 * 不切 `Dispatchers.IO`：Room 的事务绑在事务调度器上，切走会在事务外另起连接，
 * 既看不到未提交的行，也可能直接死锁。
 *
 * 语句走同一个 `SupportSQLiteDatabase`，因此 Room 的表级失效触发器照常触发，
 * 恢复后停靠区的 Flow 订阅会刷新；DML 用 `compileStatement` 而不是 `execSQL`，
 * 遵守 Android 16 对 execSQL DML 的限制（与库内其它恢复路径同口径）。
 */
internal object CardEntityTable {
    const val NAME = "card_entities"

    /**
     * 列清单，顺序即 [bindCard] 的绑定顺序。
     *
     * 与 `CardEntity` 的 `@ColumnInfo` 列名一致（注意 `isSvg` 存成 INTEGER 0/1）。
     */
    val COLUMNS = listOf(
        "id",
        "legacyCardId",
        "originSessionId",
        "originMessageId",
        "title",
        "code",
        "isSvg",
        "dataJson",
        "dockSlot",
        "dockedAt",
        "createdAt",
        "updatedAt",
    )

    val SELECT_SQL: String = buildString {
        append("SELECT ")
        append(COLUMNS.joinToString(", ") { "`$it`" })
        append(" FROM `$NAME`")
    }

    val INSERT_SQL: String = buildString {
        append("INSERT OR REPLACE INTO `$NAME` (")
        append(COLUMNS.joinToString(", ") { "`$it`" })
        append(") VALUES (")
        append(COLUMNS.joinToString(", ") { "?" })
        append(")")
    }

    const val DELETE_SQL: String = "DELETE FROM `card_entities`"

    const val COUNT_SQL: String = "SELECT COUNT(*) FROM `card_entities`"

    /**
     * 绑定顺序与 [COLUMNS] 严格一致（下标从 1 开始）。
     *
     * 放在表定义里而不是文件顶层：列清单与绑定语句必须同生共死，
     * 分开住就会有人只改一个。
     */
    fun bindCard(statement: SupportSQLiteStatement, card: CardEntity) {
        statement.bindString(1, card.id)
        statement.bindNullableString(2, card.legacyCardId)
        statement.bindNullableString(3, card.originSessionId)
        statement.bindNullableString(4, card.originMessageId)
        statement.bindString(5, card.title)
        statement.bindString(6, card.code)
        statement.bindLong(7, if (card.isSvg) 1L else 0L)
        statement.bindNullableString(8, card.dataJson)
        card.dockSlot?.let { statement.bindLong(9, it.toLong()) } ?: statement.bindNull(9)
        card.dockedAt?.let { statement.bindLong(10, it) } ?: statement.bindNull(10)
        statement.bindLong(11, card.createdAt)
        statement.bindLong(12, card.updatedAt)
    }

    private fun SupportSQLiteStatement.bindNullableString(index: Int, value: String?) {
        if (value == null) bindNull(index) else bindString(index, value)
    }
}

/** 读出全部卡片行（备份导出用）。调用方必须在 Room 事务内。 */
internal fun MuseDb.readAllCardEntities(): List<CardEntity> {
    val rows = mutableListOf<CardEntity>()
    openHelper.writableDatabase.query(CardEntityTable.SELECT_SQL).use { cursor ->
        while (cursor.moveToNext()) rows += cursor.toCardEntity()
    }
    return rows
}

/** 恢复前清空整表。调用方必须在 Room 事务内。 */
internal fun MuseDb.deleteAllCardEntities() {
    openHelper.writableDatabase.compileStatement(CardEntityTable.DELETE_SQL).use { statement ->
        statement.executeUpdateDelete()
    }
}

/**
 * 逐条写回卡片。调用方必须在 Room 事务内。
 *
 * 用 `INSERT OR REPLACE` 而不是 `INSERT`：主键是 UUID，同一次恢复内出现重复 id
 * 属于坏数据；替换语义让这类脏行覆盖而不是让整次恢复因约束失败中断。
 * 真正的完整性由 [verifyCardEntitiesRestored] 与 B-34 对账负责。
 */
internal fun MuseDb.insertCardEntities(cards: List<CardEntity>) {
    if (cards.isEmpty()) return
    openHelper.writableDatabase.compileStatement(CardEntityTable.INSERT_SQL).use { statement ->
        cards.forEach { card ->
            statement.clearBindings()
            CardEntityTable.bindCard(statement, card)
            statement.executeInsert()
        }
    }
}

/** 表内真实行数（恢复后对账用）。调用方必须在 Room 事务内。 */
internal fun MuseDb.countCardEntities(): Int = openHelper.writableDatabase.query(CardEntityTable.COUNT_SQL).use { cursor ->
    if (cursor.moveToFirst()) cursor.getInt(0) else 0
}

private fun Cursor.toCardEntity(): CardEntity = CardEntity(
    id = getString(0),
    legacyCardId = nullableString(1),
    originSessionId = nullableString(2),
    originMessageId = nullableString(3),
    title = getString(4),
    code = getString(5),
    isSvg = getInt(6) != 0,
    dataJson = nullableString(7),
    dockSlot = nullableInt(8),
    dockedAt = nullableLong(9),
    createdAt = getLong(10),
    updatedAt = getLong(11),
)

private fun Cursor.nullableString(index: Int): String? = if (isNull(index)) null else getString(index)

private fun Cursor.nullableInt(index: Int): Int? = if (isNull(index)) null else getInt(index)

private fun Cursor.nullableLong(index: Int): Long? = if (isNull(index)) null else getLong(index)

/**
 * 恢复后对卡片做一次核对：行数不足即视为失败并抛出，让整个恢复事务回滚。
 *
 * 与 [BackupService] 的 B-34 对账分工：那边按 meta 声明做通用核对，这里只补一条
 * 卡片特有的、能直接指出问题来源的信息（停靠卡是否真落库）。
 * 取数失败不阻断导入，只记日志（与既有对账同口径）。
 */
internal fun verifyCardEntitiesRestored(db: MuseDb, expected: Int) {
    if (expected <= 0) return
    val actual = runCatching { db.countCardEntities() }
        .onFailure { Logger.w("BackupService", "卡片入库对账取数失败: ${it.message}", it) }
        .getOrNull() ?: return
    check(actual >= expected) {
        "备份入库数不一致: cardEntities 声明 $expected, 实际入库 $actual"
    }
}
