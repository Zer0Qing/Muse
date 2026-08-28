package io.zer0.muse.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.common.AppJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChatSettingsStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun chatSettings_defaultsThenSaveUpdates() = runBlocking {
        val store = ChatSettingsStore(context)

        assertEquals(false, store.tokenEstimateEnabledFlow.first())
        assertEquals(true, store.pasteAsFileEnabledFlow.first())
        assertEquals(2000, store.pasteAsFileThresholdFlow.first())
        assertEquals(false, store.floorLimiterEnabledFlow.first())
        assertEquals(16, store.floorLimitFlow.first())

        store.saveTokenEstimateEnabled(true)
        store.savePasteAsFileEnabled(false)
        store.savePasteAsFileThreshold(3000)
        store.saveFloorLimiterEnabled(true)
        store.saveFloorLimit(32)

        assertEquals(true, store.tokenEstimateEnabledFlow.first())
        assertEquals(false, store.pasteAsFileEnabledFlow.first())
        assertEquals(3000, store.pasteAsFileThresholdFlow.first())
        assertEquals(true, store.floorLimiterEnabledFlow.first())
        assertEquals(32, store.floorLimitFlow.first())
    }

    @Test
    fun chatPreferences_roundTrips() = runBlocking {
        val store = ChatSettingsStore(context)
        val custom = ChatPreferences(
            showTokenEstimate = false,
            streamResponse = false,
            autoScrollToBottom = false,
            enterToSend = true,
            hapticFeedback = false,
            predictiveBackEnabled = false,
        )
        store.saveChatPreferences(custom)

        val loaded = store.getChatPreferences()
        assertEquals(false, loaded.showTokenEstimate)
        assertEquals(false, loaded.streamResponse)
        assertEquals(true, loaded.enterToSend)
        assertEquals(false, loaded.hapticFeedback)
        assertEquals(false, loaded.predictiveBackEnabled)
    }

    @Test
    fun chatPreferences_legacyJsonUsesPredictiveBackDefault() {
        val legacyJson = """{"streamResponse":false,"hapticFeedback":true}"""

        val loaded = AppJson.decodeFromString(ChatPreferences.serializer(), legacyJson)

        assertEquals(true, loaded.predictiveBackEnabled)
    }
}
