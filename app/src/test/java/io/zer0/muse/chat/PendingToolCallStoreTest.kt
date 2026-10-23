package io.zer0.muse.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.SecureKeyCipher
import kotlinx.coroutines.test.runTest
import org.junit.After
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
    private val cipher = object : SecureKeyCipher {
        override suspend fun encrypt(plain: String): String = "enc_test:${plain.reversed()}"

        override suspend fun decrypt(stored: String): String = stored.removePrefix("enc_test:").reversed()

        override suspend fun decryptOrNull(stored: String): String? =
            if (stored.startsWith("enc_test:")) stored.removePrefix("enc_test:").reversed() else stored
    }

    @Before
    fun setUp() {
        PendingToolCallStore.persistenceCipher = cipher
        PendingToolCallStore.init(context)
    }

    @After
    fun tearDown() {
        PendingToolCallStore.persistenceCipher = io.zer0.muse.data.SecureKeyStore
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

    @Test
    fun pendingArgumentsAreEncryptedOnDiskButRemainRecoverable() = runTest {
        val secret = "super-secret-password"
        val pending = PendingToolCallStore.PendingToolCall(
            chatId = "encrypted-session",
            toolCallId = "call-secret",
            toolName = "browser_type",
            arguments = """{"text":"$secret"}""",
            createdAt = 2L,
        )

        PendingToolCallStore.clearForChat(pending.chatId)
        PendingToolCallStore.save(pending)

        val file = java.io.File(context.filesDir, "pending_tool_calls.json")
        assertTrue(file.readText().startsWith("enc_test:"))
        assertTrue(!file.readText().contains(secret))
        assertEquals(pending.arguments, PendingToolCallStore.getForChat(pending.chatId).single().arguments)
        PendingToolCallStore.clearForChat(pending.chatId)
    }
}
