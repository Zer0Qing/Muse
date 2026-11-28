package io.zer0.muse.ui.agentbot

data class SpaceTemplateDraft(
    val id: String,
    val title: String,
    val description: String,
    val html: String,
    val dataJson: String,
    val refreshIntervalMinutes: Int,
    val refreshPrompt: String,
)
