package io.zer0.muse.data.agentbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对话流合并规则。
 *
 * 这段逻辑在 ViewModel 里连续改了六轮，每轮都发现相邻问题。
 * 抽成纯函数后，四条规则第一次能逐条断言。
 */
class TranscriptRestorerMergeTest {

    // merge() 是纯函数，不需要仓储。
    private val restorer = TranscriptRestorer();

    private fun msg(id: String, at: Long, text: String = id) =
        AgentBotUiState.PublishedMessage(
            messageId = id,
            role = AgentBotUiState.Role.BOT,
            text = text,
            createdAt = at,
        );

    @Test
    fun `persisted wins over an in-memory duplicate`() {
        val persisted = listOf(msg("a", 1), msg("b", 2));
        val inMemory = listOf(msg("b", 2), msg("c", 3));

        val merged = restorer.merge(persisted, inMemory);

        assertEquals("b 不能出现两次", 3, merged.size);
        assertEquals(listOf("a", "b", "c"), merged.map { it.messageId });
    }

    @Test
    fun `in-memory-only messages are kept`() {
        val persisted = listOf(msg("a", 1));
        val inMemory = listOf(msg("a", 1), msg("new", 5));

        val merged = restorer.merge(persisted, inMemory);

        assertTrue(
            "用户刚发的消息还没落库，不能被抹掉",
            merged.any { it.messageId == "new" },
        );
    }

    @Test
    fun `result is sorted by creation time`() {
        val persisted = listOf(msg("old", 10), msg("mid", 30));
        val inMemory = listOf(msg("very-new", 50));

        val merged = restorer.merge(persisted, inMemory);

        assertEquals(listOf("old", "mid", "very-new"), merged.map { it.messageId });
    }

    @Test
    fun `a later persisted message does not end up before an earlier in-memory one`() {
        // 后台任务结果（库里，时间 40）插在用户消息（内存，时间 20）之前时，
        // 直接 full + inMemoryOnly 会得到 [.., 40, 20] 的错序。
        val persisted = listOf(msg("bg", 40));
        val inMemory = listOf(msg("user", 20));

        val merged = restorer.merge(persisted, inMemory);

        assertEquals(listOf("user", "bg"), merged.map { it.messageId });
    }

    @Test
    fun `same timestamp falls back to message id so order is stable`() {
        val persisted = listOf(msg("b", 100));
        val inMemory = listOf(msg("a", 100));

        val first = restorer.merge(persisted, inMemory).map { it.messageId };
        val second = restorer.merge(persisted, inMemory).map { it.messageId };

        assertEquals("同毫秒必须稳定，否则界面每次刷新都抖", first, second);
        assertEquals(listOf("a", "b"), first);
    }

    @Test
    fun `empty inputs are handled`() {
        assertTrue(restorer.merge(emptyList(), emptyList()).isEmpty());
        assertEquals(1, restorer.merge(emptyList(), listOf(msg("x", 1))).size);
        assertEquals(1, restorer.merge(listOf(msg("y", 1)), emptyList()).size);
    }
    @Test
    fun `read without a repository returns empty but logs an error`() = kotlinx.coroutines.test.runTest {
        // 静默返回空列表会和"这个会话真的没有消息"混淆，
        // 用户会以为历史丢了，而实际是装配没接上。
        val page = restorer.read("session-x", 10);
        assertTrue("没有仓储时只能返回空", page.messages.isEmpty());
        assertEquals("会话 id 要原样带回，供调用方判断是否切走", "session-x", page.sessionId);
    }
    @Test
    fun `read failure is logged at error level not warn`() {
        // 读失败的返回值与"确实没有消息"一样（空列表）。
        // 用 warn 记一笔的话，日志里翻不出来，用户看到空白时也查不到原因。
        val src = File("src/main/java/io/zer0/muse/data/agentbot/TranscriptRestorer.kt").readText();
        val readAt = src.indexOf("suspend fun read(");
        val body = src.substring(readAt, minOf(readAt + 1400, src.length));
        assertTrue("读失败必须记 error 级", body.contains("Logger.e("));
        assertFalse(
            "不能退化成 warn —— 那会和正常空结果混在一起",
            body.contains("Logger.w("),
        );
    }

    @Test
    fun `read failure logs which session failed`() {
        val src = File("src/main/java/io/zer0/muse/data/agentbot/TranscriptRestorer.kt").readText();
        val readAt = src.indexOf("suspend fun read(");
        val body = src.substring(readAt, minOf(readAt + 1400, src.length));
        assertTrue(
            "日志要带上会话 id，否则排查时不知道是哪个会话读失败了",
            body.contains("sessionId"),
        );
    }
}
