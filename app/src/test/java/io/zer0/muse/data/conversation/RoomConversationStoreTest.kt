package io.zer0.muse.data.conversation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoomConversationStoreTest {
    private lateinit var database: MuseDb
    private lateinit var store: RoomConversationStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).allowMainThreadQueries().build()
        store = RoomConversationStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `command receipt survives outbox deletion and replays`() = runTest {
        seedSession("session-1")

        val accepted = store.commitCommand(commandReceipt("command-1", "fingerprint-a")) as CommandStoreResult.Accepted
        val receipt = accepted.receipt
        store.releaseOutbox("command-1")
        val replay = store.commitCommand(commandReceipt("command-1", "fingerprint-a"))
        val conflict = store.commitCommand(commandReceipt("command-1", "fingerprint-b"))

        assertEquals(CommandStoreResult.Replay(receipt), replay)
        assertEquals(CommandStoreResult.Conflict(receipt), conflict)
        assertNotNull(database.conversationCommandReceiptDao().find("command-1"))
        assertFalse(
            database.query("SELECT * FROM message_outbox WHERE id = 'command-1'", null)
                .use { it.moveToFirst() },
        )
    }

    @Test
    fun `editing early variant keeps later turn order`() = runTest {
        seedSession("session-1")
        store.saveSelection("selection-1", "session-1", mapOf("u1" to "u1a", "u2" to "u2a"))
        store.upsertSlot("session-1", "selection-1", "u1", "USER", null, 10, "u1a")
        store.upsertSlot("session-1", "selection-1", "a1", "ASSISTANT", "u1", 20, "a1a")
        store.upsertSlot("session-1", "selection-1", "u2", "USER", null, 30, "u2a")
        store.upsertSlot("session-1", "selection-1", "a2", "ASSISTANT", "u2", 40, "a2a")

        store.saveSelection("selection-2", "session-1", mapOf("u1" to "u1b", "u2" to "u2a"))
        val window = store.readWindow("session-1", "selection-2", limit = 20)

        assertEquals(listOf("u1", "a1", "u2", "a2"), window.map { it.slotId })
        assertEquals("u1b", window.first().messageId)
    }

    @Test
    fun `selection snapshots round trip through room json`() = runTest {
        seedSession("session-1")
        val selection = mapOf(
            "group:one" to "message:one",
            "group:two" to "message:two",
        )

        store.saveSelection("selection-json", "session-1", selection)
        val persisted = database.conversationSelectionDao().find("selection-json")
            ?: error("selection was not persisted")

        assertEquals(selection, ConversationSelectionCodec.decode(persisted.userSelectionJson))
    }

    private suspend fun seedSession(id: String) {
        database.sessionDao().insert(
            SessionEntity(
                id = id,
                title = id,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
    }

    private fun commandReceipt(commandId: String, fingerprint: String) = ConversationCommandReceiptEntity(
        commandId = commandId,
        fingerprint = fingerprint,
        payloadJson = "{}",
        sessionId = "session-1",
        messageId = "user-1",
        turnId = "turn-1",
        acceptedAt = 1L,
        resultJson = "{}",
    )
}
