package io.zer0.muse.tools

import io.zer0.ai.core.ReasoningLevel

/** Keeps visible reasoning metadata on every valid provider request in a client-side tool turn. */
internal object ToolRoundPresentationPolicy {
    fun exposeReasoning(round: Int): Boolean = round > 0

    /**
     * Tool follow-up rounds keep the user's configured reasoning level.
     *
     * Providers may still omit reasoning when the model does not support it,
     * but the client no longer suppresses a supported reasoning stream.
     */
    fun reasoningLevelForRound(configured: ReasoningLevel, round: Int): ReasoningLevel = if (round > 0) configured else ReasoningLevel.OFF

    /** Direct tool requests may use a lightweight thinking level, but never upgrade OFF. */
    fun reasoningLevelForDirectTool(configured: ReasoningLevel, supportsReasoning: Boolean): ReasoningLevel = when {
        configured == ReasoningLevel.OFF -> ReasoningLevel.OFF
        supportsReasoning -> ReasoningLevel.LOW
        else -> ReasoningLevel.OFF
    }
}
