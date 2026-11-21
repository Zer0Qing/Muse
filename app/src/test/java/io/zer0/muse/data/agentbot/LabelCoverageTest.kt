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

    private fun blockOf(fnName: String): String {
        val start = screen.indexOf("private fun $fnName(");
        require(start > 0) { "找不到 $fnName" };
        val marker = Character.toString(10.toChar()) + "private fun ";
        val end = screen.indexOf(marker, start + 10);
        return screen.substring(start, if (end > 0) end else screen.length);
    }

    @Test
    fun `every task status has a user facing label`() {
        val statuses = listOf(
            BotTaskStatus.QUEUED, BotTaskStatus.RUNNING, BotTaskStatus.WAITING_RESOURCE,
            BotTaskStatus.WAITING_APPROVAL, BotTaskStatus.REVIEWING, BotTaskStatus.CANCEL_REQUESTED,
            BotTaskStatus.SUCCEEDED, BotTaskStatus.PARTIAL, BotTaskStatus.FAILED,
            BotTaskStatus.CANCELLED, BotTaskStatus.NEEDS_USER, BotTaskStatus.UNKNOWN,
        );
        val block = blockOf("statusLabel");
        val missing = statuses.filter { !block.contains(it) };
        assertTrue(
            "这些状态没有用户可读的文案，内部词会漏到界面上：" + missing,
            missing.isEmpty(),
        );
    }

    @Test
    fun `every error has a user facing label`() {
        val block = blockOf("errorLabel");
        val missing = AgentBotError.entries.filter { !block.contains(it.name) };
        assertTrue("这些错误没有文案：" + missing, missing.isEmpty());
    }

    @Test
    fun `every prompt has a user facing label`() {
        val block = blockOf("promptLabel");
        val missing = AgentBotPrompt.entries.filter { !block.contains(it.name) };
        assertTrue("这些提示没有文案：" + missing, missing.isEmpty());
    }

    @Test
    fun `no status leaks an internal constant into the ui`() {
        // 兜底：statusLabel 必须有一个 else 分支返回 status 本身，
        // 但那条路径不该被走到 —— 上面的穷尽测试保证了这一点。
        val block = blockOf("statusLabel");
        assertTrue(
            "必须有 else 兜底，否则未知状态会崩溃",
            block.contains("else ->"),
        );
    }
}