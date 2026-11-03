package io.zer0.muse.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveSessionMutePresentationTest {
    @Test
    fun `muted session presents unchecked without mutating global config`() {
        val state = ProactiveSessionMutePresentation.of(muted = true, hasSession = true)

        assertFalse(state.checked)
        assertTrue(state.enabled)
        assertTrue(state.globalConfigUnchanged)
    }

    @Test
    fun `missing session cannot display an active switch`() {
        val state = ProactiveSessionMutePresentation.of(muted = false, hasSession = false)

        assertFalse(state.checked)
        assertFalse(state.enabled)
    }
}
