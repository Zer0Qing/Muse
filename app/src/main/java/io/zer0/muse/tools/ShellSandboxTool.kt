package io.zer0.muse.tools

import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v1.0.47 P2-6: 本地 Shell 沙箱工具(仅 Agent Mode 可用)。
 *
 * Android 上无 root 的 Shell 能力有限,但可执行白名单内的只读/安全命令,
 * 让 AI 能查询设备状态(文件列表/磁盘/进程等),辅助 Agent Mode 自主决策。
 *
 * 安全设计:
 *  - 命令白名单:仅允许 ls/cat/grep/echo/wc/head/tail/find/file/stat/df/du/uname/whoami/date/pwd
 *  - 禁止管道到危险命令(如 | sh、| bash)、禁止重定向到系统目录(> /system/...)
 *  - 禁止 &、&&、||、; 命令分隔符(防止注入第二条命令)
 *  - 工作目录锁定到应用 filesDir(禁止 cd 到外部)
 *  - 超时 10s,输出上限 8KB
 *  - 仅注册为 HIGH 风险等级,Agent Mode + 用户审批才能执行
 *
 * 注意:Android 上部分命令可能不可用(toybox 实现),执行失败时返回明确错误。
 */
object ShellSandboxTool {

    const val NAME = "execute_shell"

    /** 命令白名单(只读/安全命令)。 */
    private val ALLOWED_COMMANDS = setOf(
        "ls", "cat", "grep", "echo", "wc", "head", "tail", "find", "file", "stat",
        "df", "du", "uname", "whoami", "date", "pwd", "tree",
    )

    /** 禁止的字符(防止命令注入)。 */
    private val FORBIDDEN_CHARS = setOf('&', '|', ';', '`', '$', '(', ')', '{', '}', '<', '>')

    /** 命令超时 ms。 */
    private const val TIMEOUT_MS = 10_000L

    /** 输出上限 8KB。 */
    private const val MAX_OUTPUT_BYTES = 8 * 1024

    fun toolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME,
        // v1.0.75 fix (工具审查 02): 补触发场景与返回格式
        description = "在应用沙箱内执行白名单 Shell 命令,用于查询设备状态(文件/磁盘/进程)后做决策(仅 Agent Mode)。" +
            "允许的命令:ls/cat/grep/echo/wc/head/tail/find/file/stat/df/du/uname/whoami/date/pwd/tree。" +
            "工作目录为应用数据目录,禁止命令分隔符(&;|)和重定向(<>),超时 10 秒。" +
            "返回: 成功=命令输出,失败=[错误]原因。",
        parameters = mapOf(
            "command" to "必填,要执行的命令(如 'ls -la /sdcard/Download')",
        ),
        required = setOf("command"),
        category = "built-in",
        riskLevel = ToolRiskLevel.HIGH,
    )

    /**
     * 执行 Shell 命令。
     *
     * @param command 用户/AI 提供的命令字符串
     * @param workDir 工作目录(应用 filesDir)
     */
    suspend fun execute(command: String, workDir: File): String = withContext(Dispatchers.IO) {
        // 安全检查 0:规则型硬边界防线(采用 既有实现)
        // v1.0.52: 调用 ToolPermissionResolver.isUnsafeCommand 做第一道拦截,
        // 即使白名单/权限模式有 bug,黑名单(rm/sudo/git/curl/wget 等)和不安全语法
        // (换行注入/通配符)仍然会被拦截。这是"硬边界"——TRUSTED 模式也无法绕过。
        if (ToolPermissionResolver.isUnsafeCommand(command)) {
            return@withContext "[错误] 命令命中硬边界黑名单(危险可执行文件或不安全语法),已被拒绝执行"
        }

        // 安全检查 1:禁止危险字符
        val forbidden = command.firstOrNull { it in FORBIDDEN_CHARS }
        if (forbidden != null) {
            return@withContext "[错误] 命令包含禁止字符 '$forbidden'(&;|`$(){}<>,防止注入)"
        }

        // 安全检查 2:解析命令名,校验白名单
        val parts = command.trim().split(Regex("\\s+"))
        if (parts.isEmpty() || parts[0].isBlank()) {
            return@withContext "[错误] 命令为空"
        }
        val cmdName = parts[0]
        if (cmdName !in ALLOWED_COMMANDS) {
            return@withContext "[错误] 命令 '$cmdName' 不在白名单内。允许: ${ALLOWED_COMMANDS.joinToString("/")}"
        }

        // 安全检查 3:find 命令限制路径(禁止 / 等系统目录全盘扫描)
        if (cmdName == "find" && parts.any { it == "/" || it == "/system" || it == "/proc" || it == "/sys" }) {
            return@withContext "[错误] find 禁止扫描系统目录(/、/system、/proc、/sys)"
        }

        runCatching {
            val builder = ProcessBuilder(parts)
                .directory(workDir)
                .redirectErrorStream(true)

            val process = builder.start()
            // 审计修复 (4.2): 先 waitFor 带超时、后读输出 — 原实现先 stream.readBytes()
            // 全量读输出再 waitFor,命令不退出时 readBytes 阻塞到 EOF,10s 超时形同虚设;
            // 且 waitFor() 无超时参数可能无限挂起。现在先等超时,超时则销毁进程返回超时错误,
            // 进程已退出后再读流不会阻塞(redirectErrorStream 已合并 stderr)。
            val finished = process.waitFor(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@withContext "[超时] 命令 ${TIMEOUT_MS / 1000}s 未完成,已终止"
            }

            val output = process.inputStream.buffered().use { stream ->
                stream.readBytes().copyOf(MAX_OUTPUT_BYTES).toString(Charsets.UTF_8).trim()
            }

            val exitCode = process.exitValue()
            val truncated = if (output.length > 8000) output.substring(0, 8000) + "\n...(输出超 8000 字符,已截断)" else output

            Logger.i("ShellSandbox", "execute: $command → exit=$exitCode (${output.length} chars)")
            if (exitCode == 0) {
                truncated.ifBlank { "[成功] 命令执行完成,无输出" }
            } else {
                "[退出码 $exitCode]\n$truncated"
            }
        }.getOrElse {
            Logger.w("ShellSandbox", "execute 失败: ${it.message}", it)
            "[错误] 执行失败: ${it.message}"
        }
    }

    private fun ByteArray.copyOf(maxLength: Int): ByteArray =
        if (size <= maxLength) this else copyOf(maxLength)
}

/**
 * v1.0.47 P2-6: ShellSandboxTool 注册器。
 */
class ShellSandboxToolRegistrar(
    private val toolRegistry: ToolRegistry,
    private val workDir: File,
) {
    init { registerAll() }

    fun registerAll() {
        toolRegistry.register(ShellSandboxTool.toolDef()) { args ->
            val command = args["command"] ?: return@register "[错误] 缺少必填参数 command"
            ShellSandboxTool.execute(command, workDir)
        }
    }
}
