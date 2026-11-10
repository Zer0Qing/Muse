package io.zer0.muse.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.R
import io.zer0.muse.data.ChatPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.nio.file.Path

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MessageBubbleBodylessWorkTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `bodyless message shell renders no body or link preview`() {
        val content = "body ".repeat(2_000) + " https://example.com/preview"
        val message = UIMessage(role = MessageRole.ASSISTANT, content = content)
        compose.setContent {
            MaterialTheme {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .testTag("bodyless-shell"),
                ) {
                    MessageBubble(
                        msg = message,
                        isStreaming = false,
                        isLastAssistant = false,
                        isTranslating = false,
                        onEdit = {},
                        onQuote = {},
                        onRegenerate = {},
                        onTranslate = {},
                        rendersBody = false,
                    )
                }
            }
        }

        compose.onNodeWithTag("bodyless-shell").assertExists()
        compose.onNodeWithText("body", substring = true, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("https://example.com/preview", substring = true, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun `bodyless long message keeps quick actions and their callbacks`() {
        val content = "long response ".repeat(2_000)
        val message = UIMessage(role = MessageRole.ASSISTANT, content = content)
        val preferences =
            ChatPreferences(
                quickActionOrder = listOf("copy", "regenerate", "quote"),
            )
        var copiedText = ""
        var regenerateCount = 0
        var quoteCount = 0

        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth()) {
                    MessageBubble(
                        msg = message,
                        isStreaming = false,
                        isLastAssistant = true,
                        isTranslating = false,
                        chatPrefs = preferences,
                        onEdit = {},
                        onQuote = { quoteCount++ },
                        onRegenerate = { regenerateCount++ },
                        onTranslate = {},
                        onCopyMessage = { copiedText = it },
                        rendersBody = false,
                    )
                }
            }
        }

        compose.onNodeWithContentDescription(context.getString(R.string.action_copy))
            .assertExists()
            .performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.chat_regenerate_cd))
            .assertExists()
            .performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.message_action_quote))
            .assertExists()
            .performClick()

        assertEquals(messageCopyText(content), copiedText)
        assertEquals(1, regenerateCount)
        assertEquals(1, quoteCount)
    }

    @Test
    fun `headerless bodyless shell keeps quick actions`() {
        val content = "long response ".repeat(2_000)
        val message = UIMessage(role = MessageRole.ASSISTANT, content = content)
        var copiedText = ""

        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth()) {
                    MessageBubble(
                        msg = message,
                        isStreaming = false,
                        isLastAssistant = true,
                        isTranslating = false,
                        onEdit = {},
                        onQuote = {},
                        onRegenerate = {},
                        onTranslate = {},
                        onCopyMessage = { copiedText = it },
                        rendersBody = false,
                        rendersHeader = false,
                    )
                }
            }
        }

        compose.onNodeWithContentDescription(context.getString(R.string.action_copy))
            .assertExists()
            .performClick()
        assertEquals(messageCopyText(content), copiedText)
    }

    @Test
    fun `citation list is passed without redundant null fallback`() {
        val source = readMessageBubbleSource()

        assertFalse(
            "citationUrls is non-null; keep the markdown path warning-free",
            source.contains("msg.citationUrls ?: emptyList()"),
        )
    }

    private fun readMessageBubbleSource(): String {
        val candidates =
            listOf(
                Path.of("src/main/java/io/zer0/muse/ui/MessageBubble.kt"),
                Path.of("app/src/main/java/io/zer0/muse/ui/MessageBubble.kt"),
            )
        val path =
            candidates.firstOrNull(Files::exists)
                ?: error("Unable to locate MessageBubble.kt from ${System.getProperty("user.dir")}")
        return Files.newBufferedReader(path).use { it.readText() }
    }
}
