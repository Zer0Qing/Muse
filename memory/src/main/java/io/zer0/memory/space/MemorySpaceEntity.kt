package io.zer0.memory.space

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * v1.0.52 P2-2: 记忆空间实体(多 Space 隔离,类似 Notion 工作区)。
 *
 * 每个 Space 是一个独立的记忆库,用户可在不同场景(工作/生活/学习)切换,
 * 互不干扰。facts 表通过 space_id 字段关联到 [MemorySpaceEntity]。
 *
 * 与 [io.zer0.memory.fact.FactEntity.scope] 的关系:
 *  - scope: 按 Agent 隔离(主助手 "main" / 子助手 assistantId / 团队成员)
 *  - space_id: 按用户场景隔离(工作 / 生活 / 学习)
 *  - 两者正交,一个 fact 既属于某 Agent scope,也属于某 Space
 *
 * v9 schema: 新增 memory_spaces 表,与 facts 表关联。
 *
 * @param id Space 唯一 id(如 "default" / "work" / "life")
 * @param name 显示名称(如 "默认" / "工作" / "生活")
 * @param icon 图标标识(可选,用于 UI 展示,如 "work" / "home" / "school")
 * @param description 描述(可选,用户可填写 Space 用途)
 * @param createdAt 创建时间 ISO 8601
 * @param sortIndex 排序序号(用户可调整 Space 顺序,小的在前)
 */
@Serializable
@Entity(
    tableName = "memory_spaces",
    indices = [
        Index(value = ["sort_index"], name = "idx_memory_spaces_sort"),
    ],
)
data class MemorySpaceEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "icon")
    val icon: String? = null,

    @ColumnInfo(name = "description", defaultValue = "")
    val description: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: String,

    @ColumnInfo(name = "sort_index", defaultValue = "0")
    val sortIndex: Int = 0,
) {
    companion object {
        /** 默认 Space id(首次启动 + 迁移现有数据用)。 */
        const val DEFAULT_SPACE_ID = "default"

        /** 默认 Space 名称。 */
        const val DEFAULT_SPACE_NAME = "默认"

        /** 默认 Space 图标。 */
        const val DEFAULT_SPACE_ICON = "bookmark"
    }
}

/**
 * Space + 关联事实数量(LEFT JOIN COUNT 结果)。
 *
 * 用于 UI 列表展示每个 Space 的事实数,不持久化。
 */
data class MemorySpaceWithCount(
    @androidx.room.ColumnInfo(name = "id")
    val id: String,
    @androidx.room.ColumnInfo(name = "name")
    val name: String,
    @androidx.room.ColumnInfo(name = "icon")
    val icon: String?,
    @androidx.room.ColumnInfo(name = "description")
    val description: String,
    @androidx.room.ColumnInfo(name = "created_at")
    val createdAt: String,
    @androidx.room.ColumnInfo(name = "sort_index")
    val sortIndex: Int,
    @androidx.room.ColumnInfo(name = "fact_count")
    val factCount: Int,
)
