package io.zer0.ai.core

import io.zer0.common.ErrorCode
import io.zer0.common.Logger
import io.zer0.common.toMessage
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
 *  - [InvalidRequest]: 400 / 413 / 422 / 404,不可重试(请求本身有问题,重试无意义)
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
         * v2.5.3 (P2-3): 判断一条错误详情是否为「思考块绑定到不同会话」类 400。
         *
         * Claude 的 thinking 块签名绑定会话前缀；用户编辑历史后，“拿旧思考块 + 新上下文”
         * 发给上游会返回 400（典型文案：“Thinking block is bound to a different conversation”、
         * “Invalid signature”、“signature ... not valid” 等）。这类错误可自愈：剔除对应思考块后重试一次。
         *
         * 只匹配这一种诊断，其他 400（参数错、鉴权等）照常上抛。
         */
        fun isThinkingBlockBondError(body: String?): Boolean {
            if (body.isNullOrBlank()) return false
            val lower = body.lowercase()
            val mentionsThinkingOrSignature =
                lower.contains("thinking") || lower.contains("signature") || lower.contains("thought")
            val isBondError =
                lower.contains("bound to a different conversation") ||
                    lower.contains("different conversation") ||
                    lower.contains("invalid signature") ||
                    lower.contains("signature is invalid") ||
                    lower.contains("signature not valid") ||
                    lower.contains("thought_signature") ||
                    (lower.contains("thinking block") && lower.contains("invalid"))
            return mentionsThinkingOrSignature && isBondError
        }

        /**
         * 从 HTTP 状态码 + body + throwable 归一化为 [ProviderError]。
         *
         * 优先级:
         *  1. throwable 是 IOException → [Network]
         *  2. code == 401 / 403 → [AuthError]
         *  3. code == 400 / 413 / 422 / 404 → [InvalidRequest]
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
        fun from(code: Int?, body: String, retryAfterSec: Int? = null, throwable: Throwable? = null): ProviderError = when {
            // IOException 优先归为 Network(即使 code 非 null,如 SSE 中断后 IOException)。
            throwable is IOException ->
                Network(
                    displayMessage = safeErrorDetail(throwable.message.orEmpty()) ?: "network error",
                    cause = throwable,
                )
            code != null -> fromHttpStatus(code, body, retryAfterSec)
            throwable != null ->
                Unknown(
                    displayMessage = safeErrorDetail(throwable.message.orEmpty()) ?: "unknown error",
                    cause = throwable,
                )
            else -> Unknown(displayMessage = body.ifBlank { "unknown error" })
        }

        private fun fromHttpStatus(code: Int, body: String, retryAfterSec: Int?): ProviderError = when {
            code == 401 || code == 403 -> AuthError(
                httpCode = code,
                displayMessage = buildDisplayMessage(code, body, "auth"),
            )
            code == 400 || code == 413 || code == 422 || code == 404 -> InvalidRequest(
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

        /**
         * 从 Throwable 归一化(无 HTTP 响应场景,如纯网络异常)。
         */
        fun from(throwable: Throwable): ProviderError {
            return from(code = null, body = "", throwable = throwable)
        }

        /**
         * 解析 Retry-After 头。
         *
         * RFC 7231 允许两种格式:
         *  - 纯数字(秒):"120" → 120
         *  - HTTP-date:"Wed, 21 Oct 2026 07:28:00 GMT" → 距该时间的秒数
         *
         * 日期形式按向上取整计算，避免把仍不足一秒的等待误判成已到期；过去的日期返回
         * 0，表示服务端允许立即重试。解析失败仍返回 null，让调用方使用指数退避兜底。
         */
        fun parseRetryAfter(headerValue: String?): Int? {
            if (headerValue.isNullOrBlank()) return null
            val value = headerValue.trim()
            value.toLongOrNull()?.let { seconds ->
                return seconds
                    .takeIf { it >= 0L && it <= Int.MAX_VALUE.toLong() }
                    ?.toInt()
            }
            val target = runCatching {
                ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            }.getOrNull() ?: return null
            val delayMs = target.toEpochMilli() - System.currentTimeMillis()
            if (delayMs <= 0L) return 0
            val seconds = (delayMs + 999L) / 1000L
            return seconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        private fun buildDisplayMessage(code: Int, body: String, category: String?): String {
            return buildString {
                append("HTTP ").append(code)
                category?.let { append(" [").append(it).append("]") }
                safeErrorDetail(body)?.let { capped ->
                    append(": ").append(capped)
                }
            }
        }

        /**
         * Keep provider diagnostics useful without copying a response body that may echo
         * prompts, tool arguments, API keys, or redirect parameters.
         */
        internal fun safeErrorDetail(body: String): String? {
            if (body.isBlank()) return null
            val candidate =
                runCatching {
                    val root = Json.parseToJsonElement(body)
                    val error = (root as? JsonObject)?.get("error")
                    val errorObject = error as? JsonObject
                    val message = (errorObject?.get("message") as? JsonPrimitive)?.content
                        ?: (root as? JsonObject)?.get("message")?.let { (it as? JsonPrimitive)?.content }
                    val type = (errorObject?.get("type") as? JsonPrimitive)?.content
                    val code = (errorObject?.get("code") as? JsonPrimitive)?.content
                    listOfNotNull(message, type?.let { "type=$it" }, code?.let { "code=$it" })
                        .joinToString(" | ")
                        .ifBlank { body }
                }.getOrDefault(body)
            val redacted = redactSecrets(candidate)
            return redacted.take(200).ifBlank { null }
        }

        private fun redactSecrets(text: String): String {
            var value = text
            value = Regex(
                """(?i)(api[\s_-]?key|token|password|secret|authorization|cookie)\s*[:=]?\s*["']?[A-Za-z0-9._~+/=-]{6,}""",
            ).replace(value) { "${it.groupValues[1]}=[REDACTED]" }
            value = Regex("""(?i)\b(?:sk|rk|pk|ak)-[A-Za-z0-9][A-Za-z0-9._-]{5,}\b""")
                .replace(value, "[REDACTED]")
            value = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{8,}\b""")
                .replace(value, "Bearer [REDACTED]")
            return value
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
 *
 * v1.0.27 Phase 5-A: 现已不推荐使用,Provider 应直接抛 [ProviderException] 让类型路径
 * `(throwable as? ProviderException)?.providerError` 生效。保留作为向后兼容兜底,
 * 供未迁移的 Provider 使用。
 */
@Deprecated(
    "Provider 应直接抛 ProviderException,用 (throwable as? ProviderException)?.providerError 替代字符串推断",
    ReplaceWith("(throwable as? ProviderException)?.providerError"),
)
fun inferFromMessage(message: String, throwable: Throwable?): ProviderError? {
    if (message.isBlank() && throwable == null) return null
    val safeMessage = ProviderError.safeErrorDetail(message) ?: message.take(200)
    val msg = safeMessage.lowercase()
    return when {
        throwable is IOException ||
            msg.contains("timeout") || msg.contains("network") || msg.contains("网络") ->
            ProviderError.Network(safeMessage, throwable)
        msg.contains("401") || msg.contains("403") ||
            msg.contains("api key") || msg.contains("unauthorized") ->
            ProviderError.AuthError(displayMessage = safeMessage, cause = throwable)
        msg.contains("429") || msg.contains("rate limit") ->
            ProviderError.RateLimit(displayMessage = safeMessage, cause = throwable)
        msg.contains("500") || msg.contains("502") || msg.contains("503") ||
            msg.contains("504") || msg.contains("529") || msg.contains("overloaded") ->
            ProviderError.ServerError(httpCode = 529, displayMessage = safeMessage, cause = throwable)
        msg.contains("400") || msg.contains("422") || msg.contains("404") ->
            ProviderError.InvalidRequest(displayMessage = safeMessage, cause = throwable)
        else -> null // 不强推断为 Unknown,让上层保留原行为
    }
}

/**
 * v1.0.27 Phase 5-A: 把 [ErrorCode] 映射到类型化的 [ProviderError]。
 *
 * 用于激活 [ProviderException] 类型路径 — Provider 抛 [ProviderException] 后,
 * 消费端用 `(throwable as? ProviderException)?.providerError` 即可拿到强类型错误,
 * 不再需要 [inferFromMessage] 字符串解析。
 *
 * [displayMessage] 保留原 [ErrorCode.toMessage] 字符串,向后兼容 —
 * 即便消费端仍走 inferFromMessage 路径,也能解析出对应类型。
 *
 * 映射策略 (基于 ErrorCode 语义):
 *  - 请求/参数错误 → [ProviderError.InvalidRequest] (不可重试)
 *  - 认证/权限 → [ProviderError.AuthError] (不可重试)
 *  - 限流/资源耗尽 → [ProviderError.RateLimit] (可重试)
 *  - 服务端错误 → [ProviderError.ServerError] (可重试)
 *  - 网络/超时 → [ProviderError.Network] (可重试)
 *  - 其他 → [ProviderError.Unknown] (不可重试)
 */
fun ErrorCode.toProviderError(vararg args: Any?): ProviderError {
    val message = toMessage(*args)
    return when (this) {
        // 请求/参数错误 (不可重试)
        ErrorCode.NO_PROVIDER_CONFIGURED,
        ErrorCode.NO_MODEL_SELECTED,
        ErrorCode.INVALID_RESPONSE,
        ErrorCode.INVALID_ARGUMENT,
        ErrorCode.NOT_FOUND,
        ErrorCode.IMAGE_EMPTY_RESPONSE,
        ErrorCode.IMAGE_NO_RESULTS,
        ErrorCode.IMAGE_UNSUPPORTED_MODEL,
        ErrorCode.IMAGE_INVALID_URI,
        ErrorCode.VERTEX_AI_CONFIG_INVALID,
        ErrorCode.MEMORY_CONFIG_INVALID,
        ErrorCode.MEMORY_TOKEN_BUDGET_INVALID,
        -> ProviderError.InvalidRequest(displayMessage = message)

        // 认证错误 (不可重试)
        ErrorCode.AUTH_FAILED,
        ErrorCode.IMAGE_API_KEY_MISSING,
        ErrorCode.VERTEX_AI_TOKEN_FAILED,
        -> ProviderError.AuthError(displayMessage = message)

        // 限流/资源耗尽 (可重试)
        ErrorCode.RATE_LIMITED,
        ErrorCode.RESOURCE_EXHAUSTED,
        ErrorCode.IMAGE_RESPONSE_TOO_LARGE,
        ErrorCode.IMAGE_REFERENCE_TOO_LARGE,
        -> ProviderError.RateLimit(displayMessage = message)

        // 服务端错误 (可重试)
        ErrorCode.SERVICE_UNAVAILABLE,
        ErrorCode.OVERLOADED,
        ErrorCode.API_ERROR,
        -> ProviderError.ServerError(httpCode = 500, displayMessage = message)

        // 网络/超时 (可重试)
        ErrorCode.REQUEST_TIMEOUT,
        ErrorCode.STREAM_INTERRUPTED,
        ErrorCode.NETWORK_ERROR,
        ErrorCode.IMAGE_REFERENCE_DOWNLOAD_FAILED,
        -> ProviderError.Network(displayMessage = message)

        // 权限 (不可重试,归为 AuthError)
        ErrorCode.PERMISSION_DENIED,
        ErrorCode.PRECONDITION_FAILED,
        -> ProviderError.AuthError(displayMessage = message)

        // 图像生成失败 (其他,不可重试)
        ErrorCode.IMAGE_GEN_FAILED -> ProviderError.Unknown(displayMessage = message)
    }
}

/**
 * v1.0.27 Phase 5-A: 便捷构造 [ProviderException]。
 *
 * 等价于 `ProviderException(errorCode.toProviderError(*args))`,
 * 让 Provider 的 throw 点更简洁:
 *
 * ```kotlin
 * // 旧:
 * throw RuntimeException(ErrorCode.INVALID_RESPONSE.toMessage("empty_body", resp.code))
 * // 新:
 * throw errorCode.toProviderException("empty_body", resp.code)
 * ```
 */
fun ErrorCode.toProviderException(vararg args: Any?): ProviderException = ProviderException(toProviderError(*args))
