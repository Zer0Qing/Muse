package io.zer0.muse.conversation.runtime

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.conversation.core.CommandResult
import io.zer0.muse.conversation.core.ConversationCommand
import io.zer0.muse.conversation.core.ConversationWindowReader
import io.zer0.muse.conversation.core.MessagePart
import io.zer0.muse.conversation.core.PartKind
import io.zer0.muse.conversation.core.SelectionSetId
import io.zer0.muse.conversation.core.SlotKind
import io.zer0.muse.conversation.core.WindowSlotReference
import io.zer0.muse.conversation.core.ConversationAddress
import io.zer0.muse.conversation.core.ConversationWindowSource
import io.zer0.muse.conversation.ui.ConversationPageModel
import io.zer0.muse.conversation.ui.ConversationTimelineRow
import io.zer0.muse.conversation.ui.TimelineRow
import io.zer0.muse.data.conversation.RoomConversationStore
import io.zer0.muse.data.conversation.RoomSubmitCommandPort
import io.zer0.muse.data.conversation.SubmitIdentity
import io.zer0.muse.data.conversation.RoomConversationMessageSource
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionEntity
import io.zer0.ai.core.UIMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConversationSessionControllerTest {
    private lateinit var database: MuseDb

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MuseDb::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `submit returns real ids and persists reusable receipt`() = runTest {
        database.sessionDao().insert(SessionEntity("session-1", "test", 1, 1))
        val store = RoomConversationStore(database)
        val model = ConversationPageModel()
        val controller = ConversationSessionController(
            sessionId = "session-1",
            submitCommand = RoomSubmitCommandPort(database, store) {
                SubmitIdentity("user-1", "assistant-1", "turn-1")
            },
            windowReader = ConversationWindowReader(emptySource()),
            pageModel = model,
        )
        val command = ConversationCommand.Submit("command-1", "session-1", "hello", "selection-1")

        val result = controller.submit(command) as CommandResult.Accepted
        val receipt = database.conversationCommandReceiptDao().find("command-1")

        assertEquals("user-1", result.messageId)
        assertEquals("user-1", receipt?.messageId)
        assertEquals("turn-1", receipt?.turnId)
    }

    @Test
    fun `load maps slot kind to timeline row without inferring from parts`() = runTest {
        val model = ConversationPageModel()
        val source = object : ConversationWindowSource {
            var reads = 0
            override suspend fun readSlots(
                address: ConversationAddress,
                selectionSetId: SelectionSetId,
                around: String?,
                limit: Int,
            ): List<WindowSlotReference> {
                reads++
                return listOf(
                    WindowSlotReference(
                        slotId = "slot-1",
                        messageId = "message-1",
                        kind = SlotKind.User,
                    ),
                )
            }

            override suspend fun readParts(messageIds: List<String>): Map<String, List<MessagePart>> =
                mapOf("message-1" to listOf(MessagePart("part-1", PartKind.Reasoning, 1, "thinking")))
        }
        val controller = ConversationSessionController(
            sessionId = "session-1",
            submitCommand = RoomSubmitCommandPort(database, RoomConversationStore(database)),
            windowReader = ConversationWindowReader(source),
            pageModel = model,
        )

        controller.load("session-1", "selection-1", limit = 10)

        assertEquals(1, source.reads)
        assertEquals(TimelineRow.User("slot-1", "message-1"), model.state.first().timeline.single())
    }

    @Test
    fun `reused command id with different text is rejected`() = runTest {
        database.sessionDao().insert(SessionEntity("session-1", "test", 1, 1))
        val controller = ConversationSessionController(
            sessionId = "session-1",
            submitCommand = RoomSubmitCommandPort(database, RoomConversationStore(database)),
            windowReader = ConversationWindowReader(emptySource()),
            pageModel = ConversationPageModel(),
        )
        controller.submit(ConversationCommand.Submit("command-1", "session-1", "first", "selection-1"))

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                controller.submit(ConversationCommand.Submit("command-1", "session-1", "second", "selection-1"))
            }
        }
    }

    @Test
    fun `concurrent duplicate command executes submission only once`() = runTest {
        database.sessionDao().insert(SessionEntity("session-1", "test", 1, 1))
        var identities = 0
        val controller = ConversationSessionController(
            sessionId = "session-1",
            submitCommand = RoomSubmitCommandPort(
                database = database,
                store = RoomConversationStore(database),
                identityFactory = {
                    identities++
                    SubmitIdentity("user-$identities", "assistant-$identities", "turn-$identities")
                },
            ),
            windowReader = ConversationWindowReader(emptySource()),
            pageModel = ConversationPageModel(),
        )
        val command = ConversationCommand.Submit("command-1", "session-1", "hello", "selection-1")

        coroutineScope {
            listOf(async { controller.submit(command) }, async { controller.submit(command) }).awaitAll()
        }

        assertEquals(1, identities)
        assertEquals(1, database.messageOutboxDao().getBySessionId("session-1").size)
    }

    @Test
    fun `load projects rich message payloads into timeline rows`() = runTest {
        database.sessionDao().insert(SessionEntity("session-rich", "test", 1, 1))
        val message = UIMessage(
            role = io.zer0.ai.core.MessageRole.ASSISTANT,
            content = "body",
            reasoning = "reasoning",
            mood = "mood",
        )
        database.messageDao().upsert(
            io.zer0.muse.data.session.MessageEntity(
                id = message.id.toString(),
                sessionId = "session-rich",
                role = message.role.name,
                content = message.content,
                reasoning = message.reasoning,
                mood = message.mood,
                createdAt = 1,
            ),
        )
        val store = RoomConversationStore(database)
        store.saveSelection("selection-rich", "session-rich", emptyMap())
        store.upsertSlot(
            "session-rich",
            "selection-rich",
            "slot-rich",
            "ASSISTANT",
            "slot-user",
            1,
            message.id.toString(),
        )
        val repository =
            io.zer0.muse.data.session.SessionRepository(
                sessionDao = database.sessionDao(),
                messageDao = database.messageDao(),
                database = database,
                context = ApplicationProvider.getApplicationContext(),
                messageImageStore = io.zer0.muse.data.session.MessageImageStore(
                    java.io.File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "controller-rich"),
                ),
            )
        val model = ConversationPageModel()
        val controller = ConversationSessionController(
            sessionId = "session-rich",
            submitCommand = RoomSubmitCommandPort(database, store),
            windowReader = ConversationWindowReader(object : ConversationWindowSource {
                override suspend fun readSlots(
                    address: io.zer0.muse.conversation.core.ConversationAddress,
                    selectionSetId: SelectionSetId,
                    around: String?,
                    limit: Int,
                ) = listOf(
                    WindowSlotReference("slot-rich", message.id.toString(), SlotKind.Assistant),
                )

                override suspend fun readParts(messageIds: List<String>) = emptyMap<String, List<MessagePart>>()
            }),
            messageSource = RoomConversationMessageSource(repository),
            pageModel = model,
        )

        controller.load("session-rich", "selection-rich", limit = 10)

        val rows = model.state.first().richTimeline
        assertTrue(rows.isNotEmpty())
        assertEquals(TimelineRow.Assistant("slot-rich", message.id.toString()), model.state.first().timeline.single())
        assertTrue(
            rows.any { it is ConversationTimelineRow.Rich && it.text == "reasoning" },
        )
        assertTrue(
            rows.any { it is ConversationTimelineRow.Rich && it.text == "mood" },
        )
    }

    private fun emptySource() = object : ConversationWindowSource {
        override suspend fun readSlots(
            address: ConversationAddress,
            selectionSetId: SelectionSetId,
            around: String?,
            limit: Int,
        ): List<WindowSlotReference> = emptyList()

        override suspend fun readParts(messageIds: List<String>): Map<String, List<MessagePart>> = emptyMap()
    }
}
