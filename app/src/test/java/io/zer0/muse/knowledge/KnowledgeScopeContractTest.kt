package io.zer0.muse.knowledge

import io.mockk.coEvery
import io.mockk.mockk
import io.zer0.muse.data.knowledge.KnowledgeChunkFtsDao
import io.zer0.muse.data.knowledge.KnowledgeChunkFtsHit
import io.zer0.muse.knowledge.domain.KnowledgeScope
import io.zer0.muse.knowledge.domain.ScopeDoc
import io.zer0.muse.knowledge.retrieval.DefaultKnowledgeScopeResolver
import io.zer0.muse.rag.HybridSearchService
import io.zer0.muse.rag.VectorSearchService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R1 检索范围合同测试 — 把计划 R1 步骤 1 的五个判据钉成可执行断言。
 *
 * TDD 红测(预期红直到本批修复):
 *  - `bound_empty_is_not_all` / `scope_and_metadata_intersect`:钉 [VectorSearchService.search]
 *    的 scope 语义(与 R0 基线 A/B 同缺陷、不同角色——此处是合同驱动,转绿后永久护栏);
 *  - `hybrid_lexical_only_hit_stays_in_scope`:BM25 词法路当前完全收不到 scopeDocIds,
 *    scope 外的 BM25-only 命中会混进结果;
 *  - `invalid_binding_is_not_all`:引用不存在的库当前被解析成 None(旧语义空=未绑定),
 *    必须升级为 Invalid,禁止调用方再扩大为全库。
 * 护栏(现状正确,防回归):
 *  - `same_folder_name_in_two_kbs_does_not_merge`:同名文件夹跨库必须被 kbId 隔离。
 *
 * 夹具全部来自 [KnowledgeRegressionFixtures](合成内存数据,无 SQLite/FTS 真表,
 * 与 MetadataFilterSearchTest 同手法在纯 JVM 下运行)。
 */
class KnowledgeScopeContractTest {

    // ---------- 1. bound_empty_is_not_all ----------

    /** 显式空绑定 = 零结果。现状缺陷:emptyList() 被 isNotEmpty() 判否 → 载入全库。 */
    @Test
    fun `bound empty is not all`() = runBlocking {
        val service = KnowledgeRegressionFixtures.vectorServiceLikeProduction()

        val hits = service.search(
            queryVector = KnowledgeRegressionFixtures.sharedVector,
            topK = 10,
            threshold = 0f,
            scopeDocIds = emptyList(),
        )

        assertTrue(
            "空绑定不得返回任何文档,实际命中=${hits.map { it.docId }}",
            hits.isEmpty(),
        )
    }

    // ---------- 2. scope_and_metadata_intersect ----------

    /** metadata 过滤必须与 scope 求交。现状缺陷:metadata provider 路径不感知 scopeDocIds。 */
    @Test
    fun `scope and metadata intersect`() = runBlocking {
        val service = KnowledgeRegressionFixtures.vectorServiceLikeProduction()

        val hits = service.search(
            queryVector = KnowledgeRegressionFixtures.sharedVector,
            topK = 10,
            threshold = 0f,
            scopeDocIds = listOf(KnowledgeRegressionFixtures.ALPHA_DOC),
            metadataFilter = VectorSearchService.MetadataFilter(tag = KnowledgeRegressionFixtures.LEGAL_TAG),
        )

        assertTrue("scope∩metadata 后不得为空(alpha 库带 legal tag)", hits.isNotEmpty())
        assertTrue(
            "结果必须全部落在 scope(alpha)内,越界命中=${hits.map { it.docId }}",
            hits.all { it.docId == KnowledgeRegressionFixtures.ALPHA_DOC },
        )
    }

    // ---------- 3. hybrid_lexical_only_hit_stays_in_scope ----------

    /**
     * BM25-only 命中必须尊重 scope:向量侧已按 scope 过滤,FTS 词法路命中 scope 外文档时,
     * 即便它是词法唯一命中也不得进入结果。现状缺陷:HybridSearchService 只把 scopeDocIds
     * 传给向量侧,BM25 侧无 scope 概念,scope 外命中经 RRF 融合混入。
     */
    @Test
    fun `hybrid lexical only hit stays in scope`() = runBlocking {
        val fixtures = KnowledgeRegressionFixtures
        val vector = fixtures.vectorServiceLikeProduction()

        val fts = mockk<KnowledgeChunkFtsDao>(relaxed = true)
        coEvery { fts.searchBm25(any(), any()) } returns listOf(
            KnowledgeChunkFtsHit(chunkId = "alpha-1", score = -10.0),
            KnowledgeChunkFtsHit(chunkId = "beta-1", score = -11.0), // BM25-only 且 scope 外
        )
        val hybrid = HybridSearchService(
            ftsDao = fts,
            vectorSearch = vector,
            bm25MetaResolver = { ids ->
                ids.associateWith { id ->
                    HybridSearchService.ChunkMeta(
                        docId = if (id == "beta-1") fixtures.BETA_DOC else fixtures.ALPHA_DOC,
                        docTitle = if (id == "beta-1") "Beta 库文档" else "Alpha 库文档",
                        content = "content-$id",
                        chunkIndex = 0,
                        metadataJson = """{"tag":"legal"}""",
                    )
                }
            },
        )

        val hits = hybrid.hybridSearch(
            query = "legal",
            queryVector = fixtures.sharedVector,
            topK = 10,
            threshold = 0f,
            mmrLambda = 1f,
            scopeDocIds = listOf(fixtures.ALPHA_DOC),
        )

        assertTrue("混合检索在 scope 内不得为空(alpha-1 词法命中)", hits.isNotEmpty())
        assertTrue(
            "BM25 词法命中也必须落在 scope 内,越界命中=${hits.map { it.docId }}",
            hits.all { it.docId == fixtures.ALPHA_DOC },
        )
    }

