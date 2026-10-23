package io.zer0.muse.tools

import io.zer0.ai.core.ReasoningLevel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRoundPresentationPolicyTest {
    @Test
    fun `every valid provider round exposes reasoning when thinking is enabled`() {
        assertTrue(ToolRoundPresentationPolicy.exposeReasoning(1))
        assertTrue(ToolRoundPresentationPolicy.exposeReasoning(2))
        assertTrue(ToolRoundPresentationPolicy.exposeReasoning(3))
    }

    @Test
    fun `invalid zero round does not expose reasoning`() {
        assertFalse(ToolRoundPresentationPolicy.exposeReasoning(0))
    }

    @Test
    fun `tool continuation keeps the configured reasoning level`() {
        assertTrue(
            ToolRoundPresentationPolicy.reasoningLevelForRound(
                configured = ReasoningLevel.HIGH,
                round = 2,
            ) == ReasoningLevel.HIGH,
        )
        assertTrue(
            ToolRoundPresentationPolicy.reasoningLevelForRound(
                configured = ReasoningLevel.OFF,
                round = 2,
            ) == ReasoningLevel.OFF,
        )
    }

    @Test
    fun `direct tool request never upgrades an explicitly disabled reasoning level`() {
        assertTrue(
            ToolRoundPresentationPolicy.reasoningLevelForDirectTool(
                configured = ReasoningLevel.OFF,
                supportsReasoning = true,
            ) == ReasoningLevel.OFF,
        )
        assertTrue(
            ToolRoundPresentationPolicy.reasoningLevelForDirectTool(
                configured = ReasoningLevel.HIGH,
                supportsReasoning = true,
            ) == ReasoningLevel.LOW,
        )
        assertTrue(
            ToolRoundPresentationPolicy.reasoningLevelForDirectTool(
                configured = ReasoningLevel.HIGH,
                supportsReasoning = false,
            ) == ReasoningLevel.OFF,
        )
    }
}
