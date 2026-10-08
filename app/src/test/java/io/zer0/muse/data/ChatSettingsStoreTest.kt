package io.zer0.muse.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.datastore.preferences.core.edit
import io.zer0.common.AppJson
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

    @Test
    fun updateChatPreferences_appliesAtomicallyWithoutClobberingOtherFields() = runBlocking {
        val store = ChatSettingsStore(context)
        store.updateChatPreferences { it.copy(showTokenEstimate = false, streamResponse = false) }
        // 第二次只改另一个字段，不应把上一步的两项改动回退
        store.updateChatPreferences { it.copy(enterToSend = true) }

        val loaded = store.getChatPreferences()
        assertEquals(false, loaded.showTokenEstimate)
        assertEquals(false, loaded.streamResponse)
        assertEquals(true, loaded.enterToSend)
    }

    @Test
    fun updateChatPreferences_concurrentUpdatesDoNotLoseWrites() = runBlocking {
        val store = ChatSettingsStore(context)
        kotlinx.coroutines.coroutineScope {
            listOf(
                launch { store.updateChatPreferences { it.copy(showReasoning = false) } },
                launch { store.updateChatPreferences { it.copy(showModelName = false) } },
                launch { store.updateChatPreferences { it.copy(showTimestamp = true) } },
            ).forEach { it.join() }
        }

        val loaded = store.getChatPreferences()
        // 三处并发写都应存活，不出现后写覆盖前写
        assertEquals(false, loaded.showReasoning)
        assertEquals(false, loaded.showModelName)
        assertEquals(true, loaded.showTimestamp)
    }

    @Test
    fun corruptPreferencesJson_isBackedUpAndReset() = runBlocking {
        val store = ChatSettingsStore(context)
        // 直接往 DataStore 写坏 JSON
        context.museSettingsDataStore.edit { prefs ->
            prefs[androidx.datastore.preferences.core.stringPreferencesKey("chat_preferences_json")] = "{not-json"
        }
        // 触发一次更新，应备份坏值并正常写入新值
        store.updateChatPreferences { it.copy(showTokenEstimate = false) }

        val backup =
            context.getSharedPreferences("muse_settings_corrupt_backup", Context.MODE_PRIVATE)
                .getString("chat_preferences_json_corrupt", null)
        assertEquals("{not-json", backup)
        assertEquals(false, store.getChatPreferences().showTokenEstimate)
    }
}
