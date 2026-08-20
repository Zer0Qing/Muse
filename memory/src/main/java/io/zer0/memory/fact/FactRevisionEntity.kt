package io.zer0.memory.fact

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v13 (T4-1): 事实修订记录 — 关键记忆的变更历史,支持审计与回滚。
 *
 * 记录条件(FactStore 写入时判定):
 *  - 重要性 ≥ 1(重要/关键事实)的 update 与合并
 *  - 或实体键命中(同实体合并)的合并操作
 * 普通事实(importance=0)的日常更新不记录,避免表膨胀。
 *
 * 用途:
 *  - 用户可查看某条记忆的演变历史
 *  - 合并/更新误操作后可回滚到旧值
 *  - 反思任务合并时保留被合并方原文(可追溯)
 */
@Entity(
    tableName = "fact_revisions",
    indices = [
        Index(value = ["fact_id"], name = "idx_fact_revisions_fact_id"),
        Index(value = ["changed_at"], name = "idx_fact_revisions_changed_at"),
    ],
)
data class FactRevisionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 被修改的事实 id(facts.id,不加外键避免级联损耗)。 */
    @ColumnInfo(name = "fact_id")
    val factId: Long,

    /** 修改前内容(可为空: 新增合并无旧值场景)。 */
    @ColumnInfo(name = "old_content")
    val oldContent: String,

    /** 修改后内容。 */
    @ColumnInfo(name = "new_content")
    val newContent: String,

    /** 变更时间 ISO 8601。 */
    @ColumnInfo(name = "changed_at")
    val changedAt: String,

    /** 变更原因(update/merge/reflection 等)。 */
    @ColumnInfo(name = "reason")
    val reason: String,
)
