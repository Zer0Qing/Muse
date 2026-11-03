package io.zer0.muse.data.artifact

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionEntity
import kotlinx.coroutines.flow.first
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
class ArtifactDaoTest {
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
    fun `batch observation returns only requested message artifacts`() = runTest {
        database.sessionDao().insert(SessionEntity("session-1", "test", 1, 1))
        database.artifactDao().upsertAll(
            listOf(
                ArtifactEntity("a1", "session-1", "message-1", "one", "markdown"),
                ArtifactEntity("a2", "session-1", "message-2", "two", "markdown"),
                ArtifactEntity("a3", "session-1", "message-3", "three", "markdown"),
            ),
        )

        val observed = database.artifactDao()
            .observeByMessages(listOf("message-1", "message-3"))
            .first()
            .map { it.id }

        assertEquals(listOf("a1", "a3"), observed)
    }
}
