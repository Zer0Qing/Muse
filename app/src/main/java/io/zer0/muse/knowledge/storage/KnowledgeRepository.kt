package io.zer0.muse.knowledge.storage

import io.zer0.muse.data.knowledge.KnowledgeLegacyRowClass
import io.zer0.muse.data.knowledge.KnowledgeLegacyRowClassifier
import io.zer0.muse.data.knowledge.KnowledgeRevision
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * R2 仓储合同(计划 R2 步骤 3):新资料写入 source bytes + 完整 revision artifact;
 * preview 是 revision 的派生读模型(前缀一致、受上限约束,绝不反向成为真源);
 * 编辑 API 必须传 expectedRevisionId(乐观锁),每次编辑产生新 revision,
 * 旧 revision 永不覆盖(不可变版本链)。
 *
 * 存储分层:
 *  - 正文工件:[KnowledgeContentStore](内容寻址 blob)按 revision contentHash 存完整正文;
 *  - revision 元数据:本类内存索引(ConcurrentHashMap)。DAO 落库接线归 R4(持久作业层)
 *    (MuseDb 125→126);仓储合同先于持久化固化,接口按可换实现设计。
 *
 * 与旧链路的关系:R2 保持真实用户写入切换关闭;本类目前只服务合成库与后续批次的
 * 消费者,不接入既有导入入口(接线在 R4+)。
 */
