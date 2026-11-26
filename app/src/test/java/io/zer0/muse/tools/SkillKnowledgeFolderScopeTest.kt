package io.zer0.muse.tools

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.knowledge.KnowledgeDocEntity
import io.zer0.muse.data.session.MuseDb
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `knowledge_search` 的 folder 作用域:跨知识库同名目录不得合并。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SkillKnowledgeFolderScopeTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun doc(
        id: String,
        title: String,
        kbId: String,
        folder: String,
        content: String = "virtual display recovery",
    ) = KnowledgeDocEntity(
        id = id,
        title = title,
        content = content,
        fileType = "txt",
        kbId = kbId,
        metadataJson = if (folder.isEmpty()) "{}" else """{"folderPath":"$folder"}""",
    )

    private fun tools(dao: io.zer0.muse.data.knowledge.KnowledgeDocDao) = SkillSearchToolsImpl(
        context = context,
        client = OkHttpClient(),
        webSearchService = null,
        knowledgeDocDao = dao,
        ragService = null,
    )

    @Test
    fun `bare folder name existing in two knowledge bases is not merged`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).build()
        try {
            db.knowledgeDocDao().upsert(doc("kb1-contract", "甲库合同", "kb1", "合同"))
            db.knowledgeDocDao().upsert(doc("kb2-contract", "乙库合同", "kb2", "合同"))

            val result = tools(db.knowledgeDocDao()).execKnowledgeSearch(
                mapOf("query" to "virtual", "folder" to "合同"),
            )

            assertFalse("跨库同名目录不得合并返回,实际=$result", result.contains("甲库合同"))
            assertFalse("跨库同名目录不得合并返回,实际=$result", result.contains("乙库合同"))
            assertTrue("必须提示改用 kbId::folderPath 限定,实际=$result", result.contains("kbId::folderPath"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `kb qualified folder only searches that knowledge base`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).build()
        try {
            db.knowledgeDocDao().upsert(doc("kb1-contract", "甲库合同", "kb1", "合同"))
            db.knowledgeDocDao().upsert(doc("kb2-contract", "乙库合同", "kb2", "合同"))

            val result = tools(db.knowledgeDocDao()).execKnowledgeSearch(
                mapOf("query" to "virtual", "folder" to "kb2::合同"),
            )

            assertTrue("限定的知识库必须命中,实际=$result", result.contains("乙库合同"))
            assertFalse("未限定的知识库不得串入,实际=$result", result.contains("甲库合同"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `bare folder name in a single knowledge base still searches it`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).build()
        try {
            db.knowledgeDocDao().upsert(doc("kb1-contract", "甲库合同", "kb1", "合同"))
            db.knowledgeDocDao().upsert(doc("kb1-other", "甲库其他", "kb1", "其他"))

            val result = tools(db.knowledgeDocDao()).execKnowledgeSearch(
                mapOf("query" to "virtual", "folder" to "合同"),
            )

            assertTrue("单库同名路径不构成歧义,应正常检索,实际=$result", result.contains("甲库合同"))
            assertFalse("不得串到同库其它文件夹,实际=$result", result.contains("甲库其他"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `bare folder narrowed by a single-kb permit is not ambiguous`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).build()
        try {
            db.knowledgeDocDao().upsert(doc("kb1-contract", "甲库合同", "kb1", "合同"))
            db.knowledgeDocDao().upsert(doc("kb2-contract", "乙库合同", "kb2", "合同"))

            val scoped = SkillSearchToolsImpl(
                context = context,
                client = OkHttpClient(),
                webSearchService = null,
                knowledgeDocDao = db.knowledgeDocDao(),
                ragService = null,
                // 许可只覆盖甲库 → 裸 folder 名不再歧义
                trustedContextProvider = { ToolSearchContext(allowedDocIds = setOf("kb1-contract")) },
            )

            val result = scoped.execKnowledgeSearch(mapOf("query" to "virtual", "folder" to "合同"))

            assertTrue("许可已限定唯一知识库,应命中,实际=$result", result.contains("甲库合同"))
            assertFalse("许可外知识库不得串入,实际=$result", result.contains("乙库合同"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `session attachments never surface through the knowledge tool`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).build()
        try {
            val attachmentDocId = io.zer0.muse.rag.SessionAttachment.buildDocId("session-1", "attach-1")
            db.knowledgeDocDao().upsert(
                KnowledgeDocEntity(
                    id = attachmentDocId,
                    title = "会话附件",
                    content = "virtual display recovery attachment",
                    fileType = io.zer0.muse.rag.SessionAttachment.FILE_TYPE,
                ),
            )
            db.knowledgeDocDao().upsert(doc("doc-user", "用户文档", "default", ""))

            val result = tools(db.knowledgeDocDao()).execKnowledgeSearch(mapOf("query" to "virtual"))

            assertFalse("会话附件是会话私有资料,工具侧不得返回,实际=$result", result.contains("会话附件"))
            assertTrue("普通文档检索不受影响,实际=$result", result.contains("用户文档"))
        } finally {
            db.close()
        }
    }
}
