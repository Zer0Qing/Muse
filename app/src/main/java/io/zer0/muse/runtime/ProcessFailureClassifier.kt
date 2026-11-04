package io.zer0.muse.runtime

/**
 * 启动后活过这个窗口才算"跑起来了"。
 *
 * 与首事件超时同量级，不是照抄参考项目的数字：Muse 的运行时是**应用内安装**的，
 * 装完第一次拉不起来通常是环境缺东西，而不是"慢"。
 */
internal const val HEALTHY_WINDOW_MS = 120_000L

/**
 * 纯判定。参数都是**事实**，不读时钟也不读文件系统，所以可表驱动单测。
 *
 * @param exitCode 进程退出码；取不到时传 null（例如被强杀后 waitFor 失败）
 * @param everReady 是否曾经进入过就绪态（MCP=完成过握手；一次性 exec=产出过有效输出）
 * @param uptimeMs 就绪到退出的毫秒差；未就绪过传 null
 * @param outputTail 输出尾部（用于识别磁盘满等特征），由调用方截断
 * @param timedOut 是否由我们超时终止
 * @param userRequestedStop 调用方明确知道"这是用户停的"
 * @param healthyWindowMs 早/晚崩溃分界，可注入以便测试
 */
internal fun classifyProcessExit(
    exitCode: Int?,
    everReady: Boolean,
    uptimeMs: Long? = null,
    outputTail: String = "",
    timedOut: Boolean = false,
    userRequestedStop: Boolean = false,
    healthyWindowMs: Long = HEALTHY_WINDOW_MS,
): ProcessFailureKind = when {
    // 顺序即优先级：用户停止压倒一切（否则任何计数型恢复策略都会被正常操作污染）。
    userRequestedStop -> ProcessFailureKind.USER_STOPPED
    timedOut -> ProcessFailureKind.TIMED_OUT
    containsOutOfSpace(outputTail) -> ProcessFailureKind.NO_SPACE
    // 从没就绪：非零/取不到码＝起不来；退出码 0 却安静走掉＝自己退的，
    // 报"没产出结果"而不是"崩溃"，否则会把人往"环境坏了"的方向带。
    !everReady -> if (exitCode == null || exitCode != 0) {
        ProcessFailureKind.NEVER_STARTED
    } else {
        ProcessFailureKind.NO_RESULT
    }
    uptimeMs != null && uptimeMs < healthyWindowMs -> ProcessFailureKind.CRASHED_EARLY
    exitCode == null || exitCode != 0 -> ProcessFailureKind.CRASHED_LATE
    else -> ProcessFailureKind.NO_RESULT
}

private val OUT_OF_SPACE = listOf("ENOSPC", "No space left", "nospace", "磁盘空间不足")

private fun containsOutOfSpace(tail: String): Boolean {
    if (tail.isBlank()) return false
    return OUT_OF_SPACE.any { marker -> tail.contains(marker, ignoreCase = true) }
}
