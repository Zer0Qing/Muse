package io.zer0.memory.runtime

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.memory.budget.LlmBudget
import io.zer0.memory.compile.MemoryCompiler
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.fact.FactStore
import io.zer0.memory.llm.MemoryLlmClient
import io.zer0.memory.pin.PinnedMemoryStore
import io.zer0.memory.summary.MemoryDb
import io.zer0.memory.summary.ScopedCompiledSectionEntity
import io.zer0.memory.summary.SessionSummaryEntity
import io.zer0.memory.summary.SessionSummaryManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.Closeable
import java.nio.file.Files

/**
 * 记忆读取预算的行为回归。
 *
 * 四条契约（任一条失守都会让「预算」变成一句空话）：
 *  ① 计入的是真实 token（BPE），不是字符数/4 —— 否则中文记忆会被低估约三倍，
 *    预算看着够、实际把上下文撑爆；
 *  ② 分配顺序 pinned → facts → compiled → summary，超预算时后面的先让位；
 *  ③ 摘要与其它内容共用同一份预算，保留时溯源随之保留，裁掉时溯源一并消失；
 *  ④ tokenBudget <= 0 表示不限制。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MemoryRuntimeBudgetTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `正预算下中文记忆总量不超预算并标记截断`() = runTest {
        newHarness("zh_budget").use { harness ->
            harness.seed(
                pinned = "置顶：始终用中文回答，称呼用户为子奇。",
                facts = listOf(chineseBlock("用户负责的离线记忆项目"), chineseBlock("用户常用的技术栈")),
                sections = mapOf(
                    "today" to chineseBlock("今天讨论的内容"),
                    "week" to chineseBlock("本周的进展"),
                    "longterm" to chineseBlock("长期关注的方向"),
                ),
                summary = chineseBlock("上一段时间的会话摘要"),
            )

            val budget = 160
            val result = harness.runtime.read(harness.request(tokenBudget = budget))

            assertTrue("预算内应保留置顶记忆", result.pinned.isNotEmpty())
            assertTrue("usedTokens 必须落在预算内：${result.usedTokens}", result.usedTokens in 1..budget)
            assertTrue("有内容被裁掉时必须显式标记 truncated", result.truncated)
        }
    }

    @Test
    fun `中文长事实按 token 裁剪而不是按字符数除以四`() = runTest {
        val factText = chineseBlock("长事实", repeat = 20)
        newHarness("zh_fact").use { harness ->
            harness.seed(facts = listOf(factText))

            val budget = 400
            // 前提:字符数/4 会把这条例子判成预算以内(旧实现据此整条放行)
            assertTrue("字符数/4 必须低估到预算以内", factText.length / 4 < budget)
            assertTrue("真实 token 数必须超过预算", LlmBudget.countTokens(factText) > budget)

            val result = harness.runtime.read(harness.request(tokenBudget = budget))

            val kept = result.facts.singleOrNull()
                ?: error("该条应保留(裁到预算内而非整条丢弃)")
            assertTrue(
                "裁剪后仍超出预算：${LlmBudget.countTokens(kept.fact)}",
                LlmBudget.countTokens(kept.fact) <= budget,
            )
            assertTrue("裁剪后应带截断标记：${kept.fact}", kept.fact.contains("(memory truncated)"))
            assertTrue("usedTokens 不得超预算：${result.usedTokens}", result.usedTokens <= budget)
        }
    }

    @Test
    fun `预算紧张时先保置顶记忆再丢事实编译段与摘要`() = runTest {
        val pinnedText = "置顶：用户的猫叫团子。"
        newHarness("budget_pinned").use { harness ->
            harness.seed(
                pinned = pinnedText,
                facts = listOf(chineseBlock("用户的工作习惯")),
                sections = mapOf("today" to chineseBlock("今天的对话")),
                summary = chineseBlock("会话摘要"),
            )

            val budget = LlmBudget.countTokens(pinnedText) + 1
            val result = harness.runtime.read(harness.request(tokenBudget = budget))

            assertEquals("置顶记忆必须完整保留", listOf(pinnedText), result.pinned.map { it.content })
            assertTrue("facts 应被整体裁掉：${result.facts.map { it.fact }}", result.facts.isEmpty())
            assertTrue("compiled 段落应被整体裁掉", result.compiled?.sections.orEmpty().isEmpty())
            assertNull("摘要应被整体裁掉", result.sessionSummary)
            assertEquals(
                "只剩 pinned 一条溯源",
                setOf(MemorySourceKind.PINNED),
                result.sources.map { it.kind }.toSet(),
            )
        }
    }

    @Test
    fun `事实优先于编译段与摘要并被溯源覆盖`() = runTest {
        val pinnedText = "置顶：用户的猫叫团子。"
        newHarness("budget_facts").use { harness ->
            harness.seed(
                pinned = pinnedText,
                facts = listOf("用户用 Kotlin 写 Android 应用。", "用户的项目叫 Muse。"),
                sections = mapOf("today" to chineseBlock("今天的对话")),
                summary = chineseBlock("会话摘要"),
            )
            val storedFacts = harness.store.getByScopeAndSpace("main", "default").map { it.fact }
            assertTrue("前置:事实应已入库", storedFacts.isNotEmpty())

            val budget = LlmBudget.countTokens(pinnedText) + storedFacts.sumOf { LlmBudget.countTokens(it) } + 1
            val result = harness.runtime.read(harness.request(tokenBudget = budget))

            assertEquals("facts 应完整保留", storedFacts.toSet(), result.facts.map { it.fact }.toSet())
            assertTrue("compiled 段落应被裁掉", result.compiled?.sections.orEmpty().isEmpty())
            assertTrue(
                "被整段裁掉的 section 不得留下 sourceRevisions",
                result.compiled?.sourceRevisions.orEmpty().isEmpty(),
            )
            assertTrue(
                "provenance 仍保留全部行供 UI 诊断",
                result.compiled?.provenance.orEmpty().containsKey("today"),
            )
            assertNull("摘要应被裁掉", result.sessionSummary)
            assertTrue(
                "被裁掉的摘要不得留下溯源",
                result.sources.none { it.kind == MemorySourceKind.SESSION_SUMMARY },
            )
            assertEquals(
                "facts 溯源应逐条对应实际保留项",
                storedFacts.size,
                result.sources.count { it.kind == MemorySourceKind.FACT },
            )
        }
    }

    @Test
    fun `摘要与其它记忆共用预算且保留时带溯源`() = runTest {
        val summaryText = chineseBlock("滚动摘要")
        newHarness("budget_summary").use { harness ->
            harness.seed(
                pinned = "置顶：用户的猫叫团子。",
                facts = listOf("用户的项目叫 Muse。"),
                sections = mapOf("today" to "今天只有一句话。"),
                summary = summaryText,
            )

            val result = harness.runtime.read(harness.request(tokenBudget = 10_000))

            val summary = result.sessionSummary
            assertNotNull("预算充足时摘要必须保留", summary)
            assertEquals("预算充足时摘要不得被改写", summaryText, summary?.summary)
            assertEquals("摘要 revision 应随溯源保留", 3L, summary?.summaryRevision ?: -1L)
            val summarySource = result.sources.singleOrNull { it.kind == MemorySourceKind.SESSION_SUMMARY }
            assertEquals("摘要溯源应指向当前会话", Harness.SUMMARY_SESSION_ID, summarySource?.key)
            assertEquals("摘要来源指纹应随溯源保留", "src-fp", summarySource?.fingerprint)
            assertFalse("预算充足不应报截断", result.truncated)
            assertTrue(
                "usedTokens 应把摘要算进去：${result.usedTokens}",
                result.usedTokens >= LlmBudget.countTokens(summaryText),
            )
        }
    }

    @Test
    fun `预算为零时全部记忆原样返回`() = runTest {
        val summaryText = chineseBlock("零预算摘要")
        newHarness("budget_zero").use { harness ->
            harness.seed(
                pinned = "置顶：用户的猫叫团子。",
                facts = listOf(chineseBlock("零预算事实", repeat = 8)),
                sections = mapOf("today" to chineseBlock("零预算的今天")),
                summary = summaryText,
            )
            val storedFact = harness.store.getByScopeAndSpace("main", "default").single().fact

            val result = harness.runtime.read(harness.request(tokenBudget = 0))

            assertEquals("零预算不得改写事实", storedFact, result.facts.single().fact)
            assertEquals("零预算不得改写摘要", summaryText, result.sessionSummary?.summary)
            assertEquals("零预算应保留全部编译段落", 1, result.compiled?.sections.orEmpty().size)
            assertFalse("零预算不算截断", result.truncated)
            assertTrue(
                "零预算仍需报告实际占用",
                result.usedTokens > LlmBudget.countTokens(summaryText),
            )
            assertTrue(
                "零预算时摘要溯源仍在",
                result.sources.any { it.kind == MemorySourceKind.SESSION_SUMMARY },
            )
        }
    }

    @Test
    fun `fact pin appears exactly once and cannot leak through a legacy copy to another space`() = runTest {
        newHarness("scoped_pins").use { harness ->
            val text = "工作记忆"
            val id = harness.store.add(FactStore.Fact(fact = text), scope = "main", spaceId = "work")
            assertTrue(harness.store.setPinned(id, true, "main", "work"))
            harness.seed(pinned = text)

            val request = harness.request(tokenBudget = 0).copy(includeFacts = false, includeCompiled = false)
            val work = harness.runtime.read(request.copy(scope = request.scope.copy(spaceId = "work")))
            assertEquals(listOf(text), work.pinned.map { it.content })
            assertTrue(harness.runtime.read(request).pinned.isEmpty())
        }
    }

    // ── 夹具 ──────────────────────────────────────────────────────────────

    private fun newHarness(tag: String): Harness {
        val assistantId = "budget_$tag"
        val memoryDb = Room.inMemoryDatabaseBuilder(context, MemoryDb::class.java)
            .allowMainThreadQueries()
            .build()
        val noopLlm = object : MemoryLlmClient {
            override suspend fun callText(
                systemPrompt: String,
                userContent: String,
                model: io.zer0.ai.core.Model?,
                temperature: Float,
                maxTokens: Int,
                timeoutMs: Long,
            ): String = ""
        }
        val factProvider = FactDbProvider(context)
        val compiler = MemoryCompiler(
            memoryDb.compiledSectionDao(),
            noopLlm,
            scopedSectionDao = memoryDb.scopedCompiledSectionDao(),
        )
        val pinnedStore = PinnedMemoryStore(Files.createTempDirectory("pinned-$tag").toFile())
        val runtime = DefaultMemoryRuntime(
            factDbProvider = factProvider,
            compiler = compiler,
            summaryManager = SessionSummaryManager(memoryDb.sessionSummaryDao(), noopLlm),
            pinnedMemoryStore = pinnedStore,
        )
        return Harness(runtime, memoryDb, factProvider, pinnedStore, MemoryScopeKey(assistantId, "main", "default"))
    }

    /** 一段成规模的中文记忆:用来把预算撑到必须裁剪的量级。 */
    private fun chineseBlock(label: String, repeat: Int = 12): String = (1..repeat).joinToString("\n") { index ->
        "$label 第 $index 条：用户希望记忆系统在离线状态下保持稳定，并在预算不足时优先保留中文内容。"
    }

    private class Harness(
        val runtime: DefaultMemoryRuntime,
        private val memoryDb: MemoryDb,
        private val factProvider: FactDbProvider,
        private val pinnedStore: PinnedMemoryStore,
        val scope: MemoryScopeKey,
    ) : Closeable {

        val store: FactStore get() = factProvider.getFactStore(scope.assistantId)

        suspend fun seed(
            pinned: String? = null,
            facts: List<String> = emptyList(),
            sections: Map<String, String> = emptyMap(),
            summary: String? = null,
        ) {
            pinned?.let { pinnedStore.add(it, assistantId = scope.assistantId) }
            facts.forEach { store.add(FactStore.Fact(fact = it), scope = scope.scope, spaceId = scope.spaceId) }
            sections.forEach { (key, content) ->
                memoryDb.scopedCompiledSectionDao().upsert(
                    ScopedCompiledSectionEntity(
                        sectionKey = key,
                        scope = scope.scope,
                        spaceId = scope.spaceId,
                        content = content,
                        fingerprint = "content-fp-$key",
                        updatedAt = "2026-11-25T00:00:00Z",
                        sourceFingerprint = "source-fp-$key",
                        status = MemoryProjectionStatus.READY.name,
                    ),
                )
            }
            summary?.let {
                memoryDb.sessionSummaryDao().upsert(
                    SessionSummaryEntity(
                        sessionId = SUMMARY_SESSION_ID,
                        createdAt = "2026-11-25T00:00:00Z",
                        updatedAt = "2026-11-25T00:00:00Z",
                        summary = it,
                        messageCount = 12,
                        assistantId = scope.assistantId,
                        spaceId = scope.spaceId,
                        summaryRevision = 3,
                        sourceFingerprint = "src-fp",
                    ),
                )
            }
        }

        fun request(tokenBudget: Int) = MemoryReadRequest(
            scope = scope,
            tokenBudget = tokenBudget,
            includeSessionSummary = true,
            sessionId = SUMMARY_SESSION_ID,
        )

        override fun close() {
            factProvider.releaseAll()
            memoryDb.close()
        }

        companion object {
            const val SUMMARY_SESSION_ID = "session-budget"
        }
    }
}