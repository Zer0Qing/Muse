package io.zer0.muse.ui.chat

import io.mockk.mockk
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.ui.taskcard.TaskCardData
import io.zer0.muse.ui.taskcard.TaskCardPhase
import io.zer0.muse.ui.taskcard.TaskStep
import io.zer0.muse.ui.taskcard.TaskStepStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单卡/单工具暂停回归:
 * - 单步暂停只改变目标步骤;
 * - 整卡暂停只标记仍可暂停的步骤,不改动已经运行或完成的步骤。
 */
class ChatTaskCardCoordinatorPauseTest {

    @Test
    fun `toggling one pending step pauses only that tool call`() {
        val accessor = InMemoryChatStateAccessor()
        accessor.update {
            it.copy(
                taskCards = mapOf(
                    CARD_ID to TaskCardData(
                        id = CARD_ID,
                        title = "任务",
                        phase = TaskCardPhase.EXECUTING,
                        steps = listOf(
                            TaskStep("s0", "calculator", status = TaskStepStatus.PENDING, toolCallId = "call-0"),
                            TaskStep("s1", "web_search", status = TaskStepStatus.PENDING, toolCallId = "call-1"),
                        ),
                    ),
                ),
            )
        }
        val coordinator = ChatTaskCardCoordinator(accessor, mockk<ToolRegistry>(relaxed = true))

        coordinator.toggleTaskStepPause(CARD_ID, "s0")

        val steps = accessor.snapshot.taskCards.getValue(CARD_ID).steps
        assertTrue(steps.first { it.id == "s0" }.pauseRequested)
        assertFalse(steps.first { it.id == "s1" }.pauseRequested)
    }

    @Test
    fun `execution claim refuses a step that was paused`() {
        val accessor = InMemoryChatStateAccessor()
        accessor.update {
            it.copy(
                taskCards = mapOf(
                    CARD_ID to TaskCardData(
                        id = CARD_ID,
                        title = "任务",
                        phase = TaskCardPhase.EXECUTING,
                        steps = listOf(
                            TaskStep(
                                "s0",
                                "calculator",
                                status = TaskStepStatus.PENDING,
                                toolCallId = "call-0",
                                pauseRequested = true,
                            ),
                        ),
                    ),
                ),
            )
        }
        val coordinator = ChatTaskCardCoordinator(accessor, mockk<ToolRegistry>(relaxed = true))

        val claimed = coordinator.markTaskStepRunningIfNotPaused(CARD_ID, "call-0", 0) {
            it.copy(status = TaskStepStatus.RUNNING)
        }

        assertFalse(claimed)
        assertTrue(accessor.snapshot.taskCards.getValue(CARD_ID).steps.single().pauseRequested)
        assertTrue(accessor.snapshot.taskCards.getValue(CARD_ID).steps.single().status == TaskStepStatus.PENDING)
    }

    @Test
    fun `pausing a card marks pending steps but leaves running and completed steps alone`() {
        val accessor = InMemoryChatStateAccessor()
        accessor.update {
            it.copy(
                taskCards = mapOf(
                    CARD_ID to TaskCardData(
                        id = CARD_ID,
                        title = "任务",
                        phase = TaskCardPhase.EXECUTING,
                        steps = listOf(
                            TaskStep("s0", "calculator", status = TaskStepStatus.PENDING, toolCallId = "call-0"),
                            TaskStep("s1", "web_search", status = TaskStepStatus.RUNNING, toolCallId = "call-1"),
                            TaskStep("s2", "read_file", status = TaskStepStatus.SUCCESS, toolCallId = "call-2"),
                        ),
                    ),
                ),
            )
        }
        val coordinator = ChatTaskCardCoordinator(accessor, mockk<ToolRegistry>(relaxed = true))

        coordinator.toggleTaskCardPause(CARD_ID)

        val steps = accessor.snapshot.taskCards.getValue(CARD_ID).steps
        assertTrue(steps.first { it.id == "s0" }.pauseRequested)
        assertFalse(steps.first { it.id == "s1" }.pauseRequested)
        assertFalse(steps.first { it.id == "s2" }.pauseRequested)
    }

    private companion object {
        const val CARD_ID = "card-1"
    }
}
