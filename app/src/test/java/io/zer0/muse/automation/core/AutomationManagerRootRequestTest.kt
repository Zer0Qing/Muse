package io.zer0.muse.automation.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.zer0.muse.automation.executors.RootExecutor
import io.zer0.muse.automation.executors.RootRequestFailure
import io.zer0.muse.automation.executors.RootRequestResult
import io.zer0.muse.tools.system.ShizukuAuthorizer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [AutomationManager.requestRoot] 定向测试 —— UI 依赖的返回值与状态刷新路径。
 *
 * [RootExecutor] 用 mock 注入(见 manager 的可注入构造参数),因此不会真的执行 su:
 * 锁定「无 su / 授权超时」等失败原因能原样返回给页面,且请求后一定重新探测权限状态。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutomationManagerRootRequestTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun shizukuMock(): ShizukuAuthorizer = mockk<ShizukuAuthorizer>().apply {
        coEvery { diagnose() } returns ShizukuAuthorizer.ShizukuStatus(
            ShizukuAuthorizer.ShizukuState.NOT_INSTALLED,
            "未安装 Shizuku",
        )
    }

    private fun manager(root: RootExecutor, shizuku: ShizukuAuthorizer = shizukuMock()): AutomationManager =
        AutomationManager(
            context = context,
            shizukuAuthorizer = shizuku,
            rootExecutor = root,
        )

    @Test
    fun `request without su binary returns no su reason and keeps root disabled`() = runTest {
        val root = mockk<RootExecutor>()
        coEvery { root.requestRootAccess() } returns RootRequestResult.failed(RootRequestFailure.NO_SU_BINARY)
        coEvery { root.isAvailable() } returns false
        val manager = manager(root)

        val result = manager.requestRoot()

        assertFalse(result.granted)
        assertEquals(RootRequestFailure.NO_SU_BINARY, result.failure)
        assertFalse(manager.permissionState.value.rootEnabled)
        // 请求之后必须重新探测,而不是沿用旧状态
        coVerify(exactly = 1) { root.requestRootAccess() }
        coVerify(atLeast = 1) { root.isAvailable() }
    }

    @Test
    fun `timed out request surfaces timeout reason and refreshes state`() = runTest {
        val root = mockk<RootExecutor>()
        coEvery { root.requestRootAccess() } returns RootRequestResult.failed(RootRequestFailure.TIMEOUT)
        coEvery { root.isAvailable() } returns false
        val manager = manager(root)

        val result = manager.requestRoot()

        assertFalse(result.granted)
        assertEquals(RootRequestFailure.TIMEOUT, result.failure)
        assertFalse(manager.permissionState.value.rootEnabled)
        coVerify(exactly = 1) { root.isAvailable() }
    }

    @Test
    fun `granted request refreshes permission state to root enabled`() = runTest {
        val root = mockk<RootExecutor>()
        coEvery { root.requestRootAccess() } returns RootRequestResult.Granted
        coEvery { root.isAvailable() } returns true
        val manager = manager(root)

        // 请求前:初始状态没有 root
        assertFalse(manager.permissionState.value.rootEnabled)

        val result = manager.requestRoot()

        assertTrue(result.granted)
        assertNull(result.failure)
        // 请求后 manager 自动 refresh,卡片据此变绿
        assertTrue(manager.permissionState.value.rootEnabled)
        coVerify(exactly = 1) { root.requestRootAccess() }
    }

    @Test
    fun `denied request keeps previously enabled root off after refresh`() = runTest {
        val root = mockk<RootExecutor>()
        coEvery { root.requestRootAccess() } returns RootRequestResult.failed(RootRequestFailure.DENIED)
        coEvery { root.isAvailable() } returns false
        val manager = manager(root)

        val result = manager.requestRoot()

        assertEquals(RootRequestFailure.DENIED, result.failure)
        assertFalse(manager.permissionState.value.rootEnabled)
        assertFalse(manager.permissionState.value.anyEnabled)
    }
}
