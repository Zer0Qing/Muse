package io.zer0.muse.knowledge

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.knowledge.storage.KnowledgeLifecycleService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R8 步骤 2(计划 R8:「目录使用稳定ID，路径是投影；操作事务同步必要归属/绑定状态；
 * 目录删除明确含文档删除或解散并搬回」)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KnowledgeFolderIdentityTest {

    private lateinit var db: MuseDb
    private lateinit var service: KnowledgeLifecycleService
    private var idCounter = 0

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).allowMainThreadQueries().build()
        service = KnowledgeLifecycleService(
            folderDao = db.knowledgeFolderV2Dao(),
            v2Dao = db.knowledgeV2Dao(),
            jobDao = db.knowledgeJobDao(),
            clock = { 1000L },
            newFolderId = { "kbf-${idCounter++}" },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `rename keeps stable folder id and changes only path projection`() = runBlocking {
        val parent = service.createFolder("kb-1", "", "合同") as KnowledgeLifecycleService.FolderResult.Ok
        val child = service.createFolder("kb-1", parent.folder.folderId, "2026") as KnowledgeLifecycleService.FolderResult.Ok
        assertEquals("合同/2026", service.pathOf(child.folder.folderId))

        val renamed = service.renameFolder(parent.folder.folderId, "合同归档")
        assertTrue(renamed is KnowledgeLifecycleService.FolderResult.Ok)
        // 稳定 id 不变,子目录仍属于同一父 id。
        assertEquals(parent.folder.folderId, (renamed as KnowledgeLifecycleService.FolderResult.Ok).folder.folderId)
        assertEquals("合同归档/2026", service.pathOf(child.folder.folderId))
    }

    @Test
    fun `same name siblings conflict but different parents allowed`() = runBlocking {
        val a = service.createFolder("kb-1", "", "A") as KnowledgeLifecycleService.FolderResult.Ok
        val b = service.createFolder("kb-1", "", "B") as KnowledgeLifecycleService.FolderResult.Ok
        val conflict = service.createFolder("kb-1", a.folder.folderId, "B")
        assertTrue("不同父同名允许", conflict is KnowledgeLifecycleService.FolderResult.Ok)

        val dup = service.createFolder("kb-1", "", "A")
        assertTrue("同父同名必须拒绝", dup is KnowledgeLifecycleService.FolderResult.NameConflict)
        assertNotEquals(a.folder.folderId, b.folder.folderId)
    }

    @Test
    fun `move into own descendant is rejected as cycle`() = runBlocking {
        val root = service.createFolder("kb-1", "", "root") as KnowledgeLifecycleService.FolderResult.Ok
        val child = service.createFolder("kb-1", root.folder.folderId, "child") as KnowledgeLifecycleService.FolderResult.Ok
        val grand = service.createFolder("kb-1", child.folder.folderId, "grand") as KnowledgeLifecycleService.FolderResult.Ok

        val result = service.moveFolder(root.folder.folderId, grand.folder.folderId)
        assertTrue("移到自己后代必须拒绝", result is KnowledgeLifecycleService.FolderResult.Cycle)
    }

    @Test
    fun `cross kb move is denied`() = runBlocking {
        val a = service.createFolder("kb-1", "", "A") as KnowledgeLifecycleService.FolderResult.Ok
        val b = service.createFolder("kb-2", "", "B") as KnowledgeLifecycleService.FolderResult.Ok
        val result = service.moveFolder(a.folder.folderId, b.folder.folderId)
        assertTrue(result is KnowledgeLifecycleService.FolderResult.CrossKbDenied)
    }

    @Test
    fun `delete subtree removes children and dissolves move keeps docs`() = runBlocking {
        val root = service.createFolder("kb-1", "", "root") as KnowledgeLifecycleService.FolderResult.Ok
        val child = service.createFolder("kb-1", root.folder.folderId, "child") as KnowledgeLifecycleService.FolderResult.Ok

        // 子树删除:两个目录都消失。
        val deleted = service.deleteFolder(root.folder.folderId, KnowledgeLifecycleService.FolderDeletePolicy.DELETE_SUBTREE_DOCS)
        assertTrue(deleted is KnowledgeLifecycleService.FolderResult.Ok)
        assertEquals(null, db.knowledgeFolderV2Dao().getFolder(root.folder.folderId))
        assertEquals(null, db.knowledgeFolderV2Dao().getFolder(child.folder.folderId))
    }
}
