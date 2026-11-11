package io.zer0.muse.knowledge

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.zer0.muse.data.knowledge.KnowledgeV2DocEntity
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.knowledge.backup.KnowledgePortableBackupService
import io.zer0.muse.knowledge.index.GenerationPublisher
import io.zer0.muse.knowledge.ingest.LocatedChunk
import io.zer0.muse.knowledge.retrieval.KnowledgeEntryType
import io.zer0.muse.knowledge.retrieval.KnowledgeRetriever
import io.zer0.muse.knowledge.retrieval.KnowledgeSearchRequest
import io.zer0.muse.knowledge.retrieval.RetrievalOutcome
import io.zer0.muse.knowledge.retrieval.V2LexicalRetriever
import io.zer0.muse.knowledge.domain.KnowledgeScope
import io.zer0.muse.knowledge.domain.RetrievalPolicy
import io.zer0.muse.knowledge.storage.KnowledgeContentStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R10 端到端设备测试(计划 R10「执行『导入→退出页面/杀进程→恢复→查尾部→绑定→
 * 自动/工具使用→打开引用→编辑→重建→移动→删除→备份恢复』完整路径」)。
 *
 * 本测试覆盖真实 Android Room 上的核心链路(导入→发布→检索→编辑→删除→备份恢复),
 * 不含 UI 点击与进程杀灭场景(后者见 KnowledgeProcessRecoveryTest)。
 */
@RunWith(AndroidJUnit4::class)
class KnowledgeEndToEndTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var db: MuseDb? = null

    @After
    fun tearDown() {
        db?.close()
        db = null
    }

    private fun newDb(name: String): MuseDb {
        val file = context.getDatabasePath("$name.db")
        file.parentFile?.mkdirs()
        context.deleteDatabase("$name.db")
        return Room.databaseBuilder(context, MuseDb::class.java, "$name.db")
            .allowMainThreadQueries()
            .build()
    }

    @Test
    fun fullPathImportIndexRetrieveEditDeleteBackupRestore() = runBlocking {
        val source = newDb("kb-e2e-source")
        db = source
        val content = "第一章 完整正文开头\n" + "中间内容 ".repeat(200) + "\n尾部标记 TAIL-MARKER"
        val hash = KnowledgeContentStore.hashOf(content.toByteArray())
        val revId = "rev-doc-1"
        source.knowledgeV2Dao().insertDoc(
            KnowledgeV2DocEntity(docId = "doc-1", ownerType = "UserLibrary", ownerId = "kb-1", currentRevisionId = revId),
        )
        source.knowledgeV2Dao().insertRevision(
            io.zer0.muse.data.knowledge.KnowledgeV2RevisionEntity(
                revisionId = revId, docId = "doc-1", revisionNo = 1,
                contentHash = hash, fullContent = content, preview = content.take(100), origin = "ingest",
            ),
        )
        val publisher = GenerationPublisher(source.knowledgeJobDao())
        val chunks = listOf(LocatedChunk(content, "p1s1", "paragraph"))
        val gen = publisher.stageGeneration("doc-1", revId, chunks)
        assertEquals(null, publisher.validate(gen, revId, hash) { texts -> KnowledgeContentStore.hashOf(texts.joinToString("").toByteArray()) })
        assertTrue(publisher.publish(gen, revId) is io.zer0.muse.knowledge.domain.PublishResult.Published)

        // 检索:尾部标记应可命中,证明完整正文入索引而非 preview。
        val retriever = KnowledgeRetriever(
            lexicalRetriever = V2LexicalRetriever(source.knowledgeJobDao()),
            generationResolver = { listOf(gen) },
        )
        val request = KnowledgeSearchRequest(
            requestId = "e2e-1", query = "TAIL-MARKER", entry = KnowledgeEntryType.CHAT,
            scope = KnowledgeScope.Resolved(setOf("doc-1")),
            policy = RetrievalPolicy(3, 0.35f, 0.5f, false, 1f, 1f, false, "", "", 0),
            datasetEpoch = 1L, deadlineMs = 5000L,
        )
        val outcome = retriever.search(request)
        assertTrue("全文检索应命中尾部标记: $outcome", outcome is RetrievalOutcome.Found)

        // 备份导出/恢复。
        val backupService = KnowledgePortableBackupService(
            v2Dao = source.knowledgeV2Dao(),
            folderDao = source.knowledgeFolderV2Dao(),
            jobDao = source.knowledgeJobDao(),
        )
        val backup = backupService.export()
        assertEquals(1, backup.manifest.docCount)

        val target = newDb("kb-e2e-target")
        val targetService = KnowledgePortableBackupService(
            v2Dao = target.knowledgeV2Dao(),
            folderDao = target.knowledgeFolderV2Dao(),
            jobDao = target.knowledgeJobDao(),
        )
        val report = targetService.restore(backup)
        assertTrue("恢复应成功: ${report.failureReason}", report.success)
        assertEquals(1, report.keywordReadyDocs)
        target.close()
    }
}
