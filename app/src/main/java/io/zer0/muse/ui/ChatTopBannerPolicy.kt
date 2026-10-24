package io.zer0.muse.ui

/**
 * 聊天页顶部横幅（互斥显示，同一时刻只有一个）。
 *
 * 优先级（高 → 低）：
 *  1. [ERROR] —— 错误必须最先被看到。v2.6.8 起错误压在压缩之上：
 *     压缩只是运行反馈，错误需要用户决策/知情，被压缩 Banner 遮挡会让错误
 *     "在后台悄悄过期"（旧的 5 秒自动消失叠加压缩遮挡，错误可能一次都没出现）。
 *  2. [COMPRESSION] —— 压缩/记忆更新进度，运行反馈，仅次于错误。
 *  3. [PENDING_TOOLS] —— 断点续传（未完成工具调用），需要用户选择恢复或丢弃。
 *  4. [NOT_CONFIGURED] —— 未配置模型服务的常驻轻提示。
 *  5. [NONE] —— 不显示任何横幅。
 *
 * 互斥由本函数决定，UI 侧只做 `topBanner == X` 的显隐判断；新增横幅时
 * 必须在这里补一档并同步注释，不要在 Composable 里另加互斥条件。
 */
internal enum class ChatTopBanner {
    ERROR,
    COMPRESSION,
    PENDING_TOOLS,
    NOT_CONFIGURED,
    NONE,
}

internal fun resolveChatTopBanner(
    isCompressing: Boolean,
    showPendingResume: Boolean,
    hasErrors: Boolean,
    isConfigured: Boolean,
): ChatTopBanner = when {
    // 错误优先于压缩：压缩期间出错也必须立刻可见（错误可能决定用户是否还要等压缩）。
    hasErrors -> ChatTopBanner.ERROR
    isCompressing -> ChatTopBanner.COMPRESSION
    showPendingResume -> ChatTopBanner.PENDING_TOOLS
    !isConfigured -> ChatTopBanner.NOT_CONFIGURED
    else -> ChatTopBanner.NONE
}

/**
 * 可恢复错误的自动消失阅读窗口（毫秒）。
 *
 * 从 5 秒提到 15 秒：一条两行报错按常规阅读速度约 5 秒读完，再留出
 * 注意力切换（流式输出刚结束、视线还在消息区）的余量；同时又不足以让
 * 堆积的错误长期占住顶部。
 *
 * 计时不在 [addError] 那一刻启动，而是在错误真正成为顶部横幅之后启动
 * （见 [resolveErrorVisibility]）—— 否则压缩 Banner 挡住的 15 秒仍然白算。
 */
internal const val ERROR_AUTO_DISMISS_MS = 15_000L

/**
 * 错误"等到真正可见"的兜底上限（毫秒）。
 *
 * 自动消失只在该错误确实是顶部横幅显示者、且应用在前台时才开始读秒。
 * 若因任何原因迟迟不可见（长时间压缩、应用退到后台），最多等这么久就放弃
 * 这次自动消失 —— 宁可让用户手动关闭，也不让错误消失在没人看见的时刻。
 */
internal const val ERROR_VISIBLE_WAIT_MS = 60_000L

/**
 * 需要用户决策、禁止自动消失的错误类别。
 *
 * - [ChatErrorType.TOOL_ERROR]：工具执行失败，用户要决定重试/换方案/丢弃。
 * - [ChatErrorType.MODEL_ERROR]：模型侧返回异常，需要用户判断换模型还是改参数。
 * - [ChatErrorType.API_KEY]：凭证/余额问题，只能由用户去设置页处理。
 */
private val STICKY_ERROR_TYPES = setOf(
    ChatErrorType.TOOL_ERROR,
    ChatErrorType.MODEL_ERROR,
    ChatErrorType.API_KEY,
)

/**
 * 该错误是否允许自动消失。
 *
 * 只有"可恢复 + 不属于需要用户决策的类别"才自动消失；其余一律常驻到用户关闭。
 */
internal fun shouldAutoDismissError(type: ChatErrorType, isRecoverable: Boolean): Boolean = isRecoverable && type !in STICKY_ERROR_TYPES

/** 错误横幅在某一时刻的可见性判定结果。 */
internal enum class ErrorVisibility {
    /** 还不能开始计时（不在前台，或被更高优先级横幅占位）。 */
    PENDING,

    /** 用户此刻能看到这条错误横幅。 */
    VISIBLE,

    /** 错误已经不在列表里（用户已关闭/已被清空），无需再计时。 */
    GONE,
}

/**
 * 判定某条错误当前是否"真的显示在用户眼前"。
 *
 * 自动消失的计时必须以这个函数为闸门，不能只看 `addError` 的调用时刻。
 * 这里复用 [resolveChatTopBanner] 而不是直接判 `hasErrors`：横幅互斥规则
 * 以后若再调整（例如某类错误让位给别的横幅），计时逻辑自动跟着变，不会
 * 出现"策略改了、计时还在按旧规则跑"的两套口径。
 *
 * 单出口写法：三个前置条件先归约成"现在能不能看得见"，只在最后返回一次。
 * 这样 `when` 里不会攒出多个 return，新增条件也只改前半段。
 */
internal fun resolveErrorVisibility(
    errorStillPresent: Boolean,
    appInForeground: Boolean,
    isCompressing: Boolean,
    showPendingResume: Boolean,
    isConfigured: Boolean,
): ErrorVisibility {
    val bannerIsError = resolveChatTopBanner(
        isCompressing = isCompressing,
        showPendingResume = showPendingResume,
        hasErrors = true,
        isConfigured = isConfigured,
    ) == ChatTopBanner.ERROR
    // 已消失 → GONE；还在但用户此刻看不到（后台/被更高优先级横幅占用）→ PENDING。
    val visible = errorStillPresent && appInForeground && bannerIsError
    return when {
        !errorStillPresent -> ErrorVisibility.GONE
        visible -> ErrorVisibility.VISIBLE
        else -> ErrorVisibility.PENDING
    }
}
