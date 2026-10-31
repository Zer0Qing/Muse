package io.zer0.muse.tools

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.terminal.TermuxChannel

/**
 * v2.2.1 Termux 通道:termux_exec — 在用户安装的 Termux(完整 Linux 环境)中执行命令。
 *
 * 与另外两个执行通道的分工：
 *  - terminal_exec:应用沙盒(App 自身权限),轻量文件/脚本;
 *  - device_shell:设备级命令行(需 Shizuku/Root);
 *  - termux_exec(本工具):完整 Linux 环境(bash/apt/pip/gcc/ffmpeg……),需用户安装 Termux
 *    并按 [TermuxChannel.describe] 的引导完成授权;未就绪时如实返回引导文案。
 *
 * HIGH 风险,走既有审批链。
 *
 * 安全边界:[ShellCommandBoundary] 第一档 —— 与 [TerminalExecTool] 共用同一套「绝对不许出现」的
 * 最小集(删应用私有数据/系统分区、写块设备、格式化、下载即执行、提权与设备级命令等),
 * TRUSTED 模式与「始终允许本工具」都绕不过。第二档不在这里拦:有破坏力但可能是正常工作的命令
 * 照旧执行,由 HIGH 风险的逐次审批承担。
 */
object TermuxExecTool {
    const val NAME = "termux_exec"
    private const val TAG = "TermuxExec"

    fun toolDef() = ToolRegistry.ToolDef(
        name = NAME,
        description = "在 Termux(需用户安装并授权)的完整 Linux 环境中执行单条 bash 命令并返回输出。" +
            "适合 apt/pip/gcc/ffmpeg 等完整工具链任务;沙盒内轻量命令用 terminal_exec," +
            "设备级操控(点击/启停应用)用 device_shell。返回完整 exit 码与 stdout/stderr;" +
            "通道未就绪时返回配置引导。",
        parameters = mapOf(
            "command" to "必填。bash 命令,如 pkg list-installed / python myscript.py / ls ~",
            "timeout_ms" to "可选。超时毫秒,默认 60000,范围 1000-600000",
            "workdir" to "可选。工作目录(默认 Termux home)",
        ),
        required = setOf("command"),
        category = "built-in",
        parameterTypes = mapOf("timeout_ms" to "integer"),
        riskLevel = ToolRiskLevel.HIGH,
    )

    suspend fun execute(args: Map<String, String>, context: Context): String {
        val command = args["command"]?.trim().orEmpty()
        if (command.isEmpty()) return "Error: command is required."
        // 硬边界放在取通道之前:命中第一档时连 Termux 侧的进程都不会起。
        boundaryRejection(command)?.let { return it }
        val timeoutMs = (args["timeout_ms"]?.toLongOrNull() ?: TermuxChannel.DEFAULT_TIMEOUT_MS)
            .coerceIn(1_000L, TermuxChannel.MAX_TIMEOUT_MS)
        val workdir = args["workdir"]?.trim()?.takeIf { it.isNotEmpty() }

        val channel = TermuxChannel.get(context)
        val availability = channel.availability()
        if (availability !is TermuxChannel.Availability.Ready) {
            return TermuxChannel.describe(availability)
        }

        val result = channel.exec(command, timeoutMs, workdir)
        if (result.error != null) {
            return buildString {
                append("Termux 执行未完成: ").append(result.error)
                append("\n如尚未配置 Termux 外部调用,请在 Termux 中执行 ")
                append("`echo 'allow-external-apps = true' >> ~/.termux/termux.properties` 并重启 Termux,")
                append("并在 系统设置→应用→Termux→附加权限 授予「运行 Termux 命令」。")
            }
        }
        return buildString {
            append("[Termux] exit=").append(result.exitCode).append('\n')
            if (result.stderr.isNotBlank()) {
                append("--- stderr ---\n").append(result.stderr).append('\n')
            }
            val out = result.stdout
            if (out.isBlank()) {
                append("(无输出)")
            } else {
                append(out)
            }
        }
    }

    /**
     * 命令硬边界第一档:命中返回给模型的拒绝文案,未命中返回 null。
     *
     * `workspaceDir` 传 null:本工具在 Termux 自己的目录树里跑,当前目录还可能被 `workdir` 参数改写,
     * 应用工作区不是它的作用域,拿应用工作区当基准会把「工作区内」判错。null 时拿不到任何工作区
     * 豁免:命令碰到的每个绝对路径都按设备落点判(系统分区/应用私有数据/存储卷/设备节点照旧拦)。
     * 唯一退让是判不出落点的相对路径不参与拦截,那部分由 HIGH 风险的逐次审批卡上的命令原文承担。
     */
    internal fun boundaryRejection(command: String): String? {
        val reason = ShellCommandBoundary.forbidReason(command, workspaceDir = null) ?: return null
        Logger.w(TAG, "硬边界拒绝:$reason")
        return "Error: 命令被安全边界拒绝 — $reason。"
    }
}
