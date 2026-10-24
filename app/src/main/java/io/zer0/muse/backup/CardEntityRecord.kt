package io.zer0.muse.backup

import io.zer0.muse.data.card.CardEntity
import kotlinx.serialization.Serializable

/**
 * 卡片实体（`card_entities`）的备份线格式。
 *
 * 为什么不直接把 [CardEntity] 塞进 [BackupService.Backup]：Room 实体归卡片域所有，
 * 本身没有 `@Serializable`。备份侧用等比镜像 + 双向映射，好处是备份格式不和实体注解耦合，
 * 实体加列只要给镜像补默认值就能继续读旧备份。
 *
 * 字段名与 `CardEntity` 的 Kotlin 属性名一一对应（不是列名），这样读备份时
 * 和代码里的实体长得一样。表列名映射由 [CardEntityTable.COLUMNS] 单独负责。
 */
@Serializable
data class CardEntityRecord(
    /** UUID 主键，跨重启稳定的卡片身份。 */
    val id: String,
    /** 旧 `c_xxx` 卡片 id，仅存量回填用。 */
    val legacyCardId: String? = null,
    val originSessionId: String? = null,
    val originMessageId: String? = null,
    val title: String = "",
    /** HTML / SVG 源。 */
    val code: String = "",
    val isSvg: Boolean = false,
    /** 绑定数据（原 card_data.json 里的 JSON 文本）。 */
    val dataJson: String? = null,
    /** null = 仅在消息流；>= 0 = 停靠顺序。 */
    val dockSlot: Int? = null,
    val dockedAt: Long? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

internal fun CardEntity.toRecord(): CardEntityRecord = CardEntityRecord(
    id = id,
    legacyCardId = legacyCardId,
    originSessionId = originSessionId,
    originMessageId = originMessageId,
    title = title,
    code = code,
    isSvg = isSvg,
    dataJson = dataJson,
    dockSlot = dockSlot,
    dockedAt = dockedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun CardEntityRecord.toEntity(): CardEntity = CardEntity(
    id = id,
    legacyCardId = legacyCardId,
    originSessionId = originSessionId,
    originMessageId = originMessageId,
    title = title,
    code = code,
    isSvg = isSvg,
    dataJson = dataJson,
    dockSlot = dockSlot,
    dockedAt = dockedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

/**
 * 备份内的卡片顺序：停靠卡按槽位升序在前，未停靠卡按创建时间在后。
 *
 * 为什么在导出侧定序而不是靠表的物理顺序：`dockSlot` 上有唯一索引，恢复是逐条写回，
 * 顺序固定让备份文件可比对、恢复可复现；消息流内的卡（`dockSlot` 为 null）
 * 在 SQLite 里排序行为未定义，用 `createdAt` + `id` 兜底保证两次导出结果一致。
 */
internal fun List<CardEntityRecord>.inDockOrder(): List<CardEntityRecord> = sortedWith(
    compareBy<CardEntityRecord>(
        { it.dockSlot == null },
        { it.dockSlot ?: 0 },
        { it.createdAt },
        { it.id },
    ),
)

/**
 * 停靠槽位唯一性校验：两张卡抢同一个槽位会在 SQLite 层抛约束错误，
 * 但那条错误只写 "UNIQUE constraint failed"，排障时看不出是备份数据坏了。
 * 这里提前给出可读原因；恢复仍然 fail-fast 整体回滚（与既有实体同口径，不开特例）。
 */
internal fun requireNoDuplicateDockSlots(cards: List<CardEntity>) {
    val duplicates = cards
        .mapNotNull { it.dockSlot }
        .groupingBy { it }
        .eachCount()
        .filterValues { it > 1 }
        .keys
    check(duplicates.isEmpty()) {
        "备份中的卡片停靠槽位重复: ${duplicates.sorted()},拒绝恢复(槽位在同一时刻只能属于一张卡)"
    }
}
