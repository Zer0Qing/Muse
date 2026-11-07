package io.zer0.muse.permission

import io.zer0.common.Logger
import io.zer0.muse.terminal.TermuxChannel
import io.zer0.muse.tools.system.AccessibilityProviderInstaller
import io.zer0.muse.tools.system.RootAuthorizer
import io.zer0.muse.tools.system.ShizukuAuthorizer
import io.zer0.muse.tools.system.ShizukuInstaller
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 权限体系重设计: 协调层被动刷新与快照派生标志的单测。
 *
 * 锁定：refresh() 把四通道探针结果汇成单一快照，shellEnabled/rootEnabled/anyEnabled
 * 派生正确；单通道探针抛异常时降级为不可用而不击穿整页。依赖全部 mock，不触真实 su/Shizuku。
 */
class PermissionCoordinatorTest {

    private var previousLoggerEnabled = true

    @Before
    fun setUp() {
        // Logger.enabled 是进程级全局开关：禁用后必须还原，否则会污染同 JVM 后续测试
        // （如 EmbeddingServiceCacheTest 依赖 warn 日志捕获，enabled=false 会让它收不到日志）。
        previousLoggerEnabled = Logger.enabled
        Logger.enabled = false
    }

    @After
    fun tearDown() {
        Logger.enabled = previousLoggerEnabled
    }

    private val shizuku = mockk<ShizukuAuthorizer>()
    private val shizukuInstaller = mockk<ShizukuInstaller>()
    private val root = mockk<RootAuthorizer>()
    private val a11y = mockk<AccessibilityProviderInstaller>()
    private val termux = mockk<TermuxChannel>()

    private fun coordinator() = PermissionCoordinator(shizuku, shizukuInstaller, root, a11y, termux)

    private fun stubDefaults() {
        every { a11y.isEnabled() } returns false
        every { a11y.isProviderInstalled() } returns false
        every { a11y.isProviderApkBundled() } returns false
        every { shizukuInstaller.isInstalled() } returns false
        coEvery { shizuku.diagnose() } returns ShizukuAuthorizer.ShizukuStatus(
            ShizukuAuthorizer.ShizukuState.NOT_INSTALLED,
            "未安装",
        )
        every { root.grantState() } returns RootAuthorizer.GrantState.NoRoot
        every { root.installedManager() } returns null
        every { termux.availability() } returns TermuxChannel.Availability.NotInstalled
    }

    @Test
    fun `ready shizuku and granted root derive enabled flags`() = runTest {
        stubDefaults()
        every { a11y.isEnabled() } returns true
        every { shizukuInstaller.isInstalled() } returns true
        coEvery { shizuku.diagnose() } returns ShizukuAuthorizer.ShizukuStatus(
            ShizukuAuthorizer.ShizukuState.READY,
            "就绪",
        )
        every { root.grantState() } returns RootAuthorizer.GrantState.Granted

        val snap = coordinator().refresh()

        assertTrue(snap.accessibilityEnabled)
        assertTrue(snap.shellEnabled)
        assertTrue(snap.rootEnabled)
        assertTrue(snap.anyEnabled)
        assertEquals(ShizukuAuthorizer.ShizukuState.READY, snap.shizukuState)
    }

    @Test
    fun `previously granted root counts as enabled`() = runTest {
        stubDefaults()
        every { root.grantState() } returns RootAuthorizer.GrantState.PreviouslyGranted

        val snap = coordinator().refresh()

        assertTrue(snap.rootEnabled)
        assertFalse(snap.shellEnabled)
    }

    @Test
    fun `unknown root is not enabled`() = runTest {
        stubDefaults()
        every { root.grantState() } returns RootAuthorizer.GrantState.Unknown

        val snap = coordinator().refresh()

        assertFalse(snap.rootEnabled)
        assertFalse(snap.anyEnabled)
    }

    @Test
    fun `probe exception degrades to unavailable without crashing`() = runTest {
        stubDefaults()
        every { a11y.isEnabled() } throws RuntimeException("boom")
        every { root.grantState() } throws IllegalStateException("no libs u")
        coEvery { shizuku.diagnose() } throws RuntimeException("binder dead")

        val snap = coordinator().refresh()

        assertFalse(snap.accessibilityEnabled)
        assertEquals(RootAuthorizer.GrantState.NoRoot, snap.rootGrantState)
        assertEquals(ShizukuAuthorizer.ShizukuState.USER_SERVICE_UNAVAILABLE, snap.shizukuState)
        assertFalse(snap.anyEnabled)
    }
}
