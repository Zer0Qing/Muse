package io.zer0.muse.conversation.ui

import io.zer0.muse.conversation.core.ConversationCommand
import io.zer0.muse.conversation.core.ConversationAddress
import io.zer0.muse.conversation.core.ConversationWindowReader
import io.zer0.muse.conversation.core.ConversationWindowSource
import io.zer0.muse.conversation.runtime.ConversationSessionController
import io.zer0.muse.data.conversation.RoomSubmitCommandPort
import io.mockk.mockk
import io.mockk.coEvery
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationViewModelTest {
    @Test
    fun `page models are isolated by session and reused for the same session`() {
        val viewModel =
            ConversationViewModel(
                controllerFactory = { sessionId, _ ->
                    controllerFor(sessionId)
                },
                selectionResolver = { "selection-$it" },
            )

        val firstA = viewModel.pageModelFor("session-a")
        val firstB = viewModel.pageModelFor("session-b")
        val secondA = viewModel.pageModelFor("session-a")

        assertNotSame(firstA, firstB)
        assertSame(firstA, secondA)
    }

    @Test
    fun `loading a session resolves a missing selection snapshot`() = runTest {
        var resolvedSession: String? = null
        val viewModel =
            ConversationViewModel(
                controllerFactory = { sessionId, _ ->
                    controllerFor(sessionId)
                },
                selectionResolver = { sessionId ->
                    resolvedSession = sessionId
                    "selection-$sessionId"
                },
            )

        viewModel.load("session-a")

        assertSame("session-a", resolvedSession)
    }

    @Test
    fun `submit targets the same session page model that load fills`() = runTest {
        val submitCommand = mockk<RoomSubmitCommandPort>(relaxed = true)
        coEvery { submitCommand.accept(any()) } returns
            io.zer0.muse.conversation.core.CommandResult.Accepted("user-1", "turn-1")
        val controller =
            ConversationSessionController(
                sessionId = "session-a",
                submitCommand = submitCommand,
                windowReader = ConversationWindowReader(emptyWindowSource()),
                pageModel = ConversationPageModel(),
            )
        val viewModel =
            ConversationViewModel(
                controllerFactory = { _, _ -> controller },
                selectionResolver = { "selection-$it" },
            )
        val command =
            ConversationCommand.Submit(
                commandId = "command-1",
                sessionId = "session-a",
                text = "hello",
                selectionSetId = "selection-a",
            )

        val result = viewModel.submit(command)

        assertEquals(
            "user-1",
            (result as io.zer0.muse.conversation.core.CommandResult.Accepted).messageId,
        )
    }

    @Test
    fun `controller is created once per session`() = runTest {
        var creations = 0
        val controller = controllerFor("session-a")
        val viewModel =
            ConversationViewModel(
                controllerFactory = { _, _ ->
                    creations++
                    controller
                },
                selectionResolver = { "selection-$it" },
            )

        viewModel.load("session-a")
        viewModel.load("session-a")

        assertEquals(1, creations)
        assertTrue(creations > 0)
    }

    @Test
    fun `different sessions cannot share a controller`() {
        val shared = controllerFor("shared")
        val viewModel =
            ConversationViewModel(
                controllerFactory = { _, _ -> shared },
                selectionResolver = { "selection-$it" },
            )

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                viewModel.load("session-a")
                viewModel.load("session-b")
            }
        }
    }

    private fun controllerFor(sessionId: String): ConversationSessionController {
        return ConversationSessionController(
            sessionId = sessionId,
            submitCommand = mockk<RoomSubmitCommandPort>(relaxed = true),
            windowReader =
            ConversationWindowReader(
                object : ConversationWindowSource {
                    override suspend fun readSlots(
                        address: ConversationAddress,
                        selectionSetId: io.zer0.muse.conversation.core.SelectionSetId,
                        around: String?,
                        limit: Int,
                    ) = emptyList<io.zer0.muse.conversation.core.WindowSlotReference>()

                    override suspend fun readParts(
                        messageIds: List<String>,
                    ) = emptyMap<String, List<io.zer0.muse.conversation.core.MessagePart>>()
                },
            ),
            pageModel = ConversationPageModel(),
        )
    }

    private fun emptyWindowSource() =
        object : ConversationWindowSource {
            override suspend fun readSlots(
                address: ConversationAddress,
                selectionSetId: io.zer0.muse.conversation.core.SelectionSetId,
                around: String?,
                limit: Int,
            ) = emptyList<io.zer0.muse.conversation.core.WindowSlotReference>()

            override suspend fun readParts(messageIds: List<String>) =
                emptyMap<String, List<io.zer0.muse.conversation.core.MessagePart>>()
        }
}
