package io.zer0.muse.automation.tools

import io.zer0.common.Logger
import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel

/**
 * UI 自动化工具集 —— 把 [AutomationManager] 的能力注册为 AI 可调用的工具。
 *
 * 工具分两类:
 * - 感知类(读屏/截屏/查前台): 低风险,无需用户确认
 * - 操作类(点击/滑动/输入/按键/启动App): 高风险,受 ToolRiskLevel 管控
 *
 * AI 调用这些工具后可以实现跨 App 的任务自动化(类似豆包手机的系统级助手)。
 */
class AutomationTools(
    private val manager: AutomationManager,
) {
    fun register(registry: ToolRegistry) {
        // ── 感知类 ──────────────────────────────────────────

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_read",
                description = "读取当前屏幕内容。返回控件树摘要(文字/按钮/输入框及其坐标)," +
                    "用于判断界面上有什么、该点哪里。在做任何点击操作前应先调用此工具。",
                parameters = mapOf(
                    "query" to "可选,只返回包含该关键词的控件",
                ),
                required = emptySet(),
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args ->
            val info = manager.readScreen()
            val query = args["query"]?.takeIf { q -> q.isNotBlank() }
            val nodes = if (query != null) {
                info.nodes.filter { n ->
                    (n.text?.contains(query, ignoreCase = true) == true) ||
                        (n.contentDescription?.contains(query, ignoreCase = true) == true)
                }
            } else info.nodes
            buildString {
                appendLine(info.toSummary(nodes.size.coerceAtMost(50)))
                if (nodes.isNotEmpty()) {
                    appendLine("---")
                    appendLine("可点击的关键控件:")
                    nodes.filter { n -> n.isClickable || n.isEditable }
                        .take(15)
                        .forEachIndexed { i, n ->
                            appendLine("[$i] ${n.toShortString()}")
                        }
                }
            }
        }

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_current_app",
                description = "查询当前前台运行的应用包名和界面名。",
                parameters = emptyMap(),
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { _ ->
            val info = manager.readScreen()
            "包名: ${info.packageName ?: "未知"}\n界面: ${info.activityName ?: "未知"}\n分辨率: ${info.screenWidth}x${info.screenHeight}"
        }

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_back",
                description = "按下系统返回键,回到上一个界面。",
                parameters = emptyMap(),
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { _ ->
            if (manager.back()) "已返回" else "返回失败(可能需要无障碍或 Shell 权限)"
        }

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_home",
                description = "按下 Home 键,回到桌面。",
                parameters = emptyMap(),
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { _ ->
            if (manager.home()) "已回到桌面" else "操作失败"
        }

        // ── 操作类 ──────────────────────────────────────────

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_tap",
                description = "点击屏幕上的指定坐标(x,y)。坐标来自 screen_read 返回的控件中心位置。" +
                    "不要凭空猜测坐标,必须先 screen_read 确认。",
                parameters = mapOf(
                    "x" to "必填,点击位置 X 坐标(像素)",
                    "y" to "必填,点击位置 Y 坐标(像素)",
                ),
                required = setOf("x", "y"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val x = args["x"]?.toIntOrNull() ?: return@register "错误:x 必须是整数"
            val y = args["y"]?.toIntOrNull() ?: return@register "错误:y 必须是整数"
            if (manager.tap(x, y)) "已点击 ($x,$y)" else "点击失败"
        }

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_tap_text",
                description = "查找屏幕上包含指定文字的控件并点击其中心。" +
                    "比 screen_tap 更方便,不需要自己算坐标。",
                parameters = mapOf(
                    "text" to "必填,要查找的按钮/文字内容(支持模糊匹配)",
                ),
                required = setOf("text"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val text = args["text"] ?: return@register "错误:缺少 text 参数"
            if (manager.tapByText(text)) "已点击包含\"$text\"的控件"
            else "未找到包含\"$text\"的可点击控件"
        }

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_swipe",
                description = "在屏幕上从一个点滑动到另一个点,可用于滚动/翻页。",
                parameters = mapOf(
                    "x1" to "必填,起点 X",
                    "y1" to "必填,起点 Y",
                    "x2" to "必填,终点 X",
                    "y2" to "必填,终点 Y",
                    "duration_ms" to "可选,滑动时长(毫秒),默认 400",
                ),
                required = setOf("x1", "y1", "x2", "y2"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val x1 = args["x1"]?.toIntOrNull() ?: return@register "错误:x1 必须是整数"
            val y1 = args["y1"]?.toIntOrNull() ?: return@register "错误:y1 必须是整数"
            val x2 = args["x2"]?.toIntOrNull() ?: return@register "错误:x2 必须是整数"
            val y2 = args["y2"]?.toIntOrNull() ?: return@register "错误:y2 必须是整数"
            val dur = args["duration_ms"]?.toLongOrNull() ?: 400L
            if (manager.swipe(x1, y1, x2, y2, dur)) "已滑动" else "滑动失败"
        }

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_input",
                description = "往当前聚焦的输入框输入文字(支持中文)。输入前应先点击输入框使其聚焦。",
                parameters = mapOf(
                    "text" to "必填,要输入的文本",
                ),
                required = setOf("text"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val text = args["text"] ?: return@register "错误:缺少 text 参数"
            if (manager.inputText(text)) "已输入: $text" else "输入失败"
        }

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_launch_app",
                description = "启动指定包名的 App。可配合 screen_current_app 确认包名。",
                parameters = mapOf(
                    "package" to "必填,要启动的 App 包名,如 com.tencent.mm",
                ),
                required = setOf("package"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val pkg = args["package"] ?: return@register "错误:缺少 package 参数"
            if (manager.launchApp(pkg)) "已启动 $pkg" else "启动失败(包名可能不正确)"
        }

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_open_notifications",
                description = "打开系统通知栏,查看通知。",
                parameters = emptyMap(),
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { _ ->
            if (manager.openNotifications()) "已打开通知栏" else "操作失败"
        }

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_permission_status",
                description = "查询 UI 自动化各层权限的开通状态(无障碍/Shell/Root)。" +
                    "在尝试任何操作前应先调用此工具确认能力。",
                parameters = emptyMap(),
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { _ ->
            val state = manager.permissionState.value
            val level = manager.highestLevel()
            buildString {
                appendLine("UI 自动化权限状态:")
                appendLine("- 无障碍: ${if (state.accessibilityEnabled) "已开启" else "未开启"}")
                appendLine("- Shell: ${if (state.shellEnabled) "已就绪" else state.shizukuMessage}")
                appendLine("- Root: ${if (state.rootEnabled) "已获取" else "未获取"}")
                appendLine("- 最高可用层级: $level")
            }
        }
    }

    /** 初始化时刷新权限状态。 */
    suspend fun initialize() {
        try {
            manager.refreshPermissions()
        } catch (e: Exception) {
            Logger.w(TAG, "initial permission refresh failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "AutomationTools"
    }
}
