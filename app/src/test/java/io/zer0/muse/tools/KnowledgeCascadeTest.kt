package io.zer0.muse.tools

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.knowledge.KnowledgeBaseEntity
import io.zer0.muse.data.knowledge.KnowledgeDocEntity
import io.zer0.muse.data.knowledge.KnowledgeFolderEntity
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.rag.KnowledgeFolderBrowser
import io.zer0.muse.rag.RagConfig
import io.zer0.muse.rag.RagService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 知识库级联合同：删库 / 目录重命名 / 目录删除必须同步助手的知识库与文件夹作用域。
 *
 * 缺陷形态（都是静默的）：
 *  - 删库只删文档与文件夹行 → 助手留着指向已删库的绑定，从此「配了库但永远搜不到」；
 *  - 目录改名不同步作用域 → 助手绑的是旧路径，检索范围空掉；
 *  - 目录删除后作用域留下空数组 → 读取侧把空数组当「整库」，权限被悄悄放大。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KnowledgeCascadeTest {

    private lateinit var db: MuseDb
    private lateinit var cascade: KnowledgeCascade
    private lateinit var assistantRepo: AssistantRepository

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).allowMainThreadQueries().build()
        assistantRepo = AssistantRepository(db.assistantDao(), context)
        // 向量层用 mock 隔离，但必须保留「删文档行」这条真实契约，否则测的是残缺实现。
        val ragService = mockk<RagService>(relaxed = true)
        coEvery { ragService.deleteDocument(any()) } coAnswers {
            db.knowledgeDocDao().delete(firstArg())
            Unit
        }
        cascade = KnowledgeCascade(
            docDao = db.knowledgeDocDao(),
            chunkDao = db.knowledgeChunkDao(),
            kbDao = db.knowledgeBaseDao(),
            folderDao = db.knowledgeFolderDao(),
            ragService = ragService,
            assistantRepository = assistantRepo,
            db = db,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedKb(id: String, name: String = id) {
        db.knowledgeBaseDao().upsert(KnowledgeBaseEntity(id = id, name = name))
    }

    private suspend fun seedDoc(id: String, kbId: String, folder: String = "", internal: Boolean = false) {
        db.knowledgeDocDao().upsert(
            KnowledgeFolderBrowser.withFolderPath(
                KnowledgeDocEntity(
                    id = id,
                    title = id,
                    content = "content",
                    kbId = kbId,
                    isInternal = internal,
                    embeddingModel = RagConfig.embeddingModelKey(RagConfig()),
                ),
                folder,
            ),
        )
    }

    private suspend fun seedFolder(kbId: String, path: String) {
        db.knowledgeFolderDao().upsert(
            KnowledgeFolderEntity(
                id = "kbfolder-$kbId-$path",
                kbId = kbId,
                path = path,
                name = path.substringAfterLast('/'),
            ),
        )
    }

    private suspend fun seedAssistant(
        id: String,
        kbIds: List<String>,
        scopes: List<Pair<String, String>>,
    ) {
        db.assistantDao().upsert(
            AssistantEntity(
                id = id,
                name = id,
                knowledgeBaseIdsJson = assistantRepo.serializeStringList(kbIds),
                knowledgeFolderScopesJson =
                assistantRepo.serializeStringList(assistantRepo.encodeKnowledgeFolderScopes(scopes)),
            ),
        )
    }

    // ---------- 删库 ----------

    @Test
    fun `delete kb removes docs folders and assistant bindings`() = runBlocking {
        seedKb("kb-a")
        seedKb("default", "默认知识库")
        seedDoc("doc-1", "kb-a")
        seedDoc("doc-2", "kb-a")
        seedDoc("doc-keep", "default")
        seedFolder("kb-a", "合同")
        seedAssistant("as-1", listOf("kb-a"), listOf("kb-a" to "合同"))
        seedAssistant("as-2", listOf("default"), emptyList())

        val result = cascade.deleteKnowledgeBase("kb-a")

        assertTrue("删库应成功: $result", result.isSuccess)
        assertEquals(2, result.getOrNull()?.deletedDocs)
        assertNull("库行必须删除", db.knowledgeBaseDao().getById("kb-a"))
        assertTrue("该库文档必须清空", db.knowledgeDocDao().getAll().none { it.kbId == "kb-a" })
        assertTrue("该库文件夹必须清空", db.knowledgeFolderDao().getByKb("kb-a").isEmpty())
        assertTrue("其它库文档不受影响", db.knowledgeDocDao().getById("doc-keep") != null)

        val as1 = db.assistantDao().getById("as-1") ?: error("as-1 丢失")
        assertTrue(
            "助手的 knowledgeBaseIdsJson 不得残留已删库",
            assistantRepo.parseKnowledgeBaseIds(as1).none { it == "kb-a" },
        )
        assertTrue(
            "助手的 folder scopes 不得残留已删库",
            assistantRepo.parseKnowledgeFolderScopes(as1).none { it.first == "kb-a" },
        )
        val as2 = db.assistantDao().getById("as-2") ?: error("as-2 丢失")
        assertEquals("无关助手不得被改", listOf("default"), assistantRepo.parseKnowledgeBaseIds(as2))
    }

    @Test
    fun `internal docs of deleted kb are rehomed to default not orphaned`() = runBlocking {
        seedKb("kb-a")
        seedKb("default", "默认知识库")
        seedDoc("devdoc-1", "kb-a", internal = true)

        cascade.deleteKnowledgeBase("kb-a")

        val doc = db.knowledgeDocDao().getById("devdoc-1")
        assertTrue("内部文档不能随库消失", doc != null)
        assertEquals("内部文档必须改挂默认库", "default", doc?.kbId)
    }

    @Test
    fun `default kb cannot be deleted`() = runBlocking {
        seedKb("default", "默认知识库")
        seedDoc("doc-1", "default")

        val result = cascade.deleteKnowledgeBase("default")

        assertTrue("默认库必须拒绝删除", result.isError)
        assertTrue("默认库行必须还在", db.knowledgeBaseDao().getById("default") != null)
        assertTrue("默认库文档必须还在", db.knowledgeDocDao().getById("doc-1") != null)
    }

    // ---------- 文件夹新建 / 重命名 ----------

    @Test
    fun `create folder rejects duplicate name in same parent`() = runBlocking {
        seedKb("kb-a")

        val first = cascade.createFolder("kb-a", "合同")
        val second = cascade.createFolder("kb-a", "合同")

        assertTrue(first is KnowledgeCascade.FolderResult.Ok)
        assertTrue(
            "同父同名必须报冲突，不能静默 REPLACE 覆盖已有文件夹",
            second is KnowledgeCascade.FolderResult.NameConflict,
        )
        assertEquals("不得写出重复行", 1, db.knowledgeFolderDao().getByKb("kb-a").count { it.path == "合同" })
    }

    @Test
    fun `create folder detects collision with folder implied by documents`() = runBlocking {
        seedKb("kb-a")
        // 只有文档路径、没有显式文件夹行 —— 这种情况下也属于「已存在」
        seedDoc("doc-1", "kb-a", folder = "合同")

        val result = cascade.createFolder("kb-a", "合同")

        assertTrue("文档隐式占用的目录也算重名", result is KnowledgeCascade.FolderResult.NameConflict)
    }

    @Test
    fun `rename folder rewrites documents and assistant scopes`() = runBlocking {
        seedKb("kb-a")
        seedFolder("kb-a", "合同")
        seedFolder("kb-a", "合同/2026")
        seedDoc("doc-1", "kb-a", folder = "合同")
        seedDoc("doc-2", "kb-a", folder = "合同/2026")
        seedDoc("doc-out", "kb-a", folder = "其它")
        seedAssistant("as-1", listOf("kb-a"), listOf("kb-a" to "合同", "kb-a" to "合同/2026"))

        val result = cascade.renameFolder("kb-a", "合同", "协议")

        assertTrue("重命名应成功: $result", result is KnowledgeCascade.FolderResult.Ok)
        assertEquals("协议", (result as KnowledgeCascade.FolderResult.Ok).path)
        assertTrue("旧路径文件夹行必须消失", db.knowledgeFolderDao().getByPath("kb-a", "合同") == null)
        assertTrue("子目录必须跟着改", db.knowledgeFolderDao().getByPath("kb-a", "协议/2026") != null)
        assertEquals("协议", KnowledgeFolderBrowser.folderPath(db.knowledgeDocDao().getById("doc-1") ?: error("doc-1")))
        assertEquals(
            "协议/2026",
            KnowledgeFolderBrowser.folderPath(db.knowledgeDocDao().getById("doc-2") ?: error("doc-2")),
        )
        assertEquals(
            "不在子树内的文档不动",
            "其它",
            KnowledgeFolderBrowser.folderPath(db.knowledgeDocDao().getById("doc-out") ?: error("doc-out")),
        )

        val as1 = db.assistantDao().getById("as-1") ?: error("as-1 丢失")
        val scopes = assistantRepo.parseKnowledgeFolderScopes(as1)
        assertTrue("助手作用域必须同步改名", scopes.contains("kb-a" to "协议"))
        assertTrue("子目录作用域也要改", scopes.contains("kb-a" to "协议/2026"))
        assertTrue("不得残留旧路径", scopes.none { it.second == "合同" || it.second.startsWith("合同/") })
    }

    @Test
    fun `rename folder rejects name conflict`() = runBlocking {
        seedKb("kb-a")
        seedFolder("kb-a", "合同")
        seedFolder("kb-a", "协议")

        val result = cascade.renameFolder("kb-a", "合同", "协议")

        assertTrue("同级重名必须报冲突", result is KnowledgeCascade.FolderResult.NameConflict)
        assertTrue("原文件夹必须还在", db.knowledgeFolderDao().getByPath("kb-a", "合同") != null)
        assertTrue("目标文件夹不得被覆盖", db.knowledgeFolderDao().getByPath("kb-a", "协议") != null)
    }

    // ---------- 文件夹删除 ----------

    @Test
    fun `delete folder moves docs to root and drops assistant scopes`() = runBlocking {
        seedKb("kb-a")
        seedFolder("kb-a", "合同")
        seedFolder("kb-a", "合同/2026")
        seedDoc("doc-1", "kb-a", folder = "合同")
        seedDoc("doc-2", "kb-a", folder = "合同/2026")
        seedAssistant("as-1", listOf("kb-a"), listOf("kb-a" to "合同"))

        val result = cascade.deleteFolder("kb-a", "合同")

        assertTrue("删目录应成功: $result", result is KnowledgeCascade.FolderResult.Ok)
        assertTrue("文件夹行必须删干净", db.knowledgeFolderDao().getByKb("kb-a").isEmpty())
        assertEquals(
            "文档不删，只回根",
            "",
            KnowledgeFolderBrowser.folderPath(db.knowledgeDocDao().getById("doc-1") ?: error("doc-1")),
        )
        assertEquals(
            "子目录文档也回根",
            "",
            KnowledgeFolderBrowser.folderPath(db.knowledgeDocDao().getById("doc-2") ?: error("doc-2")),
        )

        val as1 = db.assistantDao().getById("as-1") ?: error("as-1 丢失")
        assertTrue(
            "指向已删目录的作用域必须移除（留着空数组会被读成整库）",
            assistantRepo.parseKnowledgeFolderScopes(as1).isEmpty(),
        )
        assertTrue(
            "该库已无文件夹作用域时，整库绑定也要一起清掉，避免权限被放大",
            assistantRepo.parseKnowledgeBaseIds(as1).none { it == "kb-a" },
        )
    }

    @Test
    fun `delete folder keeps sibling scopes intact`() = runBlocking {
        seedKb("kb-a")
        seedFolder("kb-a", "合同")
        seedFolder("kb-a", "资料")
        seedAssistant("as-1", listOf("kb-a"), listOf("kb-a" to "合同", "kb-a" to "资料"))

        cascade.deleteFolder("kb-a", "合同")

        val as1 = db.assistantDao().getById("as-1") ?: error("as-1 丢失")
        val scopes = assistantRepo.parseKnowledgeFolderScopes(as1)
        assertEquals("只移除被删目录", listOf("kb-a" to "资料"), scopes)
        assertTrue(
            "仍有作用域时整库绑定保留",
            assistantRepo.parseKnowledgeBaseIds(as1).contains("kb-a"),
        )
    }

    @Test
    fun `delete missing folder reports not found and changes nothing`() = runBlocking {
        seedKb("kb-a")

        val result = cascade.deleteFolder("kb-a", "不存在")

        assertTrue(result is KnowledgeCascade.FolderResult.NotFound)
        assertTrue(db.knowledgeFolderDao().getByKb("kb-a").isEmpty())
    }
}
