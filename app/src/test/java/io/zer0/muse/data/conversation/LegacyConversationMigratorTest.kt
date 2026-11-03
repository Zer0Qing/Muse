package io.zer0.muse.data.conversation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MessageEntity
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LegacyConversationMigratorTest {
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
    fun `migration is idempotent and does not rewrite messages`() = runTest {
        database.sessionDao().insert(SessionEntity("session-1", "test", 1, 1))
        database.messageDao().upsert(
            MessageEntity(
                id = "u1",
                sessionId = "session-1",
                role = "USER",
                content = "original",
                createdAt = 1,
                variantGroupId = "ug1",
            ),
        )
        val migrator = LegacyConversationMigrator(database, RoomConversationStore(database))

        val first = migrator.ensureImported("session-1", "selection-1")
        val second = migrator.ensureImported("session-1", "selection-1")

        assertEquals("selection-1", first)
        assertEquals("selection-1", second)
        assertEquals("original", database.messageDao().getById("session-1", "u1")?.content)
        assertEquals(1, database.conversationDisplaySlotDao().readWindow("session-1", 10).size)
    }

    @Test
    fun `failed transactional import leaves no completed selection marker`() = runTest {
        database.sessionDao().insert(SessionEntity("session-1", "test", 1, 1))
        val store = RoomConversationStore(database)

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                store.importLegacySlots(
                    sessionId = "session-1",
                    selectionSetId = "selection-invalid",
                    slots = listOf(
                        ImportedSlot("slot-1", "message-1", "USER", 1, null),
                        ImportedSlot("slot-2", "message-2", "ASSISTANT", 1, "slot-1"),
                    ),
                )
            }
        }

        assertEquals(null, database.conversationSelectionDao().find("selection-invalid"))
    }
}
