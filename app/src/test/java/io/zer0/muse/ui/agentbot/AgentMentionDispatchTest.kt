package io.zer0.muse.ui.agentbot

import io.zer0.muse.data.agentbot.AgentMentionDispatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AgentMentionDispatchTest {

    private val squad = listOf(
        AgentMentionDispatch.Target(botId = "bot-research", displayName = "调研员", roleHint = "查资料"),
        AgentMentionDispatch.Target(botId = "bot-code", displayName = "代码手", roleHint = "写代码"),
        AgentMentionDispatch.Target(botId = "bot-life", displayName = "生活管家"),
    )

    @Test
    fun `typing at sign opens filtered teammate capsules`() {
        val query = AgentMentionDispatch.activeQuery("帮我 @调")

        assertNotNull(query)
        assertEquals(3, query?.startIndex)
        assertEquals("调", query?.query)
        assertEquals(
            listOf("调研员"),
            AgentMentionDispatch.filter(squad, query?.query.orEmpty()).map { it.displayName },
        )
    }

    @Test
    fun `at sign inside email or completed mention does not open capsules`() {
        assertNull(AgentMentionDispatch.activeQuery("zer0@example.com"))
        assertNull(AgentMentionDispatch.activeQuery("@调研员 你好"))
        assertNull(AgentMentionDispatch.activeQuery("@调研员 "))
    }

    @Test
    fun `empty query lists all teammates with a bounded suggestion count`() {
        val matches = AgentMentionDispatch.filter(squad, "")
        assertEquals(squad.map { it.botId }, matches.map { it.botId })
    }

    @Test
    fun `selecting a capsule replaces the partial mention and keeps a trailing space`() {
        val text = "帮我 @代"
        val query = AgentMentionDispatch.activeQuery(text)
        assertNotNull(query)

        val updated = AgentMentionDispatch.applySelection(text, query!!, squad[1])

        assertEquals("帮我 @代码手 ", updated)
    }

    @Test
    fun `leading mention resolves to teammate id and clean task text`() {
        val resolution = AgentMentionDispatch.resolve("@调研员 查一下三款竞品的定价", squad)

        assertNotNull(resolution)
        assertEquals("bot-research", resolution?.target?.botId)
        assertEquals("查一下三款竞品的定价", resolution?.taskText)
    }

    @Test
    fun `mid sentence mention is removed without eating surrounding text`() {
        val resolution = AgentMentionDispatch.resolve("请 @代码手 修一下构建脚本，然后汇报", squad)

        assertNotNull(resolution)
        assertEquals("bot-code", resolution?.target?.botId)
        assertEquals("请 修一下构建脚本，然后汇报", resolution?.taskText)
    }

    @Test
    fun `longest teammate name wins when names share a prefix`() {
        val overlapping = listOf(
            AgentMentionDispatch.Target(botId = "bot-short", displayName = "小"),
            AgentMentionDispatch.Target(botId = "bot-long", displayName = "小助手"),
        )

        val resolution = AgentMentionDispatch.resolve("@小助手 整理周报", overlapping)

        assertNotNull(resolution)
        assertEquals("bot-long", resolution?.target?.botId)
        assertEquals("整理周报", resolution?.taskText)
    }

    @Test
    fun `unknown or blank task mention does not resolve`() {
        assertNull(AgentMentionDispatch.resolve("@路人甲 帮我看一下", squad))
        assertNull(AgentMentionDispatch.resolve("@调研员   ", squad))
    }

    @Test
    fun `mention target overrides model supplied assignee`() {
        assertEquals(
            "bot-code",
            AgentMentionDispatch.resolveAssignee(declared = "bot-other", target = squad[1]),
        )
        assertEquals(
            "bot-other",
            AgentMentionDispatch.resolveAssignee(declared = "bot-other", target = null),
        )
        assertEquals(
            "",
            AgentMentionDispatch.resolveAssignee(declared = "", target = null),
        )
    }
}
