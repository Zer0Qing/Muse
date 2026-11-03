package io.zer0.muse.data.conversation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.MessageEntity
import io.zer0.muse.data.session.SessionEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SelectionSnapshotResolverTest {
    private lateinit var database: MuseDb
    private lateinit var store: RoomConversationStore
    private lateinit var resolver: SelectionSnapshotResolver

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MuseDb::class.java,
        ).allowMainThreadQueries().build()
        store = RoomConversationStore(database)
        resolver = SelectionSnapshotResolver(
            store = store,
            legacyMigrator = LegacyConversationMigrator(database, store),
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `existing selection is reused for the same session`() = runTest {
        database.sessionDao().insert(SessionEntity("session-1", "test", 1, 1))
        store.saveSelection("selection-1", "session-1", emptyMap())

        assertEquals("selection-1", resolver.resolve("session-1", "selection-1"))
        assertEquals("selection-1", resolver.resolve("session-1", "selection-1"))
    }

    @Test
    fun `missing selection gets a stable snapshot`() = runTest {
        database.sessionDao().insert(SessionEntity("session-1", "test", 1, 1))

        val first = resolver.resolve("session-1", null)
        val second = resolver.resolve("session-1", null)

        assertEquals(first, second)
        assertEquals("session-1", database.conversationSelectionDao().find(first)?.sessionId)
    }

    @Test
    fun `missing selection imports legacy messages before timeline load`() = runTest {
        database.sessionDao().insert(SessionEntity("session-1", "test", 1, 1))
        database.messageDao().upsert(
            MessageEntity(
                id = "message-1",
                sessionId = "session-1",
                role = "USER",
                content = "existing history",
                createdAt = 1,
            ),
        )

        val selectionSetId = resolver.resolve("session-1", null)

        assertEquals(
            listOf("message-1"),
            store.readWindow("session-1", selectionSetId, limit = 20).map { it.messageId },
        )
    }
}
