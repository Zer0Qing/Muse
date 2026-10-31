package io.zer0.memory.compile

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.Model
import io.zer0.memory.llm.MemoryLlmClient
import io.zer0.memory.summary.MemoryDb
import io.zer0.memory.summary.ScopedCompiledSectionEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * 编译摘要的子串回溯。
 *
 * 覆盖：散文段落里能按词命中、命中来自哪一段、按命中词数排序、facts 段落同样参与、
 * 以及空洞查询不产生命中。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CompiledSectionSearchTest {

    private lateinit var db: MemoryDb
    private lateinit var compiler: MemoryCompiler

    /** 编译流程在本测试里不调 LLM，给一个空实现即可。 */
    private class NoLlm : MemoryLlmClient {
        override suspend fun callText(
            systemPrompt: String,
            userContent: String,
            model: Model?,
            temperature: Float,
            maxTokens: Int,
            timeoutMs: Long,
        ): String = ""
    }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDb::class.java)
            .allowMainThreadQueries()
            .build()
        compiler = MemoryCompiler(
            sectionDao = db.compiledSectionDao(),
            llmClient = NoLlm(),
            scopedSectionDao = db.scopedCompiledSectionDao(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun writeSection(key: String, content: String, scope: String = "main", spaceId: String = "default") {
        db.scopedCompiledSectionDao().upsert(
            ScopedCompiledSectionEntity(
                sectionKey = key,
                scope = scope,
                spaceId = spaceId,
                content = content,
                fingerprint = null,
                updatedAt = Instant.now().toString(),
            ),
        )
    }

    @Test
    fun `scanned hits carry the section they came from`() = runTest {
        writeSection("today", "- 子奇今天去山里徒步了\n- 顺手买了新登山杖")

        val hits = compiler.searchCompiledSections("子奇 登山", "main", "default", limit = 10)

        assertEquals("两行都应命中: $hits", 2, hits.size)
        assertTrue("命中应标出所在段: $hits", hits.all { it.sectionKey == "today" })
        assertEquals("命中词更多的行排前面", "子奇今天去山里徒步了", hits.first().text)
    }

    @Test
    fun `facts section participates in the scan`() = runTest {
        writeSection("facts", "- 子奇是独立开发者")
        writeSection("week", "- 这周在整理记忆模块的检索链路")

        val hits = compiler.searchCompiledSections("独立开发者", "main", "default", limit = 10)

        assertEquals(1, hits.size)
        assertEquals("facts", hits.single().sectionKey)
        assertEquals("子奇是独立开发者", hits.single().text)
    }

    @Test
    fun `other scope or space is not scanned`() = runTest {
        writeSection("today", "- 子奇今天在另一个空间做了别的事", scope = "main", spaceId = "work")

        val hits = compiler.searchCompiledSections("子奇", "main", "default", limit = 10)

        assertTrue("不该读到别的空间的摘要: $hits", hits.isEmpty())
    }

    @Test
    fun `blank query yields no hits`() = runTest {
        writeSection("today", "- 子奇今天去山里徒步了")

        assertTrue(compiler.searchCompiledSections("   ", "main", "default").isEmpty())
    }

    @Test
    fun `scanner ranks lines by how many words they contain`() {
        val sections = listOf(
            "today" to "- 子奇在写代码\n- 子奇是独立开发者,平时写代码也写文档",
        )

        val hits = scanCompiledSections(sections, "子奇 代码 文档", limit = 10)

        assertEquals(2, hits.size)
        assertTrue("命中三个词的行应排在前面: $hits", hits.first().text.contains("文档"))
    }
}
