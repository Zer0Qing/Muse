package io.zer0.muse.automation.executors

/**
 * Root 授权请求的纯逻辑 —— 命令构造、输出判定与失败原因映射。
 *
 * [RootExecutor.requestRootAccess] 只负责子进程与超时编排;这里全部是不碰 Android/进程的
 * 纯函数,单测据此覆盖各失败路径,无需真的执行 `su`(root 弹窗无法在测试环境复现)。
 */

/** Root 授权请求失败原因,UI 据此给出可读提示。 */
enum class RootRequestFailure {
    /** 设备没有 su 二进制,不会触发 root 管理器弹窗,也无法获得 Root。 */
    NO_SU_BINARY,

    /** 等待 root 管理器授权超时(弹窗一直未被处理)。 */
    TIMEOUT,

    /** 用户拒绝授权,或 su 以非 0 退出码结束。 */
    DENIED,

    /** 子进程启动/读取异常等未分类失败。 */
    ERROR,
}

/** Root 授权请求结果。[granted] 为 true 时 [failure] 必为 null。 */
data class RootRequestResult(
    val granted: Boolean,
    val failure: RootRequestFailure? = null,
) {
    companion object {
        /** 已拿到 uid=0。 */
        val Granted: RootRequestResult = RootRequestResult(granted = true)

        /** 请求失败,附失败原因。 */
        fun failed(reason: RootRequestFailure): RootRequestResult =
            RootRequestResult(granted = false, failure = reason)
    }
}

/** su 探针命令:root 管理器会因这次调用弹出授权对话框,同时输出当前 uid。 */
internal const val ROOT_PROBE_COMMAND = "id"

/**
 * 构造 su 探针的参数表。
 *
 * 与 [RootExecutor.shellPrefix] 的调用约定一致:`su -c <cmd>`;固定 argv 走 ProcessBuilder,
 * 不经过 shell 文本拼接,避免命令注入面。
 */
internal fun rootProbeArgs(prefix: List<String>): List<String> =
    prefix + listOf("-c", ROOT_PROBE_COMMAND)

/** 探针输出是否表明已获得 root(uid=0)。 */
internal fun isRootGranted(output: String?): Boolean = output?.contains("uid=0") == true

/**
 * 探针终态判定:退出码 0 且输出含 uid=0 才算授权成功。
 *
 * 部分 ROM 的 su 缺失时以 "not found" 文本报错,统一归为 [RootRequestFailure.NO_SU_BINARY],
 * 让 UI 提示"设备无 su"而不是笼统的授权被拒。
 */
internal fun evaluateRootProbe(exitCode: Int, output: String): RootRequestResult = when {
    exitCode == 0 && isRootGranted(output) -> RootRequestResult.Granted
    output.contains("not found", ignoreCase = true) ->
        RootRequestResult.failed(RootRequestFailure.NO_SU_BINARY)
    else -> RootRequestResult.failed(RootRequestFailure.DENIED)
}

/** 探针未在超时内结束(root 弹窗无人处理)→ 超时失败。 */
internal fun rootProbeTimeout(): RootRequestResult =
    RootRequestResult.failed(RootRequestFailure.TIMEOUT)

/** 子进程启动/读取异常 → 失败原因分类。 */
internal fun classifyRootProbeException(error: Throwable): RootRequestFailure = when (error) {
    is SecurityException -> RootRequestFailure.DENIED
    else -> RootRequestFailure.ERROR
}
