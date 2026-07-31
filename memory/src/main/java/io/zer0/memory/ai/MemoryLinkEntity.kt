package io.zer0.memory.ai

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * v1.0.52 P2-3: 记忆知识图谱边实体(借鉴 Operit MemoryLibrary links)。
 *
 * 表示两条事实之间的关系(如因果/包含/矛盾),用于构建用户记忆的知识图谱。
 * 与 [io.zer0.memory.fact.FactEntity] 通过 source_fact_id / target_fact_id 关联。
 *
 * v10 schema: 新增 memory_links 表。
 *
 * 设计:
 *  - source_fact_id / target_fact_id: 指向 facts.id(外键,但不加 FOREIGN KEY 约束,
 *    避免跨表级联删除性能损耗;由应用层 [MemoryLinkDao.deleteByFactId] 在事实删除时清理)
 *  - link_type: 关系类型(causes/explains/part_of/related_to/contradicts)
 *  - weight: 关系强度 0.0~1.0
 *  - space_id / scope: 与 facts 表对齐,支持多 Space + 多 Agent 隔离
 *
 * source_title / target_title 为冗余字段,用于事实被删除后仍能展示关系语义
 * (避免 JOIN 失败后关系变成无意义数字 id)。
 */
@Serializable
@Entity(
    tableName = "memory_links",
    indices = [
        Index(value = ["source_fact_id"], name = "idx_memory_links_source"),
        Index(value = ["target_fact_id"], name = "idx_memory_links_target"),
        Index(value = ["space_id"], name = "idx_memory_links_space"),
        Index(value = ["scope"], name = "idx_memory_links_scope"),
    ],
)
data class MemoryLinkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 源事实 id(指向 facts.id)。 */
    @ColumnInfo(name = "source_fact_id")
    val sourceFactId: Long,

    /** 目标事实 id(指向 facts.id)。 */
    @ColumnInfo(name = "target_fact_id")
    val targetFactId: Long,

    /** 源事实标题(冗余,事实删除后仍可展示关系语义)。 */
    @ColumnInfo(name = "source_title")
    val sourceTitle: String,

    /** 目标事实标题(冗余)。 */
    @ColumnInfo(name = "target_title")
    val targetTitle: String,

    /** 关系类型:causes / explains / part_of / related_to / contradicts。 */
    @ColumnInfo(name = "link_type", defaultValue = "related_to")
    val linkType: String = "related_to",

    /** 关系强度 0.0~1.0(默认 0.5)。 */
    @ColumnInfo(name = "weight", defaultValue = "0.5")
    val weight: Float = 0.5f,

    /** 记忆空间 id(与 facts.space_id 对齐,多 Space 隔离)。 */
    @ColumnInfo(name = "space_id", defaultValue = "default")
    val spaceId: String = "default",

    /** 记忆作用域(与 facts.scope 对齐,多 Agent 隔离)。 */
    @ColumnInfo(name = "scope", defaultValue = "main")
    val scope: String = "main",

    /** 创建时间 ISO 8601。 */
    @ColumnInfo(name = "created_at")
    val createdAt: String,
)
