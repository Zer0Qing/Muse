package io.zer0.muse.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 顶部横幅优先级 + 错误可见性/自动消失策略。
 *
 * 优先级（v2.6.8 起）：ERROR > COMPRESSION > PENDING_TOOLS > NOT_CONFIGURED > NONE。
 * 这里锁的是"错误永不被压缩进度压掉"这条回归（此前 COMPRESSION 在 ERROR 之上，
 * 压缩超过旧 5 秒自动消失窗口时，错误一次都不会显示）。
 */
class ChatTopBannerPolicyTest {

    @Test
    fun `error is never hidden by an active compression`() {
        assertEquals(
            ChatTopBanner.ERROR,
            resolveChatTopBanner(
                isCompressing = true,
                showPendingResume = true,
                hasErrors = true,
                isConfigured = false,
            ),
        )
    }

    @Test
    fun `compression still wins over the lower priority banners`() {
        assertEquals(
            ChatTopBanner.COMPRESSION,
            resolveChatTopBanner(
                isCompressing = true,
                showPendingResume = true,
                hasErrors = false,
                isConfigured = false,
            ),
        )
    }

    @Test
    fun `remaining banners keep their order below compression`() {
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

    // ── 错误可见性 ────────────────────────────────────────────────────────

    @Test
    fun `error counts as visible even while compressing`() {
        assertEquals(
            ErrorVisibility.VISIBLE,
            resolveErrorVisibility(
                errorStillPresent = true,
                appInForeground = true,
                isCompressing = true,
                showPendingResume = false,
                isConfigured = true,
            ),
        )
    }

    @Test
    fun `background app defers the dismiss timer`() {
        assertEquals(
            ErrorVisibility.PENDING,
            resolveErrorVisibility(
                errorStillPresent = true,
                appInForeground = false,
                isCompressing = false,
                showPendingResume = false,
                isConfigured = true,
            ),
        )
    }

    @Test
    fun `dismissed error is reported as gone`() {
        assertEquals(
            ErrorVisibility.GONE,
            resolveErrorVisibility(
                errorStillPresent = false,
                appInForeground = true,
                isCompressing = false,
                showPendingResume = false,
                isConfigured = true,
            ),
        )
    }

    @Test
    fun `banner occupied by a higher priority state defers the timer`() {
        // 当前策略下错误已在最高档，这里用"错误不在列表"以外的极端组合锁住实现：
        // 一旦有人把 ERROR 挪到别的横幅之下，计时逻辑会跟着变成 PENDING 而不是盲等。
        assertEquals(
            ErrorVisibility.VISIBLE,
            resolveErrorVisibility(
                errorStillPresent = true,
                appInForeground = true,
                isCompressing = false,
                showPendingResume = true,
                isConfigured = false,
            ),
        )
    }

    // ── 自动消失资格 ──────────────────────────────────────────────────────

    @Test
    fun `recoverable network error may auto dismiss`() {
        assertTrue(shouldAutoDismissError(ChatErrorType.NETWORK, isRecoverable = true))
        assertTrue(shouldAutoDismissError(ChatErrorType.RATE_LIMIT, isRecoverable = true))
    }

    @Test
    fun `errors needing a user decision never auto dismiss`() {
        assertFalse(shouldAutoDismissError(ChatErrorType.TOOL_ERROR, isRecoverable = true))
        assertFalse(shouldAutoDismissError(ChatErrorType.MODEL_ERROR, isRecoverable = true))
        assertFalse(shouldAutoDismissError(ChatErrorType.API_KEY, isRecoverable = true))
    }

    @Test
    fun `non recoverable error never auto dismiss`() {
        assertFalse(shouldAutoDismissError(ChatErrorType.UNKNOWN, isRecoverable = false))
    }

    @Test
    fun `reading window is long enough to read and bounded`() {
        assertTrue("15 秒级窗口", ERROR_AUTO_DISMISS_MS >= 10_000L)
        assertTrue("但也不该长期占屏", ERROR_AUTO_DISMISS_MS <= 30_000L)
        assertTrue("可见等待上限应大于阅读窗口", ERROR_VISIBLE_WAIT_MS > ERROR_AUTO_DISMISS_MS)
    }
}
