package io.zer0.muse.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTopBannerPolicyTest {

    @Test
    fun activeCompressionProgressIsNeverHiddenByExistingWarnings() {
        assertEquals(
            ChatTopBanner.COMPRESSION,
            resolveChatTopBanner(
                isCompressing = true,
                showPendingResume = true,
                hasErrors = true,
                isConfigured = false,
            ),
        )
    }

    @Test
    fun remainingBannersKeepTheirPriorityAfterCompression() {
        assertEquals(
            ChatTopBanner.ERROR,
            resolveChatTopBanner(false, showPendingResume = true, hasErrors = true, isConfigured = false),
        )
        assertEquals(
            ChatTopBanner.PENDING_TOOLS,
            resolveChatTopBanner(false, showPendingResume = true, hasErrors = false, isConfigured = false),
        )
        assertEquals(
            ChatTopBanner.NOT_CONFIGURED,
            resolveChatTopBanner(false, showPendingResume = false, hasErrors = false, isConfigured = false),
        )
        assertEquals(
            ChatTopBanner.NONE,
            resolveChatTopBanner(false, showPendingResume = false, hasErrors = false, isConfigured = true),
        )
    }
}
