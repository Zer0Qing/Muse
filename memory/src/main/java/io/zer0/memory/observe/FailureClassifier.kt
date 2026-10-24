package io.zer0.memory.observe

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * D3-P1: 记忆管线失败分类。
 *
 * 只回答"这个失败要不要再试一次"这一个问题,不改控制流。
 * 分类结果仅用于观测记录(见 [PipelineLog]);真正的重试/补跑策略留待 P3。
 *
 * 分类语义:
 *  - [RETRYABLE] 瞬时故障 —— 网络抖动、限流、服务端 5xx、超时、LLM 空响应、未知异常兜底。
 *  - [PERMANENT] 确定性失败 —— 配置错误、鉴权、4xx、解析/序列化失败、参数非法。
 *  - [FATAL]     进程级/不可恢复 —— 协程取消、OOM、StackOverflow 等 VM 错误。
 *
 * 判定顺序:取消/VM 错误 → 网络/IO 类型 → **配额/余额终态 → 消息模式(可重试优先于永久)** → 异常类型兜底 → 默认可重试。
 * 之所以默认可重试:记忆任务宁多试一次,不可因未知原因被判死刑而静默丢弃。
 *
 * 配额/余额不足必须排在可重试特征之前:上游常用"rate limit exceeded: quota"这类
 * 混着限流词的消息表达"钱没了",重试只会继续吃 402。这与
 * `ProviderError.InsufficientQuota`(402 / isInsufficientQuotaBody 优先判定)同一口径。
 */
enum class FailureKind {
    RETRYABLE,
    PERMANENT,
    FATAL,
}

object FailureClassifier {

    /** 可重试的消息特征(小写匹配)。 */
    private val RETRYABLE_PATTERNS = listOf(
        "http 429",
        "http 5", // 5xx 服务端临时错误
        "timeout",
        "timed out",
        "rate limit",
        "too many requests",
        "限流",
        "connection reset",
        "connection refused",
        "broken pipe",
        "unexpected end of stream",
        "eof",
        "temporarily",
        "unavailable",
        "overloaded",
        "空响应",
        "empty response",
        "failed to connect",
    )

    /** 永久性失败的消息特征(小写匹配)。 */
    private val PERMANENT_PATTERNS = listOf(
        "http 400",
        "http 401",
        "http 402",
        "http 403",
        "http 404",
        "http 422",
        "unauthorized",
        "invalid",
        "unsupported",
        "no model",
        "未配置",
        "未找到 json",
        "json 解析失败",
        "parse",
        "解析失败",
        "schema",
        "备份恢复",
    )

    /**
     * 配额/余额/鉴权类终态特征(小写匹配)。
     *
     * 与 [PERMANENT_PATTERNS] 分开维护:这些信号代表"再试也不会好"的外部状态
     * (钱没了 / 凭证失效),必须排在 [RETRYABLE_PATTERNS] 之前判定,
     * 因为上游会把额度不足包装成带限流措辞的消息。
     */
    private val TERMINAL_QUOTA_OR_AUTH_PATTERNS = listOf(
        "http 402",
        "insufficient quota",
        "insufficient_quota",
        "quota exceeded",
        "exceeded your current quota",
        "余额不足",
        "额度不足",
        "余额已用尽",
        "http 401",
        "unauthorized",
        "invalid api key",
        "incorrect api key",
        "api key not valid",
    )

    /**
     * 把异常归类为 [FailureKind]。
     *
     * @param error 捕获到的异常;null 视为可重试(防御性,正常不应出现)
     */
    fun classify(error: Throwable?): FailureKind {
        if (error == null) return FailureKind.RETRYABLE
        // 协程取消是终态信号,不可重试
        if (error is CancellationException) return FailureKind.FATAL
        // 进程级错误:内存耗尽/栈溢出等,重试无意义
        if (error is VirtualMachineError || error is ThreadDeath) return FailureKind.FATAL
        // 网络/IO 瞬时错误
        if (error is SocketTimeoutException || error is IOException) return FailureKind.RETRYABLE

        val msg = (error.message ?: "").lowercase()
        // 配额/鉴权终态优先于可重试特征:"rate limit exceeded: quota" 这类消息混着
        // 限流词,但真实含义是额度已耗尽,重试无意义。
        if (TERMINAL_QUOTA_OR_AUTH_PATTERNS.any { msg.contains(it) }) return FailureKind.PERMANENT
        if (RETRYABLE_PATTERNS.any { msg.contains(it) }) return FailureKind.RETRYABLE
        if (PERMANENT_PATTERNS.any { msg.contains(it) }) return FailureKind.PERMANENT

        // 类型兜底:序列化/解析/参数类失败重试也不会变好
        if (error is SerializationException ||
            error is IllegalArgumentException ||
            error is IllegalStateException
        ) {
            return FailureKind.PERMANENT
        }
        // 未知异常:保守判可重试
        return FailureKind.RETRYABLE
    }

    /**
     * 是否为「配额/余额/鉴权」类终态失败。
     *
     * 供调用方决定要不要做延迟补跑:上游已在节流(如 agnes 的
     * "not asked again before 02:49"),我们 30s 后再打一次只会继续吃 402。
     * 只覆盖钱与凭证两类不可恢复状态,解析/参数类失败仍允许补跑(LLM 有随机性)。
     */
    fun isTerminalQuotaOrAuth(error: Throwable?): Boolean {
        val msg = (error?.message ?: "").lowercase()
        return msg.isNotBlank() && TERMINAL_QUOTA_OR_AUTH_PATTERNS.any { msg.contains(it) }
    }
}
