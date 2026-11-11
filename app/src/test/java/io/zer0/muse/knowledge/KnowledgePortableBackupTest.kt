package io.zer0.muse.knowledge

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.knowledge.KnowledgeV2DocEntity
import io.zer0.muse.data.knowledge.KnowledgeV2FolderEntity
import io.zer0.muse.data.knowledge.KnowledgeV2RevisionEntity
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.knowledge.backup.KnowledgePortableBackupService
import io.zer0.muse.knowledge.storage.KnowledgeContentStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R9 步骤 1/2(计划 R9:「先写无原URI、无向量、ChunkOnly旧文档、损坏blob、缺文件、
 * 未知新版本、恢复中断和恢复失败回滚用例」「便携备份包含 source、完整 artifact、
 * 目录/绑定、profile、coverage 和不可再生 legacy chunks」)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KnowledgePortableBackupTest {

    private lateinit var sourceDb: MuseDb
    private lateinit var targetDb: MuseDb
    private lateinit var sourceService: KnowledgePortableBackupService
    private lateinit var targetService: KnowledgePortableBackupService

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        sourceDb = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).allowMainThreadQueries().build()
        targetDb = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).allowMainThreadQueries().build()
        sourceService = KnowledgePortableBackupService(
            v2Dao = sourceDb.knowledgeV2Dao(),
            folderDao = sourceDb.knowledgeFolderV2Dao(),
            jobDao = sourceDb.knowledgeJobDao(),
            clock = { 1000L },
            newSnapshotId = { "snap-test" },
        )
        targetService = KnowledgePortableBackupService(
            v2Dao = targetDb.knowledgeV2Dao(),
            folderDao = targetDb.knowledgeFolderV2Dao(),
            jobDao = targetDb.knowledgeJobDao(),
            clock = { 2000L },
            newSnapshotId = { "snap-restore" },
        )
    }

    @After
    fun tearDown() {
        sourceDb.close()
        targetDb.close()
    }

    private suspend fun seedDoc(db: MuseDb, docId: String, content: String, folderId: String = "") {
        val hash = KnowledgeContentStore.hashOf(content.toByteArray())
        val revId = "rev-$docId-1"
        db.knowledgeV2Dao().insertDoc(
            KnowledgeV2DocEntity(
                docId = docId,
                ownerType = "UserLibrary",
                ownerId = "kb-1",
                folderId = folderId,
                currentRevisionId = revId,
                sourceRefJson = """{"scheme":"content","location":"$content"}""",
            ),
        )
        db.knowledgeV2Dao().insertRevision(
            KnowledgeV2RevisionEntity(
                revisionId = revId,
                docId = docId,
                revisionNo = 1,
                contentHash = hash,
                fullContent = content,
                preview = content,
                origin = "ingest",
            ),
        )
    }

    @Test
    fun `export restore roundtrip preserves docs revisions folders bindings and rebuilds lexical`() = runBlocking {
        sourceDb.knowledgeFolderV2Dao().insertFolder(
            KnowledgeV2FolderEntity(folderId = "kbf-1", kbId = "kb-1", parentId = "", name = "资料"),
        )
        sourceDb.knowledgeFolderV2Dao().insertBinding(
            io.zer0.muse.data.knowledge.KnowledgeV2BindingEntity(
                bindingId = "bind-1",
                ownerType = "Assistant",
                ownerId = "assistant-1",
                targetType = "FOLDER",
                targetId = "kbf-1",
            ),
        )
        seedDoc(sourceDb, "doc-1", "知识库完整正文内容 alpha beta", folderId = "kbf-1")
        seedDoc(sourceDb, "doc-2", "第二份文档 gamma", folderId = "")

        val backup = sourceService.export()
        assertEquals(2, backup.manifest.docCount)
        assertEquals(2, backup.manifest.revisionCount)
        assertEquals(1, backup.manifest.folderCount)

        val report = targetService.restore(backup)

        assertTrue("恢复应成功: ${report.failureReason}", report.success)
        assertEquals(2, report.restoredDocs)
        assertEquals(2, report.restoredRevisions)
        assertEquals("两份完整正文都应重建词法", 2, report.keywordReadyDocs)
        assertNotNull(targetDb.knowledgeV2Dao().getDoc("doc-1"))
        assertEquals("kbf-1", targetDb.knowledgeV2Dao().getDoc("doc-1")!!.folderId)
        assertEquals(1, targetDb.knowledgeV2Dao().getRevisionsByDoc("doc-1").size)
        assertEquals(1, targetDb.knowledgeFolderV2Dao().getAllFolders().size)
        assertEquals(
            "绑定必须随备份恢复",
            listOf("bind-1"),
            targetDb.knowledgeFolderV2Dao().getAllBindings().map { it.bindingId },
        )
        // 新 datasetEpoch 必须大于旧值(不随备份回滚)。
        assertTrue("新 epoch 必须严格递增", report.newDatasetEpoch > backup.manifest.datasetEpoch)
    }

    @Test
    fun `chunk only legacy doc stays stored only never hybrid ready`() = runBlocking {
        // 构造 ChunkOnly:当前 revision 完整正文为空,metadata 有 chunk 原文。
        val revId = "rev-legacy-1"
        sourceDb.knowledgeV2Dao().insertDoc(
            KnowledgeV2DocEntity(
                docId = "doc-legacy",
                ownerType = "UserLibrary",
                ownerId = "kb-1",
                currentRevisionId = revId,
            ),
        )
        sourceDb.knowledgeV2Dao().insertRevision(
            KnowledgeV2RevisionEntity(
                revisionId = revId,
                docId = "doc-legacy",
                revisionNo = 1,
                contentHash = "",
                fullContent = "",
                preview = "preview only",
                origin = "legacy-import:CHUNK_ONLY",
            ),
        )

        val backup = sourceService.export()
        assertEquals(listOf("doc-legacy"), backup.legacyChunkOnlyDocIds)

        val report = targetService.restore(backup)
        assertTrue(report.success)
        assertEquals("不得宣称关键词可查", 0, report.keywordReadyDocs)
        assertTrue("ChunkOnly 必须列为 StoredOnly", report.storedOnlyDocIds.contains("doc-legacy"))
        assertTrue("应给出警告", report.warnings.any { it.contains("ChunkOnly") })
        assertNull("不得发布活动代", targetDb.knowledgeJobDao().getActiveGeneration("doc-legacy"))
    }

    @Test
    fun `unknown newer format version is rejected`() = runBlocking {
        seedDoc(sourceDb, "doc-1", "content")
        val backup = sourceService.export()
        val newer = backup.copy(manifest = backup.manifest.copy(formatVersion = 99))
        val report = targetService.restore(newer)
        assertTrue(!report.success)
        assertTrue(report.failureReason!!.contains("格式版本"))
        assertNull("拒绝后不得写入任何文档", targetDb.knowledgeV2Dao().getDoc("doc-1"))
    }

    @Test
    fun `long multi chunk document restores keyword ready`() = runBlocking {
        // 回归:多块 + overlap 时恢复校验必须按 overlap 重建原文,否则 hash 误报 -> 假 StoredOnly。
        val longContent = "开头\n" + "中间内容 ".repeat(500) + "\nTAIL-LONG"
        seedDoc(sourceDb, "doc-long", longContent)
        val backup = sourceService.export()
        val report = targetService.restore(backup)
        assertTrue("恢复应成功: ${report.failureReason}", report.success)
        assertEquals("长文必须重建为 KeywordReady", 1, report.keywordReadyDocs)
        assertTrue(targetDb.knowledgeJobDao().getActiveGeneration("doc-long") != null)
    }

    @Test
    fun `portable backup json roundtrip preserves content and restores`() = runBlocking {
        seedDoc(sourceDb, "doc-json", "JSON 便携备份正文 TAIL-JSON")
        val backup = sourceService.export()
        val encoded = KnowledgePortableBackupService.encode(backup)
        assertTrue("JSON 文本应包含内容", encoded.contains("TAIL-JSON"))
        val decoded = KnowledgePortableBackupService.decode(encoded)
        assertEquals(backup.manifest, decoded.manifest)
        assertEquals(backup.docs.map { it.docId }, decoded.docs.map { it.docId })

        val report = targetService.restore(decoded)
        assertTrue("JSON 往返后恢复应成功: ${report.failureReason}", report.success)
        assertEquals(1, report.restoredDocs)
    }
}
