package io.zer0.muse.knowledge

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.knowledge.KnowledgeV2DocEntity
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.knowledge.storage.KnowledgeLifecycleService
import io.zer0.muse.knowledge.storage.KnowledgeManagementFacade
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R8 步骤「所有 UI/工具/Web 管理行为共用仓储事务和作业服务」(计划 R8 输入/产出)。
 *
 * 断言三个管理入口对同一操作得到**一致结果**,且都经同一生命周期服务落库,
 * 不允许某个入口绕过统一服务直接 delete DAO。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KnowledgeManagementEntryParityTest {

    private lateinit var db: MuseDb
    private lateinit var service: KnowledgeLifecycleService
    private lateinit var facade: KnowledgeManagementFacade

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).allowMainThreadQueries().build()
        service = KnowledgeLifecycleService(
            folderDao = db.knowledgeFolderV2Dao(),
            v2Dao = db.knowledgeV2Dao(),
            jobDao = db.knowledgeJobDao(),
            clock = { 3000L },
        )
        facade = KnowledgeManagementFacade(service)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedDoc(docId: String) {
        db.knowledgeV2Dao().insertDoc(
            KnowledgeV2DocEntity(
                docId = docId,
                ownerType = "UserLibrary",
                ownerId = "kb-1",
                currentRevisionId = "rev-$docId-1",
            ),
        )
    }

    @Test
    fun `ui tool and web delete produce identical outcome`() = runBlocking {
        seedDoc("doc-ui")
        seedDoc("doc-tool")
        seedDoc("doc-web")

        val ui = facade.deleteFromUi("doc-ui", clearCitationSnapshots = false)
        val tool = facade.deleteFromTool("doc-tool", clearCitationSnapshots = false)
        val web = facade.deleteFromWeb("doc-web", clearCitationSnapshots = false)

        assertEquals(ui?.deletedDocs, tool?.deletedDocs)
        assertEquals(ui?.deletedDocs, web?.deletedDocs)
        assertEquals(ui?.citationAction, tool?.citationAction)
        assertEquals(ui?.citationAction, web?.citationAction)
        assertNull(db.knowledgeV2Dao().getDoc("doc-ui"))
        assertNull(db.knowledgeV2Dao().getDoc("doc-tool"))
        assertNull(db.knowledgeV2Dao().getDoc("doc-web"))
    }

    @Test
    fun `folder operations through facade share same stable identity`() = runBlocking {
        val created = facade.createFolderFromUi("kb-1", "", "资料") as KnowledgeLifecycleService.FolderResult.Ok
        val renamed = facade.renameFolderFromTool(created.folder.folderId, "资料库")
        assertEquals(
            created.folder.folderId,
            (renamed as KnowledgeLifecycleService.FolderResult.Ok).folder.folderId,
        )
    }

    @Test
    fun `deleting missing doc returns null consistently in all entries`() = runBlocking {
        assertEquals(null, facade.deleteFromUi("missing", false))
        assertEquals(null, facade.deleteFromTool("missing", false))
        assertEquals(null, facade.deleteFromWeb("missing", false))
    }
}
