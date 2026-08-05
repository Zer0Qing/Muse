package io.zer0.muse.tools

/**
 * v1.0.53: 工具执行结果(结构化)。参考开源实现 toolOk/toolError。
 *
 * 设计:
 *  - [content] 始终为给 LLM 看的文本(保持现有 String 语义)
 *  - [details] 附加结构化数据(供 UI 渲染/链路追踪/审批上下文),LLM 不可见
 *  - [isError] 错误标记:UI 可用它决定是否走 ErrorMessageMapper/红色样式
 *
 * 兼容:现有调用方只读 content 时零改动;新调用方用 details/isError。
 */
data class ToolOutcome(
    val content: String,
    val details: Map<String, Any?> = emptyMap(),
    val isError: Boolean = false,
) {
    companion object {
        fun ok(content: String, details: Map<String, Any?> = emptyMap()) =
            ToolOutcome(content, details, isError = false)

        fun error(content: String, details: Map<String, Any?> = emptyMap()) =
            ToolOutcome(content, details, isError = true)
    }

    /** 给 LLM 的文本(兼容旧语义,直接取 content)。 */
    val text: String get() = content
}
