package io.zer0.muse.knowledge

import io.zer0.muse.rag.VectorSearchService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R0 现状基线回归 —— 把「检索范围」两条已知缺陷钉成可复现的失败断言。
 *
 * 这些是 characterization 测试:它们断言的是**目标正确行为**,在 R1 修复前**预期为红**。
 * 红只允许在独立基线运行清单中管理,不用 @Ignore/Suppress 藏红;R1 落地范围合同后转绿。
 *
 * 每个测试都点名「哪一处生产改动会让它由红转绿」:
 *  - A: VectorSearchService 把空 scopeDocIds 当全库(getOrLoadCache / search 的 isNotEmpty 兜底)。
 *       R1 消除散布的 `takeIf { it.isNotEmpty() }`,空范围必须解析为 Empty(零结果),而非全库。
 *  - B: metadata provider 路径(scanPagedProvider)根本不接收 scopeDocIds,只按 metadata 过滤。
 *       R1 使 dense/词法/fallback 共用同一有效范围,metadata 路径必须与 scope 求交。
 *
 * 夹具全部为手工合成内存数据(见 [KnowledgeRegressionFixtures]),不含用户私有正文或秘密;
 * 不触达 SQLite/FTS,故与 MetadataFilterSearchTest 一样在纯 JVM 下运行,无需 Robolectric。
 */
class KnowledgeBaselineTest {

    /**
     * A — 空绑定不得扩大成全库。
     *
     * 现状缺陷:scopeDocIds=emptyList() 时,search 跳过 docIds provider 路径,
     * getOrLoadCache 的 `scopeDocIds.isNotEmpty()` 判否 → 载入全部块 → 返回另一库文档。
     * 目标:显式空范围 = 未绑定任何库 = 零结果(与 null「无范围约束/全库」区分)。
     * 转绿条件:R1 把空范围解析为 Empty,VectorSearchService 不再把 emptyList() 当全库。
     */
    @Test
    fun `bound empty scope must not return documents from another library`() = runBlocking {
        val service = KnowledgeRegressionFixtures.vectorServiceLikeProduction()

        val hits = service.search(
            queryVector = KnowledgeRegressionFixtures.sharedVector,
            topK = 10,
            threshold = 0f,
            scopeDocIds = emptyList(),
        )

        assertTrue(
            "空绑定(显式空范围)不得返回任何库的文档,实际命中=${hits.map { it.docId }}",
            hits.isEmpty(),
        )
    }

    /**
     * B — metadata 过滤必须与 scope 求交,不得绕过范围。
     *
     * 现状缺陷:metadataFilter 非空时走 chunkPageByMetadataProvider,该 provider 签名只有
     * (filter, limit, offset),scanPagedProvider 也只做 metadata 内存判定,全程不感知 scopeDocIds。
     * 两个库都带 legal tag → beta 库越界命中。
     * 目标:结果 = scope ∩ metadata,只允许 alpha 库。
     * 转绿条件:R1 让 metadata provider 路径同样受有效范围约束(求交),而非仅按 metadata 过滤。
     */
    @Test
    fun `metadata filter must intersect scope and never bypass it`() = runBlocking {
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
}
