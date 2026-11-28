package io.zer0.muse.automation.capability

import android.os.Build
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.automation.core.PermissionLevel
import io.zer0.muse.automation.executors.RootExecutor
import io.zer0.muse.hook.PromptContext
import io.zer0.muse.tools.system.ShizukuAuthorizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [DeviceCapabilityProfile] / [DeviceCapabilityHook] 定向测试。
 *
 * 锁定两件事：
 *  1. 档案渲染反映**真实**通道可用性（哪层可用、能不能截屏、最高层级），
 *     不把「没有权限」渲染成「能力不存在」，也不反过来谎报可用；
 *  2. Hook 的行为边界：子代理不注入、探测失败不抛异常也不丢整段档案。
 *
 * [RootExecutor]/[ShizukuAuthorizer] 用 mock 注入（见 AutomationManager 可注入构造参数），
 * 不会真的执行 su / bind Shizuku。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeviceCapabilityProfileTest {

    /**
     * 直接给定权限状态的 manager。
     *
     * Hook 测试关心的是「拿到状态后怎么渲染」，而不是探测本身能否在测试环境跑通
     * （Robolectric 下无障碍服务恒为未启用，会让断言变成对影子实现的断言）。
     */
    private fun managerWithState(accessibility: Boolean, shell: Boolean, root: Boolean): AutomationManager {
        val state = AutomationManager.PermissionState(
            accessibilityEnabled = accessibility,
            shellEnabled = shell,
            rootEnabled = root,
            shizukuState = if (shell) {
                ShizukuAuthorizer.ShizukuState.READY
            } else {
                ShizukuAuthorizer.ShizukuState.NOT_INSTALLED
            },
            shizukuMessage = "test",
        )
        return mockk<AutomationManager>().apply {
            coEvery { refreshPermissions() } returns state
            every { permissionState } returns MutableStateFlow(state)
        }
    }

    private fun promptContext(forSubagent: Boolean = false, userInput: String = "帮我打开应用截屏") = PromptContext(
        assistantId = "a1",
        sessionId = "s1",
        locale = "zh",
        forSubagent = forSubagent,
        userInput = userInput,
    )

    // ── 档案派生属性 ────────────────────────────────────────────

    @Test
    fun `no channel means no control capability and no screenshot`() {
        val profile = DeviceCapabilityProfile()
        assertEquals(PermissionLevel.NONE, profile.highestLevel)
        assertFalse(profile.hasControlChannel)
        assertFalse(profile.canScreenshot)
    }

    @Test
    fun `accessibility screenshot follows Android API capability`() {
        val profile = DeviceCapabilityProfile.fromFlags(accessibility = true, shizuku = false, root = false)
        assertEquals(PermissionLevel.ACCESSIBILITY, profile.highestLevel)
        assertTrue(profile.hasControlChannel)
        assertEquals(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE, profile.accessibilityScreenshot)
        assertEquals(profile.accessibilityScreenshot, profile.canScreenshot)
    }

    @Test
    fun `shizuku enables screenshot and outranks accessibility`() {
        val profile = DeviceCapabilityProfile.fromFlags(accessibility = true, shizuku = true, root = false)
        assertEquals(PermissionLevel.SHELL, profile.highestLevel)
        assertTrue(profile.canScreenshot)
    }

    @Test
    fun `root is the highest level`() {
        val profile = DeviceCapabilityProfile.fromFlags(accessibility = false, shizuku = true, root = true)
        assertEquals(PermissionLevel.ROOT, profile.highestLevel)
        assertTrue(profile.canScreenshot)
    }

    // ── 面向模型的渲染 ──────────────────────────────────────────

    @Test
    fun `rendered profile states each channel truthfully`() {
        val text = DeviceCapabilityProfile.fromFlags(
            accessibility = true,
            shizuku = false,
            root = false,
            virtualDisplay = false,
            nodeRuntime = true,
        ).renderForModel()

        assertTrue(text.contains("设备能力档案"))
        assertTrue(text.contains("无障碍(读屏/手势/输入): 可用"))
        assertTrue(text.contains("Shell 通道(Shizuku/adb: input/screencap/pm/am/settings): 不可用"))
        assertTrue(text.contains("Root(su: 系统分区读写/完整控制): 不可用"))
        assertTrue(text.contains("截屏能力: 不可用"))
        assertTrue(text.contains("Linux 沙盒运行时(Node/Python 脚本与数据处理): 可用"))
        assertTrue(text.contains("当前最高权限层: 无障碍"))
    }

    @Test
    fun `rendered profile warns when no control channel exists`() {
        val text = DeviceCapabilityProfile().renderForModel()
        assertTrue(text.contains("当前最高权限层: 无（只能做应用内操作，无法控制其他应用）"))
        assertTrue(text.contains("截屏能力: 不可用"))
    }

    @Test
    fun `operating guide covers channel selection verification and escalation`() {
        val guide = DeviceCapabilityProfile.fromFlags(
            accessibility = true,
            shizuku = false,
            root = false,
        ).renderOperatingGuide()
        // 先探测再动手
        assertTrue(guide, guide.contains("动手前先确认能力"))
        // 按最低够用层级选通道（避免一上来就动 Root）
        assertTrue(guide, guide.contains("按最低够用层级选通道"))
        // 应用层工具无需自动化授权 → 必须写进规程，否则模型会绕过最省事的一层
        assertTrue(guide, guide.contains("app_list"))
        assertTrue(guide, guide.contains("app_launch"))
        assertTrue(guide, guide.contains("app_settings"))
        assertTrue(guide, guide.contains("app_force_stop"))
        // 每步验证
        assertTrue(guide, guide.contains("每步都要验证"))
        // 重复流程写脚本
        assertTrue(guide, guide.contains("优先写成脚本"))
        // 缺权限如实说
        assertTrue(guide, guide.contains("缺权限就如实说"))
        assertTrue(guide, guide.contains("display=auto"))
        assertTrue(guide, guide.contains("本次专属虚拟屏"))
    }

    // ── Hook 行为边界 ──────────────────────────────────────────

    @Test
    fun `hook injects profile and guide for a normal session`() = runTest {
        val hook = DeviceCapabilityHook(
            automationManager = managerWithState(accessibility = true, shell = false, root = false),
            nodeRuntimeProbe = { true },
        )
        val injected = hook.afterComposeSystemPrompt(promptContext())

        assertTrue(injected, injected.contains("设备能力档案"))
        assertTrue(injected, injected.contains("操作手机的决策规程"))
        assertTrue(injected, injected.contains("当前最高权限层: 无障碍"))
        assertTrue(injected, injected.contains("Linux 沙盒运行时(Node/Python 脚本与数据处理): 可用"))
    }

    @Test
    fun `hook stays silent for subagents`() = runTest {
        val hook = DeviceCapabilityHook(
            automationManager = managerWithState(accessibility = true, shell = true, root = true),
        )
        assertEquals("", hook.afterComposeSystemPrompt(promptContext(forSubagent = true)))
    }

    @Test
    fun `hook reports root when all three channels are available`() = runTest {
        val hook = DeviceCapabilityHook(
            automationManager = managerWithState(accessibility = true, shell = true, root = true),
            virtualDisplayProbe = { true },
        )
        val injected = hook.afterComposeSystemPrompt(promptContext())
        assertTrue(injected.contains("当前最高权限层: Root"))
        assertTrue(injected.contains("截屏能力: 可用"))
        assertTrue(injected.contains("虚拟屏(把应用启动到独立屏幕,不打扰用户当前界面): 可用"))
    }

    @Test
    fun `hook never throws even when probes fail`() = runTest {
        // 虚拟屏与 Node 探测都抛异常：档案仍应渲染出（能力记为不可用），不得向上抛。
        val hook = DeviceCapabilityHook(
            automationManager = managerWithState(accessibility = false, shell = false, root = false),
            virtualDisplayProbe = { error("boom") },
            nodeRuntimeProbe = { error("boom") },
        )
        val injected = hook.afterComposeSystemPrompt(promptContext())
        assertTrue(injected.contains("设备能力档案"))
        assertTrue(injected.contains("虚拟屏(把应用启动到独立屏幕,不打扰用户当前界面): 不可用"))
    }

    @Test
    fun `hook injects compact one-line summary for casual chat`() = runTest {
        val hook = DeviceCapabilityHook(
            automationManager = managerWithState(accessibility = true, shell = false, root = false),
            nodeRuntimeProbe = { true },
        )
        val injected = hook.afterComposeSystemPrompt(promptContext(userInput = "你好呀"))
        assertTrue(injected.startsWith("## 设备能力\n"))
        assertFalse("闲聊不得注入完整操作规程", injected.contains("操作手机的决策规程"))
    }

    @Test
    fun `hook id and priority keep it ahead of worldbook`() {
        val hook = DeviceCapabilityHook(
            automationManager = managerWithState(accessibility = false, shell = false, root = false),
        )
        assertEquals("device_capability_profile", hook.id)
        assertTrue("设备档案应先于 Worldbook(60) 注入", hook.priority > 60)
    }
}
