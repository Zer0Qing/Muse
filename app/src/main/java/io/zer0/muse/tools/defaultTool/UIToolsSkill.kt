package io.zer0.muse.tools.defaultTool

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel
import io.zer0.muse.tools.system.AccessibilityClient
import java.io.File

/**
 * v1.0.52 P3-3: UI 自动化工具集 — 暴露给 AI 的设备操控能力。
 *
 * 依赖 [AccessibilityClient](无障碍服务通道),提供 10 个工具:
 *  - [getPageInfo]: 获取当前页面 UI 层级(节点树,含坐标/文本/可点击性)
 *  - [click] / [longPress]: 在坐标点击/长按
 *  - [swipe]: 滑动手势(指定起止坐标 + 时长)
 *  - [setText]: 在指定节点(通过 path 标识)输入文本
 *  - [screenshot]: 截图保存到文件(需 Android 14+)
 *  - [back] / [home]: 全局动作快捷键
 *  - [globalAction]: 执行任意全局无障碍动作
 *  - [getCurrentApp]: 查询当前前台应用
 *
 * 安全:
 *  - 全部 HIGH 风险等级(直接操控用户设备,可能触发不可逆操作)
 *  - 需无障碍服务已启用(ACCESSIBILITY 及以上等级)
 *  - 会话权限体系会要求审批(非 TRUSTED 模式)
 *
 * 返回格式:
 *  - 成功: 可读文本(JSON 或结构化文本)
 *  - 失败: "[错误] ..." 形式
 */
object UIToolsSkill {

    private const val TAG = "UIToolsSkill"

    // ── 工具名常量 ────────────────────────────────────────────────────────────

    const val GET_PAGE_INFO = "ui_get_page_info"
    const val CLICK = "ui_click"
    const val LONG_PRESS = "ui_long_press"
    const val SWIPE = "ui_swipe"
    const val SET_TEXT = "ui_set_text"
    const val SCREENSHOT = "ui_screenshot"
    const val BACK = "ui_back"
    const val HOME = "ui_home"
    const val GLOBAL_ACTION = "ui_global_action"
    const val GET_CURRENT_APP = "ui_get_current_app"

    // ── 工具定义 ──────────────────────────────────────────────────────────────

    fun toolDefs(): List<ToolRegistry.ToolDef> = listOf(
        ToolRegistry.ToolDef(
            name = GET_PAGE_INFO,
            description = "获取当前屏幕的 UI 层级信息(节点树)。每个节点包含路径标识(如 0.1.2)、" +
                "class、text、bounds(坐标范围)、clickable/editable/focused 状态。" +
                "用于了解当前页面有哪些可交互元素及其坐标。需先启用无障碍服务。",
            parameters = emptyMap(),
            required = emptySet(),
            category = "built-in",
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = CLICK,
            description = "在屏幕指定坐标执行点击。坐标为屏幕绝对像素。" +
                "建议先用 ui_get_page_info 获取目标元素的 bounds 再点击中心点。",
            parameters = mapOf(
                "x" to "必填,点击的 x 坐标(屏幕像素)",
                "y" to "必填,点击的 y 坐标(屏幕像素)",
            ),
            required = setOf("x", "y"),
            category = "built-in",
            parameterTypes = mapOf("x" to "integer", "y" to "integer"),
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = LONG_PRESS,
            description = "在屏幕指定坐标执行长按(按住 500ms)。常用于弹出上下文菜单。",
            parameters = mapOf(
                "x" to "必填,长按的 x 坐标",
                "y" to "必填,长按的 y 坐标",
            ),
            required = setOf("x", "y"),
            category = "built-in",
            parameterTypes = mapOf("x" to "integer", "y" to "integer"),
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = SWIPE,
            description = "在屏幕执行滑动手势。从起点滑到终点,duration 控制速度(越长越慢)。" +
                "上滑翻页:startY 大 endY 小;下滑反之。",
            parameters = mapOf(
                "start_x" to "必填,起点 x 坐标",
                "start_y" to "必填,起点 y 坐标",
                "end_x" to "必填,终点 x 坐标",
                "end_y" to "必填,终点 y 坐标",
                "duration" to "可选,滑动时长(毫秒),默认 300,越大越慢",
            ),
            required = setOf("start_x", "start_y", "end_x", "end_y"),
            category = "built-in",
            parameterTypes = mapOf(
                "start_x" to "integer", "start_y" to "integer",
                "end_x" to "integer", "end_y" to "integer", "duration" to "integer",
            ),
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = SET_TEXT,
            description = "在指定节点上设置文本(通过 ACTION_SET_TEXT)。" +
                "node_id 来自 ui_get_page_info 返回的路径标识(如 0.1.2)或 ui_get_page_info 中的 focused 节点。" +
                "用于在输入框填写内容。",
            parameters = mapOf(
                "node_id" to "必填,目标节点路径(来自 ui_get_page_info 的 [path])",
                "text" to "必填,要输入的文本",
            ),
            required = setOf("node_id", "text"),
            category = "built-in",
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = SCREENSHOT,
            description = "截取当前屏幕并保存为图片。需 Android 14+(API 34)。" +
                "返回保存路径。不传 path 时保存到应用缓存目录。",
            parameters = mapOf(
                "path" to "可选,保存路径(绝对路径),默认保存到缓存目录",
                "format" to "可选,图片格式 PNG(默认) 或 JPEG",
            ),
            required = emptySet(),
            category = "built-in",
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = BACK,
            description = "模拟按下返回键(全局动作 GLOBAL_ACTION_BACK)。",
            parameters = emptyMap(),
            required = emptySet(),
            category = "built-in",
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = HOME,
            description = "模拟按下 Home 键(回到桌面,GLOBAL_ACTION_HOME)。",
            parameters = emptyMap(),
            required = emptySet(),
            category = "built-in",
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = GLOBAL_ACTION,
            description = "执行全局无障碍动作。常用 action_id: 1=BACK, 2=HOME, 3=RECENTS(最近任务)," +
                " 4=NOTIFICATIONS, 5=QUICK_SETTINGS, 6=POWER_DIALOG, 7=TOGGLE_NOTIFICATION, 10=DISMISS_NOTIFICATION_SHADE。",
            parameters = mapOf(
                "action_id" to "必填,全局动作 ID(如 1=返回, 2=HOME)",
            ),
            required = setOf("action_id"),
            category = "built-in",
            parameterTypes = mapOf("action_id" to "integer"),
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = GET_CURRENT_APP,
            description = "获取当前前台应用/Activity 的组件名(如 com.android.settings/.Settings)。" +
                "用于了解用户当前在哪个应用。",
            parameters = emptyMap(),
            required = emptySet(),
            category = "built-in",
            riskLevel = ToolRiskLevel.HIGH,
        ),
    )

