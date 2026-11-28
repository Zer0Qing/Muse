package io.zer0.muse.data.proactive

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.session.MessageFtsDdl
import io.zer0.muse.data.session.MessageFtsRuntime
import io.zer0.muse.data.session.MessageImageStore
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.notification.MuseNotificationIdPolicy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProactiveBacklogPublishTransactionTest {
    private lateinit var db: MuseDb
    private lateinit var context: Context
    private lateinit var sessions: SessionRepository
    private lateinit var publisher: ProactiveBacklogPublisher
    private lateinit var relations: ProactiveRelationRepository
    private lateinit var claim: ClaimedBacklog
    private var previousFtsMode = false

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        previousFtsMode = MessageFtsRuntime.useFts5
        MessageFtsRuntime.useFts5 = false
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            .addCallback(
                object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(MessageFtsDdl.createSql(useFts5 = false))
                    }

                    override fun onOpen(db: SupportSQLiteDatabase) {
                        db.setForeignKeyConstraintsEnabled(true)
                    }
                },
            )
            .allowMainThreadQueries()
            .build()
        sessions = SessionRepository(
            sessionDao = db.sessionDao(),
            messageDao = db.messageDao(),
            database = db,
            context = context,
            messageImageStore = MessageImageStore(File(context.cacheDir, "proactive-publish-test")),
        )
        relations = ProactiveRelationRepository(db.proactiveRelationDao())
        publisher = ProactiveBacklogPublisher(db, sessions, relations, db.proactiveBacklogScheduleDao())
    }

    private suspend fun seedClaim() {
        db.sessionDao().insert(SessionEntity(id = SESSION, title = "test", createdAt = 1L, updatedAt = 1L, assistantId = ASSISTANT))
        val thought = PendingThoughtEntity(id = THOUGHT, assistantId = ASSISTANT, seed = "seed", createdAt = 1L)
        db.pendingThoughtDao().upsert(thought)
        val dispatch = ProactiveBacklogDispatchEntity(
            dispatchId = DISPATCH,
            thoughtId = THOUGHT,
            assistantId = ASSISTANT,
            sessionId = SESSION,
            state = BacklogDispatchState.CLAIMED,
            claimToken = TOKEN,
            claimUntil = 1_000_000L,
            createdAt = 1L,
            updatedAt = 1L,
        )
        db.proactiveBacklogDao().insertIfAbsent(dispatch)
        claim = ClaimedBacklog(dispatch, thought, TOKEN)
    }

    @After
    fun tearDown() {
        db.close()
        MessageFtsRuntime.useFts5 = previousFtsMode
    }

    @Test
    fun messageOutcomeThoughtBudgetAndDispatchRollbackTogetherAndRetryIdempotently() = runTest {
        seedClaim()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_proactive_outcome BEFORE INSERT ON proactive_outcome " +
                "BEGIN SELECT RAISE(ABORT, 'injected outcome failure'); END",
        )
        val failure = runCatching { publisher.publish(claim, "hello", "Muse", 10_000L) }.exceptionOrNull()
        assertTrue("injected DB failure should abort the publish", failure != null)
        assertEquals(0, db.messageDao().countBySession(SESSION))
        assertNull(db.pendingThoughtDao().getById(THOUGHT)?.lastSurfacedAt)
        assertEquals(BacklogDispatchState.CLAIMED, db.proactiveBacklogDao().getById(DISPATCH)?.state)
        assertNull("relation initialization also rolls back", db.proactiveRelationDao().get(ASSISTANT))

        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_proactive_outcome")
        val published = publisher.publish(claim, "hello", "Muse", 10_000L) as BacklogPublishResult.Published
        val expectedMessageId = UUID.nameUUIDFromBytes("muse-proactive:message:$DISPATCH".toByteArray()).toString()
        val expectedOutcomeId = UUID.nameUUIDFromBytes("muse-proactive:outcome:$DISPATCH".toByteArray()).toString()
        assertEquals(expectedMessageId, published.messageId)
        assertEquals(expectedOutcomeId, published.outcomeId)
        assertTrue(
            "published message is committed with its canonical session scope; byId=${db.messageDao().getByMessageId(
                published.messageId,
            )}, all=${db.messageDao().getAllForFtsRebuild()}, outcomes=${db.proactiveOutcomeDao().getAll().size}, dispatch=${db.proactiveBacklogDao().getById(
                DISPATCH,
            )?.state}, schedule=${db.proactiveBacklogScheduleDao().getNextBacklogDispatchAt()}",
            db.messageDao().getById(SESSION, published.messageId) != null,
        )
        assertEquals(1, db.messageDao().countBySession(SESSION))
        assertEquals(1, db.proactiveOutcomeDao().getAll().size)
        assertEquals(BacklogDispatchState.SENT, db.proactiveBacklogDao().getById(DISPATCH)?.state)
        assertEquals(10_000L + ProactiveBacklogRepository.DISPATCH_GAP_MS, db.proactiveBacklogScheduleDao().getNextBacklogDispatchAt())
        assertEquals(10_000L, db.pendingThoughtDao().getById(THOUGHT)?.lastSurfacedAt)
        assertEquals(1, db.proactiveRelationDao().get(ASSISTANT)?.spentToday)
        assertEquals(ProactiveNotificationState.PENDING, db.proactiveOutcomeDao().getById(expectedOutcomeId)?.notificationState)
        assertEquals(
            MuseNotificationIdPolicy.notificationIdForOutcome(expectedOutcomeId),
            db.proactiveOutcomeDao().getById(expectedOutcomeId)?.notificationId,
        )

        assertEquals(BacklogPublishResult.ClaimLost, publisher.publish(claim, "hello", "Muse", 10_001L))
        assertEquals(1, db.messageDao().countBySession(SESSION))
        assertEquals(1, db.proactiveOutcomeDao().getAll().size)
        assertEquals(1, db.proactiveRelationDao().get(ASSISTANT)?.spentToday)
    }

    @Test
    fun appendMessageWithTransactionPersistsCanonicalMessage() = runTest {
        db.sessionDao().insert(SessionEntity(id = SESSION, title = "test", createdAt = 1L, updatedAt = 1L, assistantId = ASSISTANT))
        val messageId = sessions.appendMessageWithTransaction(
            sessionId = SESSION,
            message = UIMessage(
                id = kotlin.uuid.Uuid.parse(UUID.randomUUID().toString()),
                role = MessageRole.ASSISTANT,
                content = "hello",
                createdAt = 2_000L,
            ),
        ) { }

        assertTrue(messageId != null)
        assertEquals(1, db.messageDao().countBySession(SESSION))
    }

    private companion object {
        const val ASSISTANT = "assistant-1"
        const val SESSION = "session-1"
        const val THOUGHT = "thought-1"
        const val DISPATCH = "dispatch-1"
        const val TOKEN = "claim-token"
    }
}
