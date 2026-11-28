package io.zer0.muse.tools

import io.zer0.common.Logger
import io.zer0.muse.data.audit.AuditLogger
import org.koin.core.context.GlobalContext
import java.io.File
import java.io.IOException

/**
 * `AUTO_REVIEW` 策略的规则复核器。
 *
 * 该策略把「每次都问用户」换成「先按可验证的边界规则复核」：
 *  - 只读工具与工作区内可逆写入直接放行，并写入审计日志；
 *  - 删除、外发、系统包管理（apt 等）、资金类操作与越界路径一律升级为
 *    [ToolApprovalState.Pending]，仍由用户拍板。
 *
 * 复核完全由本地规则完成，不依赖模型输出；任何无法证明安全的调用都走升级分支，
 * 不允许默认放行。
 */
object ToolAutoReviewer {

    private const val TAG = "ToolAutoReviewer"

    /** 复核结论。 */
    enum class Outcome {
        /** 规则证明安全，可以自动放行。 */
        ALLOW,

        /** 需要用户审批。 */
        REQUIRE_APPROVAL,
    }

    /**
     * 单次复核结果。
     *
     * @param ruleId 命中的规则 id，用于审计检索与测试断言
     * @param reason 给审计日志与排查用的原因
     */
    data class Decision(
        val outcome: Outcome,
        val ruleId: String,
        val reason: String,
    ) {
        /** 是否允许自动放行。 */
        val allowed: Boolean get() = outcome == Outcome.ALLOW
    }

    /** 审计出口：生产环境落 [AuditLogger]，测试注入内存实现。 */
    fun interface AuditSink {
        fun record(toolName: String, decision: Decision)
    }

    /**
     * 执行一次规则复核。
     *
     * @param toolName 工具名
     * @param args 工具全量参数
     * @param risk 工具风险等级；为 null 时按 [ToolPermissionResolver.riskLevelFor] 的单一真源推断
     * @param workspaceRoot 工作区根目录；为空时按「相对路径且不含 ..」判定工作区内
     * @param auditSink 审计出口；为空时写入应用日志与（若可用）审计日志
     */
    fun review(
        toolName: String,
        args: Map<String, Any?> = emptyMap(),
        risk: ToolRiskLevel? = null,
        workspaceRoot: String? = null,
        auditSink: AuditSink? = null,
    ): Decision {
        val decision = decide(toolName, args, risk, workspaceRoot)
        if (auditSink != null) {
            auditSink.record(toolName, decision)
        } else {
            audit(toolName, decision)
        }
        return decision
    }

    /** 把复核结果映射为审批状态：允许 → [ToolApprovalState.Auto]，其余 → [ToolApprovalState.Pending]。 */
    fun resolveState(
        toolName: String,
        args: Map<String, Any?> = emptyMap(),
        risk: ToolRiskLevel? = null,
        workspaceRoot: String? = null,
    ): ToolApprovalState = if (review(toolName, args, risk, workspaceRoot).allowed) {
        ToolApprovalState.Auto
    } else {
        ToolApprovalState.Pending
    }

