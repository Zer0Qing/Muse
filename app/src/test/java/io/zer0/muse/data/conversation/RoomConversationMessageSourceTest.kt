package io.zer0.muse.data.conversation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.AttachmentRef
import io.zer0.muse.data.session.MessageEntity
import io.zer0.muse.data.session.MessageImageStore
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.data.session.SessionEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoomConversationMessageSourceTest {
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
    fun `reads requested messages in slot order with rich fields intact`() = runTest {
        database.sessionDao().insert(SessionEntity("session-1", "test", 1, 1))
        database.messageDao().upsertAll(
            listOf(
                MessageEntity(
                    id = "00000000-0000-0000-0000-000000000001",
                    sessionId = "session-1",
                    role = "USER",
                    content = "hello",
                    createdAt = 1,
                ),
                MessageEntity(
                    id = "00000000-0000-0000-0000-000000000002",
                    sessionId = "session-1",
                    role = "ASSISTANT",
                    content = "world",
                    attachmentsJson =
                        "[{\"id\":\"a1\",\"name\":\"report.pdf\",\"mimeType\":\"application/pdf\",\"size\":42," +
                            "\"sourceType\":\"FILE\",\"extractedText\":null," +
                            "\"workspacePath\":\"attachments/report.pdf\"}]",
                    artifactIdsJson = """["artifact-1"]""",
                    createdAt = 2,
                ),
            ),
        )

        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository =
            SessionRepository(
                sessionDao = database.sessionDao(),
                messageDao = database.messageDao(),
                database = database,
                context = context,
                messageImageStore = MessageImageStore(java.io.File(context.cacheDir, "message-source-test")),
            )
        val source = RoomConversationMessageSource(repository)
        val messages = source.readMessages(
            listOf(
                "00000000-0000-0000-0000-000000000002",
                "00000000-0000-0000-0000-000000000001",
                "missing",
            ),
        )

        assertEquals(
            listOf(
                "00000000-0000-0000-0000-000000000002",
                "00000000-0000-0000-0000-000000000001",
            ),
            messages.map { it.id.toString() },
        )
        assertEquals(listOf("artifact-1"), messages.first().artifactIds)
        assertEquals(
            listOf(
                AttachmentRef(
                    "a1",
                    "report.pdf",
                    mimeType = "application/pdf",
                    size = 42,
                    workspacePath = "attachments/report.pdf",
                ),
            ),
            messages.first().attachments,
        )
        assertTrue(messages.last().content == "hello")
    }
}
