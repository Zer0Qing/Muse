package io.zer0.muse.tools.system

import io.zer0.common.Logger

/**
 * P3-3: Shell 执行器 — 三通道路由统一抽象。
 *
 * 路由优先级(SHIZUKU > ROOT):
 *  1. [ShizukuAuthorizer]:以 shell 权限执行(无需 root,更安全)
 *  2. [RootAuthorizer]:以 root 权限执行(降级方案,需 root)
 *  3. 均不可用:返回错误(ACCESSIBILITY 通道不支持 shell 执行)
 *
 * 设计:
 *  - [execute] 自动选择最优通道,调用方无需关心底层
 *  - [currentLevel] 返回当前可达的最高权限等级(供 UI 展示 + 工具能力判断)
 *  - [AccessibilityClient] 的 UI 操作不走本路由(由 [UIToolsSkill] 直接调用)
 *
 * 安全:
 *  - 执行前通过 [io.zer0.muse.tools.ToolPermissionResolver.isUnsafeCommand] 做硬边界校验
 *    (由调用方在 ToolOrchestrator 层完成,本类专注于通道路由)
 *  - Root 通道风险最高,仅在 Shizuku 不可用时降级使用
 */
class ShellExecutor(
    private val shizukuAuthorizer: ShizukuAuthorizer,
    private val rootAuthorizer: RootAuthorizer,
    private val accessibilityClient: AccessibilityClient,
) {

    companion object {
        private const val TAG = "ShellExecutor"

        /**
         * 纯逻辑:根据三通道可用性选择最高权限等级(ROOT 优先)。
         * 抽取为静态函数便于单元测试(不依赖 Android runtime)。
         */
        fun selectLevel(rootOk: Boolean, shizukuOk: Boolean, a11yOk: Boolean): AndroidPermissionLevel = when {
            rootOk -> AndroidPermissionLevel.ROOT
            shizukuOk -> AndroidPermissionLevel.SHIZUKU
            a11yOk -> AndroidPermissionLevel.ACCESSIBILITY
            else -> AndroidPermissionLevel.NONE
        }

        /**
         * 纯逻辑:根据三通道可用性选择 shell 执行通道(SHIZUKU 优先,ROOT 降级)。
         * 注意:与 [selectLevel] 的优先级不同 — shell 通道优先 Shizuku(更安全),
         * 而 [selectLevel] 优先 ROOT(等级最高)。
         */
        fun selectShellChannel(shizukuOk: Boolean, rootOk: Boolean, a11yOk: Boolean): Channel = when {
            shizukuOk -> Channel.SHIZUKU
            rootOk -> Channel.ROOT
            a11yOk -> Channel.ACCESSIBILITY
            else -> Channel.NONE
        }
    }

    /** 选中的执行通道。 */
    enum class Channel {
        NONE,
        ACCESSIBILITY,  // 仅支持 UI 操作,不支持 shell
        SHIZUKU,
        ROOT,
    }

    /**
     * 当前可达的最高权限等级。
     * - ROOT 可用 -> ROOT
     * - SHIZUKU 可用且已授权 -> SHIZUKU
     * - ACCESSIBILITY 已启用 -> ACCESSIBILITY
     * - 否则 NONE
     */
    fun currentLevel(): AndroidPermissionLevel = selectLevel(
        rootOk = rootAuthorizer.checkPermission(),
        shizukuOk = shizukuAuthorizer.checkPermission(),
        a11yOk = accessibilityClient.isEnabled(),
    )

    /**
     * 当前选中的 shell 执行通道(SHIZUKU / ROOT;ACCESSIBILITY 不支持 shell)。
     * SHIZUKU 优先(更安全),ROOT 降级。
     */
    fun currentShellChannel(): Channel = selectShellChannel(
        shizukuOk = shizukuAuthorizer.checkPermission(),
        rootOk = rootAuthorizer.checkPermission(),
        a11yOk = accessibilityClient.isEnabled(),
    )

    /**
     * 执行 shell 命令(自动路由到可用通道)。
     *
     * @param command 要执行的命令字符串
     * @return [ExecResult] 包含通道、退出码与输出
     */
    fun execute(command: String): ExecResult {
        // 1. 优先 Shizuku(shell 权限,无需 root)
        if (shizukuAuthorizer.checkPermission()) {
            val r = shizukuAuthorizer.execute(command)
            return ExecResult(Channel.SHIZUKU, r.exitCode, r.stdout, r.stderr)
        }
        // 2. 降级 Root(需 root)
        if (rootAuthorizer.checkPermission()) {
            val r = rootAuthorizer.execute(command)
            return ExecResult(Channel.ROOT, r.exitCode, r.stdout, r.stderr)
        }
        // 3. 均不可用:ACCESSIBILITY 通道不支持 shell
        Logger.w(TAG, "无可用 shell 通道(需 Shizuku 或 Root)")
        return ExecResult(
            channel = currentShellChannel(),
            exitCode = -1,
            stdout = "",
            stderr = "无可用 shell 执行通道。请启用 Shizuku(推荐)或 Root 权限。",
        )
    }

    /** 命令执行结果。 */
    data class ExecResult(
        val channel: Channel,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        val isSuccess: Boolean get() = exitCode == 0
    }
}