    // ── 执行逻辑 ──────────────────────────────────────────────────────────────

    suspend fun getPageInfo(client: AccessibilityClient): String {
        if (!ensureConnected(client)) return "[错误] 无障碍服务未启用"
        val info = client.getPageInfo()
        return info.ifBlank { "[错误] 无法获取 UI 层级(可能无活动窗口)" }
    }

    suspend fun click(client: AccessibilityClient, x: Int, y: Int): String {
        if (!ensureConnected(client)) return "[错误] 无障碍服务未启用"
        val ok = client.click(x, y)
        return if (ok) "[成功] 已点击 ($x, $y)" else "[失败] 点击未完成(坐标越界或手势被取消)"
    }

    suspend fun longPress(client: AccessibilityClient, x: Int, y: Int): String {
        if (!ensureConnected(client)) return "[错误] 无障碍服务未启用"
        val ok = client.longPress(x, y)
        return if (ok) "[成功] 已长按 ($x, $y)" else "[失败] 长按未完成"
    }

    suspend fun swipe(
        client: AccessibilityClient,
        startX: Int, startY: Int, endX: Int, endY: Int, duration: Long,
    ): String {
        if (!ensureConnected(client)) return "[错误] 无障碍服务未启用"
        val ok = client.swipe(startX, startY, endX, endY, duration)
        return if (ok) "[成功] 已滑动 ($startX,$startY) -> ($endX,$endY)" else "[失败] 滑动未完成"
    }

    suspend fun setText(client: AccessibilityClient, nodeId: String, text: String): String {
        if (!ensureConnected(client)) return "[错误] 无障碍服务未启用"
        if (nodeId.isBlank()) return "[错误] 缺少 node_id 参数"
        val ok = client.setText(nodeId, text)
        return if (ok) "[成功] 已在节点 $nodeId 输入文本" else "[失败] 设置文本失败(节点不可编辑或不存在)"
    }

    suspend fun screenshot(client: AccessibilityClient, context: Context, path: String?, format: String): String {
        if (!ensureConnected(client)) return "[错误] 无障碍服务未启用"
        val targetPath = path?.takeIf { it.isNotBlank() } ?: run {
            val ext = if (format.equals("JPEG", ignoreCase = true)) ".jpg" else ".png"
            File(context.cacheDir, "ui_screenshot_${System.currentTimeMillis()}$ext").absolutePath
        }
        val ok = client.screenshot(targetPath, format)
        if (ok) return "[成功] 截图已保存: $targetPath"
        val hint = if (client.screenshotCapabilityFailed()) {
            "截图能力不可用，请使用系统截图"
        } else {
            "需 Android 14+ 或服务异常"
        }
        return "[失败] 截图失败($hint)"
    }

    suspend fun back(client: AccessibilityClient): String {
        if (!ensureConnected(client)) return "[错误] 无障碍服务未启用"
        val ok = client.globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        return if (ok) "[成功] 已返回" else "[失败] 返回动作失败"
    }

