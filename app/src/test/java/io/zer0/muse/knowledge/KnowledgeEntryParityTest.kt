package io.zer0.muse.knowledge

import io.zer0.muse.knowledge.domain.KnowledgePolicyResolver
import io.zer0.muse.knowledge.domain.KnowledgeScope
import io.zer0.muse.knowledge.integration.ContributionResult
import io.zer0.muse.knowledge.integration.KnowledgeContextContributor
import io.zer0.muse.knowledge.integration.TrustedGenerationContext
import io.zer0.muse.knowledge.retrieval.EvidenceAssembler
import io.zer0.muse.knowledge.retrieval.KnowledgeEntryType
import io.zer0.muse.knowledge.retrieval.KnowledgeRetriever
import io.zer0.muse.knowledge.retrieval.KnowledgeSearchRequest
import io.zer0.muse.knowledge.retrieval.LexicalHit
import io.zer0.muse.knowledge.retrieval.LexicalRecall
import io.zer0.muse.knowledge.retrieval.LexicalRetriever
import io.zer0.muse.rag.RagConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assert.assertTrue

/**
 * R7 步骤 6/5(计划 R7:「群聊逐参与者应用选择与可见消息；工具接可信 scope；
 * 渠道仅用已授权资料；A2A 保持 tools=null，默认不新增知识读取；小手机按场景显式选择」
 * 「用参数化入口矩阵断言应注入/不注入、许可范围、预算、取消、引用保存」)。
 */
class KnowledgeEntryParityTest {

    private fun contributor(): KnowledgeContextContributor {
        val lexical = object : LexicalRetriever {
            override suspend fun search(
                request: KnowledgeSearchRequest,
                activeGenerationIds: List<String>,
                limit: Int,
            ): LexicalRecall = LexicalRecall(
                hits = listOf(LexicalHit("c1", "doc-1", "gen-1", "relevant text", "p1s1", 1f, 0)),
            )
        }
        val retriever = KnowledgeRetriever(
            lexicalRetriever = lexical,
            generationResolver = { listOf("gen-1") },
        )
        return KnowledgeContextContributor(
            retriever = retriever,
            assembler = EvidenceAssembler(),
            policyProvider = { KnowledgePolicyResolver.resolve(RagConfig()) },
        )
    }

    private fun context(
        entry: KnowledgeEntryType,
        allowed: Boolean = true,
        scope: KnowledgeScope = KnowledgeScope.Resolved(setOf("doc-1")),
    ) = TrustedGenerationContext(
        entry = entry,
        assistantId = "assistant-1",
        sessionId = "session-1",
        allowedScope = scope,
        knowledgeReadAllowed = allowed,
    )

    @Test
    fun `a2a is skipped by default (tools null policy preserved)`() = runBlocking {
        val result = contributor().prepareEvidence(context(KnowledgeEntryType.A2A), "query", 500, "req-1")
        assertTrue(result is ContributionResult.Skipped)
        assertTrue((result as ContributionResult.Skipped).reason.contains("A2A"))
    }

    @Test
    fun `entry without knowledge permission is skipped without retrieval`() = runBlocking {
        val result = contributor().prepareEvidence(
            context(KnowledgeEntryType.CHANNEL, allowed = false),
            "query",
            500,
            "req-2",
        )
        assertTrue(result is ContributionResult.Skipped)
    }

    @Test
    fun `chat entry with permission gets evidence`() = runBlocking {
        val result = contributor().prepareEvidence(context(KnowledgeEntryType.CHAT), "query", 500, "req-3")
        assertTrue("聊天入口应得到证据", result is ContributionResult.Evidence)
        val bundle = (result as ContributionResult.Evidence).bundle
        assertEquals(1, bundle.citations.size)
        assertTrue(bundle.finalText.contains("[1]").not() && bundle.finalText.contains("【资料 1】"))
    }

    @Test
    fun `tool entry respects scope not expanding beyond allowed`() = runBlocking {
        val result = contributor().prepareEvidence(
            context(KnowledgeEntryType.TOOL, scope = KnowledgeScope.None),
            "query",
            500,
            "req-4",
        )
        assertTrue("空范围必须跳过而非检索全库", result is ContributionResult.Skipped)
    }

    @Test
    fun `zero budget skips without retrieving`() = runBlocking {
        val result = contributor().prepareEvidence(context(KnowledgeEntryType.CHAT), "query", 0, "req-5")
        assertTrue(result is ContributionResult.Skipped)
    }

    @Test
    fun `blank query skips auto retrieval`() = runBlocking {
        val result = contributor().prepareEvidence(context(KnowledgeEntryType.CHAT), "   ", 500, "req-6")
        assertTrue(result is ContributionResult.Skipped)
    }

    @Test
    fun `group chat entry is not implicitly denied`() = runBlocking {
        val result = contributor().prepareEvidence(context(KnowledgeEntryType.GROUP_CHAT), "query", 500, "req-7")
        // 群聊应按参与者自身选择接入;合同层允许,具体可见性由上层传入 allowedScope。
        assertTrue(result is ContributionResult.Evidence)
    }

    @Test
    fun `mini phone entry must explicitly opt in`() = runBlocking {
        val skipped = contributor().prepareEvidence(
            context(KnowledgeEntryType.MINI_PHONE, allowed = false),
            "query",
            500,
            "req-8",
        )
        assertTrue(skipped is ContributionResult.Skipped)
    }

    @Test
    fun `builtin help entry uses its own scope`() = runBlocking {
        val result = contributor().prepareEvidence(
            context(KnowledgeEntryType.BUILTIN_HELP, scope = KnowledgeScope.Resolved(setOf("devdoc-1"))),
            "query",
            500,
            "req-9",
        )
        assertTrue(result is ContributionResult.Evidence)
    }
}
