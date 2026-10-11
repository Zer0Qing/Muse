package io.zer0.muse.ui.chat

import io.zer0.muse.tools.SessionPermissionMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for the interactive process-level allow-all approval bit.
 * Remote A2A generation no longer enters this UI approval path: A2aHandlerIsolationTest
 * locks the headless ChatService path and absence of ChatViewModel dependency.
 */
class RemoteApprovalModeTest {

    @Test
    fun `allow-all applies to the focused interactive session`() = runBlocking {
        assertEquals(
            SessionPermissionMode.TRUSTED,
            resolveApprovalMode(
                allowAllToolsThisRun = true,
                displayedSessionId = "s1",
                sessionId = "s1",
                focusedMode = SessionPermissionMode.ASK,
                persistedModeProvider = { SessionPermissionMode.STRICT },
            ),
        )
    }

    @Test
    fun `allow-all does not apply to a non-focused session`() = runBlocking {
        assertEquals(
            SessionPermissionMode.ASK,
            resolveApprovalMode(
                allowAllToolsThisRun = true,
                displayedSessionId = "s1",
                sessionId = "s2",
                focusedMode = SessionPermissionMode.TRUSTED,
                persistedModeProvider = { SessionPermissionMode.ASK },
            ),
        )
    }

    @Test
    fun `focused session without allow-all uses focused permission mode`() = runBlocking {
        assertEquals(
            SessionPermissionMode.STRICT,
            resolveApprovalMode(
                allowAllToolsThisRun = false,
                displayedSessionId = "s1",
                sessionId = "s1",
                focusedMode = SessionPermissionMode.STRICT,
                persistedModeProvider = { SessionPermissionMode.ASK },
            ),
        )
    }

    @Test
    fun `unfocused session without allow-all uses persisted session mode`() = runBlocking {
        assertEquals(
            SessionPermissionMode.ASK,
            resolveApprovalMode(
                allowAllToolsThisRun = false,
                displayedSessionId = "s1",
                sessionId = "s2",
                focusedMode = SessionPermissionMode.TRUSTED,
                persistedModeProvider = { SessionPermissionMode.ASK },
            ),
        )
    }

    @Test
    fun `null focus never escalates to trusted`() = runBlocking {
        assertEquals(
            SessionPermissionMode.ASK,
            resolveApprovalMode(
                allowAllToolsThisRun = true,
                displayedSessionId = null,
                sessionId = "s2",
                focusedMode = SessionPermissionMode.ASK,
                persistedModeProvider = { SessionPermissionMode.ASK },
            ),
        )
    }
}
