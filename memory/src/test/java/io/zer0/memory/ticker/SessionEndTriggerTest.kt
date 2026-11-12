package io.zer0.memory.ticker

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话收尾触发合同。
 *
 * 回归背景：`notifySessionEnd` 曾以「内存轮次计数 == 0」直接早返回。
 * 计数只活在 `_turnCounts` 这个进程内 Map 里，进程重启即归零 ——
 * 于是「重启 → 进会话 → 退出」不会跑收尾摘要，摘要停在旧版本，
 * 下一次靠阈值触发时把整段对话重新摘要，用户观感就是「记忆被反复重新处理」。
 *
 * 现在改为：内存计数 > 0 **或** 历史里确实有用户消息，就允许收尾。
 * 收尾本身幂等（rollingSummary 增量、compileToday 有指纹），多跑一次无副作用。
 */
class SessionEndTriggerTest {

    private val source: String by lazy {
        val candidates = listOf(
            File("src/main/java/io/zer0/memory/ticker/MemoryTicker.kt"),
            File("memory/src/main/java/io/zer0/memory/ticker/MemoryTicker.kt"),
        )
        candidates.firstOrNull { it.isFile }?.readText()
            ?: error("无法定位 MemoryTicker.kt")
    }

    private fun notifySessionEndBlock(): String {
        val start = source.indexOf("fun notifySessionEnd(")
        require(start >= 0) { "未找到 notifySessionEnd" }
        val end = source.indexOf("fun notifySessionEndFromProvider(", start)
        require(end > start) { "未找到下一个方法边界" }
        return source.substring(start, end)
    }

    @Test
    fun `session end no longer bails out on in-memory count alone`() {
        val block = notifySessionEndBlock()
        assertFalse(
            "不得再用「计数为 0」直接早返回：计数是内存态，重启后归零会吞掉收尾摘要",
            block.contains("if (count == 0) return"),
        )
        assertTrue(
            "必须改为同时看历史里是否有用户消息",
            block.contains("hasConversation"),
        )
        assertTrue(
            "判定必须包含 USER 消息这一路",
            block.contains("MessageRole.USER"),
        )
    }

    @Test
    fun `provider variant drops the count gate`() {
        val start = source.indexOf("fun notifySessionEndFromProvider(")
        require(start >= 0)
        val end = source.indexOf("private suspend fun runSessionEndPipeline(", start)
        require(end > start)
        val block = source.substring(start, end)
        assertFalse(
            "从持久层取历史的版本同样不能只看内存计数",
            block.contains("if (count == 0) return"),
        )
        assertTrue(
            "仍要保留「没有可摘要对话」的兜底",
            block.contains("messages.size < 2"),
        )
    }

    @Test
    fun `rolling summary stays incremental`() {
        // 收尾多跑一次之所以安全，前提是摘要按 messageCount 增量推进。
        // 这条断言把前提钉住：若有人改成全量重算，收尾幂等性就不再成立。
        val manager = listOf(
            File("src/main/java/io/zer0/memory/summary/SessionSummaryManager.kt"),
            File("memory/src/main/java/io/zer0/memory/summary/SessionSummaryManager.kt"),
        ).firstOrNull { it.isFile }?.readText() ?: error("无法定位 SessionSummaryManager.kt")
        assertTrue("摘要必须按 lastMessageCount 增量", manager.contains("lastMessageCount"))
        assertTrue("必须只取新消息", manager.contains("messages.drop(lastMessageCount)"))
    }
}
