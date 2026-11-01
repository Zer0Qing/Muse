package io.zer0.muse.tools

/**
 * 工具执行时由宿主链路注入的不可伪造上下文。
 *
 * 模型参数只描述“查什么”，不会获得修改记忆作用域的能力。
 */
data class ToolExecutionContext(
    val scope: String,
    val spaceId: String,
    val assistantId: String? = null,
    /** v2.x: 宿主会话 id(find_tools 动态装载等按会话生效的逻辑使用);旧调用点缺省 null。 */
    val sessionId: String? = null,
    /**
     * 本轮正在生成的助手消息 id。
     *
     * 卡片的身份就是承载它的消息 id（见 `ShowCardTool` 的说明），
     * 但模型在生成过程中无法知道这个 id —— 它是宿主在建占位消息时生成的。
     * 不注入的话，`update_card_data` 永远拿不到一个真实存在的 key，
     * 只能要么要求模型自己猜（不可能对），要么静默写到一个没人读的键上。
     */
    val messageId: String? = null,

    /**
     * 长任务进度回调（可选）。
     *
     * `ui_agent` / `automation_workflow` 这类要跑十几步的工具，中途没有任何反馈时
     * 用户只能看到一个转圈，分不清是卡死还是在干活。编排器把它接到任务卡的
     * `progressText`（与 skill 执行分支同一条通道）。
     */
    val onProgress: ((String) -> Unit)? = null,
)
