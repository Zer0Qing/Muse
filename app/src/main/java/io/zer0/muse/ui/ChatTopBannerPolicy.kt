package io.zer0.muse.ui

internal enum class ChatTopBanner {
    COMPRESSION,
    PENDING_TOOLS,
    ERROR,
    NOT_CONFIGURED,
    NONE,
}

internal fun resolveChatTopBanner(
    isCompressing: Boolean,
    showPendingResume: Boolean,
    hasErrors: Boolean,
    isConfigured: Boolean,
): ChatTopBanner = when {
    isCompressing -> ChatTopBanner.COMPRESSION
    hasErrors -> ChatTopBanner.ERROR
    showPendingResume -> ChatTopBanner.PENDING_TOOLS
    !isConfigured -> ChatTopBanner.NOT_CONFIGURED
    else -> ChatTopBanner.NONE
}
