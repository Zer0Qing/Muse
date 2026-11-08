package io.zer0.muse.automation.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.verify
import io.mockk.every
import io.mockk.mockk
import io.zer0.muse.R
import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * UI 自动化权限页顶栏测试。
 *
 * 回归锁定:页面必须渲染带返回键的二级页顶栏,且返回键真的调用 [AutomationSettingsPage] 的
 * `onBack`(此前导航图传了 onBack 但页面没有使用)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "zh-rCN")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AutomationSettingsPageTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun managerMock(
        accessibilityEnabled: Boolean = false,
    ): AutomationManager = mockk<AutomationManager>(relaxed = true).apply {
        val state = AutomationManager.PermissionState(accessibilityEnabled = accessibilityEnabled)
        every { permissionState } returns MutableStateFlow(state)
        coEvery { refreshPermissions() } returns state
    }

    @Test
    fun `automation settings exposes the Tasker trigger toggle wiring`() {
        val source = File("src/main/java/io/zer0/muse/automation/ui/AutomationSettingsPage.kt").readText()
        assertTrue(source.contains("taskerTriggerEnabledFlow"))
        assertTrue(source.contains("saveTaskerTriggerEnabled"))
        assertTrue(source.contains("automation_tasker_title"))
        assertTrue(source.contains("automation_tasker_desc"))
    }

    @Test
    fun `top bar back key invokes onBack`() {
        var backCount = 0
        composeTestRule.setContent {
            MaterialTheme {
                AutomationSettingsPage(
                    manager = managerMock(),
                    onBack = { backCount++ },
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.action_back))
            .performClick()

        assertEquals(1, backCount)
    }

    @Test
    fun `permission wizard entry invokes onOpenPermissionWizard`() {
        var wizardCount = 0
        composeTestRule.setContent {
            MaterialTheme {
                AutomationSettingsPage(
                    manager = managerMock(),
                    onBack = {},
                    onOpenPermissionWizard = { wizardCount++ },
                )
            }
        }

        // 25: 向导入口收进「权限与接入」子页 — 先滚到菜单项点进子页，再点向导入口验证接线。
        composeTestRule
            .onNodeWithTag("settings-scroll-container")
            .performScrollToNode(hasText(context.getString(R.string.automation_sub_access)))
        composeTestRule
            .onNodeWithText(context.getString(R.string.automation_sub_access))
            .performClick()
        composeTestRule
            .onNodeWithText(context.getString(R.string.permission_wizard_title))
            .performClick()

        assertEquals(1, wizardCount)
    }

    private fun settingsMock(automationEnabled: Boolean): SettingsRepository =
        mockk<SettingsRepository>(relaxed = true).apply {
            every { automationEnabledFlow } returns MutableStateFlow(automationEnabled)
            every { taskerTriggerEnabledFlow } returns MutableStateFlow(false)
        }

    @Test
    fun `master switch off shows the disabled banner`() {
        composeTestRule.setContent {
            MaterialTheme {
                AutomationSettingsPage(
                    manager = managerMock(),
                    onBack = {},
                    settings = settingsMock(automationEnabled = false),
                )
            }
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.automation_master_off_banner))
            .assertExists()
    }

    @Test
    fun `master switch on hides the disabled banner`() {
        composeTestRule.setContent {
            MaterialTheme {
                AutomationSettingsPage(
                    manager = managerMock(),
                    onBack = {},
                    settings = settingsMock(automationEnabled = true),
                )
            }
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.automation_master_off_banner))
            .assertDoesNotExist()
    }

    @Test
    fun `a11y off banner shows when automation is on but service is disconnected`() {
        composeTestRule.setContent {
            MaterialTheme {
                AutomationSettingsPage(manager = managerMock(accessibilityEnabled = false), onBack = {})
            }
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.automation_a11y_off_banner))
            .assertExists()
    }

    @Test
    fun `a11y off banner is hidden when accessibility is connected`() {
        composeTestRule.setContent {
            MaterialTheme {
                AutomationSettingsPage(manager = managerMock(accessibilityEnabled = true), onBack = {})
            }
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.automation_a11y_off_banner))
            .assertDoesNotExist()
    }

    /**
     * 两条横幅**不能同时出现**。
     *
     * 各自单独存在都有测试了，但"总闸关着 + 服务也断了"这一格才是互斥规则真正管的情形：
     * 这时候只该说「总闸关着，所以现在什么都不跑」，
     * 不该同时催用户去开无障碍（两句放一起只会让人以为开了无障碍就能用）。
     */
    @Test
    fun `master off and a11y off never light up together`() {
        composeTestRule.setContent {
            MaterialTheme {
                AutomationSettingsPage(
                    manager = managerMock(accessibilityEnabled = false),
                    onBack = {},
                    settings = settingsMock(automationEnabled = false),
                )
            }
        }

        composeTestRule
            .onNodeWithText(context.getString(R.string.automation_master_off_banner))
            .assertExists()
        composeTestRule
            .onNodeWithText(context.getString(R.string.automation_a11y_off_banner))
            .assertDoesNotExist()
    }

    @Test
    fun `a11y off banner action opens system accessibility settings`() {
        val manager = managerMock(accessibilityEnabled = false)
        composeTestRule.setContent {
            MaterialTheme {
                AutomationSettingsPage(manager = manager, onBack = {})
            }
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.automation_a11y_off_action))
            .performClick()
        verify {
            manager.openSystemSettings(AutomationManager.SystemSettingsTarget.ACCESSIBILITY)
        }
    }
}
