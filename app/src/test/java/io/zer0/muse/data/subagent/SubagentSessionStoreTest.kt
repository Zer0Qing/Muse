package io.zer0.muse.data.subagent

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.SecureKeyCipher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class SubagentSessionStoreTest {

    private lateinit var dir: File
    private lateinit var cipher: SecureKeyCipher

    @Before
    fun setUp() {
        dir = createTempDirectory(prefix = "subagent-session-").toFile()
        cipher = object : SecureKeyCipher {
            override suspend fun encrypt(plain: String): String =
                "enc_test:" + java.util.Base64.getEncoder().encodeToString(plain.toByteArray())

            override suspend fun decrypt(stored: String): String =
                java.util.Base64.getDecoder().decode(stored.removePrefix("enc_test:")).toString(Charsets.UTF_8)

            override suspend fun decryptOrNull(stored: String): String? = if (stored.startsWith("enc_test:")) {
                java.util.Base64.getDecoder().decode(stored.removePrefix("enc_test:")).toString(Charsets.UTF_8)
            } else {
                stored
            }
        }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun toolHistoryIsEncryptedOnDiskAndRecoverable() = runTest {
        val store = SubagentSessionStore(dir, persistenceCipher = cipher)
        val secret = "subagent-password-secret"
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCall("call-1", "browser_type", """{"text":"$secret"}"""),
                ),
            ),
            UIMessage(
                role = MessageRole.TOOL,
                content = "clipboard content: $secret",
                toolCallId = "call-1",
            ),
        )

        assertTrue(store.append("thread-1", messages).isSuccess)
        val file = store.pathOf("thread-1")
        assertTrue(file.readText().lineSequence().filter { it.isNotBlank() }.all { it.startsWith("enc_test:") })
        assertFalse(file.readText().contains(secret))
        assertEquals(messages, store.load("thread-1"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun pathRejectsTraversalThreadIds() {
        SubagentSessionStore(dir, persistenceCipher = cipher).pathOf("../../outside")
    }
}
