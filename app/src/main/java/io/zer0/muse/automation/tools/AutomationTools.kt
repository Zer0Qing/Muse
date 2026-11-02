package io.zer0.muse.automation.tools

import io.zer0.common.Logger
import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.automation.core.DeviceCommandPolicy
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
 * 工具分两类（注意：风险等级以代码为准，下面这句曾经写反过）:
 * - 感知类(读屏/查前台): **HIGH** —— 屏幕内容可能含聊天/账号等敏感信息，
 *   2026-10 的加固提交把它们从 NORMAL 提到 HIGH，需要用户确认；
 *   想少点确认就在审批面板勾「本次开启期间批准全部」。
 * - 操作类(点击/滑动/输入/按键/启动App): HIGH/NORMAL，受 ToolRiskLevel 管控。
 * - 等待原语(screen_wait): NORMAL —— 不改变设备状态。
 *
 * AI 调用这些工具后可以实现跨 App 的任务自动化(类似豆包手机的系统级助手)。
 */
class AutomationTools(
    private val manager: AutomationManager,
    private val workflowJournal: WorkflowJournal? = null,
    private val virtualDisplayClient: VirtualDisplayClient? = null,
    private val virtualDisplayManager: VirtualDisplayServerManager? = null,
    private val nodeScriptExecutor: (suspend (String, Long) -> io.zer0.muse.tools.script.SkillEngineResult)? = null,
    /**
     * 自动化总闸（设置里的「允许屏幕自动化」）。返回 false 时所有动作/感知工具都快速失败，
     * 并告诉模型去设置页打开 —— 用户需要一次停掉所有动手能力的刹车。
     * 默认恒为 true：单测/未接线场景保持既有行为。
     */
    private val automationEnabled: suspend () -> Boolean = { true },) {
    /** 所有 automation_workflow 工具调用共享的 lease 台账，按 run_id 隔离虚拟屏释放权。 */
    private val displayLeases = VirtualDisplayLeaseRegistry()

    /**
     * 「通过哪一层执行」后缀。
     *
     * appcontrol 的返回一直带 `via`（应用内/Shell/Root），screen_* 却只有 ok/失败 ——
     * 同一个 App 里两套透明度。AutomationManager 已经记了 lastActionTier，这里顺手带出来。
     */
    private fun viaSuffix(): String = when (manager.lastActionTier) {
        io.zer0.muse.automation.core.PermissionLevel.ACCESSIBILITY -> "（通过 无障碍 执行）"
        io.zer0.muse.automation.core.PermissionLevel.SHELL -> "（通过 Shell/Shizuku 执行）"
        io.zer0.muse.automation.core.PermissionLevel.ROOT -> "（通过 Root 执行）"
        io.zer0.muse.automation.core.PermissionLevel.NONE -> ""
    }

    /** 总闸关闭时统一回给模型的话。 */
    private fun automationOffMessage(): String =
        "自动化总闸已关闭（设置 → 自动化 → 允许屏幕自动化）。" +
            "在用户重新打开之前，screen_* / ui_agent / automation_workflow 都不会执行。" +
            "你仍可以调用 screen_open_settings 直接把用户带到那一页（该工具不受总闸限制），" +
            "或用 screen_permission_status 查各通道权限。"

    /** 路径滑动的点数上限：再多也不会是正常手势，只会拖慢或失败。 */
    private val maxSwipePathPoints = 16

    /** 带总闸的注册：闸关时直接返回提示，不进任何执行器。 */
    private fun registerGuarded(
        registry: ToolRegistry,
        def: ToolRegistry.ToolDef,
        block: suspend (Map<String, String>) -> String,
    ) {
        registry.register(def) { args ->
            if (automationEnabled()) block(args) else automationOffMessage()
        }
    }

    /** 带总闸的 Outcome 版注册。 */
    private fun registerOutcomeGuarded(
        registry: ToolRegistry,
        def: ToolRegistry.ToolDef,
        block: suspend (Map<String, String>) -> ToolOutcome,
    ) {
        registry.registerOutcome(def) { args ->
            if (automationEnabled()) block(args) else ToolOutcome.error(automationOffMessage())
        }
    }

    /** 带总闸 + 上下文（进度回调）的 Outcome 注册。 */
    private fun registerOutcomeWithContext(
        registry: ToolRegistry,
        def: ToolRegistry.ToolDef,
        block: suspend (Map<String, String>, io.zer0.muse.tools.ToolExecutionContext?) -> ToolOutcome,
    ) {
        registry.registerOutcomeWithContext(def) { args, context ->
            if (automationEnabled()) block(args, context) else ToolOutcome.error(automationOffMessage())
        }
    }

    fun register(registry: ToolRegistry) {
        registerWorkflow(registry)
        // 15 个内置工具按用途拆成 6 组：原函数 398 行，既超长方法又超圈复杂度，
        // 改一个工具要在 400 行里找位置。分组后每组只负责一类，增删互不干扰。
        registerScreenPerception(registry)
        registerScreenTapTools(registry)
        registerScreenSwipeTools(registry)
        registerScreenSwipePathTools(registry)
        registerScreenInputTools(registry)
        registerScreenDeviceTools(registry)
    }

    /** 感知类：读屏、当前应用、返回/桌面。 */
    private fun registerScreenPerception(registry: ToolRegistry) {
        registerGuarded(registry,
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
            // 发一个观察 id：后续 screen_tap 可以带上它，
            // 期间只要界面变了（或有新的读屏），这个 id 就作废，点击会被拒绝。
            val observation = io.zer0.muse.automation.core.AutomationObservationRegistry.record(
                scope = OBSERVATION_SCOPE,
                fingerprint = io.zer0.muse.automation.core.AutomationObservationRegistry.fingerprintOf(
                    info.packageName,
                    info.activityName,
                    nodes.mapNotNull { it.text },
                ),
                now = System.currentTimeMillis(),
            )
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
                appendLine("observation_id=${observation.id}")
            }
        }

        registerGuarded(registry,
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

        registerGuarded(registry,
            ToolRegistry.ToolDef(
                name = "screen_back",
                description = "按下系统返回键,回到上一个界面。",
                parameters = emptyMap(),
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { _ ->
            if (manager.back()) "已返回" + viaSuffix() else "返回失败(通道未就绪 —— 先 screen_permission_status)"
        }

        registerGuarded(registry,
            ToolRegistry.ToolDef(
                name = "screen_home",
                description = "按下 Home 键,回到桌面。",
                parameters = emptyMap(),
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { _ ->
            if (manager.home()) "已回到桌面" + viaSuffix() else "回桌面失败(通道未就绪 —— 先 screen_permission_status)"
        }

        // ── 操作类 ──────────────────────────────────────────

    }

    /** 点击类：坐标点击、按文字语义点击。 */
    private fun registerScreenTapTools(registry: ToolRegistry) {
        registerGuarded(registry,
            ToolRegistry.ToolDef(
                name = "screen_tap",
                description = "点击屏幕上的指定坐标(x,y)。坐标来自 screen_read 返回的控件中心位置。" +
                       "**每次调用都是一次真实点击**：不确定上一次是否生效时，先用 screen_read 看界面，" +
                       "不要直接重试 —— 对「付款/发送/删除」这类按钮重复调用会造成两次副作用。" +
                    "不要凭空猜测坐标,必须先 screen_read 确认。",
                parameters = mapOf(
                    "x" to "必填,点击位置 X 坐标(像素)",
                    "y" to "必填,点击位置 Y 坐标(像素)",
                    "observation_id" to "可选,screen_read 返回的 id;带上它可防止在已变化的界面上点击",
                ),
                required = setOf("x", "y"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val x = args["x"]?.toIntOrNull() ?: return@registerGuarded "错误:x 必须是整数"
            val y = args["y"]?.toIntOrNull() ?: return@registerGuarded "错误:y 必须是整数"
            val observationId = args["observation_id"]?.trim()
            if (!observationId.isNullOrBlank() && !io.zer0.muse.automation.core.AutomationObservationRegistry.isFresh(
                    OBSERVATION_SCOPE,
                    observationId,
                    System.currentTimeMillis(),
                )
            ) {
                // 带了这个 id 却已失效，说明模型看到的是过时画面：拒绝点击比点错安全得多
                return@registerGuarded "画面已变化（observation_id 已失效）：请先 screen_read 重新确认界面，再决定是否点击"
            }
            if (manager.tap(x, y)) "已点击 ($x,$y)" + viaSuffix() else "点击失败(坐标越界或通道未就绪 —— 先 screen_read 取真实坐标)"
        }

        registerOutcomeGuarded(registry,
            ToolRegistry.ToolDef(
                name = "screen_tap_text",
                description = "查找屏幕上包含指定文字的控件并点击其中心。" +
                       "**每次调用都是一次真实点击**：若结果不确定，先 screen_read 看界面再决定，别直接重试。" +
                       "（内部已保证「点击发出后不自动重放」，但你的下一次调用就是新的一次点击。）" +
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
            val text = args["text"] ?: return@registerOutcomeGuarded ToolOutcome.error("错误:缺少 text 参数")
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

    }

    /** 滑动类：滑动、双指缩放。 */
    private fun registerScreenSwipeTools(registry: ToolRegistry) {
        registerGuarded(registry,
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
            val x1 = args["x1"]?.toIntOrNull() ?: return@registerGuarded "错误:x1 必须是整数"
            val y1 = args["y1"]?.toIntOrNull() ?: return@registerGuarded "错误:y1 必须是整数"
            val x2 = args["x2"]?.toIntOrNull() ?: return@registerGuarded "错误:x2 必须是整数"
            val y2 = args["y2"]?.toIntOrNull() ?: return@registerGuarded "错误:y2 必须是整数"
            val dur = args["duration_ms"]?.toLongOrNull() ?: 400L
            if (manager.swipe(x1, y1, x2, y2, dur)) "已滑动" + viaSuffix() else "滑动失败(坐标越界或通道未就绪)"
        }

        registerGuarded(registry,
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
            val cx = args["center_x"]?.toIntOrNull() ?: return@registerGuarded "错误:center_x 必须是整数"
            val cy = args["center_y"]?.toIntOrNull() ?: return@registerGuarded "错误:center_y 必须是整数"
            val scale = args["scale"]?.toFloatOrNull() ?: return@registerGuarded "错误:scale 必须是数字"
            if (scale <= 0f) return@registerGuarded "错误:scale 必须大于 0"
            val dur = args["duration_ms"]?.toLongOrNull() ?: 300L
            // 以 300px 为基准指距,按 scale 换算起止指距(有界,防越界)
            val base = 300
            val end = (base * scale).toInt().coerceIn(60, 2000)
            if (manager.pinch(cx, cy, base, end, dur)) "已缩放 (scale=$scale)" + viaSuffix() else "缩放失败(需要无障碍通道；Shell/Root 无多指注入能力)"
        }

    }

    /** 路径滑动（参数最多，单独一组）。 */
    private fun registerScreenSwipePathTools(registry: ToolRegistry) {
        registerGuarded(registry,
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
            val raw = args["points"] ?: return@registerGuarded "错误:缺少 points"
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
            if (points.size < 2) return@registerGuarded "错误:至少需要 2 个合法点(格式 x,y;x,y)"
            if (points.size > maxSwipePathPoints) {
                return@registerGuarded "错误:路径点最多 $maxSwipePathPoints 个(收到 ${points.size})；请简化路径"
            }
            // 多段路径需要无障碍；Shell/Root 只能直线，不能静默降级成错误手势
            if (points.size > 2 && !manager.supportsMultiPointGesture()) {
                return@registerGuarded "失败(多段路径需要无障碍通道；当前只有 Shell/Root，只能做两点直线滑动。" +
                    "请先在设置里开启 Muse 的无障碍，或改用 screen_swipe)"
            }
            val dur = args["duration_ms"]?.toLongOrNull()?.coerceIn(50L, 5_000L) ?: 400L
            if (manager.swipePath(points, dur)) "已执行 ${points.size} 点路径滑动" + viaSuffix() else "路径滑动失败(通道未就绪或点数不足)"
        }

    }

    /** 输入与系统：文本输入、通知栏/权限/设置入口。 */
    private fun registerScreenInputTools(registry: ToolRegistry) {
        registerGuarded(registry,
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
            val text = args["text"] ?: return@registerGuarded "错误:缺少 text 参数"
            if (manager.inputText(text)) "已输入: $text" + viaSuffix() else "输入失败(输入框未聚焦或通道未就绪 —— 先 screen_tap_text 点输入框)"
        }

        // screen_launch_app 已下线：与 app_launch 完全重叠，而且风险等级不一致
        // （这里是 HIGH，app_launch 是 NORMAL）。app_launch 还支持中文应用名，
        // 对模型更友好 —— 只留一个入口，避免「同一件事两个工具、审批行为还不一样」。

        registerGuarded(registry,
            ToolRegistry.ToolDef(
                name = "screen_open_notifications",
                description = "打开系统通知栏,查看通知。",
                parameters = emptyMap(),
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { _ ->
            if (manager.openNotifications()) "已打开通知栏" + viaSuffix() else "打开通知栏失败(通道未就绪)"
        }

        // 状态查询是「修复手段」：总闸关着时更要能用，否则用户无从知道该开什么。
        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_permission_status",
                description = "查询 UI 自动化各层权限的开通状态(无障碍/Shell/Root)。" +
                    "在尝试任何操作前应先调用此工具确认能力。",
                parameters = emptyMap(),
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { _ ->
            // E3: 这里必须做**完整**刷新，不能只刷无障碍。
            // liveSnapshot() 只重新探测无障碍；用户刚在 Shizuku 里授权完再来问，
            // 会看到「Shell 未就绪」——工具其实已经能用了，这种状态不一致最费解。
            // refreshPermissions() 会真的 bind 一次 Shizuku 服务并探测 su。
            val state = manager.refreshPermissions()
            val level = manager.highestLevel()
            buildString {
                appendLine("UI 自动化权限状态:")
                appendLine("- 无障碍: ${if (state.accessibilityEnabled) "已开启" else "未开启"}")
                appendLine("- Shell: ${if (state.shellEnabled) "已就绪" else state.shizukuMessage}")
                appendLine("- Root: ${if (state.rootEnabled) "已获取" else "未获取"}")
                appendLine("- 最高可用层级: $level")
                val virtualState = virtualDisplayClient?.let { client ->
                    runCatching { client.availability() }.getOrElse { "探测失败" }
                } ?: "未接线"
                appendLine("- 虚拟屏: $virtualState")
                append(permissionGuidance(state))
            }
        }

        // 打开设置页也是修复手段：总闸关着时它正是模型该调的那个工具。
        registry.registerOutcome(
            ToolRegistry.ToolDef(
                name = "screen_open_settings",
                description = "Open the system settings page needed to grant automation access. " +
                    "Use it when screen_permission_status says a channel is off: it takes the user " +
                    "straight to the right page instead of telling them to hunt through menus. " +
                    "target: accessibility | notification_access | overlay | battery | app_details.",
                parameters = mapOf(
                    "target" to "Required. accessibility | notification_access | overlay | battery | app_details",
                ),
                required = setOf("target"),
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args ->
            val target = AutomationManager.SystemSettingsTarget.fromWire(args["target"])
                ?: return@registerOutcome ToolOutcome.error(
                    "错误: target 只支持 accessibility / notification_access / overlay / battery / app_details",
                )
            val started = manager.openSystemSettings(target)
            if (started) {
                ToolOutcome.ok(
                    "已打开系统设置页(${target.wireName})。请让用户在该页面完成授权，" +
                        "完成后再次调用 screen_permission_status 确认。",
                )
            } else {
                ToolOutcome.error("错误: 这台设备上没有可用的设置页入口(${target.wireName})")
            }
        }

        // ── 等待原语（感知类）─────────────────────────────

    }

    /** 等待与特权 Shell。 */
    private fun registerScreenDeviceTools(registry: ToolRegistry) {
        registerGuarded(registry,
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
            // 没有任何通道时，「等屏幕静止」是假的：readScreen 拿不到控件树，
            // 两次快照必然"相同"，waitForIdle 会立刻返回 true —— 模型以为等到了，
            // 其实是空等。这里直接说清楚，别让它带着错误的信心继续点。
            if (manager.highestLevel() == io.zer0.muse.automation.core.PermissionLevel.NONE) {
                return@registerGuarded "无法等待：当前没有任何可用通道（无障碍 / Shell / Root 都不可用）。" +
                    "先调 screen_permission_status 看缺哪层，再用 screen_open_settings 打开对应设置页；" +
                    "在权限就绪前，请改用固定延时（如 sleep 1s）再截图确认。"
            }
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

        registerGuarded(registry,
            ToolRegistry.ToolDef(
                name = "device_shell",
                description = deviceShellDescription(),
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
                        ?: return@registerGuarded "设备命令通道不可用：需要 Shizuku 或 Root 授权（设置 → 权限配置向导），当前两档均未就绪"
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
        registerOutcomeWithContext(registry,
            ToolRegistry.ToolDef(
                name = "automation_workflow",
                description = "执行可恢复的受控手机工作流，按序运行前台语义动作与 virtual_* 虚拟屏动作；" +
                    "支持 launch/tap_text/input_text/swipe/back/home/wait/read、虚拟屏操作，以及 node_script 执行应用沙盒内 Node 脚本。" +
                    "node_script 可访问应用沙盒文件、网络和子进程；脚本结果使用 Keystore 加密保存于恢复日志。每步失败即停，执行前需审批。" +
                    "**你已经知道该点哪里/该输什么**时用它：确定性执行、可断点续跑、不消耗视觉 token；" +
                    "不知道界面长什么样时改用 ui_agent（视觉模型边看边点）。",
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
        ) { args, executionContext ->
            val json = args["steps"]?.trim()
                ?: return@registerOutcomeWithContext ToolOutcome.error("错误:缺少 steps 参数")
            val steps = AutomationWorkflowParser.parse(json).getOrElse { error ->
                return@registerOutcomeWithContext ToolOutcome.error("错误:steps 无效:${error.message}")
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
            ).run(
                steps = steps,
                runId = runId,
                retryUnknown = retryUnknown,
                onProgress = { msg -> executionContext?.onProgress?.invoke(msg) },
            )
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


/**
 * 缺哪一层就说清怎么开、用哪个工具开。
 *
 * 以前这里只输出「未开启」，模型只能回一句「请用户去设置里开一下」——
 * 用户得自己在十几层菜单里找。对 personal agent 来说，这是断头路。
 */
internal fun permissionGuidance(state: AutomationManager.PermissionState): String {
    if (state.accessibilityEnabled && state.shellEnabled) return ""
    return buildString {
        appendLine()
        appendLine("需要开通的通道（用 screen_open_settings 把用户送到对应页面）:")
        if (!state.accessibilityEnabled) {
            appendLine("- 无障碍: target=accessibility —— 读控件树 + 手势注入 + 输入，最常用；不能截屏")
        }
        if (!state.shellEnabled && !state.rootEnabled) {
            appendLine("- Shell(Shizuku/adb): 可截屏、可操作系统界面；需在 Shizuku 里授权 Muse")
        }
        append("开通后再次调用 screen_permission_status 确认。")
    }
}


/**
 * device_shell 的工具描述。
 *
 * 抽成函数是为了能写测试：描述里列的动词必须和 [DeviceCommandPolicy] 的白名单一致 ——
 * 描述写着一个命令、策略却拒绝它，比没描述更糟（模型会一直重试同一个被拒的命令）。
 */
internal fun deviceShellDescription(): String =
    "执行单条设备命令（命令行操控手机），需要 Shizuku 或 Root 授权。" +
        "**会改变设备状态**；只读查文件请用 execute_shell（沙箱白名单，不会动设备）。" +
        "允许的动词: " + DeviceCommandPolicy.allowedVerbs.joinToString("/") + "。" +
        "input 支持 tap/swipe/draganddrop/text/keyevent/roll/press/motionevent；" +
        "am 支持 start/startservice/broadcast/force-stop/kill；" +
        "pm 支持 list/path/dump/enable/disable/grant/revoke/clear；" +
        "settings 支持 get/put/delete/list；svc 支持 power/wifi/data/bluetooth；wm 支持 size/density；" +
        "dumpsys/screencap/screendump/getprop/date 参数自由；uiautomator 仅 dump。" +
        "不支持管道/重定向/多命令拼接 —— 每次写一条完整命令。" +
        "与 screen_* 工具互补：screen_* 是高层动作，本工具是命令行自由度。"

/** 默认显示屏的观察 scope（虚拟屏有各自的 displayId，不共用这份台账）。 */
private const val OBSERVATION_SCOPE = "main-display"