    private fun decide(toolName: String, args: Map<String, Any?>, risk: ToolRiskLevel?, workspaceRoot: String?): Decision {
        val normalized = toolName.trim().lowercase()
        if (normalized.isEmpty()) {
            return requireApproval("invalid-tool", "工具名为空，无法复核")
        }
        // 第三方工具的名称由插件/MCP 自取，不能按名字推断安全性，一律交用户审批。
        if (THIRD_PARTY_TOOL_NAME.matches(normalized)) {
            return requireApproval("third-party", "第三方工具不参与自动放行")
        }
        if (DESTRUCTIVE_TOOL_NAME.containsMatchIn(normalized)) {
            return requireApproval("destructive-tool", "删除/卸载类工具需要用户确认")
        }
        if (EXTERNAL_SEND_TOOL_NAME.containsMatchIn(normalized)) {
            return requireApproval("external-send", "外发/分享类工具需要用户确认")
        }
        if (FINANCIAL_TOOL_NAME.containsMatchIn(normalized)) {
            return requireApproval("financial", "资金类工具需要用户确认")
        }
        if (normalized in SHELL_TOOL_NAMES) {
            val script = commandText(args)
            if (SYSTEM_PACKAGE_COMMAND.containsMatchIn(script)) {
                return requireApproval("system-package", "系统包管理命令需要用户确认")
            }
            if (DESTRUCTIVE_COMMAND.containsMatchIn(script)) {
                return requireApproval("destructive-command", "破坏性命令需要用户确认")
            }
            return requireApproval("shell-tool", "命令执行类工具需要用户确认")
        }
        if (normalized.startsWith("workspace_")) {
            val paths = pathArguments(args)
            if (paths.any { !isWorkspaceSafePath(it, workspaceRoot) }) {
                return requireApproval("out-of-bounds-path", "路径超出工作区边界")
            }
            if (normalized in READ_ONLY_WORKSPACE_TOOLS) {
                return allow("read-only", "只读工作区工具")
            }
        }
        val effectiveRisk = risk ?: ToolPermissionResolver.riskLevelFor(toolName)
        if (effectiveRisk == ToolRiskLevel.SAFE) {
            return allow("read-only", "只读/查询类工具")
        }
        if (normalized in SAFE_WORKSPACE_WRITE_TOOLS) {
            return allow("workspace-safe-write", "工作区内可逆写入")
        }
        return requireApproval("default-escalation", "无法证明安全，升级为用户审批")
    }

    private fun allow(ruleId: String, reason: String): Decision = Decision(Outcome.ALLOW, ruleId, reason)

    private fun requireApproval(ruleId: String, reason: String): Decision = Decision(Outcome.REQUIRE_APPROVAL, ruleId, reason)

    /** 把工具参数里的命令文本拼起来；只用于命令执行类工具的边界扫描。 */
    private fun commandText(args: Map<String, Any?>): String =
        COMMAND_ARG_KEYS.mapNotNull { key -> args[key]?.toString() }.joinToString("\n")

    /** 收集路径类参数；空串表示根目录，交给具体工具处理。 */
    private fun pathArguments(args: Map<String, Any?>): List<String> = PATH_ARG_KEYS.mapNotNull { key ->
        args[key]?.toString()?.takeIf { it.isNotBlank() }
    }

    /**
     * 判断路径是否落在工作区内。
     *
     * 工作区工具的参数形如 `挂载标识:/相对路径`；不带前缀时按默认工作区处理。
     * 绝对路径只有在显式给出 [workspaceRoot] 且解析后仍在根目录内时才放行。
     */
    internal fun isWorkspaceSafePath(rawPath: String, workspaceRoot: String?): Boolean {
        val trimmed = rawPath.trim()
        if (trimmed.isEmpty()) return true
        if (trimmed.contains('\u0000')) return false

        val root = workspaceRoot?.trim().orEmpty()
        if (isAbsolutePath(trimmed)) {
            if (root.isEmpty()) return false
            return isInsideRoot(trimmed, root)
        }

        val relative = stripMountPrefix(trimmed)
        if (relative.isBlank()) return false
        if (relative.split('/', '\\').any { it == ".." }) return false
        if (root.isEmpty()) return true
        return isInsideRoot(File(root, relative).path, root)
    }

    /** 绝对路径包含 POSIX 根、Windows 驱动器、UNC 与用户主目录简写。 */
    private fun isAbsolutePath(path: String): Boolean = path.startsWith("/") ||
        path.startsWith("\\") ||
        path.startsWith("~") ||
        WINDOWS_DRIVE_PATH.containsMatchIn(path)

    /**
     * 去掉 `挂载标识:` 前缀。
     *
     * 只有冒号前是合法标识时才剥离；否则原样返回（例如普通文件名里的冒号）。
     */
    private fun stripMountPrefix(path: String): String {
        val separator = path.indexOf(':')
        if (separator <= 1) return path
        val prefix = path.substring(0, separator)
        if (!MOUNT_ID.matches(prefix)) return path
        return path.substring(separator + 1).trimStart('/', '\\')
    }

