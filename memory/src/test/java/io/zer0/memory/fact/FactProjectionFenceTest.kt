package io.zer0.memory.fact

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * staged generation 发布围栏测试。
 *
 * 保证旧 summary revision 不能覆盖新 revision，同一 generation 重复 publish 幂等。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FactProjectionFenceTest {
    private val db: FactDb by lazy {
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FactDb::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `publish rejects stale generation after newer active revision exists`() = runTest {
        val store = FactStore(db.factDao(), db)
        store.stageSessionFacts(
            generationId = "g-old",
            sessionId = "s",
            sourceRevision = 1,
            facts = listOf(FactStore.Fact(fact = "旧事实")),
            scope = "main",
            spaceId = "default",
        )
        val first = store.publishSessionFacts("g-old", "s", 1)
        assertTrue("expected Published but got $first", first is ProjectionPublishResult.Published)

        store.stageSessionFacts(
            generationId = "g-new",
            sessionId = "s",
            sourceRevision = 2,
            facts = listOf(FactStore.Fact(fact = "新事实")),
            scope = "main",
            spaceId = "default",
        )
        val second = store.publishSessionFacts("g-new", "s", 2)
        assertTrue(second is ProjectionPublishResult.Published)

        val stale = store.publishSessionFacts("g-old", "s", 1)
        assertTrue(stale is ProjectionPublishResult.Superseded)
        assertEquals(2L, (stale as ProjectionPublishResult.Superseded).currentRevision)
    }

    @Test
    fun `publish same generation twice is idempotent`() = runTest {
        val store = FactStore(db.factDao(), db)
        store.stageSessionFacts(
            generationId = "g",
            sessionId = "s",
            sourceRevision = 1,
            facts = listOf(FactStore.Fact(fact = "事实")),
            scope = "main",
            spaceId = "default",
        )
        val first = store.publishSessionFacts("g", "s", 1)
        val second = store.publishSessionFacts("g", "s", 1)
        assertTrue("expected Published but got $first", first is ProjectionPublishResult.Published)
        assertTrue("expected Published but got $second", second is ProjectionPublishResult.Published)
    }
}
