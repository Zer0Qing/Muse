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
import io.mockk.every
import io.mockk.mockk
import io.zer0.muse.R
import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.ui.settings.SETTINGS_SCROLL_CONTAINER_TAG
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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

    private fun managerMock(): AutomationManager = mockk<AutomationManager>(relaxed = true).apply {
        every { permissionState } returns MutableStateFlow(AutomationManager.PermissionState())
        coEvery { refreshPermissions() } returns AutomationManager.PermissionState()
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
    fun `root card exposes fallback entry for magisk or app settings`() {
        composeTestRule.setContent {
            MaterialTheme {
                AutomationSettingsPage(manager = managerMock(), onBack = {})
            }
        }

        // Root 层未开启时,主行为是请求授权,同时保留外部兜底入口
        val fallback = context.getString(R.string.automation_root_fallback_action)
        composeTestRule
            .onNodeWithTag(SETTINGS_SCROLL_CONTAINER_TAG)
            .performScrollToNode(hasText(fallback))
        composeTestRule
            .onNodeWithText(context.getString(R.string.automation_root_action_request))
            .assertExists()
        composeTestRule
            .onNodeWithText(fallback)
            .assertExists()
    }
}