    /** 解析后的路径是否在根目录内；解析失败按越界处理。 */
    private fun isInsideRoot(candidate: String, workspaceRoot: String): Boolean = try {
        val root = File(workspaceRoot).canonicalFile
        val file = File(candidate).canonicalFile
        file == root || file.path.startsWith(root.path + File.separator)
    } catch (e: IOException) {
        Logger.w(TAG, "工作区路径解析失败，按越界处理: ${e.message}")
        false
    }

    /** 写审计日志；无 Koin 容器（纯 JVM 测试）时只保留应用日志。 */
    private fun audit(toolName: String, decision: Decision) {
        Logger.i(
            TAG,
            "auto_review tool=$toolName outcome=${decision.outcome} " +
                "rule=${decision.ruleId} reason=${decision.reason}",
        )
        val auditLogger = runCatching { GlobalContext.getOrNull()?.get<AuditLogger>() }.getOrNull()
            ?: return // Koin 未启动（单元测试 / 工具进程）时没有审计数据库可写
        auditLogger.log(
            category = "system",
            action = "tool_auto_review",
            target = toolName,
            detail = mapOf(
                "outcome" to decision.outcome.name,
                "rule" to decision.ruleId,
                "reason" to decision.reason,
            ),
            success = decision.allowed,
        )
    }

    /** 第三方动态工具的名称形态：插件与 MCP 工具。 */
    private val THIRD_PARTY_TOOL_NAME = Regex("^plugin_[0-9]+_.+|^mcp_.+__.+")

    /** 删除/清空/卸载类工具名。 */
    private val DESTRUCTIVE_TOOL_NAME = Regex("(^|_)(delete|remove|erase|wipe|clear|reset|uninstall|destroy|format)(_|$)")

    /** 外发/分享/提交类工具名。 */
    private val EXTERNAL_SEND_TOOL_NAME =
        Regex("(^|_)(send|share|upload|post|publish|email|sms|call|reply|relay|webhook|submit|invite)(_|$)")

    /** 资金类工具名。 */
    private val FINANCIAL_TOOL_NAME = Regex(
        "(^|_)(pay|payment|transfer|remit|bank|wallet|card|checkout|order|purchase|refund|" +
            "withdraw|deposit|recharge|invoice|loan|invest|trade)(_|$)",
    )

    /** 命令执行类工具：任何命令都要先过规则扫描。 */
    private val SHELL_TOOL_NAMES = setOf(
        "execute_shell",
        "terminal_exec",
        "termux_exec",
        "device_shell",
        "execute_python_script",
        "execute_node_script",
        "run_command",
        "terminal_job_start",
    )

    /** 系统包管理与提权命令。 */
    private val SYSTEM_PACKAGE_COMMAND = Regex("(?i)\\b(apt|apt-get|dpkg|rpm|yum|dnf|pacman|sudo|su)\\b")

    /** 破坏性命令。 */
    private val DESTRUCTIVE_COMMAND =
        Regex("(?i)\\b(rm|rmdir|shred|mkfs|dd|shutdown|reboot|poweroff|kill|pkill|chmod|chown|mount|umount)\\b")

    private val COMMAND_ARG_KEYS = listOf("command", "cmd", "code", "script", "source")

    private val PATH_ARG_KEYS = listOf("path", "from", "to", "source", "src", "destination", "dst", "target", "file", "dir")

    private val READ_ONLY_WORKSPACE_TOOLS = setOf(
        WorkspaceTool.NAME_LIST,
        WorkspaceTool.NAME_READ,
        WorkspaceTool.NAME_GREP,
        WorkspaceTool.NAME_MOUNTS,
    )

    private val SAFE_WORKSPACE_WRITE_TOOLS = setOf(
        WorkspaceTool.NAME_WRITE,
        WorkspaceTool.NAME_EDIT,
        WorkspaceTool.NAME_MKDIR,
        WorkspaceTool.NAME_MOVE,
        WorkspaceTool.NAME_COPY,
    )

    private val MOUNT_ID = Regex("^[A-Za-z0-9_.-]+$")

    private val WINDOWS_DRIVE_PATH = Regex("^[A-Za-z]:")
}
