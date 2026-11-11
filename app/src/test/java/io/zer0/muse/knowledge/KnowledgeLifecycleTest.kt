package io.zer0.muse.knowledge

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.knowledge.KnowledgeV2DocEntity
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.knowledge.index.GenerationPublisher
import io.zer0.muse.knowledge.storage.KnowledgeContentStore
import io.zer0.muse.knowledge.storage.KnowledgeLifecycleService
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
import io.zer0.muse.knowledge.ingest.LocatedChunk
import org.junit.Assert.assertTrue

/**
 * R8 步骤 1/3(计划 R8:「先写重命名/移动/删除子树、同级重名、目录环、跨库移动、
 * 失效绑定、完整正文编辑冲突测试」「所有删除先撤查询可见性再清派生物」)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KnowledgeLifecycleTest {

    private lateinit var db: MuseDb
    private lateinit var service: KnowledgeLifecycleService
    private lateinit var publisher: GenerationPublisher

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).allowMainThreadQueries().build()
        service = KnowledgeLifecycleService(
            folderDao = db.knowledgeFolderV2Dao(),
            v2Dao = db.knowledgeV2Dao(),
            jobDao = db.knowledgeJobDao(),
            clock = { 2000L },
        )
        publisher = GenerationPublisher(db.knowledgeJobDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedDocWithGeneration(docId: String, content: String): String {
        val revisionId = "rev-$docId-1"
        db.knowledgeV2Dao().insertDoc(
            KnowledgeV2DocEntity(
                docId = docId,
                ownerType = "UserLibrary",
                ownerId = "kb-1",
                currentRevisionId = revisionId,
            ),
        )
        db.knowledgeV2Dao().insertRevision(
            io.zer0.muse.data.knowledge.KnowledgeV2RevisionEntity(
                revisionId = revisionId,
                docId = docId,
                revisionNo = 1,
                contentHash = KnowledgeContentStore.hashOf(content.toByteArray()),
                fullContent = content,
                preview = content,
            ),
        )
        val chunks = listOf(LocatedChunk(content, "p1s1", "paragraph"))
        val gen = publisher.stageGeneration(docId, revisionId, chunks)
        publisher.validate(gen, revisionId, KnowledgeContentStore.hashOf(content.toByteArray())) { texts ->
            KnowledgeContentStore.hashOf(texts.joinToString("").toByteArray())
        }
        publisher.publish(gen, revisionId)
        return gen
    }

    @Test
    fun `edit with stale revision is rejected (optimistic lock)`() = runBlocking {
        seedDocWithGeneration("doc-1", "original content")
        // 错误 expectedRevisionId -> Conflict
        val conflict = service.editDocument("doc-1", "rev-wrong", "new content")
        assertTrue("陈旧 revision 必须冲突", conflict is KnowledgeLifecycleService.EditResult.Conflict)

        // 正确 revision -> 新版本,旧版本保留。
        val ok = service.editDocument("doc-1", "rev-doc-1-1", "new content")
        assertTrue(ok is KnowledgeLifecycleService.EditResult.Ok)
        val revisions = db.knowledgeV2Dao().getRevisionsByDoc("doc-1")
        assertEquals("旧 revision 保留", 2, revisions.size)
        val currentRevision = db.knowledgeV2Dao().getDoc("doc-1")!!.currentRevisionId
        assertEquals("新 revision 是当前指针", (ok as KnowledgeLifecycleService.EditResult.Ok).revisionId, currentRevision)
    }

    @Test
    fun `delete document clears visibility before removing`() = runBlocking {
        seedDocWithGeneration("doc-1", "content")
        assertNotNull(db.knowledgeJobDao().getActiveGeneration("doc-1"))

        val result = service.deleteDocument("doc-1", clearCitationSnapshots = false)
        assertNotNull(result)
        assertNull("删除后不得有活动代指针", db.knowledgeJobDao().getActiveGeneration("doc-1"))
        assertNull("文档行已删除", db.knowledgeV2Dao().getDoc("doc-1"))
        assertTrue("应说明历史引用处理", result!!.citationAction.contains("保留"))
    }

    @Test
    fun `delete folder with docs removes docs and refuses mid state`() = runBlocking {
        val folder = service.createFolder("kb-1", "", "docs") as KnowledgeLifecycleService.FolderResult.Ok
        val folderId = folder.folder.folderId
        // 手动把文档放到该目录。
        val revId = "rev-doc-9-1"
        db.knowledgeV2Dao().insertDoc(
            KnowledgeV2DocEntity(
                docId = "doc-9",
                ownerType = "UserLibrary",
                ownerId = "kb-1",
                folderId = folderId,
                currentRevisionId = revId,
            ),
        )
        db.knowledgeV2Dao().insertRevision(
            io.zer0.muse.data.knowledge.KnowledgeV2RevisionEntity(
                revisionId = revId, docId = "doc-9", revisionNo = 1,
                contentHash = "h", fullContent = "x", preview = "x",
            ),
        )
        val result = service.deleteFolder(folderId, KnowledgeLifecycleService.FolderDeletePolicy.DELETE_SUBTREE_DOCS)
        assertTrue(result is KnowledgeLifecycleService.FolderResult.Ok)
        assertNull("目录删除后文档也删除", db.knowledgeV2Dao().getDoc("doc-9"))
    }

    @Test
    fun `dissolve folder moves docs to parent instead of deleting`() = runBlocking {
        val parent = service.createFolder("kb-1", "", "parent") as KnowledgeLifecycleService.FolderResult.Ok
        val child = service.createFolder("kb-1", parent.folder.folderId, "child") as KnowledgeLifecycleService.FolderResult.Ok
        val revId = "rev-doc-10-1"
        db.knowledgeV2Dao().insertDoc(
            KnowledgeV2DocEntity(
                docId = "doc-10",
                ownerType = "UserLibrary",
                ownerId = "kb-1",
                folderId = child.folder.folderId,
                currentRevisionId = revId,
            ),
        )
        db.knowledgeV2Dao().insertRevision(
            io.zer0.muse.data.knowledge.KnowledgeV2RevisionEntity(
                revisionId = revId, docId = "doc-10", revisionNo = 1,
                contentHash = "h", fullContent = "x", preview = "x",
            ),
        )
        val result = service.deleteFolder(
            child.folder.folderId,
            KnowledgeLifecycleService.FolderDeletePolicy.DISSOLVE_MOVE_DOCS_TO_PARENT,
        )
        assertTrue(result is KnowledgeLifecycleService.FolderResult.Ok)
        val doc = db.knowledgeV2Dao().getDoc("doc-10")
        assertNotNull("文档必须保留", doc)
        assertEquals("文档搬回父目录", parent.folder.folderId, doc!!.folderId)
        assertNull("子目录行已删", db.knowledgeFolderV2Dao().getFolder(child.folder.folderId))
    }
}
