package io.zer0.muse.proactive

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProactiveSessionPreferenceTest {
    private lateinit var database: MuseDb
    private lateinit var preferences: ProactiveSessionPreferenceRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            .allowMainThreadQueries()
            .build()
        preferences = ProactiveSessionPreferenceRepository(database.sessionProactivePreferenceDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun sessionsAreUnmutedByDefault() = runTest {
        assertFalse(preferences.isMuted("never-saved"))
    }

    @Test
    fun mutingOneSessionDoesNotMuteAnother() = runTest {
        insertSession("s1")
        insertSession("s2")
        preferences.setMuted("s1", true)
        assertTrue(preferences.isMuted("s1"))
        assertFalse(preferences.isMuted("s2"))
    }

    @Test
    fun unmutingRemovesTheSessionOverride() = runTest {
        insertSession("s1")
        preferences.setMuted("s1", true)
        preferences.setMuted("s1", false)
        assertFalse(preferences.isMuted("s1"))
        assertTrue(preferences.mutedSessionIds().isEmpty())
    }

    private suspend fun insertSession(id: String) {
        database.sessionDao().insert(
            SessionEntity(
                id = id,
                title = id,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
    }
}
