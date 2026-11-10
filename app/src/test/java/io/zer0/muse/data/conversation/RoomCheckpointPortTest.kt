package io.zer0.muse.data.conversation

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.conversation.core.CheckpointSnapshot
import io.zer0.muse.conversation.core.MessagePart
import io.zer0.muse.conversation.core.PartKind
import io.zer0.muse.conversation.core.RunIdentity
import io.zer0.muse.data.chat.rewrite.ConversationService
import io.zer0.muse.data.chat.rewrite.MessageCommit
import io.zer0.muse.data.chat.rewrite.MessageCommitResult
import io.zer0.muse.data.session.ConversationTurnEntity
import io.zer0.muse.data.session.MessageFtsDdl
import io.zer0.muse.data.session.MessageFtsRuntime
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
class RoomCheckpointPortTest {
    private lateinit var database: MuseDb
    private lateinit var sessionRepository: io.zer0.muse.data.session.SessionRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MuseDb::class.java,
        )
            .allowMainThreadQueries()
            .addCallback(
                object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        MessageFtsRuntime.useFts5 = false
                        db.execSQL(MessageFtsDdl.createSql(useFts5 = false))
                    }

                    override fun onOpen(db: SupportSQLiteDatabase) {
                        db.setForeignKeyConstraintsEnabled(true)
                    }
                },
            )
            .build()
        sessionRepository = io.zer0.muse.data.session.SessionRepository(
            sessionDao = database.sessionDao(),
            messageDao = database.messageDao(),
            database = database,
            context = ApplicationProvider.getApplicationContext(),
            messageImageStore = io.zer0.muse.data.session.MessageImageStore(
                java.io.File(
                    ApplicationProvider.getApplicationContext<Context>().cacheDir,
                    "checkpoint-${java.util.UUID.randomUUID()}",
                ),
            ),
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `terminal snapshot atomically commits assistant text and parts`() = runTest {
        database.sessionDao().insert(SessionEntity("session-1", "test", 1, 1))
        ConversationService(sessionRepository).startTurn(
            ConversationTurnEntity(
                turnId = "turn-1",
                sessionId = "session-1",
                inputUserMessageId = "user-1",
                assistantMessageId = "assistant-1",
                phase = "STREAMING",
                startedAt = 1,
                updatedAt = 1,
            ),
        )
        val port = RoomCheckpointPort(
            sessionRepository = sessionRepository,
            messageCommit = MessageCommit(sessionRepository),
            assistantFactory = {
                UIMessage(
                    id = kotlin.uuid.Uuid.parse("00000000-0000-0000-0000-000000000001"),
                    role = MessageRole.ASSISTANT,
                    content = "hello",
                    createdAt = 2,
                )
            },
        )

        val commitResult = withContext(Dispatchers.IO) { port.commitTerminalResult(snapshot()) }
        assertEquals(MessageCommitResult.Committed, commitResult)
        assertEquals("COMPLETED", database.conversationTurnDao().getById("turn-1")?.phase)

        val stored = database.messageDao().getById("session-1", "00000000-0000-0000-0000-000000000001")
        val allRows = database.messageDao().getRecentBySession("session-1", 10)
        println("[checkpoint-test] stored=${stored?.id} rows=${allRows.map { it.id }}")
        assertNotNull(stored)
        assertEquals("hello", stored?.content)
        assertEquals(1, database.messagePartDao().getByMessage(stored!!.id).size)
    }

    @Test
    fun `same turn derives the same assistant identity across checkpoints`() = runTest {
        database.sessionDao().insert(SessionEntity("session-identity", "test", 1, 1))
        ConversationService(sessionRepository).startTurn(
            ConversationTurnEntity(
                turnId = "turn-identity",
                sessionId = "session-identity",
                inputUserMessageId = "user-identity",
                assistantMessageId = "assistant-identity",
                phase = "STREAMING",
                startedAt = 1,
                updatedAt = 1,
            ),
        )
        val port = RoomCheckpointPort(
            sessionRepository = sessionRepository,
            messageCommit = MessageCommit(sessionRepository),
        )
        val identity = RunIdentity(
            sessionId = "session-identity",
            incarnation = 1,
            turnId = "turn-identity",
            generationId = "generation-identity",
            streamId = "stream-identity",
            selectionSetId = "selection-identity",
        )
        val first = CheckpointSnapshot(identity, 1, "first", emptyList())
        val second = CheckpointSnapshot(identity, 2, "second", emptyList())

        port.commitTerminalResult(first)
        val replay = port.commitTerminalResult(second)

        assertEquals(MessageCommitResult.AlreadyCommitted, replay)
        assertEquals(1, database.messageDao().getRecentBySession("session-identity", 10).size)
    }

    private fun snapshot() = CheckpointSnapshot(
        identity = RunIdentity(
            sessionId = "session-1",
            incarnation = 1,
            turnId = "turn-1",
            generationId = "generation-1",
            streamId = "stream-1",
            selectionSetId = "selection-1",
        ),
        sequence = 2,
        text = "hello",
        parts = listOf(MessagePart("assistant:text", PartKind.Text, 2, "hello")),
    )
}
