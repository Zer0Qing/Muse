package io.zer0.memory.fact

import androidx.room.Entity
import androidx.room.Fts4

/**
 * Facts FTS4 全文索引虚拟表。
 *
 * 设计要点:
 * - 使用 FTS4: Room 2.8 的 @Fts4 注解稳定支持,兼容 Android 7+ 的 SQLite。
 * - 不依赖内置 tokenizer: 中文分词由 [FactFtsManager.toNgram] 预处理,
 *   索引存的是 2-gram 滑窗结果,FTS4 按空白切分即可匹配。
 * - 独立维护索引: 写入/删除/更新由 [FactStore] 触发,与 facts 表同步。
 * - rowid 由 FTS4 自动管理,entity 无显式主键字段。
 */
@Entity(tableName = "facts_fts")
@Fts4
class FactFtsEntity(
    /** 关联的 facts.id。删除/更新时按此字段定位。 */
    val fact_id: Long,
    /** 已 ngram 化的事实内容(2-gram 滑窗 + ASCII 小写词保留)。 */
    val content_ngram: String,
)
