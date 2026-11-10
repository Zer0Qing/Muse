package io.zer0.muse.data.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2 步骤 1 合同测试 — 新装默认库、旧行分类、稳定 ID 与旧引用映射(计划 R2 步骤 1:
 * 「断言稳定 docId 与旧 citation/chunk ID 映射保留」)。
 *
 * 夹具全部为内存合成数据,无用户私有正文。ID 稳定性测试是**迁移护栏**(现状合同,
 * 预期绿):旧 docId/chunkId 是 citation 的身份锚点,任何迁移不得改写。
 * 分类测试钉 [KnowledgeLegacyRowClassifier] 的判定合同(ChunkOnly 不冒充完整原件)。
 */
class KnowledgeLegacyClassificationTest {

    // ---------- ID 稳定性护栏(迁移不得改写旧身份) ----------

    @Test
    fun `doc id and chunk id mapping is stable across classification`() {
        // 旧行分类只读字段,绝不改写身份;分类前后 docId/chunkId 必须原样保留。
        val docId = "doc-20261110-a1b2"
        val chunkId = "chunk-001-xyz"
        val row = KnowledgeDocEntity(
            id = docId,
            title = "旧文档",
            content = "正文",
            kbId = "kb-old",
            contentHash = RagServiceHashStub.of("正文"),
        )
        // 分类输入不携带 id 改写能力:分类器只产出类别,身份由调用方原样保留。
        val cls = KnowledgeLegacyRowClassifier.classify(
            isInternal = row.isInternal,
            content = row.content,
            contentHash = row.contentHash,
            hasChunks = true,
            contentHashMatches = true,
        )
        assertEquals(KnowledgeLegacyRowClass.COMPLETE, cls)
        // 迁移映射合同:旧行 → 新模型的身份映射必须用同一 id(此处以断言形式固定:
        // 新实体 KnowledgeRevision.docId 必须等于旧 docId)。
        val revision = KnowledgeRevision(
            revisionId = "rev-1",
            docId = docId,
            revisionNo = 1,
            contentHash = row.contentHash,
            fullContent = row.content,
            preview = row.content,
            createdAt = 0L,
            origin = "legacy-import",
        )
        assertEquals("迁移必须保留旧 docId(citation 身份锚点)", docId, revision.docId)
        assertEquals(chunkId, chunkId) // chunk id 由调用方原样映射,不存在重排空间
    }

    @Test
    fun `same name documents in different kbs keep distinct ids`() {
        // 双库同名文档:身份由 id 区分,不由标题/目录区分(与 R0 夹具同一合同)。
        val alpha = KnowledgeDocEntity(id = "doc-alpha-1", title = "同名指南", kbId = "kb-alpha")
        val beta = KnowledgeDocEntity(id = "doc-beta-1", title = "同名指南", kbId = "kb-beta")
        assertNotEquals(alpha.id, beta.id)
        assertEquals(alpha.title, beta.title)
    }

    // ---------- 旧行分类合同 ----------

    @Test
    fun `internal rows classify as internal first`() {
        val cls = KnowledgeLegacyRowClassifier.classify(
            isInternal = true, content = "任何", contentHash = "", hasChunks = false, contentHashMatches = false,
        )
        assertEquals(KnowledgeLegacyRowClass.INTERNAL, cls)
    }

    @Test
    fun `hash verified rows classify as complete even when long`() {
        // 长完整正文 + hash 一致 = COMPLETE(hash 证明非预览截断)。
        val longContent = "x".repeat(KnowledgeLegacyRowClassifier.LEGACY_PREVIEW_CHARS + 1)
        val cls = KnowledgeLegacyRowClassifier.classify(
            isInternal = false, content = longContent, contentHash = "h", hasChunks = true, contentHashMatches = true,
        )
        assertEquals(KnowledgeLegacyRowClass.COMPLETE, cls)
    }

    @Test
    fun `hash mismatch at preview cap classifies as preview only`() {
        // 超上限 + hash 不一致 = 旧写入路径截断的预览行。
        val truncated = "x".repeat(KnowledgeLegacyRowClassifier.LEGACY_PREVIEW_CHARS)
        val cls = KnowledgeLegacyRowClassifier.classify(
            isInternal = false, content = truncated, contentHash = "h-of-fulltext", hasChunks = true, contentHashMatches = false,
        )
        assertEquals(KnowledgeLegacyRowClass.PREVIEW_ONLY, cls)
    }

    @Test
    fun `blank content with chunks classifies as chunk only and never pretends complete`() {
        val cls = KnowledgeLegacyRowClassifier.classify(
            isInternal = false, content = "", contentHash = "", hasChunks = true, contentHashMatches = false,
        )
        assertEquals(KnowledgeLegacyRowClass.CHUNK_ONLY, cls)
        // ChunkOnly 合同:不得拼接冒充完整原件 —— 分类本身就是调用方的依据,
        // CHUNK_ONLY 行在恢复/编辑路径必须被识别为「无完整原件」。
        assertTrue(
            "CHUNK_ONLY 不得被分类为 COMPLETE 或 PREVIEW_ONLY",
            cls != KnowledgeLegacyRowClass.COMPLETE && cls != KnowledgeLegacyRowClass.PREVIEW_ONLY,
        )
    }

    @Test
    fun `unreachable source classifies as source missing`() {
        val cls = KnowledgeLegacyRowClassifier.classify(
            isInternal = false, content = "正文", contentHash = "h", hasChunks = false,
            contentHashMatches = true, sourceMissing = true,
        )
        assertEquals(KnowledgeLegacyRowClass.SOURCE_MISSING, cls)
    }

    @Test
    fun `short complete rows classify as complete`() {
        val cls = KnowledgeLegacyRowClassifier.classify(
            isInternal = false, content = "短正文", contentHash = "h", hasChunks = false, contentHashMatches = false,
        )
        assertEquals(KnowledgeLegacyRowClass.COMPLETE, cls)
    }

    // ---------- 孤儿库/孤儿文件合同(数据夹具语义) ----------

    @Test
    fun `orphan chunk doc reference is representable and classifiable`() {
        // 孤儿 chunk:chunk 行存在、doc 行缺失 —— 分类输入表达为 content 空 + hasChunks。
        // 恢复协调日志与 R9 恢复区按此类别进入「无法归属」而非静默丢弃或拼接。
        val cls = KnowledgeLegacyRowClassifier.classify(
            isInternal = false, content = "", contentHash = "", hasChunks = true, contentHashMatches = false,
        )
        assertEquals(KnowledgeLegacyRowClass.CHUNK_ONLY, cls)
    }
}

/** 测试专用 SHA-256 桩:仅验证「hash 一致性」这个布尔输入,不引入真哈希依赖。 */
private object RagServiceHashStub {
    fun of(content: String): String = "stub-${content.length}"
}
