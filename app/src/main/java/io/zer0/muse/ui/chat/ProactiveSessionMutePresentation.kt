package io.zer0.muse.ui.chat

/** Pure presentation contract for the per-chat mute control. */
data class ProactiveSessionMutePresentation(
    val enabled: Boolean,
    val checked: Boolean,
    val globalConfigUnchanged: Boolean = true,
) {
    companion object {
        fun of(muted: Boolean, hasSession: Boolean): ProactiveSessionMutePresentation = ProactiveSessionMutePresentation(
            enabled = hasSession,
            checked = hasSession && !muted,
        )
    }
}
