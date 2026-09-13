package io.zer0.muse.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pending 工具调用的审批阶段必须持久化且可更新。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PendingToolCallStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        PendingToolCallStore.init(context)
    }

    @Test
    fun corruptedPendingFileIsQuarantined() = runTest {
        val pendingFile = java.io.File(context.filesDir, "pending_tool_calls.json")
        pendingFile.writeText("{not-json")

        assertTrue(PendingToolCallStore.getAllPending().isEmpty())
        assertTrue(!pendingFile.exists())
        assertTrue(
            pendingFile.parentFile?.listFiles()?.any {
                it.name.startsWith("pending_tool_calls.json.corrupt-")
            } == true,
        )
    }

    @Test
    fun approvalStateAndGenerationIdentitySurviveRoundTrip() = runTest {
        val pending = PendingToolCallStore.PendingToolCall(
            chatId = "session-approval",
            toolCallId = "call-1",
            toolName = "workspace_delete",
            arguments = "{}",
            createdAt = 1L,
            generationId = "generation-1",
            turnId = "turn-1",
        )
        PendingToolCallStore.clearForChat(pending.chatId)
        PendingToolCallStore.save(pending)

        assertTrue(
            PendingToolCallStore.updateState(
                pending.toolCallId,
                PendingToolCallStore.APPROVAL_PENDING,
            ),
        )
        val stored = PendingToolCallStore.getForChat(pending.chatId).single()
        assertEquals("APPROVAL_PENDING", stored.executionState)
        assertEquals("generation-1", stored.generationId)
        assertEquals("turn-1", stored.turnId)

        assertTrue(
            PendingToolCallStore.updateState(
                pending.toolCallId,
                PendingToolCallStore.ABORTED,
                "user_denied",
            ),
        )
        assertEquals(
            PendingToolCallStore.ABORTED,
            PendingToolCallStore.getForChat(pending.chatId).single().executionState,
        )
        assertEquals(
            "user_denied",
            PendingToolCallStore.getForChat(pending.chatId).single().abortReason,
        )
        PendingToolCallStore.clearForChat(pending.chatId)
    }
}
