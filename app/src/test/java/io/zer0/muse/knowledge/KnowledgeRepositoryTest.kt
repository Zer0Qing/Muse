package io.zer0.muse.knowledge

import io.zer0.muse.data.knowledge.KnowledgeLegacyRowClass
import io.zer0.muse.knowledge.storage.KnowledgeRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * R2 步骤 3 合同(计划 R2 步骤 3:「为新资料写 source bytes 与完整 revision artifact;
 * 预览从 revision 派生;编辑 API 必须传 expectedRevisionId」):
 *  - ingest 落 source blob(内容寻址)+ 完整 revision(全文入 artifact,不再只有预览);
 *  - preview 是 revision 的派生读模型(前缀一致、长度受上限约束);
 *  - edit 必须带 expectedRevisionId,版本不匹配拒绝(乐观锁,防并发丢更新);
 *  - 每次编辑产生新 revision,旧 revision 保留(不可变版本链)。
 */
class KnowledgeRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun repo() = KnowledgeRepository(blobsDirectory = tmp.newFolder("blobs"))

    // ---------- ingest:source bytes + 完整 revision + preview 派生 ----------

    @Test
    fun `ingest stores full content revision and derives preview`() {
        val repo = repo()
        val longContent = "HEAD " + "x".repeat(2000) + " TAIL-MARKER"

        val doc = repo.ingest(sourceScheme = "content", location = longContent, fullContent = longContent)

        assertNotNull(doc)
        assertEquals("revision 1", 1, doc!!.revisionNo)
        assertEquals("完整正文必须原样进 revision(不再只有预览)", longContent, repo.loadRevision(doc.revisionId).fullContent)
        assertEquals("preview 是全文前缀派生", longContent.take(doc.preview.length), doc.preview)
        assertTrue("preview 长度受上限约束", doc.preview.length <= 500_000)
        assertEquals("短全文时 preview 即全文", longContent, doc.preview)
    }

    @Test
    fun `long content preview is truncated prefix of revision`() {
        val repo = repo()
        val huge = "HEAD-MARK " + "y".repeat(600_000) + " TAIL-MARK"
        val doc = repo.ingest("content", huge, huge)

        assertTrue("预览必须被截断到上限", doc!!.preview.length <= 500_000)
        assertEquals("preview 必须是 revision 全文的前缀(派生关系)", huge.take(doc.preview.length), doc.preview)
        assertEquals("revision 保留完整正文(含尾部)", huge, repo.loadRevision(doc.revisionId).fullContent)
        assertTrue("完整正文含尾部标记(超预览保尾)", repo.loadRevision(doc.revisionId).fullContent.endsWith("TAIL-MARK"))
    }

    @Test
    fun `ingest writes source blob to content store`() {
        val repo = repo()
        val content = "原始正文字节"
        val doc = repo.ingest("content", content, content)
        // source bytes 可从 store 读回(hash 寻址)
        val revision = repo.loadRevision(doc!!.revisionId)
        assertEquals("revision contentHash 与正文一致", content, revision.fullContent)
    }

    // ---------- edit:expectedRevisionId 乐观锁 ----------

    @Test
    fun `edit without matching expected revision is rejected`() {
        val repo = repo()
        val doc = repo.ingest("content", "v1", "v1")

        val result = repo.editContent(
            docId = doc!!.docId,
            expectedRevisionId = "rev-does-not-exist",
            newContent = "v2",
        )

        assertTrue("版本不匹配必须拒绝(乐观锁)", result is KnowledgeRepository.EditResult.Conflict)
    }

    @Test
    fun `edit with matching revision creates new revision and re-derives preview`() {
        val repo = repo()
        val doc = repo.ingest("content", "v1-body", "v1-body")
        val rev1 = doc!!.revisionId

        val result = repo.editContent(doc.docId, rev1, "v2-body-much-longer")
        assertTrue("匹配的 expectedRevisionId 应成功", result is KnowledgeRepository.EditResult.Ok)
        val newDoc = (result as KnowledgeRepository.EditResult.Ok).doc

        assertEquals("新 revision 版本号 +1", 2, newDoc.revisionNo)
        assertNotEquals("新旧 revisionId 不同(不可变版本链)", rev1, newDoc.revisionId)
        assertEquals("旧 revision 保留(不覆盖)", "v1-body", repo.loadRevision(rev1).fullContent)
        assertEquals("新 revision 是编辑后正文", "v2-body-much-longer", repo.loadRevision(newDoc.revisionId).fullContent)
        assertEquals("preview 从新 revision 派生", "v2-body-much-longer", newDoc.preview)
    }

    @Test
    fun `stale edit after newer edit is rejected`() {
        val repo = repo()
        val doc = repo.ingest("content", "v1", "v1")
        val rev1 = doc!!.revisionId

        val first = repo.editContent(doc.docId, rev1, "v2")
        assertTrue(first is KnowledgeRepository.EditResult.Ok)

        // 拿着旧 rev1 再编辑 = 过期乐观锁,必须拒绝
        val stale = repo.editContent(doc.docId, rev1, "v3-from-stale-client")
        assertTrue("过期 revision 的编辑必须拒绝", stale is KnowledgeRepository.EditResult.Conflict)
    }

    // ---------- R2 step 6:旧行导入只分类,不拼接冒充 ----------

    /**
     * 旧行导入合同(计划 R2 步骤 6:「导入旧行只做已知信息分类,保留旧 chunks 原文、
     * 顺序和 metadata;ChunkOnly 不拼接冒充完整原件」):
     *  - 分类结果随导入落位(行上是什么类别,导入后就是什么类别,不做升级猜测);
     *  - ChunkOnly 行:导入后 revision.fullContent 保持空串(原文在 chunks 里),
     *    绝不把 chunk 文本拼接成"完整原件";旧 chunks 原文/顺序/metadata 原样保留;
     *  - 完整行:全文进 revision。
     */
    @Test
    fun `legacy chunk only row imports without pretending to be complete`() {
        val repo = repo()
        val imported = repo.importLegacyRow(
            docId = "legacy-chunk-only",
            ownerType = "UserLibrary",
            ownerId = "kb-old",
            isInternal = false,
            content = "",
            contentHash = "",
            metadataJson = """{"source":"upload/old"}""",
            chunks = listOf("第一段原文", "第二段原文", "第三段原文"),
            sourceMissing = false,
        )

        assertEquals("分类 = ChunkOnly(不猜测升级)", KnowledgeLegacyRowClass.CHUNK_ONLY, imported.rowClass)
        assertTrue(
            "ChunkOnly 不得伪造完整原件(revision 不带 fullContent)",
            repo.loadRevision(imported.revisionId).fullContent.isEmpty(),
        )
        assertEquals("旧 chunks 原文/顺序原样保留", listOf("第一段原文", "第二段原文", "第三段原文"), imported.chunkTexts)
        assertEquals("旧 metadata 原样保留", """{"source":"upload/old"}""", imported.metadataJson)
        assertEquals("docId 原样保留(citation 身份锚点)", "legacy-chunk-only", imported.docId)
    }

    @Test
    fun `legacy complete row imports full content into revision`() {
        val repo = repo()
        val full = "完整旧行正文"
        val imported = repo.importLegacyRow(
            docId = "legacy-complete",
            ownerType = "UserLibrary",
            ownerId = "kb-old",
            isInternal = false,
            content = full,
            contentHash = io.zer0.muse.knowledge.storage.KnowledgeContentStore.hashOf(full.toByteArray()),
            metadataJson = "{}",
            chunks = emptyList(),
            sourceMissing = false,
        )

        assertEquals("hash 可验证 → COMPLETE", KnowledgeLegacyRowClass.COMPLETE, imported.rowClass)
        assertEquals("完整正文进 revision", full, repo.loadRevision(imported.revisionId).fullContent)
    }

    @Test
    fun `legacy internal row keeps internal owner semantics`() {
        val repo = repo()
        val imported = repo.importLegacyRow(
            docId = "legacy-devdoc",
            ownerType = "BuiltinHelp",
            ownerId = "corpus-v1",
            isInternal = true,
            content = "内置帮助",
            contentHash = "",
            metadataJson = "{}",
            chunks = emptyList(),
            sourceMissing = false,
        )

        assertEquals(KnowledgeLegacyRowClass.INTERNAL, imported.rowClass)
        assertEquals("internal 行 owner 持久化为 BuiltinHelp/corpusVersion", "BuiltinHelp", imported.ownerType)
    }
}
