package io.zer0.muse.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 子进程环境：CPU 核数必须显式注入，否则内置 Node 只能靠 libuv 自己猜。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MuseRuntimeEnvTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `buildEnv injects positive cpu count`() {
        context.applicationInfo.nativeLibraryDir = File(context.cacheDir, "native-libs").absolutePath
        val env = MuseRuntime.buildEnv(context)
        val raw = env[MuseRuntime.ENV_CPU_COUNT]
        assertNotNull("buildEnv 必须注入 MUSE_CPU_COUNT", raw)
        val value = requireNotNull(raw).toInt()
        assertTrue("CPU 核数必须为正，实际 $value", value > 0)
        assertEquals("应与 Java 侧可用处理器数一致", MuseRuntime.childCpuCount().toString(), raw)
    }

    @Test
    fun `child cpu count never below one`() {
        assertTrue(Runtime.getRuntime().availableProcessors() >= 1)
        assertTrue(MuseRuntime.childCpuCount() >= 1)
    }
}
