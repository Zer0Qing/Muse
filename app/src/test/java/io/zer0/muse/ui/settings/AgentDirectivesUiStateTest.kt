package io.zer0.muse.ui.settings

import io.zer0.muse.data.assistant.AgentDirective
import io.zer0.muse.data.assistant.AgentDirectiveStore
import io.zer0.muse.data.assistant.DirectivePolarity
import io.zer0.muse.data.assistant.VoiceReplyFrequency
import io.zer0.muse.proactive.AgentDirectivesPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 约定页的三条核心判断（纯函数接缝）。
 *
 * 页面整体渲染要起 Koin + Room 才跑得动，那种脚手架本身比被测逻辑更容易坏；
 * 所以把判断切出来逐条钉住：作用域解析、分组与顺序、未生效计数与装配层同源。
 */
class AgentDirectivesUiStateTest {

    private fun d(agentId: String, polarity: DirectivePolarity, position: Int, text: String = "t$position") = AgentDirective(
        id = "$agentId-$polarity-$position",
        agentId = agentId,
        polarity = polarity,
        text = text,
        position = position,
    )

    @Test
    fun `选过的助手优先，被删掉时回落第一个而不是留空`() {
        assertEquals("a1", AgentDirectivesUiState.resolveAgentId(null, listOf("a1", "a2")))
        assertEquals("a2", AgentDirectivesUiState.resolveAgentId("a2", listOf("a1", "a2")))
        assertEquals(
            "选中的助手已不存在时要回落，否则页面变成一片空白、没有可编辑的规则",
            "a1",
            AgentDirectivesUiState.resolveAgentId("gone", listOf("a1", "a2")),
        )
        assertEquals("", AgentDirectivesUiState.resolveAgentId("a1", emptyList()))
    }

    @Test
    fun `分组只取本助手的规则且各自按 position 排`() {
        val all = listOf(
            d("a1", DirectivePolarity.SHOULD, 2),
            d("a1", DirectivePolarity.MUST_NOT, 0),
            d("a1", DirectivePolarity.SHOULD, 1),
            d("a1", DirectivePolarity.MUST_NOT, 1),
            d("a2", DirectivePolarity.MUST_NOT, 0, "别人的规则"),
        )
        val s = AgentDirectivesUiState.sectionsFor(all, "a1")
        assertEquals(2, s.forbidden.size)
        assertEquals(2, s.desired.size)
        assertEquals("position 要升序", listOf(0, 1), s.forbidden.map { it.position })
        assertEquals(listOf(1, 2), s.desired.map { it.position })
        assertTrue("别人的规则不能出现在这一页", s.forbidden.none { it.text == "别人的规则" })
    }

    @Test
    fun `未生效计数与装配层同一口径且测试本身有意义`() {
        // 造出确实超量的条目：预算 600、每条 30 字 × 60 条 = 1800 字，必然装不下
        val many = (1..60).map { i -> d("a1", DirectivePolarity.SHOULD, i, "y".repeat(30)) }
        val s = AgentDirectivesUiState.sectionsFor(many, "a1")
        val merged = s.forbidden + s.desired
        val expected = AgentDirectivesPrompt.build(merged, AgentDirectiveStore.MAX_TOTAL_CHARS).omitted.size
        assertTrue("先确认确实有装不下的条目（否则这条断言是空的）", expected > 0)
        assertEquals(
            "界面报的条数必须等于装配层的条数，两份口径早晚会对不上",
            expected,
            AgentDirectivesUiState.omittedCount(merged),
        )
    }

    @Test
    fun `空清单时未生效计数为零`() {
        assertEquals(0, AgentDirectivesUiState.omittedCount(emptyList()))
    }

    @Test
    fun `语音频率读不到时回落默认档而不是当成总是`() {
        val snapshot = mapOf("a1" to "OFTEN", "a2" to "乱写的值")
        assertEquals("配过的要按配置来", VoiceReplyFrequency.OFTEN, AgentDirectivesUiState.voiceFrequency(snapshot, "a1"))
        assertEquals(
            "没配过的助手必须回落默认档（默认档不注入任何文案）；写成 ALWAYS 就等于用户什么都没做却改了形态",
            VoiceReplyFrequency.RARELY,
            AgentDirectivesUiState.voiceFrequency(snapshot, "missing"),
        )
        assertEquals("存了不认识的值也要回落", VoiceReplyFrequency.RARELY, AgentDirectivesUiState.voiceFrequency(snapshot, "a2"))
        assertEquals(VoiceReplyFrequency.RARELY, AgentDirectivesUiState.voiceFrequency(emptyMap(), ""))
    }
}
