package io.zer0.muse.data.conversation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.conversation.core.ConversationAddress
import io.zer0.muse.conversation.core.ConversationWindowReader
import io.zer0.muse.conversation.core.SelectionSetId
import io.zer0.muse.data.session.MessageEntity
import io.zer0.muse.data.session.MessagePartEntity
import io.zer0.muse.data.session.MuseDb
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
class RoomConversationWindowSourceTest {
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
    fun `window source loads only requested slot parts`() = runTest {
        database.sessionDao().insert(SessionEntity("session-1", "test", 1, 1))
        database.messageDao().upsert(MessageEntity("message-1", "session-1", "USER", "hello", createdAt = 1))
        database.messagePartDao().upsertAll(
            listOf(MessagePartEntity("message-1", 0, "text", "hello", createdAt = 1)),
        )
        val store = RoomConversationStore(database)
        store.saveSelection("selection-1", "session-1", mapOf("slot-1" to "message-1"))
        store.upsertSlot("session-1", "selection-1", "slot-1", "USER", null, 1, "message-1")

        val source = RoomConversationWindowSource(database, store)
        val result = ConversationWindowReader(source).load(
            address = ConversationAddress("main", "session-1"),
            selectionSetId = SelectionSetId("selection-1"),
            around = "slot-1",
            limit = 10,
        )

        assertEquals(1, result.slots.size)
        assertEquals("message-1", result.slots.single().messageId)
        assertEquals("hello", result.slots.single().parts.single().text)
    }

    @Test
    fun `around slot loads both earlier and later slots`() = runTest {
        database.sessionDao().insert(SessionEntity("session-1", "test", 1, 1))
        val store = RoomConversationStore(database)
        store.saveSelection("selection-1", "session-1", emptyMap())
        repeat(7) { index ->
            val number = index + 1
            store.upsertSlot(
                "session-1",
                "selection-1",
                "slot-$number",
                if (number % 2 == 0) "ASSISTANT" else "USER",
                if (number % 2 == 0) "slot-${number - 1}" else null,
                number.toLong(),
                "message-$number",
            )
        }

        val source = RoomConversationWindowSource(database, store)
        val result = ConversationWindowReader(source).load(
            address = ConversationAddress("main", "session-1"),
            selectionSetId = SelectionSetId("selection-1"),
            around = "slot-4",
            limit = 3,
        )

        assertEquals(listOf("slot-3", "slot-4", "slot-5"), result.slots.map { it.slotId })
    }

    @Test
    fun `around slot applies assistant selection override`() = runTest {
        database.sessionDao().insert(SessionEntity("session-1", "test", 1, 1))
        val store = RoomConversationStore(database)
        store.saveSelection(
            selectionSetId = "selection-1",
            sessionId = "session-1",
            selection = mapOf("slot-1" to "message-1"),
            assistantSelection = mapOf("slot-2" to "message-2"),
        )
        store.upsertSlot("session-1", "selection-1", "slot-1", "USER", null, 1, "message-old")
        store.upsertSlot("session-1", "selection-1", "slot-2", "ASSISTANT", "slot-1", 2, "message-old")

        val result = store.readAround("session-1", "selection-1", "slot-2", limit = 3)

        assertEquals("message-1", result.first().messageId)
        assertEquals("message-2", result.last().messageId)
    }

    @Test
    fun `around slot uses caller selection rather than newest session selection`() = runTest {
        database.sessionDao().insert(SessionEntity("session-1", "test", 1, 1))
        val store = RoomConversationStore(database)
        store.saveSelection("selection-1", "session-1", mapOf("slot-1" to "message-v1"))
        store.upsertSlot("session-1", "selection-1", "slot-1", "USER", null, 1, "message-old")
        store.saveSelection("selection-2", "session-1", mapOf("slot-1" to "message-v2"))
        store.upsertSlot("session-1", "selection-2", "slot-2", "ASSISTANT", "slot-1", 2, "message-old")
        val source = RoomConversationWindowSource(database, store)

        val result = ConversationWindowReader(source).load(
            address = ConversationAddress("main", "session-1"),
            selectionSetId = SelectionSetId("selection-1"),
            around = "slot-2",
            limit = 3,
        )

        assertEquals("message-v1", result.slots.first().messageId)
    }
}