    suspend fun home(client: AccessibilityClient): String {
        if (!ensureConnected(client)) return "[错误] 无障碍服务未启用"
        val ok = client.globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        return if (ok) "[成功] 已回到桌面" else "[失败] HOME 动作失败"
    }

    suspend fun globalAction(client: AccessibilityClient, actionId: Int): String {
        if (!ensureConnected(client)) return "[错误] 无障碍服务未启用"
        val ok = client.globalAction(actionId)
        return if (ok) "[成功] 全局动作 $actionId 已执行" else "[失败] 全局动作 $actionId 失败"
    }

    suspend fun getCurrentApp(client: AccessibilityClient): String {
        if (!ensureConnected(client)) return "[错误] 无障碍服务未启用"
        val name = client.currentActivityName()
        return name.ifBlank { "[提示] 当前无法确定前台应用" }
    }

    // ── 内部工具 ──────────────────────────────────────────────────────────────

    private suspend fun ensureConnected(client: AccessibilityClient): Boolean {
        // P3-3: 静态实例方案下,连接由系统管理 — 服务启用后 instance 自动可用,
        // app 无法主动 bindService(原 AIDL 方案的 connect() 已移除)。
        // 这里仅做状态检查:已连接则放行,否则让调用方返回"未启用"错误。
        return client.isConnected()
    }
}

/**
 * UI 工具注册器 — 把 10 个 UI 工具注册到 [ToolRegistry]。
 *
 * 依赖 ToolRegistry + AccessibilityClient + Context。
 * 在 AppKoinModule 初始化时创建(无障碍服务启用后工具才可用,但注册始终进行,
 * 执行时由 [UIToolsSkill.ensureConnected] 检查连接状态)。
 */
class UIToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val accessibilityClient: AccessibilityClient,
    private val context: Context,
) {
    init { registerAll() }

    fun registerAll() {
        val client = accessibilityClient
        // 工具名 -> 执行 lambda 的映射,统一参数解析
        val executors: List<Pair<ToolRegistry.ToolDef, suspend (Map<String, String>) -> String>> = listOf(
            UIToolsSkill.toolDefs()[0] to { UIToolsSkill.getPageInfo(client) },
            UIToolsSkill.toolDefs()[1] to { args ->
                val x = args["x"]?.toIntOrNull() ?: return@to "[错误] 缺少或无效的 x 参数"
                val y = args["y"]?.toIntOrNull() ?: return@to "[错误] 缺少或无效的 y 参数"
                UIToolsSkill.click(client, x, y)
            },
            UIToolsSkill.toolDefs()[2] to { args ->
                val x = args["x"]?.toIntOrNull() ?: return@to "[错误] 缺少或无效的 x 参数"
                val y = args["y"]?.toIntOrNull() ?: return@to "[错误] 缺少或无效的 y 参数"
                UIToolsSkill.longPress(client, x, y)
            },
            UIToolsSkill.toolDefs()[3] to { args ->
                val sx = args["start_x"]?.toIntOrNull() ?: return@to "[错误] 缺少 start_x"
                val sy = args["start_y"]?.toIntOrNull() ?: return@to "[错误] 缺少 start_y"
                val ex = args["end_x"]?.toIntOrNull() ?: return@to "[错误] 缺少 end_x"
                val ey = args["end_y"]?.toIntOrNull() ?: return@to "[错误] 缺少 end_y"
                val dur = args["duration"]?.toLongOrNull() ?: 300L
                UIToolsSkill.swipe(client, sx, sy, ex, ey, dur)
            },
            UIToolsSkill.toolDefs()[4] to { args ->
                val nodeId = args["node_id"] ?: return@to "[错误] 缺少 node_id"
                val text = args["text"] ?: return@to "[错误] 缺少 text"
                UIToolsSkill.setText(client, nodeId, text)
            },
            UIToolsSkill.toolDefs()[5] to { args ->
                val path = args["path"]
                val format = args["format"]?.takeIf { it.isNotBlank() } ?: "PNG"
                UIToolsSkill.screenshot(client, context, path, format)
            },
            UIToolsSkill.toolDefs()[6] to { UIToolsSkill.back(client) },
            UIToolsSkill.toolDefs()[7] to { UIToolsSkill.home(client) },
            UIToolsSkill.toolDefs()[8] to { args ->
                val actionId = args["action_id"]?.toIntOrNull() ?: return@to "[错误] 缺少或无效的 action_id"
                UIToolsSkill.globalAction(client, actionId)
            },
            UIToolsSkill.toolDefs()[9] to { UIToolsSkill.getCurrentApp(client) },
        )
        executors.forEach { (def, fn) ->
            toolRegistry.register(def, fn)
        }
        Logger.i("UIToolsRegistrar", "已注册 ${executors.size} 个 UI 工具")
    }
}
