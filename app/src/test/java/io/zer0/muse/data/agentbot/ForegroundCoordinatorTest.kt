package io.zer0.muse.data.agentbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 主 Bot 前台回合的协议契约。
 *
 * 这个类守的是"模型真的会按结构输出"这件事。上一版用 `<dispatch>` 文本标记，
 * 模型根本不知道要输出它 —— 所有执行链路都在空转。改成工具调用后才成立。
 */
class ForegroundCoordinatorTest {

    private val source: String by lazy {
        File("src/main/java/io/zer0/muse/data/agentbot/ForegroundCoordinator.kt").readText()
    }

    @Test
    fun `control tools are tool definitions not text markers`() {
        assertTrue("必须定义 bot_say 工具", source.contains("TOOL_SAY = \"bot_say\""));
        assertTrue("必须定义 task_dispatch 工具", source.contains("TOOL_DISPATCH = \"task_dispatch\""));
        assertFalse(
            "文本标记模型不会输出，已废弃",
            source.contains("DISPATCH_TAG"),
        )
    }

    @Test
    fun `control tools are wired at the model call site`() {
        // 工具定义与 toolChoice 现在在 ModelCaller 的生产实现里（AppKoinModule），
        // 不再散落在 Coordinator 内部。断言改到真实装配点。
        val koin = File("src/main/java/io/zer0/muse/AppKoinModule.kt").readText();
        assertTrue(
            "必须把控制工具传给模型",
            koin.contains("tools = io.zer0.muse.data.agentbot.ForegroundCoordinator.CONTROL_TOOLS"),
        );
        assertTrue(
            "toolChoice 必须是 required，否则模型会回到纯文本",
            koin.contains("toolChoice = \"required\""),
        );
    }

    @Test
    fun `foreground turn calls the model once plus at most one retry`() {
        // 主回合 1 次 + 补救 1 次 = 2 处 modelCaller.complete 调用点。
        // 补救是计划 V15 的"仅一次受限补救"，不能循环。
        val calls = Regex("modelCaller\\.complete\\(").findAll(source).count();
        assertEquals("主回合 + 一次补救，共两处调用点", 2, calls);
    }

    @Test
    fun `tool results are parsed into a turn`() {
        assertTrue(source.contains("TOOL_SAY ->"));
        assertTrue(source.contains("TOOL_DISPATCH ->"));
        assertTrue("派工要带可改动范围", source.contains("resource_scope"));
    }

    @Test
    fun `plain text is never used as the reply`() {
        // V15: 拿模型正文兜底等于把 bot_say 约束作废，还可能把内部推理一起显示。
        assertFalse(
            "模型正文不得作为回复兜底",
            source.contains("say = text.trim()"),
        )
        assertTrue(
            "没拿到 bot_say 时要走明确的失败路径",
            source.contains("missingBotSay"),
        )
    }

    @Test
    fun `unknown control tool is logged not silently dropped`() {
        assertTrue(source.contains("未知控制工具，忽略"));
    }

    @Test
    fun `quota reject keeps the reply but drops the dispatch`() {
        assertTrue(source.contains("Verdict.Reject"));
        assertTrue(
            "拒绝时要如实说，不能假装在处理",
            source.contains("return Turn(say = turn.say"),
        )
    }

    @Test
    fun `model failure returns null so the caller can keep the draft`() {
        assertTrue(source.contains("if (completion == null) return null"));
    }

    @Test
    fun `identity is injected by the host not chosen by the model`() {
        // 工具 schema 里不得出现 botId / sessionId 这类身份参数。
        assertFalse(
            "身份由宿主注入，模型不能也不需要在参数里指定",
            source.contains("\"botId\":{"),
        )
        assertFalse(source.contains("\"sessionId\":{"));
    }

    @Test
    fun `scheduler consults the governor before claiming work`() {
        val scheduler = File("src/main/java/io/zer0/muse/data/agentbot/BotTaskScheduler.kt").readText();
        assertTrue(scheduler.contains("governor.admit("));
        assertTrue(scheduler.contains("governor.recordDispatch()"));
    }

    @Test
    fun `overdue sweep cancels the job before marking the task unknown`() {
        val scheduler = File("src/main/java/io/zer0/muse/data/agentbot/BotTaskScheduler.kt").readText();
        val sweepStart = scheduler.indexOf("suspend fun sweepOverdue");
        assertTrue("sweepOverdue 必须存在", sweepStart > 0);
        val body = scheduler.substring(sweepStart, minOf(sweepStart + 1200, scheduler.length));
        val cancelAt = body.indexOf("cancellations[run.runId]?.cancel()");
        val markAt = body.indexOf("MARK_UNKNOWN");
        assertTrue("先取消再落状态，反序会让 worker 在状态已改后继续写产物", cancelAt in 0 until markAt);
    }
}