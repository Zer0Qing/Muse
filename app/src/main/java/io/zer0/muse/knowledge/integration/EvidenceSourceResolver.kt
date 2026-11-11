package io.zer0.muse.knowledge.integration

/**
 * R7 来源打开退化状态 — 计划 R7「接上打开来源回调和 revision locator；旧版/删除/缺源
 * 都有可读退化状态，不跳到同 docId 的最新文本冒充旧证据」。
 *
 * 本类只定义**可判定的退化状态与用户文案**,不直接操作 UI;
 * 页面按状态渲染,禁止在缺源时跳到同 docId 的最新文本冒充历史证据。
 */
enum class EvidenceSourceState {
    /** 快照对应 revision 仍存在,可定位到页/段。 */
    AVAILABLE,

    /** 原件缺失,但历史引用快照仍在:展示快照,不伪装可定位。 */
    SNAPSHOT_ONLY,

    /** 文档已被删除:展示历史快照 + 来源不可用。 */
    DOC_DELETED,

    /** 引用来自旧版本(缺少 revision/locator 信息):标注"旧版来源片段"。 */
    LEGACY_CITATION,

    /** 打开时发生错误。 */
    ERROR,
}

data class EvidenceSourceResolution(
    val state: EvidenceSourceState,
    val docId: String,
    val docTitle: String,
    val revisionId: String?,
    val locator: String?,
    /** 可展示的快照正文(可能为空)。 */
    val snapshotText: String,
    /** 用户可读的状态说明。 */
    val message: String,
) {
    /** 是否允许跳转到文档详情(仅 AVAILABLE 允许)。 */
    val canOpenDocument: Boolean get() = state == EvidenceSourceState.AVAILABLE
}

object EvidenceSourceResolver {

    /**
     * 解析来源打开结果。
     *
     * @param docExists 文档当前是否存在
     * @param revisionExists 引用指向的 revision 是否仍存在
     * @param hasRevisionInfo 引用是否带 revision/locator 信息(旧引用没有)
     */
    fun resolve(
        docId: String,
        docTitle: String,
        revisionId: String?,
        locator: String?,
        snapshotText: String,
        docExists: Boolean,
        revisionExists: Boolean,
        hasRevisionInfo: Boolean,
        error: String? = null,
    ): EvidenceSourceResolution {
        val state = when {
            error != null -> EvidenceSourceState.ERROR
            !docExists -> EvidenceSourceState.DOC_DELETED
            !hasRevisionInfo -> EvidenceSourceState.LEGACY_CITATION
            !revisionExists -> EvidenceSourceState.SNAPSHOT_ONLY
            else -> EvidenceSourceState.AVAILABLE
        }
        val message = when (state) {
            EvidenceSourceState.AVAILABLE ->
                "可定位到 $locator"
            EvidenceSourceState.SNAPSHOT_ONLY ->
                "原件版本已不可用,仅显示当时引用的片段"
            EvidenceSourceState.DOC_DELETED ->
                "来源文档已删除,以下为历史引用片段"
            EvidenceSourceState.LEGACY_CITATION ->
                "旧版来源片段(无版本与定位信息)"
            EvidenceSourceState.ERROR ->
                "打开来源失败: ${error ?: "未知错误"}"
        }
        return EvidenceSourceResolution(
            state = state,
            docId = docId,
            docTitle = docTitle,
            revisionId = revisionId,
            locator = locator,
            snapshotText = snapshotText,
            message = message,
        )
    }

    /**
     * 防冒充校验:缺源/删除/旧版引用都**不得**返回"可跳转到最新文本"。
     * 只有 AVAILABLE 才允许打开文档。
     */
    fun shouldOpenDocument(resolution: EvidenceSourceResolution): Boolean =
        resolution.canOpenDocument
}
