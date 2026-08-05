package io.zer0.muse.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.zer0.muse.data.audit.AuditLogger
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

    private fun store() = SecuritySettingsStore(context, mockk<AuditLogger>(relaxed = true))

    @Test
    fun securitySettings_defaultsThenSaveUpdates() = runBlocking {
        val store = store()
        // 先读默认值，再写入，避免测试间 DataStore 文件状态互相污染。
        assertEquals(false, store.keepAwakeFlow.first())
        assertEquals(false, store.biometricEnabledFlow.first())
        assertEquals(0, store.pinFailCountFlow.first())
        assertEquals(0L, store.pinLockUntilFlow.first())

        store.saveKeepAwake(true)
        store.saveBiometricEnabled(true)
        store.savePinFailState(3, 1234L)

        assertEquals(true, store.keepAwakeFlow.first())
        assertEquals(true, store.biometricEnabledFlow.first())
        assertEquals(3, store.pinFailCountFlow.first())
        assertEquals(1234L, store.pinLockUntilFlow.first())
    }
}
