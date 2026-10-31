package io.zer0.muse.ui.settings

import io.zer0.muse.rag.EmbeddingService

/** 当前生效的 embedding 后端摘要，供设置页展示。 */
data class ActiveBackendSummary(
    /** 主行文案：后端显示名与模型名，形如「本地关键词 · local-keyword-v1」。 */
    val primary: String,
    /** 降级原因；未降级时为 null，调用方据此决定是否渲染告警行。 */
    val warning: String?,
)

/** 主行里显示名与模型名之间的分隔符。 */
private const val PRIMARY_SEPARATOR = " · "

/**
 * 把 [EmbeddingService.BackendState] 折算成设置页可直接渲染的两行文案。
 *
 * 降级（[EmbeddingService.BackendState.degraded]=true）时一定给出非空 [ActiveBackendSummary.warning]：
 * 原因缺失时用 [degradedFallback] 兜底，避免"正在用别的后端"这件事重新变成静默状态。
 *
 * @param state 实际生效的后端快照；null 表示尚未解析过（还没跑过检索）
 * @param degradedFallback 降级但后端没给原因时的兜底文案（由调用方传入本地化字符串）
 * @return 摘要；[state] 为 null 时返回 null，调用方据此整块不渲染
 */
fun resolveActiveBackendSummary(
    state: EmbeddingService.BackendState?,
    degradedFallback: String,
): ActiveBackendSummary? {
    if (state == null) return null
    val displayName = state.displayName.trim()
    val modelName = state.modelName.trim()
    val primary = if (modelName.isEmpty()) displayName else "$displayName$PRIMARY_SEPARATOR$modelName"
    val reason = state.reason?.trim()
    val warning = when {
        !state.degraded -> null
        !reason.isNullOrEmpty() -> reason
        else -> degradedFallback
    }
    return ActiveBackendSummary(primary = primary, warning = warning)
}
