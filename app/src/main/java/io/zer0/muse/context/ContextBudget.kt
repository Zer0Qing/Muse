package io.zer0.muse.context

/**
 * M4.3: 上下文预算分区。
 *
 * 每个进入 LLM 请求的注入源一个分区,上限独立配置、独立观测,
 * 避免单一来源(大附件/超长记忆/巨型工具 schema)挤占整个上下文窗口。
 */
enum class ContextSection {
    /** 附件/文档正文(经 buildSendText 拼接进消息正文)。 */
    ATTACHMENT_TEXT,
    /** 长期记忆编译摘要(<long_term_memory>)。 */
    LONG_TERM_MEMORY,
    /** FTS 召回的相关事实(<relevant_memory>)。 */
    RELEVANT_MEMORY,
    /** RAG 检索注入的文档片段。 */
    RAG_CITATION,
    /** 视觉模型生成的图片描述注入。 */
    VISION_DESCRIPTION,
    /** 工具 function schema 总量。 */
    TOOL_SCHEMA,
}

/**
 * M4.3: 统一上下文预算。
 *
 * 为历史消息之外的所有注入源设置字符上限;截断策略为保留头部 +
 * 显式截断注记(截断事实必须对下游可诊断,不允许静默丢弃)。
 *
 * 历史消息条数仍由 Assistant.contextMessageSize / limitContextWithContext 管理,
 * 不在本预算内重复限制(单一职责,避免双重截断)。
 *
 * @param limits 分区上限(字符);可用自定义值构造用于测试
 */
class ContextBudget(private val limits: Map<ContextSection, Int> = DEFAULT_LIMITS) {

    /** 单分区截断结果。 */
    data class Clamped(val text: String, val truncated: Boolean, val originalChars: Int)

    /**
     * 按分区上限截断文本。
     *
     * 空白文本原样返回;未超限原样返回;超限保留头部并附注记。
     * 审查修复(P2): 注记长度从 [limit] 内预留,保证结果总长 ≤ 上限,
     * 而不是截取 limit 后再追加注记导致必然超预算。
     */
    fun clamp(section: ContextSection, text: String): Clamped {
        val limit = limits[section]
        val originalChars = text.length
        return when {
            limit == null || originalChars <= limit -> Clamped(text, false, originalChars)
            else -> {
                val notice = "\n\n[内容已按上下文预算截断: 分区=$section 原始 $originalChars 字符 上限 $limit 字符]"
                // 注记计入预算:头部预留 = 上限 - 注记长度(下限 0)
                val headLength = (limit - notice.length).coerceAtLeast(0)
                Clamped(text.take(headLength) + notice, true, originalChars)
            }
        }
    }

    /** 便捷入口:只取截断后文本。 */
    fun clampText(section: ContextSection, text: String): String = clamp(section, text).text

    companion object {
        /**
         * 默认分区上限(字符)。以中英混合 1 token ≈ 2-3 字符估算:
         * - 记忆摘要 24k 字符 ≈ 8-12k token,不会挤占对话正文
         * - 附件 60k 字符 ≈ 单文件全文注入的实际上限
         * - 工具 schema 400k 字符 ≈ 100+ 工具全量展示的安全余量
         */
        val DEFAULT_LIMITS: Map<ContextSection, Int> = mapOf(
            ContextSection.ATTACHMENT_TEXT to 60_000,
            ContextSection.LONG_TERM_MEMORY to 24_000,
            ContextSection.RELEVANT_MEMORY to 8_000,
            ContextSection.RAG_CITATION to 8_000,
            ContextSection.VISION_DESCRIPTION to 12_000,
            ContextSection.TOOL_SCHEMA to 400_000,
        )
    }
}
