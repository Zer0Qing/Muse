package io.zer0.muse.conversation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import io.zer0.muse.conversation.core.CommandResult
import io.zer0.muse.conversation.core.ConversationCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class ConversationHostState(
    val sessionId: String,
    val model: ConversationPageModel? = null,
    val isSubmitting: Boolean = false,
    val error: String? = null,
)

@Composable
fun ConversationHost(
    sessionId: String,
    loadSession: suspend (String) -> ConversationPageModel,
    submit: suspend (ConversationCommand.Submit) -> CommandResult,
    commandId: (String) -> String,
    screen: @Composable (ConversationPageModel, (String) -> Unit) -> Unit,
) {
    require(sessionId.isNotBlank()) { "session id must not be blank" }
    val currentLoadSession by rememberUpdatedState(loadSession)
    val currentSubmit by rememberUpdatedState(submit)
    val currentCommandId by rememberUpdatedState(commandId)
    var state by remember(sessionId) { mutableStateOf(ConversationHostState(sessionId)) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(sessionId) {
        state = ConversationHostState(sessionId)
        state =
            try {
                state.copy(model = currentLoadSession(sessionId))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                state.copy(error = error.message ?: "conversation load failed")
            }
    }

    state.model?.let { model ->
        screen(model) { text ->
            if (!state.isSubmitting) {
                state = state.copy(isSubmitting = true, error = null)
                scope.launch {
                    val command =
                        ConversationCommand.Submit(
                            commandId = currentCommandId(sessionId),
                            sessionId = sessionId,
                            text = text,
                            selectionSetId = "legacy:$sessionId",
                        )
                    state =
                        try {
                            currentSubmit(command)
                            if (model.state.value.input == text) {
                                model.updateInput("")
                            }
                            state.copy(isSubmitting = false)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            state.copy(
                                isSubmitting = false,
                                error = error.message ?: "conversation submit failed",
                            )
                        }
                }
            }
        }
    }
}
