package io.zer0.muse.ui.knowledge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 导入/删除的失败处理合同（源码级）。
 *
 * 这些是「静默失败」类缺陷，行为只能靠源码合同钉住：
 *  - 删除文档不等结果就弹「已删除」→ 用户以为清干净了，文档其实还在；
 *  - 导入失败只弹提示不清理 → 库里留一个 0 chunk 的空壳文档，永远搜不到。
 */
class KnowledgeImportFailureContractTest {

    private fun screen(): String = File("src/main/java/io/zer0/muse/ui/knowledge/KnowledgeScreen.kt").readText()

    @Test
    fun `document delete awaits result before showing success`() {
        val source = screen()
        // 旧写法：直接 scope.launch { deleteDocument(id) } 然后无条件 toast「已删除」
        val oldDelete = Regex(
            "scope\\.launch\\s*\\{\\s*ragService\\.deleteDocument\\(doc\\.id\\)\\s*\\}",
        )
        assertFalse(
            "删除文档不得先弹成功再异步删",
            oldDelete.containsMatchIn(source),
        )
        assertTrue(
            "删除必须包在 resultOf 里等结果",
            source.contains("resultOf { ragService.deleteDocument(doc.id) }"),
        )
        assertTrue(
            "删除失败必须显示原因",
            source.contains("R.string.knowledge_delete_failed_reason"),
        )
    }

    @Test
    fun `import failure cleans up created document and chunks`() {
        val source = screen()
        assertTrue(
            "导入失败必须有统一清理入口",
            source.contains("suspend fun cleanupFailedImport(docId: String)"),
        )
        assertTrue(
            "清理要删分块，不能只删文档行",
            source.contains("chunkDao.deleteByDoc(docId)"),
        )
        // 只钉住行为：IO catch 里必须出现清理调用。注释文案会随解释调整，不该让测试跟着改。
        // 文件里还有别的 IO catch，不能用第一次出现的位置；必须找紧跟清理调用的那一个。
        val ioCleanup = Regex(
            "catch \\(e: java\\.io\\.IOException\\)\\s*\\{[^}]*cleanupFailedImport\\(id\\)",
        )
        assertTrue(
            "文本流式路径的 IO 异常也要清理（此前会留 0 chunk 幽灵文档）",
            ioCleanup.containsMatchIn(source),
        )
        assertTrue(
            "通用异常同样清理",
            source.contains("createdDocId?.let { id -> cleanupFailedImport(id) }"),
        )
        val zeroChunkCleanup = Regex(
            "if \\(chunkCount <= 0\\)\\s*\\{[^}]*cleanupFailedImport\\(docId\\)",
        )
        assertTrue(
            "索引零块时也要清理而不是留空壳",
            zeroChunkCleanup.containsMatchIn(source),
        )
    }

    @Test
    fun `legacy doc is rejected before any read`() {
        val source = screen()
        val legacyGuard = source.indexOf("val isLegacyDoc = lowerName.endsWith(\".doc\")")
        val textBranch = source.indexOf("if (isTextCandidate && !unsupportedTextCandidate)")

        assertTrue("必须显式识别 .doc", legacyGuard >= 0)
        assertTrue("必须在文本分支之前拒收", textBranch > legacyGuard)
        assertTrue(
            "拒收要给出可执行提示（另存为 docx）",
            source.contains("R.string.knowledge_import_legacy_doc"),
        )
        val legacyInParserList = Regex(
            "lowerName\\.endsWith\\(\"\\.docx\"\\)\\s*\\|\\|\\s*lowerName\\.endsWith\\(\"\\.doc\"\\)",
        )
        assertTrue(
            "解析型清单不得再把 .doc 当作可解析格式",
            !legacyInParserList.containsMatchIn(source),
        )
    }

    @Test
    fun `parse path uses ParseOutcome instead of raw string`() {
        val source = screen()
        assertTrue(
            "解析型导入必须走 parseToOutcome",
            source.contains("documentParserAdapter.parseToOutcome("),
        )
        assertTrue("FAILED 不得落库", source.contains("ParseCoverage.FAILED -> {"))
        assertTrue("NO_TEXT 不得建 chunk", source.contains("ParseCoverage.NO_TEXT -> {"))
        assertTrue(
            "PARTIAL 要写入 metadata 覆盖度",
            source.contains("withParseCoverage("),
        )
        assertTrue(
            "PARTIAL 要在 UI 上可见",
            source.contains("R.string.knowledge_doc_partial_badge"),
        )
    }

    @Test
    fun `no cloud-default embedding fallback remains`() {
        val source = screen()
        assertFalse(
            "embedding_model 必须统一走 embeddingModelKey，不得用 cloud-default 兜底",
            source.contains("\"cloud-default\""),
        )
        assertTrue(
            "导入路径写 embeddingModelKey",
            source.contains("RagConfig.embeddingModelKey(ragConfig)"),
        )
    }

    @Test
    fun `kb and folder mutations go through shared cascade`() {
        val source = screen()
        assertTrue("删库走共享级联", source.contains("cascade.deleteKnowledgeBase(kb.id)"))
        assertTrue("目录新建走共享级联", source.contains("cascade.createFolder("))
        assertTrue("目录重命名走共享级联", source.contains("cascade.renameFolder("))
        assertTrue("目录删除走共享级联", source.contains("cascade.deleteFolder("))
    }
}
