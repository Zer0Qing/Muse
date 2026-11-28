package io.zer0.muse

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.AppearanceSettingsStore
import io.zer0.muse.data.HomeLayoutMode
import io.zer0.muse.ui.chat.ChatSurface
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HomeLayoutModeTest {

    @Test
    fun `fromWire maps null empty and unknown values to CLASSIC`() {
        assertEquals(HomeLayoutMode.CLASSIC, HomeLayoutMode.fromWire(null))
        assertEquals(HomeLayoutMode.CLASSIC, HomeLayoutMode.fromWire(""))
        assertEquals(HomeLayoutMode.CLASSIC, HomeLayoutMode.fromWire("unexpected"))
        assertEquals(HomeLayoutMode.CLASSIC, HomeLayoutMode.fromWire("classic"))
        assertEquals(HomeLayoutMode.DRAWER, HomeLayoutMode.fromWire("drawer"))
    }

    @Test
    fun `chat surface drawer task hides own top bar without claiming mini phone`() {
        assertTrue(ChatSurface.HOME_DRAWER_TASK.hidesOwnTopBar)
        assertFalse(ChatSurface.HOME_DRAWER_TASK.isMiniPhone)
        assertTrue(ChatSurface.AGENT.hidesOwnTopBar)
        assertFalse(ChatSurface.FULL.hidesOwnTopBar)
    }

    @Test
    fun `appearance store defaults to CLASSIC and round trips DRAWER`() = runTest {
        val store = AppearanceSettingsStore(ApplicationProvider.getApplicationContext<Context>())
        assertEquals(HomeLayoutMode.CLASSIC, store.homeLayoutModeFlow.first())

        store.saveHomeLayoutMode(HomeLayoutMode.DRAWER)
        assertEquals(HomeLayoutMode.DRAWER, store.homeLayoutModeFlow.first())

        store.saveHomeLayoutMode(HomeLayoutMode.CLASSIC)
        assertEquals(HomeLayoutMode.CLASSIC, store.homeLayoutModeFlow.first())
    }
}
