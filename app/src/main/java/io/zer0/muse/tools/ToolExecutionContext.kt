package io.zer0.muse.tools

/**
 * 工具执行时由宿主链路注入的不可伪造上下文。
 *
 * 模型参数只描述“查什么”，不会获得修改记忆作用域的能力。
 */
data class ToolExecutionContext(
    val scope: String,
    val spaceId: String,
)
