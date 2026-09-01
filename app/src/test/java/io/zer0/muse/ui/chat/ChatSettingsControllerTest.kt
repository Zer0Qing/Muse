package io.zer0.muse.ui.chat

import io.mockk.coVerify
import io.mockk.mockk
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.ChatUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSettingsControllerTest {

    private class ScopedAccessor(
        initial: ChatUiState,
        override val coroutineScope: CoroutineScope,
    ) : ChatStateAccessor {
        private val state = MutableStateFlow(initial)
        override val snapshot: ChatUiState get() = state.value
        override fun update(transform: (ChatUiState) -> ChatUiState) = state.update(transform)
        override val messagesSnapshot: List<UIMessage> get() = emptyList()
        override fun updateMessages(transform: (List<UIMessage>) -> List<UIMessage>) = Unit
    }

    private fun controller(
        scope: CoroutineScope,
        state: ChatUiState = ChatUiState(),
        settings: SettingsRepository = mockk(relaxed = true),
    ): Pair<ChatSettingsController, ScopedAccessor> {
        val accessor = ScopedAccessor(state, scope)
        return ChatSettingsController(
            accessor, settings, mockk(relaxed = true), mockk<SessionModelSelectionStore>(relaxed = true),
        ) to accessor
    }

    @Test
    fun `toggleDrawer sets drawer open`() = runTest {
        val (c, accessor) = controller(this)
        c.toggleDrawer(true)
        assertTrue(accessor.snapshot.isDrawerOpen)
    }

    @Test
    fun `toggleDrawMode toggles draw mode`() = runTest {
        val (c, accessor) = controller(this)
        c.toggleDrawMode()
        assertTrue(accessor.snapshot.isDrawMode)
        c.toggleDrawMode()
        assertFalse(accessor.snapshot.isDrawMode)
    }

    @Test
    fun `clearToast clears toast`() = runTest {
        val (c, accessor) = controller(this, ChatUiState().copy(toast = "hi"))
        c.clearToast()
        assertTrue(accessor.snapshot.toast == null)
    }

    @Test
    fun `setToolModel delegates to settings`() = runTest {
        val settings = mockk<SettingsRepository>(relaxed = true)
        val (c, _) = controller(this, settings = settings)
        c.setToolModel("m1")
        advanceUntilIdle()
        coVerify { settings.saveToolModel("m1") }
    }
}