class KnowledgeRepository(
    blobsDirectory: File,
    private val contentStore: KnowledgeContentStore = KnowledgeContentStore(File(blobsDirectory, "store")),
) {

    /** 文档当前指针(docId → 最新 revisionId);旧 revision 全部保留。 */
    private val currentRevisionByDoc = ConcurrentHashMap<String, String>()

    /** revision 工件索引(revisionId → KnowledgeRevision)。 */
    private val revisions = ConcurrentHashMap<String, KnowledgeRevision>()

    /** 版本号索引(docId → 当前 revisionNo)。 */
    private val revisionNoByDoc = ConcurrentHashMap<String, Int>()

    sealed interface EditResult {
        data class Ok(val doc: KnowledgeRevision) : EditResult
        /** expectedRevisionId 与当前版本不符:并发更新冲突,调用方应刷新后重试。 */
        data object Conflict : EditResult
    }

    /**
     * 摄取新资料:source bytes 入内容寻址 store,完整 revision 建档,preview 从全文派生。
     * 返回最新 revision;store 写入失败抛 IOException 由调用方决定重试(不得静默丢正文)。
     */
    fun ingest(sourceScheme: String, location: String, fullContent: String): KnowledgeRevision {
        val ref = contentStore.put(sourceScheme, fullContent.toByteArray())
        requireNotNull(ref) { "source bytes 写入失败(scheme=$sourceScheme)" }
        // location 记入来源审计(外部 URI 仅作来源说明,不是可恢复副本)。
        return createRevision(
            docId = newDocId(),
            parentRevisionId = null,
            fullContent = fullContent,
            origin = "ingest:$sourceScheme:${location.take(80)}",
        )
    }

    /**
     * 编辑正文:expectedRevisionId 乐观锁 —— 与当前版本不符返回 [EditResult.Conflict];
     * 匹配则产生 revisionNo+1 的新 revision(旧 revision 保留),preview 重新派生。
     */
    fun editContent(docId: String, expectedRevisionId: String, newContent: String): EditResult {
        val currentId = currentRevisionByDoc[docId] ?: return EditResult.Conflict
        if (currentId != expectedRevisionId) return EditResult.Conflict
        val currentNo = revisionNoByDoc[docId] ?: return EditResult.Conflict
        val next = createRevision(
            docId = docId,
            parentRevisionId = currentId,
            fullContent = newContent,
            origin = "edit",
            baseRevisionNo = currentNo,
        )
        return EditResult.Ok(next)
    }

    /** 读取 revision 工件(全文真源;preview 只是它的派生)。 */
    fun loadRevision(revisionId: String): KnowledgeRevision =
        requireNotNull(revisions[revisionId]) { "revision 不存在: $revisionId" }

    /**
     * R2 步骤 6:旧行导入 — 只做已知信息分类,不猜测、不拼接冒充。
     *
     * 合同(由 KnowledgeRepositoryTest 钉住):
     *  - 分类由 [KnowledgeLegacyRowClassifier] 按行上可验证信息判定(importLegacyRowParams);
     *  - ChunkOnly 行:revision.fullContent 保持空串 —— 旧 chunks 原文/顺序/metadata
     *    原样返回([LegacyImport.chunkTexts]/[LegacyImport.metadataJson]),绝不拼接成
     *    "完整原件";恢复声明内容由 R9 决定;
     *  - 完整行(含长正文):全文进 revision,preview 派生;
     *  - docId 原样保留(citation 身份锚点);owner 持久落位。
     */
    fun importLegacyRow(
        docId: String,
        ownerType: String,
        ownerId: String,
        isInternal: Boolean,
        content: String,
        contentHash: String,
        metadataJson: String,
        chunks: List<String>,
        sourceMissing: Boolean,
    ): LegacyImport {
        val hashMatches = contentHash.isNotBlank() &&
            contentHash == KnowledgeContentStore.hashOf(content.toByteArray())
        val rowClass = KnowledgeLegacyRowClassifier.classify(
            isInternal = isInternal,
            content = content,
            contentHash = contentHash,
            hasChunks = chunks.isNotEmpty(),
            contentHashMatches = hashMatches,
            sourceMissing = sourceMissing,
        )
        // ChunkOnly:不建"完整原件"revision;以空 fullContent 的 revision 占位登记身份,
        // 原文留在 chunks(调用方/R4 索引层消费),不在此拼接。
        val revision = createRevision(
            docId = docId,
            parentRevisionId = null,
            fullContent = if (rowClass == KnowledgeLegacyRowClass.CHUNK_ONLY) "" else content,
            origin = "legacy-import:$rowClass",
        )
        return LegacyImport(
            docId = docId,
            ownerType = ownerType,
            ownerId = ownerId,
            rowClass = rowClass,
            revisionId = revision.revisionId,
            metadataJson = metadataJson,
            chunkTexts = chunks.toList(),
        )
    }

    /** 旧行导入结果 — 分类 + 落位身份 + 原样保留的旧数据。 */
    data class LegacyImport(
        val docId: String,
        val ownerType: String,
        val ownerId: String,
        val rowClass: KnowledgeLegacyRowClass,
        val revisionId: String,
        val metadataJson: String,
        val chunkTexts: List<String>,
    )

    private fun createRevision(
        docId: String,
        parentRevisionId: String?,
        fullContent: String,
        origin: String,
        baseRevisionNo: Int? = null,
    ): KnowledgeRevision {
        val revisionNo = (baseRevisionNo ?: 0) + 1
        val revisionId = "rev-${docId.takeLast(8)}-$revisionNo-${shortHash(fullContent)}"
        val revision = KnowledgeRevision(
            revisionId = revisionId,
            docId = docId,
            revisionNo = revisionNo,
            contentHash = KnowledgeContentStore.hashOf(fullContent.toByteArray()),
            fullContent = fullContent,
            preview = derivePreview(fullContent),
            createdAt = System.currentTimeMillis(),
            origin = if (parentRevisionId != null) "$origin:from=${parentRevisionId.take(12)}" else origin,
        )
        revisions[revisionId] = revision
        currentRevisionByDoc[docId] = revisionId
        revisionNoByDoc[docId] = revisionNo
        return revision
    }

    private fun newDocId(): String = "doc-${System.currentTimeMillis()}-${shortHash(System.nanoTime().toString())}"

    companion object {
        /** 与旧链路预览上限同源(500_000 字符);preview 是全文安全前缀。 */
        const val PREVIEW_MAX_CHARS = 500_000

        /** preview 派生合同:全文 ≤ 上限 → 全文;否则截断前缀(派生,非独立真源)。 */
        fun derivePreview(fullContent: String): String =
            if (fullContent.length <= PREVIEW_MAX_CHARS) fullContent else fullContent.take(PREVIEW_MAX_CHARS)

        private fun shortHash(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
                .joinToString("") { "%02x".format(it) }.take(8)
    }
}
