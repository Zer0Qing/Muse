package io.zer0.muse.hook

import io.zer0.ai.core.UIMessage
import io.zer0.muse.transformer.TransformContext

/**
 * P1-1: Skill Hook 基础接口。
 *
 * 所有 Hook 都实现此接口,通过 [HookRegistry] 注册后参与 AI 对话流程。
 * 与既有 [io.zer0.muse.transformer.Transformer] 的边界:
 *  - Transformer 管"消息列表变形"(transform/visualTransform/onGenerationFinish)
 *  - SkillHook 管"流程拦截/追加/生命周期事件"(更广维度的介入点)
 *
 * 设计原则:
 *  - 优先级排序: priority 数值越大越先执行(与 Lorebook priority DESC 对齐)
 *  - 幂等可重入: 同一 Hook 可能被多次调用,实现方需保证幂等
 *  - 容错: 单个 Hook 抛异常不阻断主流程,由 [HookRegistry] 捕获并记录
 *
 * @property id 唯一标识(用于注册/注销/调试)
 * @property priority 优先级(数值越大越先执行,默认 50)
 * @property enabled 是否启用( false 的 Hook 不参与执行)
 */
interface SkillHook {
    val id: String
    val priority: Int get() = 50
    val enabled: Boolean get() = true
}

// ════════════════════════════════════════════════════════════════
// 1. 应用生命周期 Hook
// ════════════════════════════════════════════════════════════════

/**
 * 应用生命周期 Hook — 响应 App 的创建/前后台切换。
 *
 * 典型场景:
 *  - 初始化第三方 SDK
 *  - 上报启动事件
 *  - 前台恢复连接 / 后台释放资源
 */
interface AppLifecycleHook : SkillHook {
    suspend fun onAppCreate() {}
    suspend fun onAppForeground() {}
    suspend fun onAppBackground() {}
}

// ════════════════════════════════════════════════════════════════
// 2. 消息处理 Hook
// ════════════════════════════════════════════════════════════════

/**
 * 消息处理 Hook — 介入用户消息发送和 AI 消息渲染。
 *
 * 与 Transformer.visualTransform 的区别:
 *  - visualTransform 处理"已生成的消息列表"做 UI 显示转换
 *  - MessageProcessingPlugin 在"发送前/渲染前"做拦截/修改
 */
interface MessageProcessingPlugin : SkillHook {
    /**
     * 用户消息发送前调用。
     * @return 修改后的消息内容;返回 null 表示拦截该消息(不发送)
     */
    suspend fun onUserMessageSend(message: String): String? { return message }

    /**
     * AI 消息渲染前调用(可做最终内容修改,如脱敏/格式化)。
     * @return 修改后的内容
     */
    suspend fun onAssistantMessageRender(content: String): String { return content }
}

// ════════════════════════════════════════════════════════════════
// 3. Prompt 流水线 Hook
// ════════════════════════════════════════════════════════════════

/**
 * Prompt 组装上下文 — 传给 SystemPromptComposeHook 的上下文数据。
 */
data class PromptContext(
    val assistantId: String?,
    val sessionId: String?,
    val locale: String,
    val forSubagent: Boolean,
)

/**
 * 系统提示组装 Hook — 在 SystemPromptAssembler 组装完系统提示后调用。
 *
 * 典型场景:
 *  - Worldbook 的 alwaysActive 条目注入
 *  - 动态追加指令(如基于时间/位置的场景感知)
 *
 * @return 追加到系统提示末尾的内容(空串表示不追加)
 */
interface SystemPromptComposeHook : SkillHook {
    suspend fun afterComposeSystemPrompt(context: PromptContext): String { return "" }
}

/**
 * 工具清单组装 Hook — 在工具列表组装后调用,可过滤/排序/追加。
 *
 * 典型场景:
 *  - 根据上下文动态启用/禁用某些工具
 *  - 注入自定义工具描述
 */
