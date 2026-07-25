package io.zer0.muse.data.session

import androidx.room.Entity
import androidx.room.Fts4

/**
 * Phase 10.3: 消息全文索引虚拟表(Fts4)。
 *
 * 设计要点:
 * - 使用 FTS4(非 FTS5): Room 2.8 的 @Fts4 注解稳定支持,FTS5 需 raw SQL 且 schema 校验复杂。
 * - 不使用内置 tokenizer(unicode61/icu): 中文分词由 [MessageFtsManager.toNgram] 预处理,
 *   索引存的已是 2-gram 滑窗结果,FTS4 默认按空白切分即可。
 * - 不用 contentEntity(外部内容表): 简化同步逻辑,独立维护索引(写入/删除由 SessionRepository 触发)。
 * - rowid 由 FTS4 自动管理,entity 无显式主键字段。
 *
 * 同步策略:
 * - [SessionRepository.appendMessage]: 新消息 → insertFts(toNgram(content))
 * - [SessionRepository.upsertMessage]: 流式更新 → deleteFts + insertFts(幂等)
 * - [SessionRepository.truncateFrom] / [deleteSession]: 级联删 FTS
 * - [SessionRepository.ensureFtsIndexConsistent]: app 启动时比较 count,不一致则全量 rebuild
 */
@Entity(tableName = "messages_fts")
@Fts4
data class MessageFtsEntity(
    /** 关联的 messages.id(UUID 字符串)。删除/更新时按此字段定位。 */
    val message_id: String,
    /** 已 ngram 化的内容(2-gram 滑窗 + ASCII 小写词保留)。 */
    val content_ngram: String,
)
