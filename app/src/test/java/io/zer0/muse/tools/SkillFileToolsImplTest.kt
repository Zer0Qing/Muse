package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SkillFileToolsImplTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun writeReadRoundTripInSandbox() {
        val impl = SkillFileToolsImpl(context, OkHttpClient())
        val write = impl.execWriteFile(
            mapOf("path" to "notes/test.txt", "content" to "hello file tools"),
        )
        assertFalse(write.contains("error", ignoreCase = true))

        val read = impl.execReadFile(mapOf("path" to "notes/test.txt"))
        assertTrue(read.contains("hello file tools"))
    }

    @Test
    fun resolveSandboxFileRejectsTraversal() {
        val impl = SkillFileToolsImpl(context, OkHttpClient())
        val result = impl.execReadFile(mapOf("path" to "../../outside.txt"))
        assertTrue(result.contains("路径", ignoreCase = true) || result.contains("violation", ignoreCase = true))
    }
}
