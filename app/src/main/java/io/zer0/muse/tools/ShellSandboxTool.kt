package io.zer0.muse.tools

import io.zer0.common.Logger
import io.zer0.muse.tools.system.sanitizeShellCommandForLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

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
 *  - 超时 10s,长输出完整落盘并提供分页读取
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

    /** C-26: find 禁止扫描的系统目录前缀(前缀匹配,精确匹配可被 /system/bin 绕过)。 */
    private val SYSTEM_DIR_PREFIXES = listOf(
        "/system", "/proc", "/sys", "/dev", "/etc", "/bin", "/sbin", "/vendor",
        "/data", "/storage/emulated", "/sdcard", "/mnt", "/apex", "/product",
    )

    /** C-26: 路径参数必须落在沙箱(filesDir)内的读文件类命令。 */
    private val SANDBOX_PATH_COMMANDS = setOf(
        "ls", "cat", "head", "tail", "file", "stat", "du", "grep", "tree", "wc", "find",
    )

    /** 禁止的字符(防止命令注入)。 */
    private val FORBIDDEN_CHARS = setOf('&', '|', ';', '`', '$', '(', ')', '{', '}', '<', '>')

    /** 命令超时 ms。 */
    private const val TIMEOUT_MS = 10_000L

    fun toolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME,
        // v1.0.75 fix (工具审查 02): 补触发场景与返回格式
        // C-26: 示例改为沙箱内路径(/sdcard 等外部路径已被路径限制拦截)
        description = "在应用沙箱内执行白名单 Shell 命令,用于查询设备状态(文件/磁盘/进程)后做决策(仅 Agent Mode)。" +
            "允许的命令:ls/cat/grep/echo/wc/head/tail/find/file/stat/df/du/uname/whoami/date/pwd/tree。" +
            "工作目录为应用数据目录,路径参数仅允许应用数据目录内;禁止命令分隔符(&;|)和重定向(<>),超时 10 秒。" +
            "长输出会保存到可分段读取的应用文件。" +
            "返回: 成功=命令输出,失败=[错误]原因。",
        parameters = mapOf(
            "command" to "必填,要执行的命令(如 'ls -la' 或 'cat notes.txt')",
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
        // C-26: 精确匹配可被 "/system/bin" 绕过,改前缀匹配
        if (cmdName == "find" && parts.any { arg -> arg == "/" || SYSTEM_DIR_PREFIXES.any { arg.startsWith(it) } }) {
            return@withContext "[错误] find 禁止扫描系统目录(/、/system、/proc、/sys 等)"
        }

        // 安全检查 3.5:读文件类命令的路径参数必须解析到沙箱(filesDir)内,
        // 防止 cat /data/... 或相对路径逃逸越权读取;只读命令不受影响。
        if (cmdName in SANDBOX_PATH_COMMANDS) {
            val badArg = parts.drop(1)
                .filter { it.isNotBlank() && !it.startsWith("-") }
                .firstOrNull { arg ->
                    val resolved = if (arg.startsWith("/")) File(arg) else File(workDir, arg)
                    runCatching {
                        // C-07: 目录边界用"目标路径 == 沙箱根 或 目标路径以 '沙箱根 + 分隔符' 开头"判断,
                        // 不能只用 startsWith(沙箱根) — 否则 /data/user2 会被误判为 /data/user 的子路径。
                        val root = workDir.canonicalPath
                        val target = resolved.canonicalPath
                        !(target == root || target.startsWith(root + File.separator))
                    }.getOrDefault(true)
                }
            if (badArg != null) {
                return@withContext "[错误] 路径 '$badArg' 超出沙箱范围(仅允许应用数据目录内)"
            }
        }

        runCatching {
            val builder = ProcessBuilder(parts)
                .directory(workDir)
                .redirectErrorStream(true)

            val process = builder.start()
            val capture = ToolOutputCapture(File(workDir, TOOL_OUTPUTS_DIR), "execute_shell")
            val readFailure = AtomicReference<Throwable?>(null)
            val reader = thread(name = "muse-shell-output") {
                try {
                    process.inputStream.bufferedReader().use { stream ->
                        val buffer = CharArray(4_096)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            capture.append(String(buffer, 0, count))
                        }
                    }
                } catch (error: Throwable) {
                    readFailure.set(error)
                }
            }
            val finished = process.waitFor(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                reader.join()
                readFailure.get()?.let { throw it }
                return@withContext capture.finish(
                    header = "[超时] 命令 ${TIMEOUT_MS / 1000}s 未完成,已终止",
                    emptyMessage = "(无输出)",
                )
            }
            reader.join()
            readFailure.get()?.let { throw it }
            val exitCode = process.exitValue()
            val result = capture.finish(
                header = if (exitCode == 0) "" else "[退出码 $exitCode]",
                emptyMessage = if (exitCode == 0) "[成功] 命令执行完成,无输出" else "(无输出)",
            )

            Logger.i(
                "ShellSandbox",
                "execute verb=${sanitizeShellCommandForLog(command)} → exit=$exitCode",
            )
            result
        }.getOrElse {
            Logger.w("ShellSandbox", "execute 失败: ${it.message}", it)
            "[错误] 执行失败: ${it.message}"
        }
    }
}

/**
 * v1.0.47 P2-6: ShellSandboxTool 注册器。
 */
class ShellSandboxToolRegistrar(
    private val toolRegistry: ToolRegistry,
    private val workDir: File,
) {
    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(ShellSandboxTool.toolDef()) { args ->
            val command = args["command"] ?: return@register "[错误] 缺少必填参数 command"
            ShellSandboxTool.execute(command, workDir)
        }
    }
}
