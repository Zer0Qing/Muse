package io.zer0.muse.automation.tools

import io.zer0.common.Logger
import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.automation.vdisplay.VirtualDisplayClient
import io.zer0.muse.automation.vdisplay.VirtualDisplayLeaseRegistry
import io.zer0.muse.automation.vdisplay.VirtualDisplayServerManager
import io.zer0.muse.tools.ToolOutcome
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel
import io.zer0.muse.tools.WorkflowJournal
import java.util.UUID

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
    private val workflowJournal: WorkflowJournal? = null,
    private val virtualDisplayClient: VirtualDisplayClient? = null,
    private val virtualDisplayManager: VirtualDisplayServerManager? = null,
    private val nodeScriptExecutor: (suspend (String, Long) -> io.zer0.muse.tools.script.SkillEngineResult)? = null,
) {
    /** 所有 automation_workflow 工具调用共享的 lease 台账，按 run_id 隔离虚拟屏释放权。 */
    private val displayLeases = VirtualDisplayLeaseRegistry()

    fun register(registry: ToolRegistry) {
        registerWorkflow(registry)
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
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val info = manager.readScreen()
            val query = args["query"]?.takeIf { q -> q.isNotBlank() }
            val nodes = if (query != null) {
                info.nodes.filter { n ->
                    (n.text?.contains(query, ignoreCase = true) == true) ||
                        (n.contentDescription?.contains(query, ignoreCase = true) == true)
                }
            } else {
                info.nodes
            }
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
                riskLevel = ToolRiskLevel.HIGH,
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

        registry.registerOutcome(
            ToolRegistry.ToolDef(
                name = "screen_tap_text",
                description = "查找屏幕上包含指定文字的控件并点击其中心。" +
                    "比 screen_tap 更方便,不需要自己算坐标；可在长页面内有限次滚动查找，并可验证点击后的文字。",
                parameters = mapOf(
                    "text" to "必填,要查找的按钮/文字内容(支持模糊匹配)",
                    "max_swipes" to "可选,找不到时最多向上滚动次数(0-5,默认 0)",
                    "verify_text" to "可选,点击后等待出现的文字；用于确认动作真正生效",
                ),
                required = setOf("text"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val text = args["text"] ?: return@registerOutcome ToolOutcome.error("错误:缺少 text 参数")
            val maxSwipes = args["max_swipes"]?.toIntOrNull()?.coerceIn(0, 5) ?: 0
            val verifyText = args["verify_text"]?.trim()?.takeIf { it.isNotBlank() }
            val outcome = manager.tapByTextWithRetryDetailed(text, maxSwipes = maxSwipes, verifyText = verifyText)
            val details = mapOf(
                "attempts" to outcome.attempts,
                "matched" to outcome.matched,
                "verified" to outcome.verified,
                "matchedLabel" to outcome.matchedLabel,
                "verifyRequested" to !verifyText.isNullOrBlank(),
            )
            if (outcome.success) {
                ToolOutcome.ok("已点击包含\"$text\"的控件", details)
            } else {
                ToolOutcome.error("未找到或点击后未验证包含\"$text\"的控件", details)
            }
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
                name = "screen_pinch",
                description = "双指缩放手势(放大/缩小)。两指以 (center_x, center_y) 为中心对称开合。" +
                    "需要无障碍通道;Shizuku/Root 的 input 命令不支持多指。",
                parameters = mapOf(
                    "center_x" to "必填,缩放中心 X 坐标",
                    "center_y" to "必填,缩放中心 Y 坐标",
                    "scale" to "必填,缩放倍数(>1 放大,<1 缩小),如 2.0 / 0.5",
                    "duration_ms" to "可选,手势时长(毫秒),默认 300",
                ),
                required = setOf("center_x", "center_y", "scale"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val cx = args["center_x"]?.toIntOrNull() ?: return@register "错误:center_x 必须是整数"
            val cy = args["center_y"]?.toIntOrNull() ?: return@register "错误:center_y 必须是整数"
            val scale = args["scale"]?.toFloatOrNull() ?: return@register "错误:scale 必须是数字"
            if (scale <= 0f) return@register "错误:scale 必须大于 0"
            val dur = args["duration_ms"]?.toLongOrNull() ?: 300L
            // 以 300px 为基准指距,按 scale 换算起止指距(有界,防越界)
            val base = 300
            val end = (base * scale).toInt().coerceIn(60, 2000)
            if (manager.pinch(cx, cy, base, end, dur)) "已缩放 (scale=$scale)" else "缩放失败(需要无障碍通道)"
        }

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_swipe_path",
                description = "多段滑动:单指依次经过多个路径点(至少 2 个),适合解锁图案/复杂拖拽。",
                parameters = mapOf(
                    "points" to "必填,路径点列表,格式 \"x1,y1;x2,y2;x3,y3\"(至少 2 个点)",
                    "duration_ms" to "可选,总时长(毫秒),默认 400",
                ),
                required = setOf("points"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val raw = args["points"] ?: return@register "错误:缺少 points"
            val points = raw.split(";").mapNotNull { seg ->
                val p = seg.split(",")
                if (p.size == 2) {
                    val x = p[0].trim().toIntOrNull() ?: return@mapNotNull null
                    val y = p[1].trim().toIntOrNull() ?: return@mapNotNull null
                    x to y
                } else {
                    null
                }
            }
            if (points.size < 2) return@register "错误:至少需要 2 个合法点(格式 x,y;x,y)"
            val dur = args["duration_ms"]?.toLongOrNull() ?: 400L
            if (manager.swipePath(points, dur)) "已执行 ${points.size} 点路径滑动" else "路径滑动失败"
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
            // v2.7: 实时快照 —— 无障碍可能刚开启而缓存未刷新（见 AutomationManager.liveSnapshot）。
            val state = manager.liveSnapshot()
            val level = manager.highestLevel()
            buildString {
                appendLine("UI 自动化权限状态:")
                appendLine("- 无障碍: ${if (state.accessibilityEnabled) "已开启" else "未开启"}")
                appendLine("- Shell: ${if (state.shellEnabled) "已就绪" else state.shizukuMessage}")
                appendLine("- Root: ${if (state.rootEnabled) "已获取" else "未获取"}")
                appendLine("- 最高可用层级: $level")
            }
        }

        // ── 等待原语（感知类）─────────────────────────────

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_wait",
                description = "Wait before the next action: idle = until the screen stops changing; " +
                    "text = until a text appears (or disappears with appear=false); " +
                    "window = until the foreground app/activity changes. " +
                    "Use after launching an app or navigating so clicks do not fire too early.",
                parameters = mapOf(
                    "mode" to "Optional. idle | text | window (default idle)",
                    "text" to "Required when mode=text. Substring to look for",
                    "appear" to "Optional. true=wait for appearance (default), false=disappearance",
                    "timeout_ms" to "Optional. Max wait ms, default 15000, cap 60000",
                ),
                required = emptySet(),
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args ->
            val timeout = args["timeout_ms"]?.toLongOrNull()?.coerceIn(1_000L, 60_000L) ?: 15_000L
            val snapshotProvider: suspend () -> io.zer0.muse.automation.core.ScreenSnapshot = {
                io.zer0.muse.automation.core.ScreenSnapshot(info = manager.readScreen())
            }
            when (args["mode"]?.lowercase() ?: "idle") {
                "text" -> {
                    val text = args["text"]?.takeIf { it.isNotBlank() }
                    if (text == null) {
                        "error: text is required when mode=text"
                    } else {
                        val appear = args["appear"]?.lowercase() != "false"
                        val ok = io.zer0.muse.automation.core.WaitPrimitives.waitForText(
                            text = text,
                            appear = appear,
                            snapshotProvider = snapshotProvider,
                            timeoutMs = timeout,
                        )
                        if (ok) "text condition satisfied" else "timeout after ${timeout}ms"
                    }
                }
                "window" -> {
                    val before = manager.readScreen()
                    val ok = io.zer0.muse.automation.core.WaitPrimitives.waitForWindowChange(
                        initialPackage = before.packageName,
                        initialActivity = before.activityName,
                        snapshotProvider = snapshotProvider,
                        timeoutMs = timeout,
                    )
                    if (ok) "foreground window changed" else "timeout after ${timeout}ms"
                }
                else -> {
                    val ok = io.zer0.muse.automation.core.WaitPrimitives.waitForIdle(
                        snapshotProvider = snapshotProvider,
                        timeoutMs = timeout,
                    )
                    if (ok) "screen is idle" else "timeout after ${timeout}ms"
                }
            }
        }

        // ── 设备命令通道（自动化一期）─────────────────────────

        registry.register(
            ToolRegistry.ToolDef(
                name = "device_shell",
                description = "执行单条设备命令（命令行操控手机），需要 Shizuku 或 Root 授权。" +
                    "支持：input(tap/swipe/text/keyevent/roll/draganddrop/press)、am(start/broadcast/force-stop/startservice/kill)、" +
                    "pm(list/path/dump/enable/disable/grant/revoke/clear)、settings(get/put/delete/list)、svc(power/wifi/data/bluetooth)、" +
                    "wm(size/density)、dumpsys、uiautomator dump、screencap、screendump、content(query/insert/update/delete)、cmd、getprop、date。" +
                    "不支持管道/重定向/多命令拼接——每次写一条完整命令。与 screen_* 工具互补：screen_* 是高层动作，本工具是命令行自由度。",
                parameters = mapOf(
                    "command" to "必填。单条设备命令，如 input tap 540 1200 / input swipe 540 1500 540 500 300 / " +
                        "am start -n com.tencent.mm/.ui.LauncherUI / settings get secure location_mode / uiautomator dump",
                ),
                required = setOf("command"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val command = args["command"]?.trim().orEmpty()
            when (val check = io.zer0.muse.automation.core.DeviceCommandPolicy.validate(command)) {
                is io.zer0.muse.automation.core.DeviceCommandPolicy.Check.Invalid ->
                    "命令被拒绝: ${check.reason}"
                io.zer0.muse.automation.core.DeviceCommandPolicy.Check.Valid -> {
                    val result = manager.execTiered(command)
                        ?: return@register "设备命令通道不可用：需要 Shizuku 或 Root 授权（设置 → 权限配置向导），当前两档均未就绪"
                    val (tier, detail) = result
                    buildString {
                        append("[").append(tier).append("] exit=").append(detail.exitCode).append('\n')
                        if (detail.output.isNotBlank()) {
                            append(detail.output)
                        } else {
                            append("(无输出)")
                        }
                    }
                }
            }
        }
    }

    private fun registerWorkflow(registry: ToolRegistry) {
        registry.registerOutcome(
            ToolRegistry.ToolDef(
                name = "automation_workflow",
                description = "执行可恢复的受控手机工作流，按序运行前台语义动作与 virtual_* 虚拟屏动作；" +
                    "支持 launch/tap_text/input_text/swipe/back/home/wait/read、虚拟屏操作，以及 node_script 执行应用沙盒内 Node 脚本。" +
                    "node_script 可访问应用沙盒文件、网络和子进程；脚本结果使用 Keystore 加密保存于恢复日志。每步失败即停，执行前需审批。",
                parameters = mapOf(
                    "steps" to "必填 JSON 数组，最多 20 步；支持 " +
                        "virtual_ensure/virtual_launch/virtual_tap/virtual_tap_text/virtual_swipe/" +
                        "virtual_text/virtual_key/virtual_wait/virtual_read/virtual_close；" +
                        "virtual_tap_text 支持 text/exact/maxSwipes/verifyText，在虚拟屏内有界滚动并验证点击；" +
                        "node_script 步骤使用 code/timeoutMs (1-300 秒)，失败按未知副作用处理",
                    "run_id" to "可选。恢复之前中断的工作流；首次运行留空会生成新的 runId",
                    "retry_unknown" to "可选。某步骤崩溃时结果可能未知；仅在确认后设 true 才重试该动作",
                ),
                required = setOf("steps"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val json = args["steps"]?.trim()
                ?: return@registerOutcome ToolOutcome.error("错误:缺少 steps 参数")
            val steps = AutomationWorkflowParser.parse(json).getOrElse { error ->
                return@registerOutcome ToolOutcome.error("错误:steps 无效:${error.message}")
            }
            val runId = args["run_id"]?.trim().takeIf { !it.isNullOrBlank() } ?: "automation-${UUID.randomUUID()}"
            val retryUnknown = args["retry_unknown"]?.equals("true", ignoreCase = true) == true
            AutomationWorkflow(
                manager,
                workflowJournal,
                virtualDisplayClient,
                virtualDisplayManager,
                nodeScriptExecutor,
                displayLeases,
            ).run(steps, runId, retryUnknown)
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
