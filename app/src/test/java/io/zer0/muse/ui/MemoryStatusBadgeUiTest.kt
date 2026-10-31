package io.zer0.muse.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 徽章与详情说明真的渲染出来了才算完成 —— 只测判定函数测不到「列表里看不看得见」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MemoryStatusBadgeUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun factRow(item: MemoryItem) {
        composeTestRule.setContent {
            MaterialTheme {
                MemoryFactRow(item = item)
            }
        }
    }

    private fun factItem(
        mergedIntoId: Long? = null,
        suppressed: Boolean = false,
        superseded: Boolean = false,
    ): MemoryItem = MemoryItem(
        id = "7",
        title = "喜欢咖啡",
        content = "喜欢咖啡",
        source = "Fact",
        scope = "main",
        mergedIntoId = mergedIntoId,
        suppressed = suppressed,
        superseded = superseded,
    )

    @Test
    fun mergedRowShowsMergedBadge() {
        factRow(factItem(mergedIntoId = 42L))

        composeTestRule.onNodeWithText(context.getString(R.string.memory_status_merged)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.memory_status_superseded)).assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.memory_status_suppressed)).assertDoesNotExist()
    }

    @Test
    fun healthyRowShowsNoBadge() {
        factRow(factItem())

        composeTestRule.onNodeWithText(context.getString(R.string.memory_status_merged)).assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.memory_status_suppressed)).assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.memory_status_superseded)).assertDoesNotExist()
    }

    @Test
    fun rowWithAllThreeStatesShowsAllBadges() {
        // 三种状态互相独立：全中时一个都不能被吞掉，只是次要的会画得更淡。
        factRow(factItem(mergedIntoId = 42L, suppressed = true, superseded = true))

        composeTestRule.onNodeWithText(context.getString(R.string.memory_status_superseded)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.memory_status_merged)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.memory_status_suppressed)).assertExists()
    }

    @Test
    fun detailDialogExplainsHitStates() {
        val note = context.getString(R.string.memory_status_merged_detail) + "\n" +
            context.getString(R.string.memory_status_suppressed_detail)

        composeTestRule.setContent {
            MaterialTheme {
                FactEditDialog(
                    title = "编辑记忆",
                    initialContent = "喜欢咖啡",
                    statusNote = note,
                    onDismiss = {},
                    onConfirm = { _, _ -> },
                )
            }
        }

        composeTestRule.onNodeWithText(note).assertIsDisplayed()
    }
}
