package io.zer0.memory.llm

import io.zer0.ai.core.Model

/**
 * memory 模块的 LLM 客户端抽象 (对应 openhanako 的 callText)。
 *
 * memory 模块不直接依赖 ai 模块的 [io.zer0.ai.core.Provider],
 * 而是通过这个接口由 app 模块注入实现(委托给 [io.zer0.ai.ChatService.completeText])。
 *
 * 这样 memory 模块保持独立,可以单独编译测试。
 */
interface MemoryLlmClient {

    /**
     * 非流式调用 LLM,返回完整文本。
     *
     * @param systemPrompt system 消息内容
     * @param userContent user 消息内容
     * @param model 目标模型,null 时由实现侧用 Provider 配置里的第一个模型
     * @param temperature 采样温度(0~2),memory 路径固定 0.3
     * @param maxTokens 输出上限(memory 路径按调用类型分配: rolling 150-750 / compile 300-600 / fact 4096)
     * @param timeoutMs v1.78: 超时毫秒,默认 30s。超时抛 TimeoutCancellationException。
     *                  防止 LLM 调用挂起导致 daily pipeline 永久卡死。
     * @return 完整文本
     * @throws Exception 网络/HTTP/解析错误/超时
     */
    suspend fun callText(
        systemPrompt: String,
        userContent: String,
        model: Model? = null,
        temperature: Float = 0.3f,
        maxTokens: Int = 600,
        timeoutMs: Long = 30_000L,
    ): String
}
