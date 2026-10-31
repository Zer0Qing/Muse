package io.zer0.muse.ui

import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 多选态动作栏的可用性约束。
 *
 * 合并期间删掉源行，落库时找不到源只能报「合并失败」，用户却什么都没改过——
 * 所以这段时间必须把删除一起挡住，而不仅是把合并入口换成 spinner。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MemorySelectionActionsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun setActions(selectedCount: Int, mergeRunning: Boolean, onMerge: () -> Unit = {}, onDelete: () -> Unit = {}) {
        composeTestRule.setContent {
            MaterialTheme {
                // 生产里这三个按钮排在 MuseTopBar 的 Row 里；测试也要给行容器，
                // 否则根布局把它们叠在原点，点击全落到最上面那个按钮。
                Row {
                    MemorySelectionActions(
                        selectedCount = selectedCount,
                        mergeRunning = mergeRunning,
                        onCancel = {},
                        onMerge = onMerge,
                        onDelete = onDelete,
                    )
                }
            }
        }
    }

    @Test
    fun mergingBlocksDeleteAndReplacesMergeEntry() {
        val merged = AtomicBoolean(false)
        val deleted = AtomicBoolean(false)
        setActions(
            selectedCount = 2,
            mergeRunning = true,
            onMerge = { merged.set(true) },
            onDelete = { deleted.set(true) },
        )

        composeTestRule.onNodeWithContentDescription(context.getString(R.string.memory_center_merge_action))
            .assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.memory_center_merge_running))
            .assertExists()

        val delete = composeTestRule.onNodeWithContentDescription(context.getString(R.string.action_delete))
        delete.assertExists()
        delete.assertIsNotEnabled()
        delete.performClick()

        assertFalse("deleting the sources mid-merge would make the merge fail for no visible reason", deleted.get())
        assertFalse("the merge entry must not fire twice", merged.get())
    }

    @Test
    fun idleSelectionKeepsBothActionsAvailable() {
        val merged = AtomicBoolean(false)
        val deleted = AtomicBoolean(false)
        setActions(
            selectedCount = 2,
            mergeRunning = false,
            onMerge = { merged.set(true) },
            onDelete = { deleted.set(true) },
        )

        val merge = composeTestRule.onNodeWithContentDescription(context.getString(R.string.memory_center_merge_action))
        merge.assertIsEnabled()
        merge.performClick()
        val delete = composeTestRule.onNodeWithContentDescription(context.getString(R.string.action_delete))
        delete.assertIsEnabled()
        delete.performClick()

        assertTrue(merged.get())
        assertTrue(deleted.get())
    }

    @Test
    fun singleSelectionDisablesMergeButStillAllowsDelete() {
        setActions(selectedCount = 1, mergeRunning = false)

        composeTestRule.onNodeWithContentDescription(context.getString(R.string.memory_center_merge_action))
            .assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.action_delete))
            .assertIsEnabled()
    }
}
