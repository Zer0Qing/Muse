package io.zer0.muse.data.agentbot

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 枚举到文案的映射必须穷尽。
 *
 * `statusLabel(status: String)` 收 String 而不是枚举，编译器帮不上忙 ——
 * 新增一个状态却忘了加文案，界面上就会出现 `WAITING_APPROVAL` 这样的内部词。
 * 这是审查报告里点名过的缺陷类型。
 */
class LabelCoverageTest {

    private val screen: String by lazy {
        File("src/main/java/io/zer0/muse/ui/agentbot/AgentBotScreen.kt").readText()
    }

    private val cards: String by lazy {
        File("src/main/java/io/zer0/muse/ui/agentbot/AgentStreamCards.kt").readText()
    }

    private fun blockOf(source: String, visibility: String, fnName: String): String {
        val signature = "$visibility fun $fnName("
        val start = source.indexOf(signature)
        require(start >= 0) { "找不到 $signature" }
        val marker = "\n$visibility fun "
        val end = source.indexOf(marker, start + signature.length)
        return source.substring(start, if (end > 0) end else source.length)
    }

    @Test
    fun `every task status has a user facing label`() {
        val statuses = listOf(
            BotTaskStatus.QUEUED, BotTaskStatus.RUNNING, BotTaskStatus.WAITING_RESOURCE,
            BotTaskStatus.WAITING_APPROVAL, BotTaskStatus.REVIEWING, BotTaskStatus.CANCEL_REQUESTED,
            BotTaskStatus.SUCCEEDED, BotTaskStatus.PARTIAL, BotTaskStatus.FAILED,
            BotTaskStatus.CANCELLED, BotTaskStatus.NEEDS_USER, BotTaskStatus.UNKNOWN,
        )
        val block = blockOf(cards, "internal", "agentStatusLabel")
        val missing = statuses.filter { !block.contains(it) }
        assertTrue(
            "这些状态没有用户可读的文案，内部词会漏到界面上：" + missing,
            missing.isEmpty(),
        )
    }

    @Test
    fun `every error has a user facing label`() {
        val block = blockOf(screen, "private", "agentErrorLabel")
        val missing = AgentBotError.entries.filter { !block.contains(it.name) }
        assertTrue("这些错误没有文案：" + missing, missing.isEmpty())
    }

    @Test
    fun `every prompt has a user facing label`() {
        val block = blockOf(cards, "internal", "agentPromptLabel")
        val missing = AgentBotPrompt.entries.filter { !block.contains(it.name) }
        assertTrue("这些提示没有文案：" + missing, missing.isEmpty())
    }

    @Test
    fun `no status leaks an internal constant into the ui`() {
        // 兜底：agentStatusLabel 必须有一个 else 分支，避免未知状态漏到界面或崩溃。
        val block = blockOf(cards, "internal", "agentStatusLabel")
        assertTrue(
            "必须有 else 兜底，否则未知状态会崩溃",
            block.contains("else ->"),
        )
    }
}