    // ---------- 4. invalid_binding_is_not_all ----------

    /**
     * 绑定引用的目标全部不存在 → Invalid。现状(过渡骨架):展开为空一律返回 None
     * (旧语义「空=未绑定」),调用方再把 None 当全库处理 → 失效绑定静默扩成全库检索。
     */
    @Test
    fun `invalid binding is not all`() = runBlocking {
        val resolver = DefaultKnowledgeScopeResolver(docsByKbIds = { emptyList() })

        val scope = resolver.resolveBinding(knowledgeBaseIds = listOf("kb-ghost"), folderScopes = emptyList())

        assertTrue(
            "引用不存在的库必须解析为 Invalid,实际=$scope",
            scope is KnowledgeScope.Invalid,
        )
    }

    // ---------- 5. same_folder_name_in_two_kbs_does_not_merge(护栏) ----------

    /** 同名文件夹在两个库各存在一份:绑定 kb-a 的文件夹不得合并 kb-b 的同名文件夹文档。 */
    @Test
    fun `same folder name in two kbs does not merge`() = runBlocking {
        val docs = listOf(
            ScopeDoc(docId = "doc-a-share", kbId = "kb-a", folderPath = "共享目录"),
            ScopeDoc(docId = "doc-b-share", kbId = "kb-b", folderPath = "共享目录"),
            ScopeDoc(docId = "doc-a-root", kbId = "kb-a", folderPath = ""),
        )
        val resolver = DefaultKnowledgeScopeResolver(docsByKbIds = { ids ->
            // 生产接线等价:按绑定 kb 集合取文档(两库同名文件夹都在结果里)。
            docs.filter { it.kbId in ids }
        })

        val scope = resolver.resolveBinding(
            knowledgeBaseIds = emptyList(),
            folderScopes = listOf("kb-a" to "共享目录"),
        )

        assertTrue("命中文件夹应解析为 Resolved,实际=$scope", scope is KnowledgeScope.Resolved)
        val resolved = scope as KnowledgeScope.Resolved
        assertEquals(
            "同名文件夹跨库不得合并:只能命中 kb-a 的文档",
            setOf("doc-a-share"),
            resolved.docIds,
        )
    }

    // ---------- 6. empty permit never re-expands to all ----------

    /**
     * 空许可(失效/空绑定、解析失败)在 mention 合并层不得重新扩成全库。
     * 这是 Critical 修复的合同钉:许可=空列表时,无论 mention 是否存在、是否越界,
     * 结果都必须是显式空列表(零结果)——null 只允许来自「未绑定(许可=null)」分支。
     * R0 基线 A/B 钉的是检索层,本测试钉 controller 侧合并层(最后一公里)。
     */
    @Test
    fun `empty permit is never re-expanded to full library`() {
        // 失效绑定:许可=空列表。无 mention → 零结果,不得变 null(全库)。
        val noMention = io.zer0.muse.knowledge.retrieval.mergeMentionWithPermit(
            mentionDocIds = null,
            permittedDocIds = emptyList(),
        )
        assertTrue(
            "空许可+无 mention 必须是空列表(零结果),实际=$noMention",
            noMention != null && noMention.isEmpty(),
        )

        // 空许可 + mention(全部指向许可外)→ 同样零结果,mention 不得救回全库。
        val withOutOfScopeMention = io.zer0.muse.knowledge.retrieval.mergeMentionWithPermit(
            mentionDocIds = listOf("doc-ghost"),
            permittedDocIds = emptyList(),
        )
        assertTrue(
            "空许可+越界 mention 必须是空列表(零结果),实际=$withOutOfScopeMention",
            withOutOfScopeMention != null && withOutOfScopeMention.isEmpty(),
        )

        // 未绑定(许可=null)+ 无 mention → null(全库,既有行为保持)。
        val unbound = io.zer0.muse.knowledge.retrieval.mergeMentionWithPermit(
            mentionDocIds = null,
            permittedDocIds = null,
        )
        assertTrue(
            "未绑定+无 mention 保持 null(AllUserLibraries),实际=$unbound",
            unbound == null,
        )

        // 未绑定 + mention → mention 定向(既有行为)。
        val unboundWithMention = io.zer0.muse.knowledge.retrieval.mergeMentionWithPermit(
            mentionDocIds = listOf("doc-1"),
            permittedDocIds = null,
        )
        assertEquals("未绑定+mention 定向到 mention 文档", listOf("doc-1"), unboundWithMention)

        // 有许可 + mention 部分越界 → 只留许可内部分。
        val partialMention = io.zer0.muse.knowledge.retrieval.mergeMentionWithPermit(
            mentionDocIds = listOf("doc-in", "doc-out"),
            permittedDocIds = listOf("doc-in"),
        )
        assertEquals("mention 只能在许可内定向", listOf("doc-in"), partialMention)

        // 有许可 + mention 全部越界 → 回退许可(mention 不越权也不清空检索)。
        val allOutMention = io.zer0.muse.knowledge.retrieval.mergeMentionWithPermit(
            mentionDocIds = listOf("doc-out"),
            permittedDocIds = listOf("doc-in"),
        )
        assertEquals("全部越界 mention 回退许可", listOf("doc-in"), allOutMention)
    }
}
