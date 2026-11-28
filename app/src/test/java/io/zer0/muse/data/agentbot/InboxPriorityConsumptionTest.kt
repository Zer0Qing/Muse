package io.zer0.muse.data.agentbot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InboxPriorityConsumptionTest {

    @Test
    fun `unhandled early events do not starve later user input`() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val graph = AgentBotFlowTestGraph(context)
        try {
            repeat(40) { index ->
                graph.repository.appendEvent(
                    botId = "assistant-a",
                    originSessionId = "session-a",
                    taskId = "task-$index",
                    runId = "run-$index",
                    kind = BotEventKind.APPROVAL_RESOLVED,
                    dedupeKey = "approval-resolved-$index",
                )
            }
            val userInput = graph.repository.appendEvent(
                botId = "assistant-a",
                originSessionId = "session-a",
                taskId = "",
                runId = "",
                kind = BotEventKind.USER_INPUT_RECEIVED,
                dedupeKey = "user-input-1",
            )
            val processor = BotInboxProcessor(graph.repository)
            val firstTurnKinds = mutableListOf<String>()
            val first = processor.runTurn("assistant-a") { event ->
                firstTurnKinds += event.kind
                event.kind == BotEventKind.USER_INPUT_RECEIVED
            }

            assertEquals("首个有限窗口中的待重试事件仍保留", 0, first.consumed)
            assertTrue(firstTurnKinds.none { it == BotEventKind.USER_INPUT_RECEIVED })
            assertEquals(0L, graph.db.botEventDao().getById(userInput.eventId)?.consumedAt)

            val laterTurnKinds = mutableListOf<String>()
            val second = processor.runTurn("assistant-a") { event ->
                laterTurnKinds += event.kind
                event.kind == BotEventKind.USER_INPUT_RECEIVED
            }

            assertEquals("后续窗口必须让用户输入得到处理", 1, second.consumed)
            assertTrue(laterTurnKinds.contains(BotEventKind.USER_INPUT_RECEIVED))
            assertTrue(
                "失败事件仍留在队列",
                graph.repository.pendingEvents("assistant-a", 100).any {
                    it.kind == BotEventKind.APPROVAL_RESOLVED
                },
            )
        } finally {
            graph.close()
        }
    }
}
