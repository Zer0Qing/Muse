package io.zer0.muse.conversation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsNotEnabled
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConversationScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `composer updates page model and sends text`() = runTest {
        val model = ConversationPageModel()
        var sent: String? = null
        compose.setContent {
            MaterialTheme {
                val state by model.state.collectAsStateWithLifecycle()
                ConversationComposer(
                    input = state.input,
                    onValueChange = model::updateInput,
                    onSend = {
                        sent = model.state.value.input
                        model.updateInput("")
                    },
                )
            }
        }

        compose.onNode(hasSetTextAction()).performTextInput("hello")
        compose.onNodeWithTag("conversation-send").performClick()

        assertEquals("hello", sent)
        assertEquals("", model.state.first().input)
    }

    @Test
    fun `screen leaves accepted draft ownership to submit host`() = runTest {
        val model = ConversationPageModel()
        model.updateInput("hello")
        var sent: String? = null

        compose.setContent {
            MaterialTheme {
                ConversationScreen(
                    model = model,
                    onSend = { sent = it },
                )
            }
        }

        compose.onNodeWithTag("conversation-send").performClick()

        assertEquals("hello", sent)
        assertEquals("hello", model.state.first().input)
    }

    @Test
    fun `submitting state disables send while preserving the draft`() = runTest {
        val model = ConversationPageModel()
        model.updateInput("keep me")
        var sent = 0

        compose.setContent {
            MaterialTheme {
                ConversationScreen(
                    model = model,
                    onSend = { sent++ },
                    isSubmitting = true,
                    error = "network",
                )
            }
        }

        compose.onNodeWithTag("conversation-send").assertIsNotEnabled()
        compose.onNodeWithText("network").assertExists()
        compose.onNodeWithText("keep me").assertExists()
        assertEquals(0, sent)
    }

    @Test
    fun `loading state renders without a model`() {
        compose.setContent {
            MaterialTheme {
                ConversationScreen(
                    model = null,
                    onSend = {},
                    isLoading = true,
                )
            }
        }

        compose.onNodeWithTag("conversation-loading").assertExists()
    }
}
