package io.zer0.muse.data.session

/**
 * R-DB-05: messages_fts 建表 SQL 与 FTS5 external content 触发器。
 *
 * - FTS5 可用:external content 指向 messages,索引原文并使用 SQLite `snippet()`
 *   生成高亮片段;通过触发器与 messages 表增删改同步,避免手动同步漂移。
 * - FTS5 不可用:回退现有 FTS4 + content_ngram 方案,继续由 SessionRepository
 *   手动 delete + insert ngram 维护。
 */
internal object MessageFtsDdl {

    private val FTS5_SQL = """
        CREATE VIRTUAL TABLE IF NOT EXISTS `messages_fts` USING fts5(
            content,
            content='messages',
            content_rowid='rowid',
            tokenize='unicode61'
        )
    """.trimIndent()

    private val FTS4_SQL = """
        CREATE VIRTUAL TABLE IF NOT EXISTS `messages_fts` USING FTS4(
            `message_id` TEXT,
            `content_ngram` TEXT
        )
    """.trimIndent()

    /** FTS5 external content 触发器(与 messages 表增删改同步)。 */
    val fts5TriggerSqls: List<String> = listOf(
        """
        CREATE TRIGGER IF NOT EXISTS messages_fts_ai
        AFTER INSERT ON messages BEGIN
            INSERT INTO messages_fts(rowid, content) VALUES (new.rowid, new.content);
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS messages_fts_ad
        AFTER DELETE ON messages BEGIN
            INSERT INTO messages_fts(messages_fts, rowid, content)
            VALUES ('delete', old.rowid, old.content);
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS messages_fts_au
        AFTER UPDATE OF content ON messages BEGIN
            INSERT INTO messages_fts(messages_fts, rowid, content)
            VALUES ('delete', old.rowid, old.content);
            INSERT INTO messages_fts(rowid, content) VALUES (new.rowid, new.content);
        END
        """.trimIndent(),
    )

    fun createSql(useFts5: Boolean): String = if (useFts5) FTS5_SQL else FTS4_SQL
}
