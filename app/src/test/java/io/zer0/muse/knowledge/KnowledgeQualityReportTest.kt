package io.zer0.muse.knowledge

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.knowledge.domain.KnowledgeScope
import io.zer0.muse.knowledge.domain.RetrievalPolicy
import io.zer0.muse.knowledge.index.GenerationPublisher
import io.zer0.muse.knowledge.ingest.LocatedChunk
import io.zer0.muse.knowledge.retrieval.EvidenceAssembler
import io.zer0.muse.knowledge.retrieval.KnowledgeEntryType
import io.zer0.muse.knowledge.retrieval.KnowledgeRetriever
import io.zer0.muse.knowledge.retrieval.KnowledgeSearchRequest
import io.zer0.muse.knowledge.retrieval.RetrievalOutcome
import io.zer0.muse.knowledge.retrieval.V2LexicalRetriever
import io.zer0.muse.knowledge.storage.KnowledgeContentStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R10 步骤「跑固定来源标签集，报告 Recall@10、最终证据覆盖、无答案误引、范围越界、
 * 引用定位；不以单一 LLM 自评替代来源校验」。
 *
 * 与 `KnowledgeBaselineCorpusTest` 的 Recall 断言互补，本类专门度量三个**负向**指标:
 *  - 无答案题不得返回任何命中(误引 = 0);
 *  - 范围外文档不得进入结果(越界 = 0);
 *  - 每条证据必须有可解释 locator 且 citation 编号与最终文本一致(悬空引用 = 0)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KnowledgeQualityReportTest {

    @Serializable
    data class CorpusDoc(val docId: String, val content: String)

    @Serializable
    data class CorpusQuestion(
        val id: String,
        val question: String,
        val answerable: Boolean,
        val language: String,
        val category: String,
        val expectedDocId: String? = null,
    )

    @Serializable
    data class Corpus(val version: Int, val docs: List<CorpusDoc>, val questions: List<CorpusQuestion>)

    private lateinit var db: MuseDb
    private lateinit var retriever: KnowledgeRetriever

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).allowMainThreadQueries().build()
        retriever = KnowledgeRetriever(
            lexicalRetriever = V2LexicalRetriever(db.knowledgeJobDao()),
            generationResolver = { scope ->
                val docIds = when (scope) {
                    is KnowledgeScope.Resolved -> scope.docIds.toList()
                    is KnowledgeScope.AllUserLibraries -> db.knowledgeV2Dao().getAllDocs().map { it.docId }
                    else -> emptyList()
                }
                docIds.mapNotNull { db.knowledgeJobDao().getActiveGeneration(it) }
            },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun loadCorpus(): Corpus {
        val stream = javaClass.classLoader!!.getResourceAsStream("knowledge/qa_corpus.json")
            ?: error("缺少测试资源 knowledge/qa_corpus.json")
        return json.decodeFromString(Corpus.serializer(), stream.bufferedReader().use { it.readText() })
    }

    private fun request(query: String, scope: KnowledgeScope) = KnowledgeSearchRequest(
        requestId = "quality",
        query = query,
        entry = KnowledgeEntryType.DEBUG,
        scope = scope,
        policy = RetrievalPolicy(10, 0.35f, 0.5f, false, 1f, 1f, false, "", "", 0),
        datasetEpoch = 1L,
        deadlineMs = 10_000L,
    )

    private suspend fun seedCorpus(corpus: Corpus) {
        val publisher = GenerationPublisher(db.knowledgeJobDao())
        for (doc in corpus.docs) {
            db.knowledgeV2Dao().insertDoc(
                io.zer0.muse.data.knowledge.KnowledgeV2DocEntity(
                    docId = doc.docId,
                    ownerType = "UserLibrary",
                    ownerId = "kb-quality",
                    currentRevisionId = "rev-${doc.docId}",
                ),
            )
            val hash = KnowledgeContentStore.hashOf(doc.content.toByteArray())
            db.knowledgeV2Dao().insertRevision(
                io.zer0.muse.data.knowledge.KnowledgeV2RevisionEntity(
                    revisionId = "rev-${doc.docId}",
                    docId = doc.docId,
                    revisionNo = 1,
                    contentHash = hash,
                    fullContent = doc.content,
                    preview = doc.content.take(200),
                    origin = "quality",
                ),
            )
            val gen = publisher.stageGeneration(
                docId = doc.docId,
                revisionId = "rev-${doc.docId}",
                chunks = listOf(LocatedChunk(doc.content, "p1s1", "paragraph")),
            )
            publisher.validate(gen, "rev-${doc.docId}", hash) { texts ->
                KnowledgeContentStore.hashOf(texts.joinToString("").toByteArray())
            }
            publisher.publish(gen, "rev-${doc.docId}")
        }
    }

    @Test
    fun `unanswerable questions produce no evidence`() = runBlocking {
        val corpus = loadCorpus()
        seedCorpus(corpus)
        val unanswerable = corpus.questions.filter { !it.answerable }
        assertTrue("语料必须包含无答案题", unanswerable.isNotEmpty())

        var falseHits = 0
        for (question in unanswerable) {
            val outcome = retriever.search(request(question.question, KnowledgeScope.AllUserLibraries))
            val candidates = when (outcome) {
                is RetrievalOutcome.Found -> outcome.candidates
                is RetrievalOutcome.Partial -> outcome.candidates
                else -> emptyList()
            }
            if (candidates.isNotEmpty()) falseHits++
        }
        println("QUALITY unanswerable=${unanswerable.size} falseHits=$falseHits")
        assertEquals("无答案题不得返回命中(误引必须为 0)", 0, falseHits)
    }

    @Test
    fun `out of scope documents never leak into results`() = runBlocking {
        val corpus = loadCorpus()
        seedCorpus(corpus)
        // 只允许 doc-1;其它文档的同主题查询不得命中。
        val allowed = setOf("doc-1")
        val answerable = corpus.questions.filter { it.answerable }
        var violations = 0
        for (question in answerable) {
            val outcome = retriever.search(request(question.question, KnowledgeScope.Resolved(allowed)))
            val candidates = when (outcome) {
                is RetrievalOutcome.Found -> outcome.candidates
                is RetrievalOutcome.Partial -> outcome.candidates
                else -> emptyList()
            }
            if (candidates.any { it.docId !in allowed }) violations++
        }
        println("QUALITY scopeViolations=$violations")
        assertEquals("范围越界必须为 0", 0, violations)
    }

    @Test
    fun `every evidence unit has locator and no dangling citation`() = runBlocking {
        val corpus = loadCorpus()
        seedCorpus(corpus)
        val answerable = corpus.questions.filter { it.answerable }.take(24)
        var unitsWithoutLocator = 0
        var danglingCitations = 0

        for (question in answerable) {
            val outcome = retriever.search(request(question.question, KnowledgeScope.AllUserLibraries))
            val bundle = EvidenceAssembler().assemble(outcome, budgetTokens = 500, traceId = question.id)
            if (bundle.isEmpty) continue
            if (bundle.units.any { it.locator.isBlank() }) unitsWithoutLocator++
            for (unit in bundle.units) {
                if (bundle.citations.none { it.index == unit.index }) danglingCitations++
            }
            assertEquals("citation 数必须等于实际证据单元数", bundle.units.size, bundle.citations.size)
        }
        println("QUALITY unitsWithoutLocator=$unitsWithoutLocator danglingCitations=$danglingCitations")
        assertEquals("证据必须有可解释定位", 0, unitsWithoutLocator)
        assertEquals("悬空引用必须为 0", 0, danglingCitations)
    }
}
