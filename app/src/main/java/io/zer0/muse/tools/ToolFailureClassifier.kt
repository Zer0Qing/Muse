package io.zer0.muse.tools

/**
 * 工具失败回执的结构化分类。
 *
 * ## 为什么需要它
 *
 * 助手自审：「工具失败只允许我修正一次 —— 这条约束本身是对的，但如果错误信息是含糊的
 * （比如"参数不合法"），我就只能猜，一猜就废」。
 *
 * 现状：失败时只注入一段固定的四级优先级引导（参数错→修正、权限→换工具、网络→重试、
 * 其他→告知用户），模型得自己从错误文本里猜这属于哪一级。猜错就浪费掉唯一一次修正机会。
 *
 * 这里把「这条错误属于哪一类、能不能安全重试」算出来，直接附在失败回执里。
 * 分类只依赖错误文本（各工具返回值没有统一结构），所以规则要保守：
 * 判断不了就归 [FailureKind.UNKNOWN] 且 `retryable=false` —— 宁可让模型来问，
 * 也不要让它对不可重试的失败反复重试。
 */
object ToolFailureClassifier {

    /** 失败类别。 */
    enum class FailureKind {
        /** 参数/路径/格式错误 —— 改参数后重试有意义。 */
        INVALID_ARGUMENT,

        /** 缺权限或通道未就绪 —— 重试无用，要用户去开。 */
        PERMISSION,

        /** 资源不存在（文件/记录/id）。 */
        NOT_FOUND,

        /** 网络或上游暂时不可用 —— 通常可重试一次。 */
        TRANSIENT,

        /** 超时。 */
        TIMEOUT,

        /** 被取消或中断，非错误。 */
        CANCELLED,

        /** 命中安全/审批限制。 */
        BLOCKED,

        /** 无法归类的失败。 */
        UNKNOWN,
    }

    /** 一条失败回执的结构化结论。 */
    data class Verdict(
        val kind: FailureKind,
        val retryable: Boolean,
        /** 给模型的一句话建议，直接拼进回执。 */
        val hint: String,
    )

    private val TIMEOUT_MARKERS = listOf("[超时]", "超时", "timed out", "timeout")
    private val CANCELLED_MARKERS = listOf("[中断]", "[取消]", "已取消", "cancelled", "canceled", "aborted")
    private val PERMISSION_MARKERS = listOf(
        "权限", "未授权", "permission denied", "unauthorized", "forbidden", "403",
        "shizuku", "root", "无障碍", "通知使用权", "使用情况访问",
    )
    private val NOT_FOUND_MARKERS = listOf(
        "不存在",
        "未找到",
        "找不到",
        "not found",
        "no such file",
        "404",
    )
    private val TRANSIENT_MARKERS = listOf(
        "网络", "连接失败", "连接超时", "timeout connecting", "connection", "503", "502", "429",
        "rate limit", "too many requests", "暂时", "稍后重试",
    )
    private val BLOCKED_MARKERS = listOf(
        "审批",
        "拒绝",
        "被拦截",
        "安全",
        "越权",
        "denied",
        "blocked",
        "approval",
    )
    private val INVALID_ARGUMENT_MARKERS = listOf(
        "缺少必填", "缺少参数", "参数", "格式", "不合法", "非法", "invalid", "must be", "required",
        "解析失败", "类型错误",
    )

    /**
     * 分类规则表。**顺序即优先级**：越靠前越先判定。
     *
     * 超时/取消必须排在权限之前 —— 取消消息里常夹带「授权」等词，
     * 先判权限会把它误报成「缺权限」。
     */
    private val RULES: List<Rule> = listOf(
        Rule(FailureKind.TIMEOUT, true, TIMEOUT_MARKERS, "超时：可原样重试一次；仍超时就把超时事实告诉用户，不要换着参数反复试。"),
        Rule(FailureKind.CANCELLED, false, CANCELLED_MARKERS, "操作已取消，不是失败。不要重试，按用户当前意图继续。"),
        Rule(
            FailureKind.PERMISSION,
            false,
            PERMISSION_MARKERS,
            "缺权限/通道未就绪：重试无用。先调 tool_status 看缺什么，再把开通路径告诉用户。",
        ),
        Rule(FailureKind.BLOCKED, false, BLOCKED_MARKERS, "被安全策略或审批拦下：不要绕过，向用户说明被拦原因并等指示。"),
        Rule(
            FailureKind.NOT_FOUND,
            false,
            NOT_FOUND_MARKERS,
            "目标不存在：先确认路径/id 是否正确（必要时列目录或搜索），不要用同一个值重试。",
        ),
        Rule(FailureKind.TRANSIENT, true, TRANSIENT_MARKERS, "暂时性故障：可重试一次；仍失败就如实告知，不要连续重试消耗时间。"),
        Rule(
            FailureKind.INVALID_ARGUMENT,
            true,
            INVALID_ARGUMENT_MARKERS,
            "参数问题：按错误原文改参数后重试一次；改不动就问用户，不要盲猜。",
        ),
    )

    /** 一条分类规则。 */
    private class Rule(
        val kind: FailureKind,
        val retryable: Boolean,
        val markers: List<String>,
        val hint: String,
    ) {
        fun matches(lower: String): Boolean = markers.any { lower.contains(it) }
    }

    private val UNKNOWN = Verdict(
        kind = FailureKind.UNKNOWN,
        retryable = false,
        hint = "无法判断原因：不要盲目重试。先看错误原文；确实需要继续时先向用户说明情况。",
    )

    /**
     * 分类一条失败结果。
     *
     * @param result 工具返回的原始文本（未注入引导语的版本）
     * @param timedOut 是否由执行器的超时逻辑判定（比文本更可靠）
     */
    fun classify(result: String, timedOut: Boolean = false): Verdict {
        val lower = result.trimStart().lowercase()
        // 执行器已判定超时时,文本再像别的类别也按超时处理 —— 那是更可靠的信号。
        val forced = if (timedOut) RULES.first() else null
        val rule = forced ?: RULES.firstOrNull { it.matches(lower) }
        return rule?.let { Verdict(it.kind, it.retryable, it.hint) } ?: UNKNOWN
    }

    /**
     * 生成拼进失败回执的结构化行。
     *
     * 形如：`[失败分类] kind=permission retryable=false hint=...`
     * 用 key=value 而不是自然语言，方便模型直接读取，也便于日志检索。
     */
    fun receiptLine(verdict: Verdict): String =
        "[失败分类] kind=${verdict.kind.name.lowercase()} retryable=${verdict.retryable} hint=${verdict.hint}"
}
