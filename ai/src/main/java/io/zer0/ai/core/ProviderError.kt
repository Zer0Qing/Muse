package io.zer0.ai.core

import io.zer0.common.Logger
import java.io.IOException

/**
 * v1.0.1: Provider 错误归一化 sealed class。
 *
 * 用类型安全的错误分类替代字符串 contains 匹配,供 ChatViewModel / ChatService /
 * Provider 各层统一消费。
 *
 * 设计原则:
 *  - 每种错误类型携带最小必要信息(code / retryAfterSec / cause)
 *  - [isRetryable] 集中表达"是否值得重试"的策略,调用方不再自行推理
 *  - [Retry-After] 头解析下沉到构造时,而非各调用方重复解析
 *
 * 类型语义:
 *  - [Network]: IOException / 超时 / 连接断开,可重试
 *  - [RateLimit]: 429 / RESOURCE_EXHAUSTED,可重试;优先用 Retry-After 头
 *  - [ServerError]: 5xx(含 Anthropic 529 overloaded),可重试
 *  - [AuthError]: 401 / 403,不可重试(凭证问题)
 *  - [InvalidRequest]: 400 / 422 / 404,不可重试(请求本身有问题,重试无意义)
 *  - [Cancelled]: 协程或 AbortSignal 取消,不可重试
 *  - [Unknown]: 未识别的错误,默认不可重试(避免无限重试未知问题)
 */
sealed class ProviderError {
    /** HTTP 状态码(若来自 HTTP 响应),null 表示非 HTTP 错误(如纯 IOException)。 */
    abstract val httpCode: Int?

    /** 是否值得重试(由具体类型决定)。 */
    abstract val isRetryable: Boolean

    /** 人类可读的错误消息(含状态码分类 + body 摘要)。 */
    abstract val displayMessage: String

    /** 原始 throwable(可选,用于日志和调试)。 */
    open val cause: Throwable? = null

    /** 网络异常 / 超时 / 连接断开。 */
    data class Network(
        override val displayMessage: String,
        override val cause: Throwable? = null,
    ) : ProviderError() {
        override val httpCode: Int? = null
        override val isRetryable: Boolean = true
    }

    /**
     * 限流(429 / RESOURCE_EXHAUSTED)。
     *
     * @param retryAfterSec Retry-After 头解析出的秒数,null 表示未提供。
     *   重试时应优先使用此值,缺省时用指数退避。
     */
    data class RateLimit(
        override val httpCode: Int? = 429,
        val retryAfterSec: Int? = null,
        override val displayMessage: String,
        override val cause: Throwable? = null,
    ) : ProviderError() {
        override val isRetryable: Boolean = true
    }

    /** 服务器错误(500-599,含 Anthropic 529 overloaded)。 */
    data class ServerError(
        override val httpCode: Int,
        override val displayMessage: String,
        override val cause: Throwable? = null,
    ) : ProviderError() {
        override val isRetryable: Boolean = true
    }

    /** 认证/权限错误(401 / 403),不可重试。 */
    data class AuthError(
        override val httpCode: Int? = null,
        override val displayMessage: String,
        override val cause: Throwable? = null,
    ) : ProviderError() {
        override val isRetryable: Boolean = false
    }

    /** 请求参数错误(400 / 422 / 404),不可重试。 */
    data class InvalidRequest(
        override val httpCode: Int? = null,
        override val displayMessage: String,
        override val cause: Throwable? = null,
    ) : ProviderError() {
        override val isRetryable: Boolean = false
    }

    /** 取消(协程或 AbortSignal),不可重试。 */
    data class Cancelled(
        override val displayMessage: String = "cancelled",
        override val cause: Throwable? = null,
    ) : ProviderError() {
        override val httpCode: Int? = null
        override val isRetryable: Boolean = false
    }

    /** 未识别错误,默认不可重试。 */
    data class Unknown(
        override val httpCode: Int? = null,
        override val displayMessage: String,
        override val cause: Throwable? = null,
    ) : ProviderError() {
        override val isRetryable: Boolean = false
    }

