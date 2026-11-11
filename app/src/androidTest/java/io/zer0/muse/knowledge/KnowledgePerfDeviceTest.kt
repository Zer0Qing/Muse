package io.zer0.muse.knowledge

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.zer0.muse.data.knowledge.IndexGenerationState
import io.zer0.muse.data.knowledge.KnowledgeGenChunkEntity
import io.zer0.muse.data.knowledge.KnowledgeGenLexicalEntity
import io.zer0.muse.data.knowledge.KnowledgeGenerationEntity
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.knowledge.domain.KnowledgeScope
import io.zer0.muse.knowledge.domain.RetrievalPolicy
import io.zer0.muse.knowledge.retrieval.KnowledgeEntryType
import io.zer0.muse.knowledge.retrieval.KnowledgeSearchRequest
import io.zer0.muse.knowledge.retrieval.V2LexicalRetriever
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

/**
 * R10 设备性能基线(计划 R10「执行 1k/10k chunks 与低存储基准，记录型号、系统、
 * 数据规模、耗时分布与新增内存」)。
 *
 * 在真实 Android 文件库上测检索与导入(词法行写入)耗时;输出 `PERF` 行供报告。
 * 模拟器结果不等同于用户真机,报告会区分设备型号。
 */
@RunWith(AndroidJUnit4::class)
class KnowledgePerfDeviceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var db: MuseDb? = null

    @After
    fun tearDown() {
        db?.close()
        context.deleteDatabase("kb-perf-device.db")
    }

    private suspend fun seed(db: MuseDb, count: Int, generations: Int = 10) {
        val perGen = count / generations
        var insertMs = 0L
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
                    content = "内容 row-$g-$i padding padding",
                    locator = "p1s${i + 1}",
                    kind = "paragraph",
                )
            }
            val lexical = (0 until perGen).map { i ->
                KnowledgeGenLexicalEntity(
                    id = "$genId#$i",
                    generationId = genId,
                    chunkIndex = i,
                    lexicalText = "内容 row-$g-$i padding padding" + if (i % 100 == 0) " needle-term" else "",
                )
            }
            insertMs += measureTimeMillis { db.knowledgeJobDao().insertGenChunks(chunks) }
            insertMs += measureTimeMillis { db.knowledgeJobDao().insertGenLexical(lexical) }
        }
        println("PERF-DEVICE insert chunks=$count totalMs=$insertMs")
    }

    private fun percentile(values: List<Long>, p: Double): Long {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)]
    }

    @Test
    fun recordDeviceRetrievalBaseline() = runBlocking {
        val allGens = (0 until 10).map { "gen-$it" }
        for (size in listOf(1_000, 10_000)) {
            val dbName = "kb-perf-$size.db"
            context.deleteDatabase(dbName)
            val database = Room.databaseBuilder(context, MuseDb::class.java, dbName)
                .allowMainThreadQueries()
                .build()
            db?.close()
            db = database

            seed(database, size, generations = 10)
            val retriever = V2LexicalRetriever(database.knowledgeJobDao(), oversample = 4)
            val request = KnowledgeSearchRequest(
                requestId = "perf-device",
                query = "needle-term",
                entry = KnowledgeEntryType.DEBUG,
                scope = KnowledgeScope.AllUserLibraries,
                policy = RetrievalPolicy(10, 0.35f, 0.5f, false, 1f, 1f, false, "", "", 0),
                datasetEpoch = 1L,
                deadlineMs = 10_000L,
            )
            repeat(5) { retriever.search(request, allGens, 10) }
            val samples = mutableListOf<Long>()
            repeat(20) { samples.add(measureTimeMillis { retriever.search(request, allGens, 10) }) }
            val p50 = percentile(samples, 0.50)
            val p95 = percentile(samples, 0.95)
            println("PERF-DEVICE chunks=$size p50=${p50}ms p95=${p95}ms")
            assertTrue("设备检索退化: chunks=$size p95=$p95", p95 < 3000)
            database.close()
            context.deleteDatabase(dbName)
        }
    }
}
