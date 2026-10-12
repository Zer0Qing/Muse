package io.zer0.ai.core

/**
 * 模型输出预算解析策略。
 *
 * 模型元数据中的 [Model.maxOutputTokens] 是模型能力上限，
 * 调用方传入的 maxTokens 只是本次请求预算。最终预算不能超过任一已知上限。
 *
 * 规则：
 *  - 调用方传入正数时，使用 min(调用方预算, 模型上限)；
 *  - 调用方未传预算时，若模型目录有上限则使用该上限，避免依赖供应商过小的隐式默认值；
 *  - 调用方传入 0 或负数视为未设置；
 *  - 模型上限未知时保留调用方正数，调用方未设置时继续让 Provider 使用自身默认值。
 */
object ModelOutputPolicy {

    /**
     * 估算将要发送给 Provider 的输入 token 数。
     *
     * 这是预算保护用的保守估算,不是计费口径:中文按字符计,ASCII 按约 4 字符/token,
     * 每张图片预留约 1000 token,并纳入推理/工具调用与工具 schema。
     */
    fun estimateInputTokens(messages: List<UIMessage>, tools: List<ToolDefinition>? = null): Int {
        var total = 0L
        for (message in messages) {
            total += MESSAGE_OVERHEAD_TOKENS
            total += estimateTextTokens(message.content)
            total += estimateTextTokens(message.reasoning.orEmpty())
            total += message.imageBase64List.size * IMAGE_TOKEN_RESERVE
            message.toolCalls.orEmpty().forEach { call ->
                total += estimateTextTokens(call.name)
                total += estimateTextTokens(call.arguments)
            }
        }
        tools.orEmpty().forEach { tool ->
            total += estimateTextTokens(tool.name)
            total += estimateTextTokens(tool.description)
            total += estimateTextTokens(tool.parametersJsonSchema)
        }
        return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * 根据输入 token 数给输出预留空间；未知上下文窗口时不做猜测。
     *
     * 这是请求预算的第二道边界：模型 maxOutputTokens 是能力上限，
     * contextWindow - inputTokens - reserveTokens 是本次请求的可用上限。
     *
     * 注意：上下文窗口是输入+输出共享的，不能把整段剩余上下文都当成本次输出预算。
     * 模型未声明 maxOutputTokens 且调用方也未给预算时返回 null（交回 Provider 默认值），
     * 避免把 max_tokens 放大到接近上下文窗口而被上游以 400 拒绝。
     */
    fun resolveForContext(requestedMaxTokens: Int?, model: Model, inputTokens: Int, reserveTokens: Int = 1_024): Int? {
        require(inputTokens >= 0) { "inputTokens must not be negative" }
        require(reserveTokens >= 0) { "reserveTokens must not be negative" }
        val contextWindow = model.contextWindow?.takeIf { it > 0 }
        if (contextWindow == null) return resolve(requestedMaxTokens, model)
        val available = (contextWindow - inputTokens - reserveTokens).coerceAtLeast(1)
        val modelBound = model.maxOutputTokens?.takeIf { it > 0 }
        val requested = requestedMaxTokens?.takeIf { it > 0 }
        // 模型未声明输出上限时，不能把 available（整段剩余上下文）直接当成本次输出预算：
        //  - 上下文窗口由输入+输出共享，全给输出本就不成立；
        //  - 供应商普遍对单次输出另设上限（例如 65536），放大后必然被上游 400 拒绝。
        // 因此：无模型上限且调用方未给预算 → 返回 null，交回 Provider 自身默认值；
        //      调用方给了预算 → 仅按剩余上下文收紧，不外扩。
        val contextBound = if (modelBound == null && requested == null) {
            null
        } else {
            minOf(available, modelBound ?: available)
        }
        return contextBound?.let { minOf(requested ?: it, it) }
    }

    /**
     * 解析本次请求最终发送给 Provider 的输出 token 上限。
     *
     * 不擅自扩大调用方显式设置的正数；只有调用方未设置时，才采用模型目录声明的能力上限。
     */
    fun resolve(requestedMaxTokens: Int?, model: Model): Int? {
        val requested = requestedMaxTokens?.takeIf { it > 0 }
        val declaredModelLimit = model.maxOutputTokens?.takeIf { it > 0 }
        // 输出上限不可能超过模型上下文窗口；目录值仍原样保留并由 ModelRegistry
        // 标记可疑，这里只在实际请求预算层阻止不可能的值。
        val contextLimit = model.contextWindow?.takeIf { it > 0 }
        val modelLimit = if (declaredModelLimit != null && contextLimit != null) {
            minOf(declaredModelLimit, contextLimit)
        } else {
            declaredModelLimit
        }
        return when {
            requested != null && modelLimit != null -> minOf(requested, modelLimit)
            requested != null -> requested
            modelLimit != null -> modelLimit
            else -> null
        }
    }

    /** 显式预算被模型能力上限收紧时返回 true，供诊断日志和 UI 使用。 */
    fun wasClamped(requestedMaxTokens: Int?, model: Model): Boolean {
        val requested = requestedMaxTokens?.takeIf { it > 0 }
        val declaredModelLimit = model.maxOutputTokens?.takeIf { it > 0 }
        if (requested == null || declaredModelLimit == null) return false
        val contextLimit = model.contextWindow?.takeIf { it > 0 }
        val modelLimit = if (contextLimit != null) {
            minOf(declaredModelLimit, contextLimit)
        } else {
            declaredModelLimit
        }
        return requested > modelLimit
    }

    private fun estimateTextTokens(text: String): Int {
        if (text.isBlank()) return 0
        var cjk = 0
        var other = 0
        text.forEach { ch ->
            if (isCjk(ch)) cjk++ else other++
        }
        return cjk + ((other + ASCII_CHARS_PER_TOKEN - 1) / ASCII_CHARS_PER_TOKEN)
    }

    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x4E00..0x9FFF ||
            code in 0x3400..0x4DBF ||
            code in 0x3040..0x30FF ||
            code in 0xAC00..0xD7AF
    }

    private const val ASCII_CHARS_PER_TOKEN = 4
    private const val MESSAGE_OVERHEAD_TOKENS = 4
    private const val IMAGE_TOKEN_RESERVE = 1_000
}
