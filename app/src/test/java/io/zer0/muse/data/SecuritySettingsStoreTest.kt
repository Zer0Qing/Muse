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
class SecuritySettingsStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun store() = SecuritySettingsStore(context)

    @Test
    fun securitySettings_defaultsThenSaveUpdates() = runBlocking {
        val store = store()
        // 先读默认值，再写入，避免测试间 DataStore 文件状态互相污染。
        assertEquals(false, store.keepAwakeFlow.first())
        assertEquals(false, store.autoLaunchFlow.first())
        assertEquals(true, store.anrDetectionFlow.first())

        store.saveKeepAwake(true)
        store.saveAutoLaunch(true)
        store.saveAnrDetection(false)

        assertEquals(true, store.keepAwakeFlow.first())
        assertEquals(true, store.autoLaunchFlow.first())
        assertEquals(false, store.anrDetectionFlow.first())
    }
}
