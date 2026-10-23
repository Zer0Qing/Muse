package io.zer0.muse.automation.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.zer0.muse.automation.executors.RootExecutor
import io.zer0.muse.automation.executors.ShellExecutor
import io.zer0.muse.tools.system.ShizukuAuthorizer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutomationManagerExecTieredFallbackTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `runtime Shizuku failure falls back to an available Root executor`() = runTest {
        val authorizer = mockk<ShizukuAuthorizer>()
        coEvery { authorizer.diagnose() } returns ShizukuAuthorizer.ShizukuStatus(
            ShizukuAuthorizer.ShizukuState.READY,
            "ready",
        )
        val shell = mockk<ShellExecutor>()
        val root = mockk<RootExecutor>()
        coEvery { root.isAvailable() } returns true
        coEvery { shell.execDetailed("id") } returns ShellExecutor.ExecDetail(-1, "binder disconnected")
        coEvery { root.execDetailed("id") } returns ShellExecutor.ExecDetail(0, "uid=0(root)")

        val manager =
            AutomationManager(
                context = context,
                shizukuAuthorizer = authorizer,
                rootExecutor = root,
                shellExecutor = shell,
            )

        val result = manager.execTiered("id")

        assertEquals("Root", result?.first)
        assertEquals(0, result?.second?.exitCode)
        coVerify(exactly = 1) { shell.execDetailed("id") }
        coVerify(exactly = 1) { root.execDetailed("id") }
    }
}
