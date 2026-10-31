package io.zer0.memory.compile

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.Model
import io.zer0.memory.fact.FactDb
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.fact.FactStore
import io.zer0.memory.llm.MemoryLlmClient
import io.zer0.memory.summary.CompiledSectionDao
import io.zer0.memory.summary.CompiledSectionEntity
import io.zer0.memory.summary.MemoryDb
import io.zer0.memory.summary.ScopedCompiledSectionEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

/**
 * v12 (T2-1): 编译产物与 facts 表对账测试。
 * 用户编辑/合并事实后,FACTS section 的对应行自动替换为 facts 表现值。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReconcileFactsSectionTest {

    private lateinit var memoryDb: MemoryDb
    private lateinit var sectionDao: CompiledSectionDao
    private lateinit var factDb: FactDb
    private lateinit var factStore: FactStore
    private lateinit var compiler: MemoryCompiler
    private lateinit var scopedCompiler: MemoryCompiler

    private class NoopLlm : MemoryLlmClient {
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
        memoryDb = Room.inMemoryDatabaseBuilder(context, MemoryDb::class.java)
            .allowMainThreadQueries()
            .build()
        sectionDao = memoryDb.compiledSectionDao()
        factDb = Room.inMemoryDatabaseBuilder(context, FactDb::class.java)
            .allowMainThreadQueries()
            .build()
        factStore = FactStore(factDb.factDao(), factDb)
        compiler = MemoryCompiler(
            sectionDao = sectionDao,
            llmClient = NoopLlm(),
            fileWriter = null,
            factStore = factStore,
        )
        scopedCompiler = MemoryCompiler(
            sectionDao = sectionDao,
            llmClient = NoopLlm(),
            fileWriter = null,
            factStore = null,
            scopedSectionDao = memoryDb.scopedCompiledSectionDao(),
            compileContext = MemoryCompileContext(
                getScope = { "main" },
                getSpaceId = { "work" },
            ),
        )
    }

    @After
    fun tearDown() {
        memoryDb.close()
        factDb.close()
    }

    @Test
    fun emptyFactStoreClearsExistingFactsSection() = runTest {
        sectionDao.upsert(
            CompiledSectionEntity(
                sectionKey = MemoryCompiler.Section.FACTS.key,
                content = "已经删除的事实",
                fingerprint = "old",
                updatedAt = java.time.Instant.now().toString(),
            ),
        )

        val noStoreCompiler = MemoryCompiler(
            sectionDao = sectionDao,
            llmClient = NoopLlm(),
            fileWriter = null,
            factStore = null,
        )
        val changed = noStoreCompiler.reconcileFactsSectionWithStore(emptyList())

        assertEquals("空事实表应清空旧 FACTS 段", 1, changed)
        assertEquals("", noStoreCompiler.readSection(MemoryCompiler.Section.FACTS))
    }

    @Test
    fun `scoped compiled sections do not leak between spaces`() = runTest {
        val scopedDao = memoryDb.scopedCompiledSectionDao()
        scopedDao.upsert(
            ScopedCompiledSectionEntity(
                sectionKey = MemoryCompiler.Section.FACTS.key,
                scope = "main",
                spaceId = "work",
                content = "work-only",
                updatedAt = java.time.Instant.now().toString(),
            ),
        )
        scopedDao.upsert(
            ScopedCompiledSectionEntity(
                sectionKey = MemoryCompiler.Section.FACTS.key,
                scope = "main",
                spaceId = "life",
                content = "life-only",
                updatedAt = java.time.Instant.now().toString(),
            ),
        )

        assertEquals("work-only", scopedCompiler.readSection(MemoryCompiler.Section.FACTS))
        assertEquals("", scopedCompiler.readSection(MemoryCompiler.Section.TODAY))
    }

    @Test
    fun `reading scoped compiled memory writes its file to the scoped directory`() = runTest {
        val root = Files.createTempDirectory("muse-scoped-memory").toFile()
        try {
            val target = MemoryCompileTarget(assistantId = "assistant-b", scope = "assistant-b", spaceId = "work")
            memoryDb.scopedCompiledSectionDao().upsert(
                ScopedCompiledSectionEntity(
                    sectionKey = MemoryCompiler.Section.FACTS.key,
                    scope = target.normalizedScope,
                    spaceId = target.normalizedSpaceId,
                    content = "assistant-b private fact",
                    updatedAt = java.time.Instant.now().toString(),
                ),
            )
            val writer = MemoryFileWriter(root)
            val isolatedCompiler = MemoryCompiler(
                sectionDao = sectionDao,
                llmClient = NoopLlm(),
                fileWriter = writer,
                scopedSectionDao = memoryDb.scopedCompiledSectionDao(),
            )

            val rendered = isolatedCompiler.readCompiledMemoryMarkdown(target = target)

            assertTrue(rendered.contains("assistant-b private fact"))
            assertNull("scoped render must not overwrite shared root memory.md", writer.readMemoryMd())
            assertEquals(rendered, writer.readMemoryMd(target))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `assistant scoped tombstone prevents stale compiled fact resurrection`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val assistantId = "tombstone-test-${System.nanoTime()}"
        val databaseName = "facts_$assistantId.db"
        context.deleteDatabase(databaseName)
        val provider = FactDbProvider(context)
        try {
            val childStore = provider.getFactStore(assistantId)
            val deletedId = childStore.add(
                FactStore.Fact(fact = "deleted child-only preference"),
                scope = assistantId,
                spaceId = "work",
            )
            assertTrue(childStore.delete(deletedId, assistantId, assistantId, "work"))

            val target = MemoryCompileTarget(assistantId = assistantId, scope = assistantId, spaceId = "work")
            memoryDb.scopedCompiledSectionDao().upsert(
                ScopedCompiledSectionEntity(
                    sectionKey = MemoryCompiler.Section.FACTS.key,
                    scope = assistantId,
                    spaceId = "work",
                    content = "deleted child-only preference",
                    updatedAt = java.time.Instant.now().toString(),
                ),
            )
            val providerCompiler = MemoryCompiler(
                sectionDao = sectionDao,
                llmClient = NoopLlm(),
                fileWriter = null,
                factStore = factStore,
                scopedSectionDao = memoryDb.scopedCompiledSectionDao(),
                factDbProvider = provider,
            )

            val changed = providerCompiler.reconcileFactsSectionWithStore(emptyList(), target)

            assertEquals(1, changed)
            assertEquals("", providerCompiler.readSection(MemoryCompiler.Section.FACTS, target))
            assertTrue(childStore.getByScopeAndSpace(assistantId, "work").isEmpty())
            assertTrue(
                "deleted child facts must not be absorbed into the default store",
                factStore.getByScopeAndSpace("main", "work").isEmpty(),
            )
        } finally {
            provider.release(assistantId)
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun `edited fact projects table value and absorbs orphan lines`() = runTest {
        // 预置编译产物(模拟 LLM 编译结果)
        sectionDao.upsert(
            CompiledSectionEntity(
                sectionKey = MemoryCompiler.Section.FACTS.key,
                content = "用户喜欢喝美式咖啡\n用户最近在筹备搬家",
                fingerprint = null,
                updatedAt = java.time.Instant.now().toString(),
            ),
        )
        // facts 表里该事实已被用户编辑为措辞变体(去主语+全半角差异,归一化后等价)
        factStore.add(FactStore.Fact(fact = "喜欢喝美式咖啡", entityKey = "用户"))

        val changed = compiler.reconcileFactsSectionWithStore(factStore.getByScopeAndSpace("main", "default"))

        assertEquals("应有更新", 1, changed)
        val content = compiler.readSection(MemoryCompiler.Section.FACTS)
        assertTrue("产物应含表值新表述", content.contains("喜欢喝美式咖啡"))
        assertTrue("旧主语行应被投影替换", !content.contains("用户喜欢喝美式咖啡"))
        // D3-P2: 孤儿行("筹备搬家")先被吸收进表,再随投影保留(防丢失)
        assertTrue("孤儿行应被吸收保留", content.contains("用户最近在筹备搬家"))
    }

    @Test
    fun `full width variant replaced by normalized match`() = runTest {
        sectionDao.upsert(
            CompiledSectionEntity(
                sectionKey = MemoryCompiler.Section.FACTS.key,
                content = "用户喜欢ｚｈａｎｇｓａｎ",
                fingerprint = null,
                updatedAt = java.time.Instant.now().toString(),
            ),
        )
        // facts 表现值为全角变体
        factStore.add(FactStore.Fact(fact = "用户喜欢zhangsan"))

        val changed = compiler.reconcileFactsSectionWithStore(factStore.getByScopeAndSpace("main", "default"))
        assertEquals("全半角变体应触发投影更新", 1, changed)
        val content = compiler.readSection(MemoryCompiler.Section.FACTS)
        assertTrue("产物应为表值(半角)", content.contains("用户喜欢zhangsan"))
        assertTrue("全角变体应被替换", !content.contains("ｚｈａｎｇｓａｎ"))
    }

    @Test
    fun `unmatched orphan line is absorbed then projected with table facts`() = runTest {
        sectionDao.upsert(
            CompiledSectionEntity(
                sectionKey = MemoryCompiler.Section.FACTS.key,
                content = "用户喜欢摄影",
                fingerprint = null,
                updatedAt = java.time.Instant.now().toString(),
            ),
        )
        factStore.add(FactStore.Fact(fact = "用户喜欢喝茶"))

        val changed = compiler.reconcileFactsSectionWithStore(factStore.getByScopeAndSpace("main", "default"))

        assertEquals("吸收+投影应触发更新", 1, changed)
        val content = compiler.readSection(MemoryCompiler.Section.FACTS)
        assertTrue("表内事实应在", content.contains("用户喜欢喝茶"))
        assertTrue("孤儿行应被吸收保留(不再丢失)", content.contains("用户喜欢摄影"))
    }

    /**
     * v18: 投影就是注入内容 —— 已出场(分数低于阈值)与已被取代的旧记忆都不写进 FACTS 段,
     * 但行仍在库里(记忆页可见)。
     */
    @Test
    fun `projection skips facts that left recall`() = runTest {
        factStore.add(FactStore.Fact(fact = "用户喜欢美式咖啡", source = "user_explicit"))
        factDb.factDao().insert(
            io.zer0.memory.fact.FactEntity(
                fact = "用户以前喝摩卡",
                tags = "[]",
                createdAt = java.time.Instant.now().toString(),
                importance = 0,
                suppressedAt = java.time.Instant.now().toString(),
            ),
        )

        compiler.reconcileFactsSectionWithStore(factStore.getByScopeAndSpace("main", "default"))

        val content = compiler.readSection(MemoryCompiler.Section.FACTS)
        assertTrue("在场的记忆应投影", content.contains("美式咖啡"))
        assertTrue("已出场的记忆不该写进上下文", !content.contains("摩卡"))
        assertEquals("两条都还在库里", 2, factStore.getByScopeAndSpace("main", "default").size)
    }
}
