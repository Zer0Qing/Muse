package io.zer0.muse.data.agentbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 计划 V15：bot_say 未调用时必须明确报错，不能用模型正文兜底。
 *
 * 这条不是"体验优化"，是防止**伪造回复**：模型可能把内部推理一起吐出来，
 * 用户却以为那是主 Bot 正式说的话。
 */
class BotSayContractTest {

    private val coordinator: String by lazy {
        File("src/main/java/io/zer0/muse/data/agentbot/ForegroundCoordinator.kt").readText()
    }

    private val viewModel: String by lazy {
        File("src/main/java/io/zer0/muse/ui/agentbot/AgentBotViewModel.kt").readText()
    }

    @Test
    fun `model raw text is never used as the reply`() {
        assertFalse(
            "拿模型正文兜底等于把 bot_say 约束作废",
            coordinator.contains("var say = text.trim()"),
        )
        assertTrue(
            "say 必须只从 bot_say 工具参数来",
            coordinator.contains("var say = \"\""),
        )
    }

    @Test
    fun `missing bot_say is surfaced instead of silently ending`() {
        assertTrue(coordinator.contains("missingBotSay"));
        assertTrue(
            "既没说话也没派工时，调用方必须知道",
            coordinator.contains("missingBotSay = say.isBlank() && goal.isBlank()"),
        )
    }

    @Test
    fun `a single retry is attempted before giving up`() {
        assertTrue(coordinator.contains("retryWithBotSay("));
        // 补救只能一次：数调用点的次数。
        val retryCalls = Regex("retryWithBotSay\\(").findAll(coordinator).count();
        assertTrue(
            "补救函数定义 + 一处调用，不能循环重试（实际 " + retryCalls + " 处）",
            retryCalls == 2,
        )
    }

    @Test
    fun `retry reminder does not restate the persona`() {
        assertTrue(
            "复述人格会让两段提示互相打架，这里只需把动作拉回 bot_say",
            coordinator.contains("请只用 bot_say 工具回复我"),
        )
    }

    @Test
    fun `view model reports the failure to the user`() {
        assertTrue(viewModel.contains("turn.missingBotSay"));
        assertTrue(viewModel.contains("AgentBotError.MISSING_BOT_SAY"));
    }

    @Test
    fun `keepalive treats a query failure as still busy`() {
        val service = File("src/main/java/io/zer0/muse/data/agentbot/AgentBotKeepAliveService.kt").readText();
        assertTrue(
            "读失败按还在忙处理，宁可多保活也不能在任务跑到一半时退出",
            service.contains("getOrDefault(true)"),
        )
        assertFalse(
            "旧写法会把异常当成没任务，导致任务还在跑服务就退了",
            service.contains("hasRunningWork() }.getOrDefault(false)"),
        )
    }
}
