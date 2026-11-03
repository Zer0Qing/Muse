package io.zer0.muse.conversation.ui

import androidx.lifecycle.ViewModel
import io.zer0.muse.conversation.core.CommandResult
import io.zer0.muse.conversation.core.ConversationCommand
import io.zer0.muse.conversation.runtime.ConversationSessionController

class ConversationViewModel(
    private val controllerFactory: (String, ConversationPageModel) -> ConversationSessionController,
    private val selectionResolver: suspend (String) -> String,
) : ViewModel() {
    private val pageModels = mutableMapOf<String, ConversationPageModel>()

    fun pageModelFor(sessionId: String): ConversationPageModel {
        require(sessionId.isNotBlank()) { "session id must not be blank" }
        return pageModels.getOrPut(sessionId) {
            ConversationPageModel()
        }
    }

    suspend fun load(
        sessionId: String,
        explicitSelectionSetId: String? = null,
        around: String? = null,
        limit: Int = 50,
    ) {
        val pageModel = pageModelFor(sessionId)
        val selectionSetId =
            explicitSelectionSetId?.takeIf { it.isNotBlank() }
                ?: selectionResolver(sessionId)
        controllerFor(sessionId, pageModel).load(
            sessionId = sessionId,
            selectionSetId = selectionSetId,
            around = around,
            limit = limit,
        )
    }

    suspend fun submit(command: ConversationCommand.Submit): CommandResult {
        val pageModel = pageModelFor(command.sessionId)
        return controllerFor(command.sessionId, pageModel).submit(command)
    }

    private fun controllerFor(sessionId: String, pageModel: ConversationPageModel): ConversationSessionController =
        controllersBySession.getOrPut(sessionId) {
            controllerFactory(sessionId, pageModel).also { controller ->
                require(controller.sessionId == sessionId) {
                    "controller factory returned a controller for another session"
                }
            }
        }

    private val controllersBySession = mutableMapOf<String, ConversationSessionController>()
}
