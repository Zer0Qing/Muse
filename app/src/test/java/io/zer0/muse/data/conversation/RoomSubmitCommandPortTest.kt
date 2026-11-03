package io.zer0.muse.data.conversation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.conversation.core.ConversationCommand
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoomSubmitCommandPortTest {
    private lateinit var database: MuseDb
    private lateinit var store: RoomConversationStore
    private lateinit var port: RoomSubmitCommandPort
    private var identityCalls = 0

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MuseDb::class.java,
        ).allowMainThreadQueries().build()
        store = RoomConversationStore(database)
        port = RoomSubmitCommandPort(
            database = database,
            store = store,
            identityFactory = {
                identityCalls++
                SubmitIdentity(
                    userMessageId = "00000000-0000-0000-0000-00000000000$identityCalls",
                    assistantMessageId = "10000000-0000-0000-0000-00000000000$identityCalls",
                    turnId = "20000000-0000-0000-0000-00000000000$identityCalls",
                )
            },
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `first submit creates message outbox turn and receipt in one transaction`() = runTest {
        database.sessionDao().insert(SessionEntity("session-1", "test", 1, 1))
        store.saveSelection("selection-1", "session-1", emptyMap())

        val accepted = port.accept(
            ConversationCommand.Submit(
                commandId = "command-1",
                sessionId = "session-1",
                text = "hello",
                selectionSetId = "selection-1",
            ),
        )

        assertEquals("00000000-0000-0000-0000-000000000001", accepted.messageId)
        assertEquals("20000000-0000-0000-0000-000000000001", accepted.turnId)
        assertNotNull(database.messageDao().getById("session-1", accepted.messageId))
        assertNotNull(database.messageOutboxDao().getBySessionId("session-1").singleOrNull())
        assertNotNull(database.conversationTurnDao().getById(accepted.turnId!!))
        assertNotNull(database.conversationCommandReceiptDao().find("command-1"))
        assertEquals(
            listOf("00000000-0000-0000-0000-000000000001"),
            store.readWindow("session-1", "selection-1", limit = 20).map { it.messageId },
        )
    }

    @Test
    fun `replayed submit returns original identity without duplicate rows`() = runTest {
        database.sessionDao().insert(SessionEntity("session-2", "test", 1, 1))
        store.saveSelection("selection-2", "session-2", emptyMap())
        val command = ConversationCommand.Submit(
            commandId = "command-2",
            sessionId = "session-2",
            text = "hello",
            selectionSetId = "selection-2",
        )
        val first = port.accept(command)
        val replay = port.accept(command)

        assertEquals(first, replay)
        assertEquals(1, identityCalls)
        assertEquals(1, database.messageOutboxDao().getBySessionId("session-2").size)
        assertEquals(1, database.conversationTurnDao().getBySession("session-2").size)
        assertEquals(1, database.conversationDisplaySlotDao().readWindow("session-2", 20).size)
    }

    @Test
    fun `conflicting fingerprint does not mutate primary rows`() = runTest {
        database.sessionDao().insert(SessionEntity("session-3", "test", 1, 1))
        store.saveSelection("selection-3", "session-3", emptyMap())
        port.accept(
            ConversationCommand.Submit(
                commandId = "command-3",
                sessionId = "session-3",
                text = "first",
                selectionSetId = "selection-3",
            ),
        )

        val conflict = runCatching {
            port.accept(
                ConversationCommand.Submit(
                    commandId = "command-3",
                    sessionId = "session-3",
                    text = "second",
                    selectionSetId = "selection-3",
                ),
            )
        }

        assertEquals(true, conflict.isFailure)
        assertEquals(1, identityCalls)
    }
}
