package io.zer0.muse.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.R
import io.zer0.muse.ui.memory.TimelineEventCard
import io.zer0.muse.ui.memory.TimelineItem
import io.zer0.muse.ui.memory.TimelineItemActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

/**
 * 时间轴卡片删除链路：长按 → 动作面板 → 二次确认 → 按底层条目删除。
 *
 * 时间轴卡片只带展示字段，删的是哪一条全靠 [TimelineItem.itemId] / [TimelineItem.scope]
 * 还原；摘要卡片还要落到正确的会话上。所以这里断言的是「传给删除的实际参数」，
 * 不是「界面上少了一行」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MemoryTimelineDeleteTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val factCard = TimelineItem(
        id = "Fact_42",
        content = FACT_CONTENT,
        source = "Fact",
        importance = 1,
        createdAt = "2026-10-01T09:00:00Z",
        itemId = "42",
        scope = "main",
    )

    private val summaryCard = TimelineItem(
        id = "Summary_session-9",
        content = SUMMARY_CONTENT,
        source = "Summary",
        importance = 0,
        createdAt = "2026-10-02T09:00:00Z",
        itemId = "session-9",
        sessionId = "session-9",
        scope = "main",
    )

    @Test
    fun longPressFactCardDeletesThroughConfirmation() {
        val factDeletes = AtomicReference<List<String>>(emptyList())
        val summaryDeletes = AtomicReference<List<String>>(emptyList())
        setTimeline(factCard, factDeletes, summaryDeletes)

        composeTestRule.onNodeWithText(FACT_CONTENT).performTouchInput { longClick() }
        composeTestRule.onNodeWithText(context.getString(R.string.memory_menu_delete)).performClick()

        // 二次确认出现，此时还没有删除
        composeTestRule.onNodeWithText(context.getString(R.string.memory_delete_confirm_title)).assertExists()
        assertEquals("confirm is required before a fact is deleted", 0, factDeletes.get().size)

        composeTestRule.onNodeWithText(context.getString(R.string.common_delete)).performClick()

        assertEquals(listOf("42"), factDeletes.get())
        assertEquals("a fact delete must not touch session summaries", 0, summaryDeletes.get().size)
    }

    @Test
    fun longPressSummaryCardDeletesItsSession() {
        val factDeletes = AtomicReference<List<String>>(emptyList())
        val summaryDeletes = AtomicReference<List<String>>(emptyList())
        setTimeline(summaryCard, factDeletes, summaryDeletes)

        composeTestRule.onNodeWithText(SUMMARY_CONTENT).performTouchInput { longClick() }
        composeTestRule.onNodeWithText(context.getString(R.string.memory_menu_delete)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.memory_delete_confirm_title)).assertExists()

        composeTestRule.onNodeWithText(context.getString(R.string.common_delete)).performClick()

        assertEquals("the summary delete must carry this card's sessionId", listOf("session-9"), summaryDeletes.get())
        assertEquals("a summary delete must not touch facts", 0, factDeletes.get().size)
    }

    @Test
    fun cancellingConfirmationDeletesNothing() {
        val factDeletes = AtomicReference<List<String>>(emptyList())
        val summaryDeletes = AtomicReference<List<String>>(emptyList())
        setTimeline(factCard, factDeletes, summaryDeletes)

        composeTestRule.onNodeWithText(FACT_CONTENT).performTouchInput { longClick() }
        composeTestRule.onNodeWithText(context.getString(R.string.memory_menu_delete)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.common_cancel)).performClick()

        assertEquals("cancelling the confirmation must delete nothing", 0, factDeletes.get().size)
        assertEquals(0, summaryDeletes.get().size)
        composeTestRule.onNodeWithText(context.getString(R.string.memory_delete_confirm_title)).assertDoesNotExist()
    }

    @Test
    fun bulkDeleteAsksFirstAndThenDeletesEverySelectedId() {
        val deleted = AtomicReference<List<String>?>(null)
        val picked =
            listOf(
                MemoryItem(id = "1", title = "first", content = "first", source = "Fact", scope = "main"),
                MemoryItem(id = "2", title = "second", content = "second", source = "Fact", scope = "main"),
            )
        composeTestRule.setContent {
            MaterialTheme {
                var pending by remember { mutableStateOf<List<MemoryItem>?>(null) }
                Column {
                    MemorySelectionActions(
                        selectedCount = picked.size,
                        mergeRunning = false,
                        onCancel = {},
                        onMerge = {},
                        onDelete = { pending = picked },
                    )
                }
                pending?.let { targets ->
                    MemoryBulkDeleteDialog(
                        count = targets.size,
                        onConfirm = {
                            pending = null
                            deleted.set(targets.map { it.id })
                        },
                        onDismiss = { pending = null },
                    )
                }
            }
        }

        // 点批量删除先弹确认，不直接落库
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.action_delete)).performClick()
        assertNull("bulk delete must not run before confirmation", deleted.get())
        composeTestRule.onNodeWithText(context.getString(R.string.memory_bulk_delete_confirm_title)).assertExists()
        composeTestRule
            .onNodeWithText(context.getString(R.string.memory_bulk_delete_consequence, picked.size))
            .assertExists()

        // 取消不删
        composeTestRule.onNodeWithText(context.getString(R.string.common_cancel)).performClick()
        assertNull("cancelling must delete nothing", deleted.get())

        // 再来一次，确认后才删，且两条 id 都在
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.action_delete)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.common_delete)).performClick()

        assertEquals(listOf("1", "2"), deleted.get())
    }

    /**
     * 拼出与 MemoryScreen 相同的接线：卡片长按开面板，面板确认后经 [routeMemoryItemAction] 删除。
     *
     * 刻意复用生产组件而不是另写一套假界面 —— 面板、确认框、分派三处任何一处接错这里都会红。
     */
    private fun setTimeline(
        card: TimelineItem,
        factDeletes: AtomicReference<List<String>>,
        summaryDeletes: AtomicReference<List<String>>,
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                var target by remember { mutableStateOf<TimelineItem?>(null) }
                Box {
                    TimelineEventCard(item = card, onLongPress = { target = card })
                }
                target?.let { pending ->
                    TimelineItemActions(
                        target = pending,
                        onDelete = { routable ->
                            routeMemoryItemAction(
                                item = routable,
                                onFact = { factId, _ -> factDeletes.set(factDeletes.get() + factId) },
                                onSummary = { sessionId -> summaryDeletes.set(summaryDeletes.get() + sessionId) },
                                onCompile = {},
                                onExperience = {},
                                onUnsupported = {},
                            )
                            target = null
                        },
                        onDismiss = { target = null },
                    )
                }
            }
        }
    }

    private companion object {
        private const val FACT_CONTENT = "buy milk on Friday"
        private const val SUMMARY_CONTENT = "we settled the travel plan for October"
    }
}
