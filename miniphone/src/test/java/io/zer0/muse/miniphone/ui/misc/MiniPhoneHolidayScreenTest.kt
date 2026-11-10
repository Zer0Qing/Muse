package io.zer0.muse.miniphone.ui.misc

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.miniphone.R
import io.zer0.muse.miniphone.data.db.entity.MpHolidayEventEntity
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 节日页：状态由可替换的 Flow 与回调驱动，不碰 Room。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class MiniPhoneHolidayScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val rows = MutableStateFlow(emptyList<MpHolidayEventEntity>())
    private val added = mutableListOf<List<String>>()
    private val deleted = mutableListOf<String>()
    private var backClicks = 0
    private var nextId = 0

    private fun res(id: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(id)

    private fun event(id: String, dateKey: String, name: String, year: Int) = MpHolidayEventEntity(
        id = id,
        dateKey = dateKey,
        name = name,
        note = "",
        year = year,
    )

    private fun render() {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneHolidayScreen(
                    events = rows,
                    onAdd = { dateKey, name, note, year ->
                        added += listOf(dateKey, name, note, year.toString())
                        rows.value = rows.value + event("h-${nextId++}", dateKey, name, year)
                    },
                    onDelete = { id ->
                        deleted += id
                        rows.value = rows.value.filterNot { it.id == id }
                    },
                    onBack = { backClicks += 1 },
                )
            }
        }
    }

    @Test
    fun `空态提示可见`() {
        render()
        compose.onNodeWithTag(MiniPhoneMiscTags.Holiday.EMPTY).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneMiscTags.Holiday.PAGE).assertIsDisplayed()
        compose.onNodeWithText(res(R.string.mp_holiday_empty)).assertIsDisplayed()
    }

    @Test
    fun `默认每年重复添加带零年份`() {
        render()
        compose.onNodeWithTag(MiniPhoneMiscTags.Holiday.NAME_INPUT).performTextInput("情人节")
        compose.onNodeWithTag(MiniPhoneMiscTags.Holiday.DATE_INPUT).performTextInput("02-14")
        compose.onNodeWithTag(MiniPhoneMiscTags.Holiday.ADD).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(MiniPhoneMiscTags.Holiday.event("h-0")).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(listOf(listOf("02-14", "情人节", "", "0")), added)
    }

    @Test
    fun `切到只一次后添加带正年份`() {
        render()
        compose.onNodeWithTag(MiniPhoneMiscTags.Holiday.MODE_ONCE).performClick()
        compose.onNodeWithTag(MiniPhoneMiscTags.Holiday.NAME_INPUT).performTextInput("纪念日")
        compose.onNodeWithTag(MiniPhoneMiscTags.Holiday.DATE_INPUT).performTextInput("2026-02-14")
        compose.onNodeWithTag(MiniPhoneMiscTags.Holiday.YEAR_INPUT).performTextInput("2026")
        compose.onNodeWithTag(MiniPhoneMiscTags.Holiday.ADD).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(MiniPhoneMiscTags.Holiday.event("h-0")).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(listOf(listOf("2026-02-14", "纪念日", "", "2026")), added)
    }

    @Test
    fun `列表区分每年重复与只一次`() {
        rows.value = listOf(event("rec", "02-14", "每年情人节", 0), event("once", "2026-02-14", "一次性纪念日", 2026))
        render()
        val recurring = res(R.string.mp_holiday_recurring)
        val once = res(R.string.mp_holiday_once)
        // 顶部模式选择用的也是这两句话，只数整句会一直成立：断言要落在列表里各自那一行上。
        // 列表在添加面板下面，Robolectric 首屏放不下，先滚进可见区再断言。
        compose.onNodeWithTag(MiniPhoneMiscTags.Holiday.event("rec")).performScrollTo().assertIsDisplayed()
        compose.onNode(hasTestTag(MiniPhoneMiscTags.Holiday.event("rec")) and hasAnyDescendant(hasText(recurring)))
            .assertIsDisplayed()
        compose.onNode(hasTestTag(MiniPhoneMiscTags.Holiday.event("rec")) and hasAnyDescendant(hasText(once)))
            .assertDoesNotExist()
        compose.onNodeWithTag(MiniPhoneMiscTags.Holiday.LIST).performScrollToIndex(1)
        compose.onNodeWithTag(MiniPhoneMiscTags.Holiday.event("once")).performScrollTo().assertIsDisplayed()
        compose.onNode(hasTestTag(MiniPhoneMiscTags.Holiday.event("once")) and hasAnyDescendant(hasText(once)))
            .assertIsDisplayed()
        compose.onNode(hasTestTag(MiniPhoneMiscTags.Holiday.event("once")) and hasAnyDescendant(hasText(recurring)))
            .assertDoesNotExist()
    }

    @Test
    fun `点返回触发回调`() {
        render()
        compose.onNodeWithTag(MiniPhoneMiscTags.Holiday.BACK).performClick()
        assertEquals(1, backClicks)
    }
}