interface ToolPromptComposeHook : SkillHook {
    /**
     * @param tools 当前工具列表
     * @return 修改后的工具列表
     */
    suspend fun filterToolPromptItems(tools: List<ToolPromptItem>): List<ToolPromptItem> { return tools }
}

/**
 * 工具清单条目(简化模型,供 Hook 操作)。
 */
data class ToolPromptItem(
    val name: String,
    val description: String,
    val parametersJson: String?,
    val enabled: Boolean = true,
)

/**
 * Prompt 最终化事件 — 发送给模型前的最后修改机会。
 */
data class PromptFinalizeEvent(
    val preparedHistory: List<UIMessage>,
    val assistantId: String?,
    val sessionId: String?,
    val transformContext: TransformContext,
)

/**
 * Prompt 最终化结果。
 */
data class PromptFinalizeResult(
    val preparedHistory: List<UIMessage>,
)

/**
 * Prompt 最终化 Hook — 在消息列表发送给模型前调用。
 *
 * 与 Transformer.transform 的区别:
 *  - transform 在管道中早期执行,处理原始消息
 *  - PromptFinalizeHook 在管道之后、发送之前执行,可基于最终状态做截断/注入
 *
 * 典型场景:
 *  - 楼层式上下文限制(P1-4): 截断超出楼层限制的历史消息
 *  - Worldbook 关键词触发注入(P1-2): 扫描最近 N 层 USER 消息,注入匹配条目
 */
interface PromptFinalizeHook : SkillHook {
    suspend fun beforeFinalizePrompt(event: PromptFinalizeEvent): PromptFinalizeResult {
        return PromptFinalizeResult(event.preparedHistory)
    }
}

// ════════════════════════════════════════════════════════════════
// 4. 工具生命周期 Hook
// ════════════════════════════════════════════════════════════════

/**
 * 工具调用动作枚举。
 */
sealed class ToolCallAction {
    /** 允许执行 */
    object Allow : ToolCallAction()
    /** 拦截执行,返回自定义结果给 LLM */
    data class Block(val reason: String, val fakeResult: String? = null) : ToolCallAction()
}

/**
 * 工具执行结果(简化模型)。
 */
data class ToolExecutionResult(
    val toolName: String,
    val success: Boolean,
    val output: String,
    val durationMs: Long,
)

/**
 * 工具生命周期 Hook — 介入工具调用的各阶段。
 *
 * 典型场景:
 *  - 审计日志: 记录每次工具调用
 *  - 权限二次校验: 基于上下文拦截高风险调用
 *  - 结果后处理: 对工具输出做脱敏/格式化
 */
interface ToolLifecycleHook : SkillHook {
    /**
     * 工具调用请求时调用,可拦截。
     * @return [ToolCallAction.Allow] 放行; [ToolCallAction.Block] 拦截
     */
    suspend fun onToolCallRequested(toolName: String, params: Map<String, Any>): ToolCallAction {
        return ToolCallAction.Allow
    }

    /**
     * 权限检查完成后调用。
     */
    suspend fun onToolPermissionChecked(toolName: String, approved: Boolean) {}

    /**
     * 工具执行完成后调用。
     */
    suspend fun onToolExecutionResult(result: ToolExecutionResult) {}
}

// ════════════════════════════════════════════════════════════════
// 5. 摘要生成 Hook
// ════════════════════════════════════════════════════════════════

/**
 * 摘要生成 Hook — 介入对话压缩/摘要生成的各阶段。
 *
 * 典型场景:
 *  - 自定义摘要提示词
 *  - 摘要结果后处理(如提取关键决策点)
 *  - 基于摘要触发记忆固化
 */
interface SummaryGenerateHook : SkillHook {
    /**
     * 准备摘要提示词前调用,可修改待压缩的历史消息。
     * @return 修改后的历史消息
     */
    suspend fun beforePrepareSummaryPrompt(history: List<UIMessage>): List<UIMessage> { return history }

    /**
     * 摘要生成后调用,可修改最终摘要内容。
     * @return 修改后的摘要
     */
    suspend fun afterGenerateSummary(summary: String): String { return summary }
}
