package io.zer0.muse.data.session

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class SessionRepositoryConversationLookupTest {

    private val sessionDao = mockk<SessionDao>(relaxed = true)
    private lateinit var repository: SessionRepository

    @Before
    fun setUp() {
        repository = SessionRepository(
            sessionDao = sessionDao,
            messageDao = mockk<MessageDao>(relaxed = true),
            database = mockk<MuseDb>(relaxed = true),
            context = ApplicationProvider.getApplicationContext(),
            messageImageStore = mockk<MessageImageStore>(relaxed = true),
        )
    }

    @Test
    fun getSessionsByIdsDeduplicatesAndChunksDaoQueries() = runTest {
        val ids = (0 until 901).map { "session-$it" } + "session-0"
        val sessionsById = ids.distinct().associateWith { id ->
            SessionEntity(id = id, title = id, createdAt = 1L, updatedAt = 1L)
        }
        val requestedBatches = mutableListOf<List<String>>()
        coEvery { sessionDao.getByIds(any()) } coAnswers {
            val batch = firstArg<List<String>>()
            requestedBatches += batch
            batch.map(sessionsById::getValue)
        }

        val sessions = repository.getSessionsByIds(ids)

        assertEquals(901, sessions.size)
        assertEquals(901, sessions.map { it.id }.toSet().size)
        assertEquals(2, requestedBatches.size)
        assertEquals(900, requestedBatches.first().size)
        assertEquals(1, requestedBatches.last().size)
        coVerify(exactly = 2) { sessionDao.getByIds(any()) }
    }

    @Test
    fun getSessionsByIdsReturnsImmediatelyForEmptyInput() = runTest {
        assertEquals(emptyList<SessionEntity>(), repository.getSessionsByIds(emptyList()))
        coVerify(exactly = 0) { sessionDao.getByIds(any()) }
    }
}
