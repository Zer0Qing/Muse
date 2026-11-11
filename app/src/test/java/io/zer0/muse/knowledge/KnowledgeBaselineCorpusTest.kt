package io.zer0.muse.knowledge

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.knowledge.domain.KnowledgeScope
import io.zer0.muse.knowledge.domain.RetrievalPolicy
import io.zer0.muse.knowledge.index.GenerationPublisher
import io.zer0.muse.knowledge.ingest.LocatedChunk
import io.zer0.muse.knowledge.retrieval.KnowledgeEntryType
import io.zer0.muse.knowledge.retrieval.KnowledgeSearchRequest
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
 * R0 固定质量语料(计划 R0:「建立不少于 120 题的来源标签集，分答案存在/不存在、语言、
 * 长文、代码与范围类型。记录现状 Recall@10、错误来源与最终证据」)。
 *
 * 合成语料 `src/test/resources/knowledge/qa_corpus.json`:24 份文档、120 题
 * (96 有答案:中英关键词/数字编号/长文尾部;24 无答案)。不含用户私有正文。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KnowledgeBaselineCorpusTest {

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
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun loadCorpus(): Corpus {
        val stream = javaClass.classLoader!!.getResourceAsStream("knowledge/qa_corpus.json")
            ?: error("缺少测试资源 knowledge/qa_corpus.json")
        val text = stream.bufferedReader().use { it.readText() }
        return json.decodeFromString(Corpus.serializer(), text)
    }

    private fun request(query: String) = KnowledgeSearchRequest(
        requestId = "baseline",
        query = query,
        entry = KnowledgeEntryType.DEBUG,
        scope = KnowledgeScope.AllUserLibraries,
        policy = RetrievalPolicy(10, 0.35f, 0.5f, false, 1f, 1f, false, "", "", 0),
        datasetEpoch = 1L,
        deadlineMs = 10_000L,
    )

    @Test
    fun `corpus has at least 120 questions with required coverage`() {
        val corpus = loadCorpus()
        assertTrue("题目数必须 >= 120", corpus.questions.size >= 120)
        assertEquals(24, corpus.docs.size)
        assertTrue("需要无答案题", corpus.questions.any { !it.answerable })
        assertTrue("需要中文题", corpus.questions.any { it.language == "zh" })
        assertTrue("需要英文题", corpus.questions.any { it.language == "en" })
        assertTrue("需要长文题", corpus.questions.any { it.category == "long_tail" })
        assertTrue("需要数字/代码题", corpus.questions.any { it.category == "numeric_code" })
    }

    @Test
    fun `lexical recall at 10 meets baseline on synthetic corpus`() = runBlocking {
        val corpus = loadCorpus()
        val publisher = GenerationPublisher(db.knowledgeJobDao())
        val retriever = V2LexicalRetriever(db.knowledgeJobDao(), oversample = 10)

        // 灌入 24 份文档(每份 1 chunk)。
        for (doc in corpus.docs) {
            db.knowledgeV2Dao().insertDoc(
                io.zer0.muse.data.knowledge.KnowledgeV2DocEntity(
                    docId = doc.docId,
                    ownerType = "UserLibrary",
                    ownerId = "kb-baseline",
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
                    origin = "baseline",
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

        val answerable = corpus.questions.filter { it.answerable }
        var hits = 0
        val misses = mutableListOf<String>()
        for (question in answerable) {
            val recall = retriever.search(request(question.question), emptyList(), 10)
            // 直接按全库范围查询:传所有活动代。
            val allGenerations = corpus.docs.map { "gen-${it.docId}-1" }
            val fullRecall = retriever.search(request(question.question), allGenerations, 10)
            val found = fullRecall.hits.any { it.docId == question.expectedDocId }
            if (found) hits++ else misses.add("${question.id}:${question.question}")
            assertEquals(0, recall.hits.size) // 空活动代列表必须零结果(不扩域)
        }
        val recallAt10 = hits.toDouble() / answerable.size
        println("R0 基线 Recall@10 = $recallAt10 (${hits}/${answerable.size}), misses=$misses")
        assertTrue("Recall@10 必须 >= 0.90,实际=$recallAt10 misses=$misses", recallAt10 >= 0.90)
    }
}
