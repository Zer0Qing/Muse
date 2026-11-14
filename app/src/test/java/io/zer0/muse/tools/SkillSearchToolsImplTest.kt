package io.zer0.muse.tools

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.knowledge.KnowledgeDocEntity
import io.zer0.muse.data.session.MuseDb
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import io.zer0.muse.web.WebSearchResult
import io.zer0.muse.web.WebSearchService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SkillSearchToolsImplTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun impl() = SkillSearchToolsImpl(
        context = context,
        client = OkHttpClient(),
        webSearchService = null,
        knowledgeDocDao = null,
        ragService = null,
    )

    @Test
    fun `web search tool boundary preserves every query token`() = runBlocking {
        class RecordingSearchService : WebSearchService {
            override val name: String = "Recording"
            val queries = mutableListOf<String>()

            override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> {
                queries += query
                return listOf(
                    WebSearchResult(
                        title = query,
                        url = if (query.startsWith("site:github.com")) {
                            "https://github.com/example/python"
                        } else {
                            "https://example.com/$maxResults"
                        },
                        snippet = query,
                        source = name,
                    ),
                )
            }
        }

        val service = RecordingSearchService()
        val tools = SkillSearchToolsImpl(
            context = context,
            client = OkHttpClient(),
            webSearchService = service,
            knowledgeDocDao = null,
            ragService = null,
        )

        listOf(
            "python android",
            "android python",
            "天气 成都",
            "site:github.com python",
        ).forEach { query ->
            val args = ToolArgsParser.parse(
                "{\"query\":\"$query\",\"max_results\":5}",
                "web_search",
            )
            tools.execWebSearch(args, turnKey = "tool-boundary-$query")
        }

        assertEquals(
            listOf("python android", "android python", "天气 成都", "site:github.com python"),
            service.queries,
        )
    }

    @Test
    fun validatePublicUrl_rejectsPrivateAndLoopback() {
        val tools = impl()
        assertFalse(tools.validatePublicUrl("http://localhost:8080/x"))
        assertFalse(tools.validatePublicUrl("http://127.0.0.1/x"))
        assertFalse(tools.validatePublicUrl("http://192.168.1.1/x"))
        assertFalse(tools.validatePublicUrl("http://10.0.0.1/x"))
        assertFalse(tools.validatePublicUrl("file:///etc/passwd"))
    }

    @Test
    fun validatePublicUrl_acceptsPublicHttps() {
        val tools = impl()
        assertTrue(tools.validatePublicUrl("https://example.com/path"))
    }

    @Test
    fun includeInternalSearchReturnsSeededInternalDocument() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).build()
        try {
            db.knowledgeDocDao().upsert(
                KnowledgeDocEntity(
                    id = "devdoc-agent",
                    title = "Agent capabilities",
                    content = "virtual display recovery",
                    fileType = "devdoc",
                    isInternal = true,
                ),
            )
            db.knowledgeDocDao().upsert(
                KnowledgeDocEntity(
                    id = "doc-user",
                    title = "User notes",
                    content = "virtual display recovery notes",
                    fileType = "txt",
                    isInternal = false,
                ),
            )
            // R1 入口许可合同:include_internal 是执行器侧的许可,不是模型可自报的扩权参数。
            // 上下文允许时(true)模型传 true 生效;上下文不允许(或未接)时一律按 false 处理。
            val toolsAllowed = SkillSearchToolsImpl(
                context = context,
                client = OkHttpClient(),
                webSearchService = null,
                knowledgeDocDao = db.knowledgeDocDao(),
                ragService = null,
                trustedContextProvider = { ToolSearchContext(allowInternalDocs = true) },
            )
            val toolsDenied = SkillSearchToolsImpl(
                context = context,
                client = OkHttpClient(),
                webSearchService = null,
                knowledgeDocDao = db.knowledgeDocDao(),
                ragService = null,
                trustedContextProvider = { ToolSearchContext(allowInternalDocs = false) },
            )

            val allowedResult = toolsAllowed.execKnowledgeSearch(
                mapOf("query" to "virtual", "include_internal" to "true"),
            )
            val deniedResult = toolsDenied.execKnowledgeSearch(
                mapOf("query" to "virtual", "include_internal" to "true"),
            )

            assertTrue(
                "上下文允许 internal 时模型传 true 应可见内部文档",
                allowedResult.contains("Agent capabilities"),
            )
            assertFalse(
                "上下文不允许 internal 时模型传 true 不得越权(只能收窄)",
                deniedResult.contains("Agent capabilities"),
            )
            assertTrue("收窄后普通文档检索不受影响", deniedResult.contains("User notes"))
        } finally {
            db.close()
        }
    }

    /**
     * R1 步骤「未接可信上下文的外部入口不能先开放全库」:
     * 无 trustedContextProvider(= 外部/未授权入口)时,include_internal 一律按 false 处理,
     * 模型自报 true 不得打开内部文档;普通检索保持可用(不得因缺上下文而不可用)。
     */
    @Test
    fun untrustedEntryWithoutTrustedContextCannotExposeInternalDocs() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).build()
        try {
            db.knowledgeDocDao().upsert(
                KnowledgeDocEntity(
                    id = "devdoc-agent",
                    title = "Agent capabilities",
                    content = "virtual display recovery",
                    fileType = "devdoc",
                    isInternal = true,
                ),
            )
            db.knowledgeDocDao().upsert(
                KnowledgeDocEntity(
                    id = "doc-user",
                    title = "User notes",
                    content = "virtual display recovery notes",
                    fileType = "txt",
                    isInternal = false,
                ),
            )
            val tools = SkillSearchToolsImpl(
                context = context,
                client = OkHttpClient(),
                webSearchService = null,
                knowledgeDocDao = db.knowledgeDocDao(),
                ragService = null,
            )

            val result = tools.execKnowledgeSearch(
                mapOf("query" to "virtual", "include_internal" to "true"),
            )

            assertFalse(
                "未接可信上下文时模型传 include_internal=true 不得返回内部文档",
                result.contains("Agent capabilities"),
            )
            assertTrue("普通检索保持可用", result.contains("User notes"))
        } finally {
            db.close()
        }
    }

    /**
     * R1 步骤「工具使用执行器提供的上下文,模型参数只能收窄」:
     * 可信上下文给定绑定文档许可后,模型的 folder/top_k 等参数只能在该许可内收窄,
     * 不得通过省略 folder 之类的方式访问许可之外的文档。
     */
    @Test
    fun modelParametersCannotWidenBeyondTrustedScope() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).build()
        try {
            db.knowledgeDocDao().upsert(
                KnowledgeDocEntity(
                    id = "doc-in-scope",
                    title = "In scope note",
                    content = "virtual display recovery",
                    fileType = "txt",
                    isInternal = false,
                    metadataJson = """{"folder_path":"合同"}""",
                ),
            )
            db.knowledgeDocDao().upsert(
                KnowledgeDocEntity(
                    id = "doc-out-of-scope",
                    title = "Out of scope note",
                    content = "virtual display recovery",
                    fileType = "txt",
                    isInternal = false,
                ),
            )
            val tools = SkillSearchToolsImpl(
                context = context,
                client = OkHttpClient(),
                webSearchService = null,
                knowledgeDocDao = db.knowledgeDocDao(),
                ragService = null,
                trustedContextProvider = {
                    ToolSearchContext(allowedDocIds = setOf("doc-in-scope"))
                },
            )

            // 模型不传 folder(试图访问全库) → 结果必须仍被许可裁剪
            val widened = tools.execKnowledgeSearch(mapOf("query" to "virtual"))
            assertFalse(
                "许可外文档不得出现(模型省略 folder 不能扩权),实际=$widened",
                widened.contains("Out of scope note"),
            )
            assertTrue("许可内文档可见", widened.contains("In scope note"))
        } finally {
            db.close()
        }
    }
}
