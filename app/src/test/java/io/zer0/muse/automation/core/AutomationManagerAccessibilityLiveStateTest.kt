package io.zer0.muse.automation.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import io.zer0.muse.automation.executors.MuseAccessibilityService
import io.zer0.muse.automation.executors.RootExecutor
import io.zer0.muse.tools.system.ShizukuAuthorizer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.7 回归：无障碍服务**异步连接**后，权限状态必须能反映真实情况。
 *
 * ## 复现的 bug
 *
 * `AutomationManager.permissionState` 是缓存值，只在 [AutomationManager.refreshPermissions]
 * 被显式调用时更新（App 启动、从设置页返回、请求 Root 后）。但无障碍服务是异步连接的：
 * 用户在系统设置里开启开关后 `MuseAccessibilityService.onServiceConnected` 才执行。
 * 若启动时服务尚未连接（缓存 a11y=false），之后服务连上却没人刷新，
 * 缓存会一直停在 false —— 工具预检据此误判“未授权”，
 * 而权限向导实时读系统设置却显示“已开启”（用户实测现象）。
 *
 * ## 锁定的行为
 *
 * [AutomationManager.liveSnapshot] 每次调用都**重新探测无障碍**（同步轻量查询），
 * 因此服务连上后立即返回 true，不依赖启动时的缓存。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutomationManagerAccessibilityLiveStateTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        // 静态实例是全局的，测试间必须清理，避免污染其他用例
        io.zer0.muse.accessibility.MuseAccessibilityService.instance = null
    }

    private fun manager(): AutomationManager {
        val shizuku = mockk<ShizukuAuthorizer>(relaxed = true).apply {
            coEvery { diagnose() } returns ShizukuAuthorizer.ShizukuStatus(
                ShizukuAuthorizer.ShizukuState.NOT_INSTALLED,
                "未安装 Shizuku",
            )
        }
        val root = mockk<RootExecutor>(relaxed = true).apply {
            coEvery { isAvailable() } returns false
        }
        return AutomationManager(context = context, shizukuAuthorizer = shizuku, rootExecutor = root)
    }

    private fun fakeService(connected: Boolean): MuseAccessibilityService {
        // 用真实实例 + 反射设私有 connected 字段：
        // AccessibilityService 是 Android framework 类，mockk 在 Robolectric 下代理不稳定；
        // 而 isAccessibilityServiceEnabled() 非 open，无法用匿名子类覆盖。
        // 注意：必须用 app 模块的子类（automation.executors.MuseAccessibilityService），
        // 因为 AccessibilityExecutor 读的是它的 companion.instance（对基类实例做 as? 转换）。
        val svc = MuseAccessibilityService()
        val field = io.zer0.muse.accessibility.MuseAccessibilityService::class.java.getDeclaredField("connected")
        field.isAccessible = true
        field.setBoolean(svc, connected)
        return svc
    }

    @Test
    fun `cached state stays false until refresh, but liveSnapshot reflects a late connection`() = runTest {
        val mgr = manager()

        // 启动时服务未连接 → 缓存探测得 a11y=false
        mgr.refreshPermissions()
        assertFalse("启动时无障碍未连接", mgr.permissionState.value.accessibilityEnabled)

        // 用户随后在系统设置开启无障碍 → 服务异步连接（模拟 onServiceConnected 后的静态实例）
        io.zer0.muse.accessibility.MuseAccessibilityService.instance = fakeService(connected = true)

        // 关键断言：缓存仍是 false（bug 的根源），但实时快照已能反映真实状态
        assertFalse("缓存不会自动更新（这正是 bug）", mgr.permissionState.value.accessibilityEnabled)
        assertTrue("liveSnapshot 必须实时反映已连接的无障碍服务", mgr.liveSnapshot().accessibilityEnabled)
    }

    @Test
    fun `liveSnapshot writes back to cached state for observers`() = runTest {
        val mgr = manager()
        mgr.refreshPermissions()
        io.zer0.muse.accessibility.MuseAccessibilityService.instance = fakeService(connected = true)

        mgr.liveSnapshot()

        // 实时探测结果应写回缓存，让依赖 permissionState 的 UI 保持一致
        assertTrue("实时探测后缓存应同步更新", mgr.permissionState.value.accessibilityEnabled)
    }

    @Test
    fun `liveSnapshot reflects disconnection`() = runTest {
        val mgr = manager()
        io.zer0.muse.accessibility.MuseAccessibilityService.instance = fakeService(connected = true)
        mgr.refreshPermissions()
        assertTrue("连接时缓存应为 true", mgr.permissionState.value.accessibilityEnabled)

        // 服务断开
        io.zer0.muse.accessibility.MuseAccessibilityService.instance = null

        assertFalse("断开后 liveSnapshot 必须为 false", mgr.liveSnapshot().accessibilityEnabled)
    }

    @Test
    fun `liveSnapshot is cheap and does not require refresh`() = runTest {
        val mgr = manager()
        // 未调用过 refreshPermissions，缓存是初始默认值（a11y=false）
        io.zer0.muse.accessibility.MuseAccessibilityService.instance = fakeService(connected = true)

        assertTrue(
            "即使从未 refresh，liveSnapshot 也应直接反映服务状态",
            mgr.liveSnapshot().accessibilityEnabled,
        )
    }
}