    companion object {
        private const val TAG = "ProviderError"

        /**
         * 从 HTTP 状态码 + body + throwable 归一化为 [ProviderError]。
         *
         * 优先级:
         *  1. throwable 是 IOException → [Network]
         *  2. code == 401 / 403 → [AuthError]
         *  3. code == 400 / 422 / 404 → [InvalidRequest]
         *  4. code == 429 → [RateLimit](解析 Retry-After 头)
         *  5. code in 500..599 → [ServerError]
         *  6. code == null 且 throwable != null → [Network] 或 [Unknown]
         *  7. 其他 → [Unknown]
         *
         * @param code HTTP 状态码,null 表示非 HTTP 错误
         * @param body 错误响应 body(已截断)
         * @param retryAfterSec Retry-After 头秒数(由调用方解析后传入,避免本类依赖 OkHttp)
         * @param throwable 原始异常
         */
        fun from(
            code: Int?,
            body: String,
            retryAfterSec: Int? = null,
            throwable: Throwable? = null,
        ): ProviderError {
            // 1. IOException 优先归为 Network(即使 code 非 null,如 SSE 中断后 IOException)
            if (throwable is IOException) {
                return Network(
                    displayMessage = throwable.message ?: "network error",
                    cause = throwable,
                )
            }

            // 2. 按 HTTP 状态码分类
            if (code != null) {
                return when {
                    code == 401 || code == 403 -> AuthError(
                        httpCode = code,
                        displayMessage = buildDisplayMessage(code, body, "auth"),
                    )
                    code == 400 || code == 422 || code == 404 -> InvalidRequest(
                        httpCode = code,
                        displayMessage = buildDisplayMessage(code, body, "invalid request"),
                    )
                    code == 429 -> RateLimit(
                        httpCode = code,
                        retryAfterSec = retryAfterSec,
                        displayMessage = buildDisplayMessage(code, body, "rate limited"),
                    )
                    code in 500..599 -> ServerError(
                        httpCode = code,
                        displayMessage = buildDisplayMessage(code, body, "server error"),
                    )
                    else -> Unknown(
                        httpCode = code,
                        displayMessage = buildDisplayMessage(code, body, null),
                    )
                }
            }

            // 3. 无 HTTP code,有 throwable
            return if (throwable != null) {
                Unknown(
                    displayMessage = throwable.message ?: "unknown error",
                    cause = throwable,
                )
            } else {
                Unknown(displayMessage = body.ifBlank { "unknown error" })
            }
        }

        /**
         * 从 Throwable 归一化(无 HTTP 响应场景,如纯网络异常)。
         */
        fun from(throwable: Throwable): ProviderError {
            return from(code = null, body = "", throwable = throwable)
        }

        /**
         * 解析 Retry-After 头(秒数)。
         *
         * 支持两种格式:
         *  - 纯数字(秒):"120" → 120
         *  - HTTP-date:"Wed, 21 Oct 2026 07:28:00 GMT" → null(暂不解析,用指数退避兜底)
         */
        fun parseRetryAfter(headerValue: String?): Int? {
            if (headerValue.isNullOrBlank()) return null
            return headerValue.trim().toIntOrNull()
        }

        private fun buildDisplayMessage(code: Int, body: String, category: String?): String {
            return buildString {
                append("HTTP ").append(code)
                category?.let { append(" [").append(it).append("]") }
                if (body.isNotBlank()) {
                    val capped = if (body.length > 200) body.take(200) else body
                    append(": ").append(capped)
                }
            }
        }
    }
}

/**
 * v1.0.1: Provider 异常基类,携带归一化的 [ProviderError]。
 *
 * 各 Provider 在 HTTP 错误 / 解析失败时抛此异常的子类,上层 catch 后可直接取
 * [providerError] 字段做类型分支,无需字符串匹配。
 *
 * 注意:该异常仅用于"需要向上传递错误信息"的场景(如 completeText)。
 * 流式 streamChat 的错误通过 [ChatStreamEvent.Error] 传递,也应填充
 * [ChatStreamEvent.Error.providerError] 字段供消费方使用。
 */
open class ProviderException(
    val providerError: ProviderError,
    message: String = providerError.displayMessage,
    cause: Throwable? = providerError.cause,
) : RuntimeException(message, cause) {
    val httpCode: Int? get() = providerError.httpCode
    val isRetryable: Boolean get() = providerError.isRetryable
}

/**
 * v1.0.1: ChatStreamEvent.Error 扩展字段,携带归一化的 [ProviderError]。
 *
 * 现有代码用 [ChatStreamEvent.Error.message] + [ChatStreamEvent.Error.throwable] 传递错误,
 * 上层用字符串 contains 判断类型,脆弱。新增 [providerError] 字段后,上层可直接
 * `event.providerError?.let { it is ProviderError.RateLimit }` 做类型分支。
 *
 * 向后兼容:[providerError] 为可选字段,默认 null;现有调用方不传时行为不变。
 */
val ChatStreamEvent.Error.providerError: ProviderError?
    get() {
        // 优先从 throwable 提取(若 throwable 是 ProviderException)
        val pe = (throwable as? ProviderException)?.providerError
        if (pe != null) return pe
        // 兜底:从 message 字符串推断(兼容旧 Provider 未抛 ProviderException 的场景)
        return runCatching {
            inferFromMessage(message, throwable)
        }.getOrElse {
            Logger.w("ProviderError", "inferFromMessage failed: ${it.message}")
            null
        }
    }

/**
 * v1.0.1: 从错误消息字符串推断 [ProviderError](兜底路径)。
 *
 * 仅在 Provider 未抛 [ProviderException] 时使用,解析 message 中的关键字。
 * 保留向后兼容,新代码应直接构造 [ProviderException]。
 *
 * v1.0.1 (P4): 改为 public,供 ChatViewModel.classifyErrorType 使用,
 * 替代原字符串 contains 匹配。
 */
fun inferFromMessage(message: String, throwable: Throwable?): ProviderError? {
    if (message.isBlank() && throwable == null) return null
    val msg = message.lowercase()
    return when {
        throwable is IOException ||
            msg.contains("timeout") || msg.contains("network") || msg.contains("网络") ->
            ProviderError.Network(message, throwable)
        msg.contains("401") || msg.contains("403") ||
            msg.contains("api key") || msg.contains("unauthorized") ->
            ProviderError.AuthError(displayMessage = message, cause = throwable)
        msg.contains("429") || msg.contains("rate limit") ->
            ProviderError.RateLimit(displayMessage = message, cause = throwable)
        msg.contains("500") || msg.contains("502") || msg.contains("503") ||
            msg.contains("504") || msg.contains("529") || msg.contains("overloaded") ->
            ProviderError.ServerError(httpCode = 529, displayMessage = message, cause = throwable)
        msg.contains("400") || msg.contains("422") || msg.contains("404") ->
            ProviderError.InvalidRequest(displayMessage = message, cause = throwable)
        else -> null  // 不强推断为 Unknown,让上层保留原行为
    }
}
