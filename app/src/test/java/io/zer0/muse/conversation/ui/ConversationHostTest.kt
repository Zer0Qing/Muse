package io.zer0.muse.conversation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.conversation.core.CommandResult
import io.zer0.muse.conversation.core.ConversationCommand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConversationHostTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `session switch loads each session once and exposes its own model`() = runTest {
        val loads = mutableListOf<String>()
        val models = mutableMapOf<String, ConversationPageModel>()
        var sessionId by mutableStateOf("session-a")
        compose.setContent {
            MaterialTheme {
                ConversationHost(
                    sessionId = sessionId,
                    loadSession = { id ->
                        loads += id
                        models.getOrPut(id) { ConversationPageModel() }
                    },
                    submit = { error("not used") },
                    commandId = { "command-$it" },
                    screen = { model, _ ->
                        val state by model.state.collectAsStateWithLifecycle()
                        Text(text = "${state.input}:${System.identityHashCode(model)}")
                    },
                )
            }
        }
        compose.waitForIdle()
        assertEquals(listOf("session-a"), loads)

        sessionId = "session-b"
        compose.waitForIdle()

        assertEquals(listOf("session-a", "session-b"), loads)
        assertNotSame(models["session-a"], models["session-b"])
    }

    @Test
    fun `submit uses a stable command identity and clears input after acceptance`() = runTest {
        val accepted = CompletableDeferred<Unit>()
        var command: ConversationCommand.Submit? = null
        val model = ConversationPageModel()
        compose.setContent {
            MaterialTheme {
                ConversationHost(
                    sessionId = "session-a",
                    loadSession = { model },
                    submit = {
                        command = it
                        accepted.complete(Unit)
                        CommandResult.Accepted("user-1", "turn-1")
                    },
                    commandId = { "command-fixed" },
                    screen = { pageModel, onSend ->
                        val state by pageModel.state.collectAsStateWithLifecycle()
                        Column {
                            Text(state.input)
                            Button(
                                onClick = {
                                    pageModel.updateInput("hello")
                                    onSend("hello")
                                },
                                modifier = Modifier.testTag("send"),
                            ) {
                                Text("send")
                            }
                        }
                    },
                )
            }
        }
        compose.onNodeWithTag("send").performClick()
        accepted.await()
        compose.waitForIdle()

        assertEquals("command-fixed", command?.commandId)
        assertEquals("session-a", command?.sessionId)
        assertEquals("hello", command?.text)
        assertEquals("", model.state.first().input)
    }

    @Test
    fun `failed submit keeps the draft and exposes an error`() = runTest {
        val model = ConversationPageModel()
        model.updateInput("keep me")
        compose.setContent {
            MaterialTheme {
                ConversationHost(
                    sessionId = "session-a",
                    loadSession = { model },
                    submit = { error("network") },
                    commandId = { "command-fixed" },
                    screen = { pageModel, onSend ->
                        val state by pageModel.state.collectAsStateWithLifecycle()
                        Column {
                            Text(state.input)
                            Button(
                                onClick = { onSend(state.input) },
                                modifier = Modifier.testTag("send"),
                            ) {
                                Text("send")
                            }
                        }
                    },
                )
            }
        }
        compose.onNodeWithTag("send").performClick()
        compose.waitForIdle()

        assertEquals("keep me", model.state.first().input)
    }
}
