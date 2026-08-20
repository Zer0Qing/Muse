package io.zer0.memory.compile

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.memory.fact.FactDb
import io.zer0.memory.fact.FactStore
import io.zer0.memory.llm.MemoryLlmClient
import io.zer0.memory.summary.CompiledSectionDao
import io.zer0.memory.summary.CompiledSectionEntity
import io.zer0.memory.summary.MemoryDb
import io.zer0.ai.core.Model
import io.zer0.memory.summary.SessionSummaryManager
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
            factStore = null,
        )
    }

    @After
    fun tearDown() {
        memoryDb.close()
        factDb.close()
    }

    @Test
    fun `edited fact replaces matching line in section`() = runTest {
        // 预置编译产物(模拟 LLM 编译结果)
        sectionDao.upsert(
            CompiledSectionEntity(
                sectionKey = MemoryCompiler.Section.FACTS.key,
                content = "用户喜欢喝美式咖啡\n用户最近在筹备搬家",
                fingerprint = null,
                updatedAt = java.time.Instant.now().toString(),
            )
        )
        // facts 表里该事实已被用户编辑为措辞变体(去主语+全半角差异,归一化后等价)
        factStore.add(FactStore.Fact(fact = "喜欢喝美式咖啡", entityKey = "用户"))

        val replaced = compiler.reconcileFactsSectionWithStore(factStore.getByScopeAndSpace("main", "default"))

        assertEquals("应替换 1 行", 1, replaced)
        val content = compiler.readSection(MemoryCompiler.Section.FACTS)
        assertTrue("产物应含新表述", content.contains("喜欢喝美式咖啡"))
        assertTrue("产物应不再含旧主语表述", !content.contains("用户喜欢喝美式咖啡"))
        assertTrue("其他行应保留", content.contains("用户最近在筹备搬家"))
    }

    @Test
    fun `full width variant replaced by normalized match`() = runTest {
        sectionDao.upsert(
            CompiledSectionEntity(
                sectionKey = MemoryCompiler.Section.FACTS.key,
                content = "用户喜欢ｚｈａｎｇｓａｎ",
                fingerprint = null,
                updatedAt = java.time.Instant.now().toString(),
            )
        )
        // facts 表现值为全角变体
        factStore.add(FactStore.Fact(fact = "用户喜欢zhangsan"))

        val replaced = compiler.reconcileFactsSectionWithStore(factStore.getByScopeAndSpace("main", "default"))
        assertEquals("全半角变体应匹配替换", 1, replaced)
    }

    @Test
    fun `no matching fact leaves section unchanged`() = runTest {
        sectionDao.upsert(
            CompiledSectionEntity(
                sectionKey = MemoryCompiler.Section.FACTS.key,
                content = "用户喜欢摄影",
                fingerprint = null,
                updatedAt = java.time.Instant.now().toString(),
            )
        )
        factStore.add(FactStore.Fact(fact = "用户喜欢喝茶"))

        val replaced = compiler.reconcileFactsSectionWithStore(factStore.getByScopeAndSpace("main", "default"))

        assertEquals("无匹配不替换", 0, replaced)
        assertEquals("内容不变", "用户喜欢摄影", compiler.readSection(MemoryCompiler.Section.FACTS))
    }
}
