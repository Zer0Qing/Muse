package io.zer0.muse.ui.settings

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.R
import io.zer0.muse.ui.SETTINGS_GROUP_ROW_TAG
import io.zer0.muse.ui.SettingsGroupCards
import io.zer0.muse.ui.navigation.AssistantsRoute
import io.zer0.muse.ui.navigation.SettingsProxyRoute
import io.zer0.muse.ui.navigation.UserProfileEditRoute
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 首页分类行与分类页条目行的渲染/点击回归。
 *
 * 断言两件容易回退的事：分类页渲染的是该分类声明的全部条目（不多不少），
 * 点条目触发它自己的路由；首页只有 14 个分类行，子页专有项（权限向导、审计日志等）
 * 不许出现在这一层。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class SettingsGroupScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun string(resId: Int): String = context.getString(resId)

    @Test
    fun `home shows fourteen category rows`() {
        composeTestRule.setContent {
            MaterialTheme {
                SettingsGroupCards(onOpenGroup = {})
            }
        }

        composeTestRule.onAllNodesWithTag(SETTINGS_GROUP_ROW_TAG).assertCountEquals(14)
        SettingsCatalog.groups.forEach { group ->
            composeTestRule.onNodeWithText(string(group.titleRes)).assertIsDisplayed()
        }
    }

    @Test
    fun `home keeps sub page entries inside their category`() {
        composeTestRule.setContent {
            MaterialTheme {
                SettingsGroupCards(onOpenGroup = {})
            }
        }

        // 这些是分类页里的条目，不该作为一级分类行出现。
        listOf(
            R.string.permission_wizard_title,
            R.string.settings_audit_log,
            R.string.settings_screen_pii_guard,
            R.string.settings_screen_data_backup,
        ).forEach { titleRes ->
            composeTestRule
                .onAllNodesWithText(string(titleRes))
                .assertCountEquals(0)
        }
    }

    @Test
    fun `clicking a category row opens that category`() {
        var opened: SettingsGroupKind? = null
        composeTestRule.setContent {
            MaterialTheme {
                SettingsGroupCards(onOpenGroup = { opened = it })
            }
        }

        composeTestRule.onNodeWithText(string(SettingsCatalog.group(SettingsGroupKind.DATA_STORAGE).titleRes))
            .performClick()

        assertEquals(SettingsGroupKind.DATA_STORAGE, opened)
    }

    @Test
    fun `memory category renders exactly its declared entries`() {
        composeTestRule.setContent {
            MaterialTheme {
                SettingsGroupScreen(kind = SettingsGroupKind.MEMORY, onBack = {}, onNavigate = {})
            }
        }

        composeTestRule.onAllNodesWithTag(SETTINGS_ENTRY_ROW_TAG).assertCountEquals(2)
        listOf(SettingsEntryId.MEMORY, SettingsEntryId.RAG).forEach { id ->
            val title = string(SettingsCatalog.entries.first { it.id == id }.titleRes)
            composeTestRule.onNode(hasTestTag(SETTINGS_ENTRY_ROW_TAG) and hasText(title)).assertIsDisplayed()
        }
    }

    @Test
    fun `data and backup category renders all of its entries`() {
        composeTestRule.setContent {
            MaterialTheme {
                SettingsGroupScreen(kind = SettingsGroupKind.DATA_STORAGE, onBack = {}, onNavigate = {})
            }
        }

        composeTestRule.onAllNodesWithTag(SETTINGS_ENTRY_ROW_TAG).assertCountEquals(5)
    }

    @Test
    fun `clicking an entry navigates to its own page`() {
        var navigated: Any? = null
        composeTestRule.setContent {
            MaterialTheme {
                SettingsGroupScreen(
                    kind = SettingsGroupKind.ASSISTANTS,
                    onBack = {},
                    onNavigate = { navigated = it },
                )
            }
        }

        composeTestRule
            .onNode(hasTestTag(SETTINGS_ENTRY_ROW_TAG) and hasText(string(R.string.settings_user_profile_title)))
            .performScrollTo()
            .performClick()
        assertEquals(UserProfileEditRoute, navigated)

        navigated = null
        composeTestRule
            .onNode(hasTestTag(SETTINGS_ENTRY_ROW_TAG) and hasText(string(R.string.chat_agent_tab_title)))
            .performScrollTo()
            .performClick()
        assertEquals(io.zer0.muse.ui.navigation.SettingsAgentRoute, navigated)
    }

    @Test
    fun `clicking an entry in another category navigates too`() {
        var navigated: Any? = null
        composeTestRule.setContent {
            MaterialTheme {
                SettingsGroupScreen(
                    kind = SettingsGroupKind.CONNECTION,
                    onBack = {},
                    onNavigate = { navigated = it },
                )
            }
        }

        composeTestRule
            .onNode(hasTestTag(SETTINGS_ENTRY_ROW_TAG) and hasText(string(R.string.proxy_title)))
            .performScrollTo()
            .performClick()
        assertEquals(SettingsProxyRoute, navigated)
    }

    @Test
    fun `assistant category declares the assistants entry route`() {
        // 助手管理指向助手列表，且这一项在清单里属于「助手与角色」。
        val entry = SettingsCatalog.entries.first { it.id == SettingsEntryId.ASSISTANTS }
        assertEquals(SettingsEntryAction.Navigate(AssistantsRoute), entry.action)
    }
}
