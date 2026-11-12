package io.zer0.muse.ui.chat

import io.mockk.every
import io.mockk.mockk
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.ui.ChatAgentState
import io.zer0.muse.ui.ChatSessionState
import io.zer0.muse.ui.ChatUiState
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatExportCoordinatorTest {

    @Test
    fun `state fallback formats positional arguments without Android resources`() {
        assertEquals(
            "重试 tool 超过 2 秒未响应,已终止",
            auditStateString(null, R.string.audit_retry_timeout, "重试 %1\$s 超过 %2\$d 秒未响应,已终止", "tool", 2L),
        )
    }

    @Test
    fun `state localization forwards the resource and arguments`() {
        val localize: (Int, Array<out Any>) -> String = { resourceId, args ->
            assertEquals(R.string.audit_export_model, resourceId)
            assertEquals(listOf("test-model"), args.toList())
            "Modelo: ${args.single()}"
        }

        assertEquals(
            "Modelo: test-model",
            auditStateString(localize, R.string.audit_export_model, "模型: %1\$s", "test-model"),
        )
    }

    @Test
    fun `json export uses agent session title while agent mode is active`() = runTest {
        val taskSession = session("task-1", "Task title")
        val agentSession = session("agent-1", "Agent title")
        val accessor = InMemoryChatStateAccessor(
            ChatUiState(
                sessionState = ChatSessionState(
                    currentSessionId = taskSession.id,
                    sessions = listOf(taskSession, agentSession),
                ),
                agentState = ChatAgentState(
                    isAgentMode = true,
                    agentSessionId = agentSession.id,
                ),
            ),
            scope = this,
        )
        val settings = mockk<SettingsRepository>(relaxed = true)
        val repository = mockk<SessionRepository>(relaxed = true)
        every { repository.observeMessages(agentSession.id) } returns flowOf(emptyList())

        val output = ChatExportCoordinator(accessor, settings, repository).exportSessionAsJson()
        val title = Json.parseToJsonElement(output).jsonObject["title"]?.toString()

        assertEquals("\"Agent title\"", title)
    }

    private fun session(id: String, title: String) = SessionEntity(
        id = id,
        title = title,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )
}
