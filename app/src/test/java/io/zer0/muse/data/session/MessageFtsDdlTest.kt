package io.zer0.muse.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-DB-05: messages_fts DDL 选择与 FTS5 查询词转换测试。
 */
class MessageFtsDdlTest {

    @Test
    fun `fts5 ddl uses external content and unicode61`() {
        val sql = MessageFtsDdl.createSql(useFts5 = true)
        assertTrue(sql.contains("USING fts5"))
        assertTrue(sql.contains("content='messages'"))
        assertTrue(sql.contains("tokenize='unicode61'"))
    }

    @Test
    fun `fts4 ddl keeps ngram columns`() {
        val sql = MessageFtsDdl.createSql(useFts5 = false)
        assertTrue(sql.contains("USING FTS4"))
        assertTrue(sql.contains("message_id"))
        assertTrue(sql.contains("content_ngram"))
    }

    @Test
    fun `fts5 triggers keep insert delete and update in sync`() {
        assertEquals(3, MessageFtsDdl.fts5TriggerSqls.size)
        assertTrue(MessageFtsDdl.fts5TriggerSqls[0].contains("AFTER INSERT ON messages"))
        assertTrue(MessageFtsDdl.fts5TriggerSqls[1].contains("AFTER DELETE ON messages"))
        assertTrue(MessageFtsDdl.fts5TriggerSqls[2].contains("AFTER UPDATE OF content ON messages"))
    }

    @Test
    fun `toFts5MatchQuery quotes phrases and escapes quotes`() {
        assertEquals("\"你好世界\"", MessageFtsManager.toFts5MatchQuery("你好世界"))
        assertEquals("\"hello\" \"world\"", MessageFtsManager.toFts5MatchQuery("hello  world"))
        assertEquals("\"a\" \"\"\"quote\"\"\"", MessageFtsManager.toFts5MatchQuery("a \"quote\""))
        assertEquals("", MessageFtsManager.toFts5MatchQuery("   "))
    }
}
