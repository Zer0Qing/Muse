package io.zer0.muse.tools

import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.tools.ToolRequirements.Channel

/**
 * v2.6.7: tool_status —— 一次告诉模型「现在真正能用什么」。
 *
 * ## 为什么需要它
 *
 * 助手自审原话：「206 个工具，而且没有地图。我一开始根本不知道自己有什么，
 * 是靠 find_tools 空查询才拿到全表。档位和'我能不能用'没关系。」
 *
 * 现状的问题不是工具少，是**边界不清**：schema 里有 200+ 个名字，
 * 但其中依赖无障碍/Shizuku/Root 的那批在通道未就绪时必然失败。
 * 模型只能在调用时撞墙才知道，这会产出一句「我帮你点一下」然后当场失败 ——
 * 比根本不提供更伤体验。
 *
 * 本工具把「已注册」和「现在能用」拆开报：
 *  - usable：本轮真能调用的（含应用内工具 + 通道已就绪的）
 *  - blocked：已注册但通道未就绪的，附缺什么、怎么开
 *
 * 这样模型在动手前就能知道自己手脚被绑到什么程度，而不是先许诺再失败。
 */
object ToolStatusTool {

    /** 受阻清单每行最多列几个工具名，避免一行过长。 */
    private const val BLOCKED_PREVIEW_LIMIT = 14

    fun toolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = "tool_status",
        description = "Report which tools are actually callable right now, split into usable and blocked-by-permission. " +
            "Call this before promising device actions (tap/swipe/read screen/shell) so you never claim " +
            "something you cannot do. Returns channel readiness and, for blocked tools, what to enable.",
        parameters = mapOf(
            "detail" to "Optional. 'summary' (default) = counts plus blocked list; 'full' = also list every usable tool name.",
        ),
        required = emptySet(),
        category = "built-in",
        riskLevel = ToolRiskLevel.SAFE,
    )

    /**
     * 执行。
     *
     * @param args 参数（detail）
     * @param registry 工具注册表（用于枚举已注册工具）
     * @param automation 自动化管理器（用于读通道状态）
     * @param runtimePermissionChecks 单项运行时权限探针（contacts/sms/notification 等）
     */
    suspend fun execute(
        args: Map<String, String>,
        registry: ToolRegistry,
        automation: AutomationManager,
        runtimePermissionChecks: Map<Channel, Boolean> = emptyMap(),
    ): String {
        val detail = args["detail"]?.trim()?.lowercase() ?: "summary"
        val state = automation.refreshPermissions()
        val channels = buildChannels(state, runtimePermissionChecks)
        val registered = registry.listTools().map { it.name }
        val (usable, blocked) = registered.partition { ToolRequirements.isUsable(it, channels) }

        return buildString {
            appendLine("## 工具可用性（实时）")
            appendLine()
            appendChannelSection(state, runtimePermissionChecks)
            appendLine()
            appendLine("已注册工具 ${registered.size} 个：可用 ${usable.size}，因权限受阻 ${blocked.size}。")
            if (blocked.isNotEmpty()) {
                appendLine()
                appendLine("### 当前不可调用（先别答应，也不要试）")
                appendBlockedSection(blocked, channels)
                appendLine()
                append("开启任一通道后重新调用本工具即可看到更新。")
            }
            if (detail == "full" && usable.isNotEmpty()) {
                appendLine()
                appendLine("### 当前可用")
                append(usable.joinToString(", "))
            }
        }
    }

    /** 把三层自动化 + 单项运行时权限合成可用通道集合。 */
    private fun buildChannels(state: AutomationManager.PermissionState, runtimePermissionChecks: Map<Channel, Boolean>): Set<Channel> =
        buildSet {
            if (state.accessibilityEnabled) add(Channel.ACCESSIBILITY)
            if (state.shellEnabled) add(Channel.SHELL)
            if (state.rootEnabled) add(Channel.ROOT)
            runtimePermissionChecks.forEach { (ch, granted) -> if (granted) add(ch) }
        }

    /** 通道就绪状态段。 */
    private fun StringBuilder.appendChannelSection(
        state: AutomationManager.PermissionState,
        runtimePermissionChecks: Map<Channel, Boolean>,
    ) {
        appendLine("通道状态:")
        appendLine("- 无障碍: ${if (state.accessibilityEnabled) "可用" else "未开启"}")
        appendLine(
            "- Shell(Shizuku/adb): " +
                if (state.shellEnabled) {
                    "可用"
                } else {
                    "不可用（${state.shizukuMessage}）"
                },
        )
        appendLine("- Root: ${if (state.rootEnabled) "可用" else "未获取"}")
        val granted = runtimePermissionChecks.filterValues { it }.keys
        val missing = runtimePermissionChecks.filterValues { !it }.keys
        if (granted.isNotEmpty()) appendLine("- 单项权限已授予: ${granted.joinToString("、") { it.label() }}")
        if (missing.isNotEmpty()) appendLine("- 单项权限缺失: ${missing.joinToString("、") { it.label() }}")
        appendLine("- 最高可用层级: ${state.highestLevelName()}")
    }

    /** 受阻工具按"缺什么"分组，便于一次说清开哪一项能解锁多少。 */
    private fun StringBuilder.appendBlockedSection(blocked: List<String>, channels: Set<Channel>) {
        blocked.groupBy { missingChannelsOf(it, channels) }
            .entries
            .sortedByDescending { it.value.size }
            .forEach { (missing, tools) ->
                val label = if (missing.isEmpty()) "其他" else missing.joinToString(" 或 ") { it.label() }
                val tail = if (tools.size > BLOCKED_PREVIEW_LIMIT) " …" else ""
                appendLine(
                    "- 需要 $label （${tools.size} 个）: " +
                        tools.take(BLOCKED_PREVIEW_LIMIT).joinToString(", ") + tail,
                )
            }
    }

    /** 计算某工具缺哪些通道（用于分组展示）。单表达式，避免多点 return。 */
    private fun missingChannelsOf(toolName: String, available: Set<Channel>): Set<Channel> {
        val req = ToolRequirements.of(toolName)
        val missingAll = req.allOf.filterNot { it in available }.toSet()
        val anyUnsatisfied = req.anyOf.isNotEmpty() && req.anyOf.none { it in available }
        return when {
            missingAll.isNotEmpty() -> missingAll
            anyUnsatisfied -> req.anyOf
            else -> emptySet()
        }
    }

    private fun Channel.label(): String = when (this) {
        Channel.ACCESSIBILITY -> "无障碍"
        Channel.SHELL -> "Shizuku/ADB"
        Channel.ROOT -> "Root"
        Channel.USAGE_STATS -> "使用情况访问"
        Channel.NOTIFICATION_LISTENER -> "通知使用权"
        Channel.CONTACTS -> "联系人"
        Channel.SMS -> "短信"
        Channel.PHONE_CALL -> "电话"
        Channel.CALENDAR -> "日历"
        Channel.LOCATION -> "定位"
        Channel.RUNTIME -> "运行时权限"
    }
}
