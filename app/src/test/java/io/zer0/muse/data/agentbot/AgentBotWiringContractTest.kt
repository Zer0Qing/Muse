package io.zer0.muse.data.agentbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 中枢页面接线的三条硬约束（第十三轮修复）。
 *
 * 这三条都不是"代码风格"问题，各自对应一种用户能感觉到的坏体验：
 *  - 输入被模型卡住 → 用户以为发不出去；
 *  - 主 Bot 看不到上文 → "那个""再来一次"理解不了；
 *  - 派工不记来源消息 → 审核阶段无法回溯用户到底说了什么。
 */
class AgentBotWiringContractTest {

    private val viewModel: String by lazy {
        File("src/main/java/io/zer0/muse/ui/agentbot/AgentBotViewModel.kt").readText()
    }

    @Test
    fun `input is released before the model call`() {
        val sendStart = viewModel.indexOf("fun send()")
        assertTrue("send() 必须存在", sendStart > 0);
        val body = viewModel.substring(sendStart, minOf(sendStart + 3200, viewModel.length));

        val releaseAt = body.indexOf("ui.setSending(false)");
        val modelAt = body.indexOf("runForegroundTurn(");
        assertTrue(
            "必须先放开输入再调模型，否则发送中会一直亮到模型答完",
            releaseAt in 0 until modelAt,
        )
    }

    @Test
    fun `foreground turn runs in its own coroutine not the send coroutine`() {
        val sendStart = viewModel.indexOf("fun send()")
        val body = viewModel.substring(sendStart, minOf(sendStart + 3200, viewModel.length));
        assertTrue(
            "回合要丢到独立 Job，不能占用发送协程",
            body.contains("viewModelScope.launch {"),
        )
    }

    @Test
    fun `history is loaded instead of passing an empty list`() {
        assertTrue(
            "主 Bot 必须看到上文，否则理解不了指代",
            viewModel.contains("getRecentMessages("),
        )
        assertFalse(
            "空列表兜底会让多轮对话退化成单轮",
            viewModel.contains("val history = emptyList<"),
        )
    }

    @Test
    fun `history read failure degrades to single turn instead of failing the round`() {
        assertTrue(
            "读不到历史不该让整轮失败",
            viewModel.contains("退化为单轮"),
        )
        assertTrue(viewModel.contains("getOrDefault(emptyList())"));
    }

    @Test
    fun `dispatch records which message triggered it`() {
        assertTrue(
            "派工必须记住来源消息，审核阶段要能回溯",
            viewModel.contains("requestMessageId = acceptedMessageId"),
        )
        assertFalse(
            "不能留空字符串",
            viewModel.contains("requestMessageId = \"\""),
        )
    }

    @Test
    fun `message id comes from the accepted batch not from a guess`() {
        assertTrue(
            "来源消息 id 必须来自 facade 的接纳结果",
            viewModel.contains("accepted.batchId"),
        )
    }

    @Test
    fun `history limit is bounded`() {
        assertTrue(
            "上下文条数要有上限，否则首字延迟会被历史推高",
            viewModel.contains("HISTORY_LIMIT ="),
        )
    }
}
