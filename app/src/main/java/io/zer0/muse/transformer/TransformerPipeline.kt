package io.zer0.muse.transformer

import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger

/**
 * Transformer 管道执行器(Phase 8.1 H1)。
 *
 * 按顺序执行全部 Transformer,前一个的输出作为后一个的输入。
 * 单个 Transformer 抛异常不中断整条管道,记录日志后跳过(容错)。
 *
 * 用法:
 * ```
 * val pipeline = TransformerPipeline(listOf(
 *     MemoryInjectionTransformer(memoryTicker, settings),
 *     ThinkTagTransformer(),
 *     // ...
 * ))
 * val processed = pipeline.execute(messages, context)
 * ```
 *
 * 设计权衡:
 *  - 容错优于严格: 单个 Transformer 失败不阻断主流程,聊天仍可继续
 *  - 顺序敏感: Transformer 按添加顺序执行,顺序由调用方保证
 *  - 并行性: 当前串行执行,后续可考虑无依赖的并行(但 message 列表有状态,并行复杂)
 */
class TransformerPipeline(
    private val transformers: List<Transformer>,
) {
    /**
     * 执行管道。
     *
     * 不强制指定 dispatcher:Transformer 接口约定 IO 型 Transformer 须自行 `withContext(IO)`,
     * 此处保持 suspend 让 Transformer 自决,避免对纯计算型 Transformer 强加线程切换开销。
     *
     * @param messages 原始消息列表
     * @param context 执行上下文
     * @return 处理后的消息列表;若管道为空,原样返回
     */
    suspend fun execute(
        messages: List<UIMessage>,
        context: TransformContext,
    ): List<UIMessage> {
        // L-PIPE3: 空管道提前返回,避免无谓的调度器切换/循环
        if (transformers.isEmpty()) return messages
        var current = messages
        for (transformer in transformers) {
            current = try {
                transformer.transform(current, context)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // H-PIPE1: 协程取消必须重抛,不可吞掉
                throw e
            } catch (e: Error) {
                // M-PIPE2: OOM/StackOverflow 等 Error 不属于可恢复异常,重抛让上层处理
                throw e
            } catch (err: Exception) {
                // L-PIPE4: 只记类名 + 截断后的 message,避免日志泄露消息内容
                val summary = "${err::class.simpleName}: ${err.message?.take(200)}"
                Logger.e("TransformerPipeline", "Transformer ${transformer.name} failed, skipping: $summary")
                current  // 失败则跳过,保留前一步结果
            }
        }
        return current
    }

    /**
     * 应用所有 Transformer 的 [Transformer.visualTransform] 钩子(流式视觉转换)。
     *
     * 用于流式过程中:对当前 UI 显示的消息列表跑一遍 visual 钩子,
     * 返回的列表仅用于 UI 渲染,不替换实际存储的消息。
     *
     * 与 [execute] 同样的容错策略:单个 Transformer 失败不阻断管道。
     *
     * @param messages 当前 UI 上的消息列表
     * @param context 执行上下文
     * @return 转换后的列表(仅供 UI 显示,不替换实际消息)
     */
    suspend fun applyVisualTransform(
        messages: List<UIMessage>,
        context: TransformContext,
    ): List<UIMessage> {
        if (transformers.isEmpty()) return messages
        var current = messages
        for (transformer in transformers) {
            current = try {
                transformer.visualTransform(current, context)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Error) {
                throw e
            } catch (err: Exception) {
                val summary = "${err::class.simpleName}: ${err.message?.take(200)}"
                Logger.e("TransformerPipeline", "Transformer ${transformer.name} visualTransform failed, skipping: $summary")
                current
            }
        }
        return current
    }

    /**
     * 应用所有 Transformer 的 [Transformer.onGenerationFinish] 钩子(生成完成后处理)。
     *
     * 用于生成完成后:对最终消息列表跑一遍 finish 钩子,
     * 返回的列表可用于替换实际存储的消息(由调用方决定是否落盘)。
     *
     * 与 [execute] 同样的容错策略:单个 Transformer 失败不阻断管道。
     *
     * @param messages 生成完成后的消息列表
     * @param context 执行上下文
     * @return 处理后的列表(可替换实际消息)
     */
    suspend fun applyOnGenerationFinish(
        messages: List<UIMessage>,
        context: TransformContext,
    ): List<UIMessage> {
        if (transformers.isEmpty()) return messages
        var current = messages
        for (transformer in transformers) {
            current = try {
                transformer.onGenerationFinish(current, context)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Error) {
                throw e
            } catch (err: Exception) {
                val summary = "${err::class.simpleName}: ${err.message?.take(200)}"
                Logger.e("TransformerPipeline", "Transformer ${transformer.name} onGenerationFinish failed, skipping: $summary")
                current
            }
        }
        return current
    }

    /** 便捷构造: 构建器模式。 */
    class Builder {
        private val list = mutableListOf<Transformer>()
        fun add(transformer: Transformer): Builder = apply { list.add(transformer) }
        fun build(): TransformerPipeline = TransformerPipeline(list.toList())
    }
}
