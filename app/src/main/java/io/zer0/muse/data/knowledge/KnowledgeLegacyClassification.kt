package io.zer0.muse.data.knowledge

/**
 * R2 旧行分类合同 — 导入旧行只做已知信息分类,不做任何拼接冒充(计划 R2 步骤:
 * 「导入旧行只做已知信息分类,保留旧 chunks 原文、顺序和 metadata;ChunkOnly 不拼接冒充完整原件」)。
 *
 * 分类依据 = 旧行上实际可验证的信息,缺什么就如实标记;调用方按类别决定
 * 可用的操作(编辑/重建/恢复声明),不得把 ChunkOnly 当作完整原件。
 */
enum class KnowledgeLegacyRowClass {
    /** 新装默认库的行:kb=default 且从未导入过旧备份。 */
    FRESH_INSTALL,

    /** doc.content 完整(非预览截断)且原件可达。 */
    COMPLETE,

    /** doc.content 只是预览(超上限被截断),完整正文需要原件/revision 才能恢复。 */
    PREVIEW_ONLY,

    /** doc 行缺失或预览为空,只有 chunks 保留原文;不得拼接冒充完整原件。 */
    CHUNK_ONLY,

    /** 源不可达(filePath 失效/无原件),但正文行完整或为预览;重建需用户重导。 */
    SOURCE_MISSING,

    /** 内部文档(devdoc seed 行),不属于用户资料生命周期。 */
    INTERNAL,
}

/**
 * 旧行分类器 — 纯函数,只看行上已有字段,不触网络/不猜测。
 *
 * 判定规则(与 R0 盘点的旧行为证据一致):
 *  - internal 标记优先(内部文档不参与用户资料分类);
 *  - 行内 hash 与现内容可验证一致(调用方传 contentHashMatches,基于行上 content_hash
 *    与 computeContentHash(content) 比对)→ COMPLETE(哈希证明正文完整,与旧链路
 *    reindexDocument 的 inline 判定同源);
 *  - hash 不一致或缺失且内容达到旧预览上限 → PREVIEW_ONLY(旧写入路径把超限正文
 *    截断为预览存行,content_hash 记录的是完整原文的 hash,与行内容不匹配);
 *  - content 为空但存在 chunk → CHUNK_ONLY(由调用方传入 hasChunks);
 *  - 调用方已验证源不可达 → SOURCE_MISSING(仍保留正文/预览行,重建需用户重导);
 *  - 其余默认 COMPLETE(短正文,无需重建)。
 */
object KnowledgeLegacyRowClassifier {

    /** 与 RagService.reindexDocument 的旧预览上限同源(500_000 字符)。 */
    const val LEGACY_PREVIEW_CHARS = 500_000

    fun classify(
        isInternal: Boolean,
        content: String,
        contentHash: String,
        hasChunks: Boolean,
        contentHashMatches: Boolean,
        sourceMissing: Boolean = false,
    ): KnowledgeLegacyRowClass = when {
        isInternal -> KnowledgeLegacyRowClass.INTERNAL
        sourceMissing -> KnowledgeLegacyRowClass.SOURCE_MISSING
        content.isBlank() && hasChunks -> KnowledgeLegacyRowClass.CHUNK_ONLY
        contentHashMatches -> KnowledgeLegacyRowClass.COMPLETE
        content.length >= LEGACY_PREVIEW_CHARS -> KnowledgeLegacyRowClass.PREVIEW_ONLY
        else -> KnowledgeLegacyRowClass.COMPLETE
    }
}
