package io.zer0.ai.core

/**
 * 模型输出预算解析策略。
 *
 * 参考 Hana 的模型能力目录：模型元数据中的 [Model.maxOutputTokens] 是模型能力上限，
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
     * 根据输入 token 数给输出预留空间；未知上下文窗口时不做猜测。
     *
     * 这是请求预算的第二道边界：模型 maxOutputTokens 是能力上限，
     * contextWindow - inputTokens - reserveTokens 是本次请求的可用上限。
     */
    fun resolveForContext(
        requestedMaxTokens: Int?,
        model: Model,
        inputTokens: Int,
        reserveTokens: Int = 1_024,
    ): Int? {
        require(inputTokens >= 0) { "inputTokens must not be negative" }
        require(reserveTokens >= 0) { "reserveTokens must not be negative" }
        val contextWindow = model.contextWindow?.takeIf { it > 0 } ?: return resolve(requestedMaxTokens, model)
        val available = (contextWindow - inputTokens - reserveTokens).coerceAtLeast(1)
        val modelBound = model.maxOutputTokens?.takeIf { it > 0 }
        val contextBound = minOf(available, modelBound ?: available)
        val requested = requestedMaxTokens?.takeIf { it > 0 }
        return minOf(requested ?: contextBound, contextBound)
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
}
