package io.zer0.muse.ui.chat

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.ui.ChatUiState
import io.zer0.muse.ui.taskcard.TaskCardData
import io.zer0.muse.ui.taskcard.TaskCardPhase
import io.zer0.muse.ui.taskcard.TaskStep
import io.zer0.muse.ui.taskcard.TaskStepStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3: 任务卡失败步骤重试的可靠性测试。
 *
 * 覆盖:
 *  - 重试超时写回 [TaskStepStatus.TIMED_OUT](不再永远 RUNNING / 误写 FAILED);
 *  - 取消传播:步骤写回 [TaskStepStatus.CANCELLED] 且不吞 CancellationException;
 *  - 成功/失败仍按原语义写回;
 *  - TIMED_OUT 可被 "ALL_FAILED" 重试,而 CANCELLED 不在重试范围。
 */
class ChatTaskCardCoordinatorRetryTest {

    private val registry: ToolRegistry = mockk(relaxed = true)

    private fun stubToolRegistered(name: String = "calculator") {
        every { registry.listTools() } returns listOf(
            ToolRegistry.ToolDef(
                name = name,
                description = "phase3 test tool",
                parameters = emptyMap(),
            ),
        )
    }

    private fun cardWith(vararg statuses: Pair<String, TaskStepStatus>) = TaskCardData(
        id = CARD_ID,
        title = "任务",
        phase = TaskCardPhase.DONE,
        steps = statuses.map { (id, status) ->
            TaskStep(id = id, title = "calculator", detail = "{}", status = status, rawArgs = "{}")
        },
    )

    private fun accessorWithCard(card: TaskCardData, scope: CoroutineScope? = null): InMemoryChatStateAccessor {
        val accessor = InMemoryChatStateAccessor(scope = scope)
        accessor.update { it.copy(taskCards = mapOf(CARD_ID to card)) }
        return accessor
    }

    private fun stepOf(accessor: InMemoryChatStateAccessor, stepId: String): TaskStep? =
        accessor.snapshot.taskCards[CARD_ID]?.steps?.firstOrNull { it.id == stepId }

    /** 轮询等待步骤进入预期终态(重试在独立协程里执行)。 */
    private suspend fun awaitStatus(
        accessor: InMemoryChatStateAccessor,
        stepId: String,
        expected: TaskStepStatus,
    ) {
        withTimeout(AWAIT_TIMEOUT_MS) {
            while (stepOf(accessor, stepId)?.status != expected) {
                delay(10)
            }
        }
    }

    @Test
    fun `retry success writes SUCCESS and finishes the card`() = runBlocking {
        stubToolRegistered()
        coEvery { registry.executeFromJson("calculator", any()) } coAnswers { "3" }
        val accessor = accessorWithCard(cardWith("s0" to TaskStepStatus.FAILED))
        val coordinator = ChatTaskCardCoordinator(accessor, registry, retryTimeoutMs = 5_000L)

        coordinator.retryFailedStep(CARD_ID, "s0")
        awaitStatus(accessor, "s0", TaskStepStatus.SUCCESS)

        assertEquals("3", stepOf(accessor, "s0")?.result)
        assertEquals(TaskCardPhase.DONE, accessor.snapshot.taskCards[CARD_ID]?.phase)
    }

    @Test
    fun `retry failure writes FAILED`() = runBlocking {
        stubToolRegistered()
        coEvery { registry.executeFromJson("calculator", any()) } coAnswers { """{"error":"boom"}""" }
        val accessor = accessorWithCard(cardWith("s0" to TaskStepStatus.FAILED))
        val coordinator = ChatTaskCardCoordinator(accessor, registry, retryTimeoutMs = 5_000L)

        coordinator.retryFailedStep(CARD_ID, "s0")
        awaitStatus(accessor, "s0", TaskStepStatus.FAILED)

        assertTrue(accessor.snapshot.taskCards[CARD_ID]?.isExpanded == true)
    }

    @Test
    fun `retry timeout writes TIMED_OUT terminal status`() = runBlocking {
        stubToolRegistered()
        coEvery { registry.executeFromJson("calculator", any()) } coAnswers {
            delay(5_000)
            "too late"
        }
        val accessor = accessorWithCard(cardWith("s0" to TaskStepStatus.FAILED))
        val coordinator = ChatTaskCardCoordinator(accessor, registry, retryTimeoutMs = 50L)

        coordinator.retryFailedStep(CARD_ID, "s0")
        awaitStatus(accessor, "s0", TaskStepStatus.TIMED_OUT)

        val step = stepOf(accessor, "s0")
        assertNotNull(step)
        assertTrue(step!!.result.startsWith("[超时]"))
        assertEquals(TaskCardPhase.DONE, accessor.snapshot.taskCards[CARD_ID]?.phase)
    }

    @Test
    fun `retry cancellation marks step CANCELLED and propagates`() = runBlocking {
        stubToolRegistered()
        coEvery { registry.executeFromJson("calculator", any()) } coAnswers {
            delay(30_000)
            "never"
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val accessor = accessorWithCard(cardWith("s0" to TaskStepStatus.FAILED), scope = scope)
        val coordinator = ChatTaskCardCoordinator(accessor, registry, retryTimeoutMs = 60_000L)

        coordinator.retryFailedStep(CARD_ID, "s0")
        // 先确认重试已真正开始(RUNNING),再取消整个作用域
        awaitStatus(accessor, "s0", TaskStepStatus.RUNNING)
        scope.cancel()

        // 取消后步骤必须落到 CANCELLED(而不是永远 RUNNING,也不是 FAILED)
        awaitStatus(accessor, "s0", TaskStepStatus.CANCELLED)
        assertTrue(stepOf(accessor, "s0")!!.result.startsWith("[取消]"))
    }

    @Test
    fun `ALL_FAILED retries TIMED_OUT but skips CANCELLED steps`() = runBlocking {
        stubToolRegistered()
        coEvery { registry.executeFromJson("calculator", any()) } coAnswers { "ok" }
        val accessor = accessorWithCard(
            cardWith(
                "s0" to TaskStepStatus.TIMED_OUT,
                "s1" to TaskStepStatus.CANCELLED,
            ),
        )
        val coordinator = ChatTaskCardCoordinator(accessor, registry, retryTimeoutMs = 5_000L)

        coordinator.retryFailedStep(CARD_ID, "ALL_FAILED")

        awaitStatus(accessor, "s0", TaskStepStatus.SUCCESS)
        // 取消的步骤不在可重试集合内,保持原状
        assertEquals(TaskStepStatus.CANCELLED, stepOf(accessor, "s1")?.status)
    }

    @Test
    fun `non retryable status is ignored`() = runBlocking {
        stubToolRegistered()
        val accessor = accessorWithCard(cardWith("s0" to TaskStepStatus.SUCCESS))
        val coordinator = ChatTaskCardCoordinator(accessor, registry, retryTimeoutMs = 5_000L)

        coordinator.retryFailedStep(CARD_ID, "s0")
        delay(50)

        assertEquals(TaskStepStatus.SUCCESS, stepOf(accessor, "s0")?.status)
        io.mockk.coVerify(exactly = 0) { registry.executeFromJson(any<String>(), any<String>()) }
    }

    private companion object {
        const val CARD_ID = "card-1"
        const val AWAIT_TIMEOUT_MS = 5_000L
    }
}
