package io.zer0.muse.automation.capability

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 总闸关闭时必须**在提示词里就说清楚**。
 *
 * 只靠工具调用失败才发现「闸关了」，会白白烧掉一轮甚至几轮 ——
 * 对一个多步 agent 来说，那是最贵的浪费。
 */
class DeviceCapabilityAutomationSwitchTest {

    @Test
    fun `guide starts with the master switch warning when automation is off`() {
        val profile = DeviceCapabilityProfile.fromFlags(
            accessibility = true,
            shizuku = false,
            root = false,
            automationEnabled = false,
        )
        val guide = profile.renderOperatingGuide()
        assertTrue("应出现总闸关闭小节", guide.contains("自动化总闸已关闭"))
        assertTrue("应说明哪些工具会被拒", guide.contains("automation_workflow"))
        assertTrue("应给出去哪儿打开", guide.contains("允许屏幕自动化"))
        assertTrue("应指出还能用的工具", guide.contains("app_launch"))
        assertFalse("不应声称可以正常操作手机", guide.startsWith("## 操作手机的决策规程"))
    }

    @Test
    fun `guide has no master switch warning when automation is on`() {
        val profile = DeviceCapabilityProfile.fromFlags(
            accessibility = true,
            shizuku = false,
            root = false,
            automationEnabled = true,
        )
        val guide = profile.renderOperatingGuide()
        assertFalse(guide.contains("自动化总闸已关闭"))
        assertTrue(guide.startsWith("## 操作手机的决策规程"))
    }
}
