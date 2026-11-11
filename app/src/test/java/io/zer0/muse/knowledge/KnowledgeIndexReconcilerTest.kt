package io.zer0.muse.knowledge

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.knowledge.KnowledgeActiveGenerationEntity
import io.zer0.muse.data.knowledge.KnowledgeGenChunkEntity
import io.zer0.muse.data.knowledge.KnowledgeGenLexicalEntity
import io.zer0.muse.data.knowledge.KnowledgeGenerationEntity
import io.zer0.muse.data.knowledge.KnowledgeV2RetentionLeaseEntity
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.knowledge.index.KnowledgeIndexReconciler
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.zer0.muse.data.knowledge.IndexGenerationState

/**
 * R4 协调器合同(计划 R4 「outbox 只负责 ANN、缓存失效和清理」的清理面):
 *  - 坏代残留被清;ACTIVE 代不动;
 *  - 悬挂活动指针被清;
 *  - 过期租约回收。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KnowledgeIndexReconcilerTest {

    private lateinit var db: MuseDb
    private lateinit var reconciler: KnowledgeIndexReconciler

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).allowMainThreadQueries().build()
        reconciler = KnowledgeIndexReconciler(
            jobDao = db.knowledgeJobDao(),
            v2Dao = db.knowledgeV2Dao(),
            clock = { 1_000_000L },
            retiredRetentionMs = 1000L,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedGeneration(
        docId: String,
        generationId: String,
        state: IndexGenerationState,
        chunkCount: Int = 1,
        createdAt: Long = 1_000_000L,
    ) {
        db.knowledgeJobDao().insertGeneration(
            KnowledgeGenerationEntity(
                generationId = generationId,
                docId = docId,
                generationNo = 1,
                state = state.name,
                revisionId = "rev-$docId",
                chunkCount = chunkCount,
                createdAt = createdAt,
            ),
        )
        db.knowledgeJobDao().insertGenChunks(
            (0 until chunkCount).map {
                KnowledgeGenChunkEntity(
                    id = "$generationId#$it",
                    generationId = generationId,
                    chunkIndex = it,
                    content = "content $it",
                    locator = "p1s${it + 1}",
                    kind = "paragraph",
                )
            },
        )
        db.knowledgeJobDao().insertGenLexical(
            (0 until chunkCount).map {
                KnowledgeGenLexicalEntity(
                    id = "$generationId#$it",
                    generationId = generationId,
                    chunkIndex = it,
                    lexicalText = "content $it",
                )
            },
        )
    }

    @Test
    fun `failed generation leftovers are cleaned and active generation untouched`() = runBlocking {
        seedGeneration("doc-1", "gen-active", IndexGenerationState.ACTIVE, chunkCount = 2)
        db.knowledgeJobDao().upsertActiveGeneration(
            KnowledgeActiveGenerationEntity("doc-1", "gen-active", 0L),
        )
        seedGeneration("doc-2", "gen-failed", IndexGenerationState.FAILED, chunkCount = 3)

        val report = reconciler.reconcile()

        assertEquals(1, report.cleanedFailedGenerations)
        assertEquals("失败代 chunk 已清", 0, db.knowledgeJobDao().countGenChunks("gen-failed"))
        assertEquals("失败代词法已清", 0, db.knowledgeJobDao().countGenLexical("gen-failed"))
        // 活动代完全不动。
        assertEquals(2, db.knowledgeJobDao().countGenChunks("gen-active"))
        assertEquals("gen-active", db.knowledgeJobDao().getActiveGeneration("doc-1"))
    }

    @Test
    fun `dangling active pointer is cleared`() = runBlocking {
        // 指针指向未存在的代。
        db.knowledgeJobDao().upsertActiveGeneration(
            KnowledgeActiveGenerationEntity("doc-9", "gen-missing", 0L),
        )
        // 悬浮指针的 doc 没有 FAILED/RETIRED 代,reconcile() 候选集不含它 → 用 reconcileDoc 专项。
        val report = reconciler.reconcileDoc("doc-9")
        assertEquals(1, report.danglingPointersCleared)
        assertNull("悬挂指针必须清除", db.knowledgeJobDao().getActiveGeneration("doc-9"))
    }

    @Test
    fun `expired leases are reclaimed`() = runBlocking {
        db.knowledgeV2Dao().insertLease(
            KnowledgeV2RetentionLeaseEntity(
                leaseId = "lease-expired",
                contentKey = "hash-1",
                holderId = "reader",
                acquiredAt = 0L,
                expiresAt = 500_000L,
            ),
        )
        db.knowledgeV2Dao().insertLease(
            KnowledgeV2RetentionLeaseEntity(
                leaseId = "lease-active",
                contentKey = "hash-2",
                holderId = "reader",
                acquiredAt = 0L,
                expiresAt = 2_000_000L,
            ),
        )

        val report = reconciler.reconcile()

        assertEquals(1, report.expiredLeases)
        assertEquals("有效租约保留", 1, db.knowledgeV2Dao().getActiveLeases("hash-2", 1_000_000L).size)
        assertEquals("过期租约已删", 0, db.knowledgeV2Dao().getActiveLeases("hash-1", 500_000L).size)
    }

    @Test
    fun `retired generation older than retention is reclaimed`() = runBlocking {
        seedGeneration("doc-3", "gen-retired", IndexGenerationState.RETIRED, chunkCount = 2, createdAt = 900_000L)
        val report = reconciler.reconcileDoc("doc-3")
        assertEquals(1, report.retiredGenerations)
        assertEquals(0, db.knowledgeJobDao().countGenChunks("gen-retired"))
    }

    @Test
    fun `fresh retired generation is kept`() = runBlocking {
        seedGeneration("doc-4", "gen-retired-new", IndexGenerationState.RETIRED, chunkCount = 2, createdAt = 999_800L)
        val report = reconciler.reconcileDoc("doc-4")
        assertEquals(0, report.retiredGenerations)
        assertEquals("未过保留期不得回收", 2, db.knowledgeJobDao().countGenChunks("gen-retired-new"))
    }

    @Test
    fun `reconcile doc with healthy active pointer keeps it`() = runBlocking {
        seedGeneration("doc-5", "gen-good", IndexGenerationState.ACTIVE, chunkCount = 1)
        db.knowledgeJobDao().upsertActiveGeneration(KnowledgeActiveGenerationEntity("doc-5", "gen-good", 0L))
        val report = reconciler.reconcileDoc("doc-5")
        assertEquals(0, report.danglingPointersCleared)
        assertNotNull(db.knowledgeJobDao().getActiveGeneration("doc-5"))
    }
}
