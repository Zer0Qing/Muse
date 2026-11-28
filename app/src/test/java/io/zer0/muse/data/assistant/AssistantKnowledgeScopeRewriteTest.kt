package io.zer0.muse.data.assistant

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MuseDb
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 助手知识库作用域的改写规则。
 *
 * 读取侧的语义是「folderScopes 非空时以它为准，空数组回落到整库绑定」，
 * 所以改写时必须同时维护 knowledgeBaseIdsJson —— 只改 scopes 会让权限静默扩大或缩小。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AssistantKnowledgeScopeRewriteTest {

    private lateinit var db: MuseDb
    private lateinit var repo: AssistantRepository

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).allowMainThreadQueries().build()
        repo = AssistantRepository(db.assistantDao(), context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seed(id: String, kbIds: List<String>, scopes: List<Pair<String, String>>) {
        db.assistantDao().upsert(
            AssistantEntity(
                id = id,
                name = id,
                knowledgeBaseIdsJson = repo.serializeStringList(kbIds),
                knowledgeFolderScopesJson = repo.serializeStringList(repo.encodeKnowledgeFolderScopes(scopes)),
            ),
        )
    }

    @Test
    fun `detach knowledge base clears both json fields`() = runBlocking {
        seed("as-1", listOf("kb-a", "kb-b"), listOf("kb-a" to "合同", "kb-b" to ""))
        seed("as-2", listOf("kb-b"), emptyList())

        val updated = repo.detachKnowledgeBase("kb-a")

        assertEquals("只改写真正引用它的助手", 1, updated)
        val as1 = db.assistantDao().getById("as-1") ?: error("as-1 丢失")
        assertEquals(listOf("kb-b"), repo.parseKnowledgeBaseIds(as1))
        assertEquals(listOf("kb-b" to ""), repo.parseKnowledgeFolderScopes(as1))
        val as2 = db.assistantDao().getById("as-2") ?: error("as-2 丢失")
        assertEquals(listOf("kb-b"), repo.parseKnowledgeBaseIds(as2))
    }

    @Test
    fun `detach unknown kb writes nothing`() = runBlocking {
        seed("as-1", listOf("kb-a"), listOf("kb-a" to "合同"))

        val updated = repo.detachKnowledgeBase("kb-x")

        assertEquals("无引用时不得写库", 0, updated)
        val as1 = db.assistantDao().getById("as-1") ?: error("as-1 丢失")
        assertEquals(listOf("kb-a"), repo.parseKnowledgeBaseIds(as1))
    }

    @Test
    fun `rename folder rewrites matching subtree scopes`() = runBlocking {
        seed("as-1", listOf("kb-a"), listOf("kb-a" to "合同", "kb-a" to "合同/2026", "kb-a" to "其它"))

        val updated = repo.rewriteKnowledgeFolderScopes("kb-a", "合同", "协议")

        assertEquals(1, updated)
        val as1 = db.assistantDao().getById("as-1") ?: error("as-1 丢失")
        assertEquals(
            listOf("kb-a" to "协议", "kb-a" to "协议/2026", "kb-a" to "其它"),
            repo.parseKnowledgeFolderScopes(as1),
        )
    }

    @Test
    fun `rename does not touch other kb scopes with same path`() = runBlocking {
        seed("as-1", listOf("kb-a", "kb-b"), listOf("kb-a" to "合同", "kb-b" to "合同"))

        repo.rewriteKnowledgeFolderScopes("kb-a", "合同", "协议")

        val as1 = db.assistantDao().getById("as-1") ?: error("as-1 丢失")
        assertEquals(
            "同名文件夹跨库不得混改",
            listOf("kb-a" to "协议", "kb-b" to "合同"),
            repo.parseKnowledgeFolderScopes(as1),
        )
    }

    @Test
    fun `deleting folder drops scopes and the kb binding projection`() = runBlocking {
        seed("as-1", listOf("kb-a"), listOf("kb-a" to "合同"))

        val updated = repo.rewriteKnowledgeFolderScopes("kb-a", "合同", newPath = null)

        assertEquals(1, updated)
        val as1 = db.assistantDao().getById("as-1") ?: error("as-1 丢失")
        assertTrue(
            "空 scopes + 残留 kbId 会被读成整库授权，必须一起清",
            repo.parseKnowledgeFolderScopes(as1).isEmpty() &&
                repo.parseKnowledgeBaseIds(as1).isEmpty(),
        )
    }

    @Test
    fun `deleting folder keeps kb binding when sibling scopes remain`() = runBlocking {
        seed("as-1", listOf("kb-a"), listOf("kb-a" to "合同", "kb-a" to "资料"))

        repo.rewriteKnowledgeFolderScopes("kb-a", "合同", newPath = null)

        val as1 = db.assistantDao().getById("as-1") ?: error("as-1 丢失")
        assertEquals(listOf("kb-a" to "资料"), repo.parseKnowledgeFolderScopes(as1))
        assertEquals("仍有作用域时保留整库投影", listOf("kb-a"), repo.parseKnowledgeBaseIds(as1))
    }

    @Test
    fun `assistant without scopes is never rewritten`() = runBlocking {
        // 只用整库绑定（scopes 为空）的助手，目录改名与它无关
        seed("as-1", listOf("kb-a"), emptyList())

        assertEquals(0, repo.rewriteKnowledgeFolderScopes("kb-a", "合同", "协议"))
        assertEquals(0, repo.rewriteKnowledgeFolderScopes("kb-a", "合同", newPath = null))
        val as1 = db.assistantDao().getById("as-1") ?: error("as-1 丢失")
        assertEquals(listOf("kb-a"), repo.parseKnowledgeBaseIds(as1))
    }
}
