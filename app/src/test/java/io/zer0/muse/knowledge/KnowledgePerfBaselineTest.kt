package io.zer0.muse.knowledge

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.knowledge.IndexGenerationState
import io.zer0.muse.data.knowledge.KnowledgeGenChunkEntity
import io.zer0.muse.data.knowledge.KnowledgeGenLexicalEntity
import io.zer0.muse.data.knowledge.KnowledgeGenerationEntity
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.knowledge.domain.KnowledgeScope
import io.zer0.muse.knowledge.domain.RetrievalPolicy
import io.zer0.muse.knowledge.ingest.ParsedBlock
import io.zer0.muse.knowledge.ingest.StructuredChunker
import io.zer0.muse.knowledge.retrieval.KnowledgeEntryType
import io.zer0.muse.knowledge.retrieval.KnowledgeSearchRequest
import io.zer0.muse.knowledge.retrieval.V2LexicalRetriever
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.system.measureTimeMillis

/**
 * R0 性能基线(**JVM/Robolectric 下限,不替代真机**) — 计划 R0「在 1k/10k chunks 档
 * 记录本地检索、内存、导入和恢复基线；设备不足明确列为待验，不编造结果」。
 *
 * 本测试只记录 JVM 上的算法级基线(检索延迟、分块吞吐),用于发现退化;
 * 真机 CPU/IO/内存与冷暖启动数据归 R10 `KnowledgeSqliteDeviceTest` 之外的性能项,
 * 未跑设备前不得把本结果当作设备承诺。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KnowledgePerfBaselineTest {

    private lateinit var db: MuseDb
    private lateinit var retriever: V2LexicalRetriever

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).allowMainThreadQueries().build()
        retriever = V2LexicalRetriever(db.knowledgeJobDao(), oversample = 4)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedChunks(count: Int, generations: Int = 10) {
        val perGen = count / generations
        for (g in 0 until generations) {
            val genId = "gen-$g"
            db.knowledgeJobDao().insertGeneration(
                KnowledgeGenerationEntity(
                    generationId = genId,
                    docId = "doc-$g",
                    generationNo = 1,
                    state = IndexGenerationState.ACTIVE.name,
                    revisionId = "rev-$g",
                    chunkCount = perGen,
                ),
            )
            val chunks = (0 until perGen).map { i ->
                KnowledgeGenChunkEntity(
                    id = "$genId#$i",
                    generationId = genId,
                    chunkIndex = i,
                    content = "普通内容 row-$g-$i padding padding padding",
                    locator = "p1s${i + 1}",
                    kind = "paragraph",
                )
            }
            val lexical = (0 until perGen).map { i ->
                KnowledgeGenLexicalEntity(
                    id = "$genId#$i",
                    generationId = genId,
                    chunkIndex = i,
                    lexicalText = "普通内容 row-$g-$i padding padding padding" +
                        if (i % 100 == 0) " needle-term" else "",
                )
            }
            db.knowledgeJobDao().insertGenChunks(chunks)
            db.knowledgeJobDao().insertGenLexical(lexical)
        }
    }

    private fun request(query: String) = KnowledgeSearchRequest(
        requestId = "perf",
        query = query,
        entry = KnowledgeEntryType.DEBUG,
        scope = KnowledgeScope.AllUserLibraries,
        policy = RetrievalPolicy(10, 0.35f, 0.5f, false, 1f, 1f, false, "", "", 0),
        datasetEpoch = 1L,
        deadlineMs = 10_000L,
    )

    private fun percentile(values: List<Long>, p: Double): Long {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    @Test
    fun `record jvm lexical retrieval baseline at 1k and 10k chunks`() = runBlocking {
        val results = mutableListOf<String>()
        for (size in listOf(1_000, 10_000)) {
            // 每个尺寸用独立库,避免前一轮数据干扰。
            db.close()
            db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).allowMainThreadQueries().build()
            retriever = V2LexicalRetriever(db.knowledgeJobDao(), oversample = 4)
            seedChunks(size)
            val allGens = (0 until 10).map { "gen-$it" }

            // 预热 5 次,测量 20 次。
            repeat(5) { retriever.search(request("needle-term"), allGens, 10) }
            val samples = mutableListOf<Long>()
            repeat(20) {
                samples.add(measureTimeMillis { retriever.search(request("needle-term"), allGens, 10) })
            }
            val p50 = percentile(samples, 0.50)
            val p95 = percentile(samples, 0.95)
            val line = "PERF chunks=$size p50=${p50}ms p95=${p95}ms samples=${samples.size}"
            println(line)
            results.add(line)
            assertTrue("JVM 检索退化: $line", p95 < 2000)
        }
        assertTrue(results.size == 2)
    }

    @Test
    fun `record jvm chunker throughput for one megabyte text`() {
        val text = buildString {
            repeat(20_000) { append("第").append(it).append("段内容 padding padding。\n") }
        }
        val chunker = StructuredChunker(chunkSize = 500, chunkOverlap = 50)
        val elapsed = measureTimeMillis {
            val chunks = chunker.chunkFromBlocks(
                listOf(ParsedBlock(text = text, page = 0, section = 1, kind = "paragraph")),
            )
            assertTrue(chunks.isNotEmpty())
        }
        val line = "PERF chunker chars=${text.length} elapsed=${elapsed}ms"
        println(line)
        assertTrue("分块吞吐退化: $line", elapsed < 5000)
    }
}
