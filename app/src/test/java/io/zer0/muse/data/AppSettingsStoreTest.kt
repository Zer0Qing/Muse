package io.zer0.muse.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppSettingsStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun language_defaultsToSystem() = runBlocking {
        val store = AppSettingsStore(context)
        assertEquals("system", store.languageFlow.first())
    }

    @Test
    fun saveLanguage_updatesFlowAndSyncCache() = runBlocking {
        val store = AppSettingsStore(context)
        store.saveLanguage("en")
        assertEquals("en", store.languageFlow.first())
        assertEquals("en", store.getLanguageSync())
    }
}
