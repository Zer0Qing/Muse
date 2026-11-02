package io.zer0.muse.data.notification

import io.zer0.common.Logger
import kotlinx.serialization.Serializable
import java.io.File

/**
 * 通知历史的**有界**存储（对比清单 P2-02「通知历史」）。
 *
 * 现状：`MuseNotificationListenerService` 只维护"当前还在的通知"，用户问
 * "今天上午有什么通知"时什么都答不上来 —— 而这是最自然的问法之一。
 *
 * 三条约束，都是"宁可少留"：
 *  1. **条数上限**：只保留最近 [MAX_ENTRIES] 条；
 *  2. **保留期限**：超过 [SensitiveDataPolicy 的通知类别] 天数的自动清理；
 *  3. **单条截断**：标题/正文各自封顶，避免一条长推送占满存储。
 *
 * 存储用 JSONL（一行一条）而不是数据库：这类数据不需要查询能力，
 * 而引入 Room 表意味着一次 schema 迁移 —— 代价远大于收益。
 */
class NotificationHistoryStore(
    private val file: File,
    private val maxEntries: Int = MAX_ENTRIES,
) {

    @Serializable
    data class Entry(
        val atMillis: Long,
        val packageName: String,
        val title: String = "",
        val text: String = "",
    )

    /** 追加一条。写失败只记日志：通知历史是附加能力，不能影响通知本身。 */
    @Synchronized
    fun append(entry: Entry) {
        val normalized = entry.copy(
            title = entry.title.trim().take(MAX_TITLE_CHARS),
            text = entry.text.trim().take(MAX_TEXT_CHARS),
        )
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(io.zer0.common.AppJson.encodeToString(Entry.serializer(), normalized) + "\n")
        }.onFailure { Logger.w(TAG, "写入通知历史失败: ${it.message}") }
    }

    /** 最近的条目（最新在前），并按条数上限裁剪。 */
    @Synchronized
    fun recent(limit: Int = DEFAULT_QUERY_LIMIT): List<Entry> {
        // 按时间排序而不是依赖追加顺序：时钟被调整过之后，文件里的顺序可能与时间不一致
        return readAll()
            .sortedBy { it.atMillis }
            .takeLast(limit.coerceAtLeast(1))
            .reversed()
    }

    /**
     * 清理过期与超额条目。
     *
     * @return 保留下来的条数
     */
    @Synchronized
    fun prune(nowMillis: Long, retentionDays: Int = DEFAULT_RETENTION_DAYS): Int {
        val all = readAll()
        val cutoff = nowMillis - retentionDays.coerceAtLeast(1) * 24L * 60 * 60 * 1000
        val kept = all.filter { it.atMillis >= cutoff }.takeLast(maxEntries.coerceAtLeast(1))
        if (kept.size == all.size && file.length() > 0) return kept.size
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(
                kept.joinToString("") { io.zer0.common.AppJson.encodeToString(Entry.serializer(), it) + "\n" },
            )
        }.onFailure { Logger.w(TAG, "清理通知历史失败: ${it.message}") }
        return kept.size
    }

    /** 读全部条目；坏行跳过（一行坏了不该让整份历史不可用）。 */
    private fun readAll(): List<Entry> {
        if (!file.isFile) return emptyList()
        return runCatching { file.readLines() }.getOrDefault(emptyList())
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) {
                    null
                } else {
                    runCatching {
                        io.zer0.common.AppJson.decodeFromString(Entry.serializer(), trimmed)
                    }.getOrNull()
                }
            }
    }

    companion object {
        private const val TAG = "NotificationHistory"

        /** 最多保留多少条。 */
        const val MAX_ENTRIES = 500

        /** 默认查询条数。 */
        const val DEFAULT_QUERY_LIMIT = 50

        /** 默认保留天数（与敏感数据政策保持一致）。 */
        const val DEFAULT_RETENTION_DAYS = 3

        const val MAX_TITLE_CHARS = 120
        const val MAX_TEXT_CHARS = 300

        /** 默认文件名（放在应用私有目录）。 */
        const val FILE_NAME = "notification_history.jsonl"
    }
}
