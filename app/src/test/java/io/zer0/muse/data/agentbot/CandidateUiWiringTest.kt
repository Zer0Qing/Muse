package io.zer0.muse.data.agentbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 候选列表必须真的渲染到界面。
 *
 * 上一轮加了 `pendingCandidates` StateFlow 却没有 UI，用户看不到候选。
 * 这条断言防的是同一类问题再次发生。
 */
class CandidateUiWiringTest {

    private val screen: String by lazy {
        File("src/main/java/io/zer0/muse/ui/agentbot/AgentBotScreen.kt").readText()
    }

    private val viewModel: String by lazy {
        File("src/main/java/io/zer0/muse/ui/agentbot/AgentBotViewModel.kt").readText()
    }

    @Test
    fun `candidate list is collected and rendered`() {
        assertTrue(
            "候选列表必须来自当前作用域投影",
            screen.contains("projection?.candidates"),
        );
        assertTrue("必须有有键候选列表", screen.contains("items(candidates, key = { \"candidate:\${it.stagedId}\" })"));
    }

    @Test
    fun `candidate section offers a discard action`() {
        assertTrue(screen.contains("onDiscard = viewModel::discardCandidate"));
        assertTrue(screen.contains("onDiscardCandidate = viewModel::discardCandidate"));
    }

    @Test
    fun `candidates are refreshed when binding a conversation`() {
        val bindAt = viewModel.indexOf("fun bind(");
        assertTrue(bindAt > 0);
        val bindEnd = Regex("\\R    \\}\\R").find(viewModel, bindAt)?.range?.first
            ?: error("找不到 bind 函数结尾")
        val body = viewModel.substring(bindAt, bindEnd);
        assertTrue(
            "进入页面就要能看到已有候选，不能等用户操作后才出现",
            body.contains("refreshCandidates()"),
        );
    }

    @Test
    fun `discarding refreshes the list immediately`() {
        val discardAt = viewModel.indexOf("fun discardCandidate(");
        assertTrue(discardAt > 0);
        val body = viewModel.substring(discardAt, minOf(discardAt + 500, viewModel.length));
        assertTrue(body.contains("refreshCandidates()"));
    }

    @Test
    fun `candidate strings exist in all seven locales`() {
        val locales = listOf("values", "values-en", "values-es", "values-ja", "values-ko", "values-pt-rBR", "values-ru");
        val counts = locales.associateWith { loc ->
            val file = File("src/main/res/$loc/strings_ui_misc.xml");
            if (!file.isFile) -1
            else Regex("<string name=\"agent_bot_").findAll(file.readText()).count();
        };
        val expected = counts["values"] ?: -1;
        counts.forEach { (loc, count) ->
            assertEquals("$loc 的中枢文案数与中文不一致", expected, count);
        };
    }
}
