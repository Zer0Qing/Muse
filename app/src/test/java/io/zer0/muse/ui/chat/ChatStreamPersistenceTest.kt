package io.zer0.muse.ui.chat

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.lorebook.LorebookRepository
import io.zer0.muse.data.promptinjection.PromptInjectionRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.data.skill.SkillRepository
import io.zer0.muse.hook.HookRegistry
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.transformer.TransformerPipeline
import io.zer0.muse.ui.ChatErrorType
import io.zer0.muse.vision.VisionBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Test

class ChatStreamPersistenceTest {

    @Test
    fun `intermediate persistence waits for database write before returning`() = runTest {
        val accessor = mockk<ChatStateAccessor>(relaxed = true)
        every { accessor.messagesSnapshot } returns emptyList()
        every { accessor.coroutineScope } returns backgroundScope
        val repository = mockk<SessionRepository>(relaxed = true)
        val writeStarted = CompletableDeferred<Unit>()
        val allowWriteToFinish = CompletableDeferred<Unit>()
        val message = UIMessage(role = io.zer0.ai.core.MessageRole.ASSISTANT, content = "partial")
        coEvery { repository.upsertMessage("session", message, skipFts = true) } coAnswers {
            writeStarted.complete(Unit)
            allowWriteToFinish.await()
        }
        val coordinator = ChatStreamCoordinator(
            accessor = accessor,
            sessionRepository = repository,
            memoryTicker = mockk(relaxed = true),
            settings = mockk<SettingsRepository>(relaxed = true),
            appContext = mockk(relaxed = true),
            notificationManager = mockk<MuseNotificationManager>(relaxed = true),
            assistantRepository = mockk<AssistantRepository>(relaxed = true),
            visionBridge = mockk<VisionBridge>(relaxed = true),
            toolRegistry = mockk<ToolRegistry>(relaxed = true),
            skillRepository = mockk<SkillRepository>(relaxed = true),
            idListJson = Json,
            lorebookRepository = mockk<LorebookRepository>(relaxed = true),
            promptInjectionRepository = mockk<PromptInjectionRepository>(relaxed = true),
            transformerPipeline = mockk<TransformerPipeline>(relaxed = true),
            hookRegistry = mockk<HookRegistry>(relaxed = true),
        )

        val job = launch {
            coordinator.persistCurrentAssistant(
                sessionId = "session",
                assistantId = message.id,
                msg = message,
                addError = { _: ChatErrorType, _: String -> },
            )
        }
        writeStarted.await()

        assertFalse(job.isCompleted)
        allowWriteToFinish.complete(Unit)
        job.join()
    }
}
