package io.zer0.muse.automation

import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.automation.tools.permissionGuidance
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 权限状态的可执行指引。
 *
 * 背景：以前 `screen_permission_status` 只输出「未开启」，模型只能回一句
 * 「请用户去设置里开一下」——对 personal agent 来说这是断头路。
 * 现在缺哪层就给出对应的 `screen_open_settings` target。
 */
class AutomationPermissionGuidanceTest {

    @Test
    fun `no channel ready points at both accessibility and shell`() {
        val text = permissionGuidance(
            AutomationManager.PermissionState(
                accessibilityEnabled = false,
                shellEnabled = false,
                rootEnabled = false,
            ),
        )
        assertTrue("应指引无障碍", text.contains("target=accessibility"))
        assertTrue("应指引 Shell", text.contains("Shizuku"))
        assertTrue("应让模型回读状态", text.contains("screen_permission_status"))
    }

    @Test
    fun `accessibility missing but shell ready only asks for accessibility`() {
        val text = permissionGuidance(
            AutomationManager.PermissionState(
                accessibilityEnabled = false,
                shellEnabled = true,
                rootEnabled = false,
            ),
        )
        assertTrue(text.contains("target=accessibility"))
        assertTrue("已有 Shell 就不该再提 Shell", !text.contains("Shizuku"))
    }

    @Test
    fun `nothing to say when accessibility and shell are both ready`() {
        val text = permissionGuidance(
            AutomationManager.PermissionState(
                accessibilityEnabled = true,
                shellEnabled = true,
                rootEnabled = false,
            ),
        )
        assertTrue("都就绪时不该有多余提示", text.isEmpty())
    }
}
