package io.zer0.muse.automation.vdisplay

import android.content.Context
import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.automation.core.DeviceCommandPolicy
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel
import kotlinx.coroutines.delay
import java.io.File

/**
 * v2.2.1 虚拟屏:AI 工具 `virtual_screen`。
 *
 * 把虚拟屏能力注册为工具:ensure(建屏)/ open(屏内开应用)/ read(控件树)/ shot(截图)/
 * close(销毁)/ status(状态)。输入动作另注册为高风险 `virtual_screen_input`,
 * 由本类统一构造并校验 `input -d <displayId> ...`，避免 Agent 退回 device_shell
 * 自己拼接命令。
 */
class VirtualDisplayTool(
    private val context: Context,
    private val client: VirtualDisplayClient,
    private val manager: VirtualDisplayServerManager,
    private val automationManager: AutomationManager,
) {
    fun register(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "virtual_screen",
                description =
                "虚拟屏(后台隐藏屏幕):在独立显示里运行应用,截图/操作不占用前台、不打扰用户。" +
                    "action=ensure 创建或复用虚拟屏(返回 displayId);action=open 在虚拟屏里打开应用(需 package);" +
                    "action=shot 截取虚拟屏画面(保存为 JPEG 文件,返回路径);action=close 销毁虚拟屏;" +
                    "action=status 查询状态。在虚拟屏里点按/滑动/按键:用 device_shell 执行 " +
                    "`input -d <displayId> tap/swipe/keyevent ...`。需要 Shizuku 或 Root 权限通道(设置→权限向导)。",
                parameters =
                mapOf(
                    "action" to "必填:ensure | open | read | shot | close | status",
                    "package" to "open 时的应用包名(如 com.android.settings)",
                    "width" to "可选:虚拟屏宽度(默认 720)",
                    "height" to "可选:虚拟屏高度(默认 1280)",
                    "dpi" to "可选:虚拟屏密度(默认 320)",
                    "display" to "可选:指定 displayId(默认最近一次 ensure 的)",
                ),
                required = setOf("action"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            when (val action = args["action"]?.trim()?.lowercase()) {
                "ensure" -> handleEnsure(args)
                "open" -> handleOpen(args)
                "read" -> handleRead(args)
                "shot" -> handleShot(args)
                "close" -> handleClose(args)
                "status" -> handleStatus()
                null, "" -> "错误:缺少 action 参数(ensure/open/read/shot/close/status)"
                else -> "错误:未知 action '$action'(ensure/open/read/shot/close/status)"
            }
        }

        registry.register(
            ToolRegistry.ToolDef(
                name = "virtual_screen_input",
                description = "在后台虚拟屏内执行一次输入动作。先用 virtual_screen shot 确认画面，" +
                    "再调用 tap/swipe/text/key/wait；动作只作用于指定 display，不切换用户前台。" +
                    "需要 Shizuku 或 Root，属于高风险跨应用操作，每次执行前需审批。",
                parameters = mapOf(
                    "action" to "必填:tap | swipe | text | key | wait",
                    "display" to "可选:displayId，默认最近一次 ensure 的虚拟屏",
                    "x" to "tap 的 X 坐标",
                    "y" to "tap 的 Y 坐标",
                    "x1" to "swipe 起点 X",
                    "y1" to "swipe 起点 Y",
                    "x2" to "swipe 终点 X",
                    "y2" to "swipe 终点 Y",
                    "duration_ms" to "swipe 持续毫秒，默认 400",
                    "text" to "text 输入内容，最多 500 字符",
                    "key" to "key 按键名或数字，如 BACK/HOME/ENTER/KEYCODE_4",
                    "ms" to "wait 等待毫秒，50-10000",
                ),
                required = setOf("action"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args -> handleInput(args) }
    }

    private suspend fun handleEnsure(args: Map<String, String>): String {
        val width = args["width"]?.toIntOrNull() ?: 720
        val height = args["height"]?.toIntOrNull() ?: 1280
        val dpi = args["dpi"]?.toIntOrNull() ?: 320
        if (width !in 320..1920 || height !in 320..2560) {
            return "错误:尺寸超范围(宽 320-1920,高 320-2560)"
        }
        return client.ensureDisplay(width, height, dpi).fold(
            onSuccess = {
                "虚拟屏已就绪:displayId=${it.displayId}(${it.width}x${it.height})。" +
                    "用 virtual_screen open 打开应用,用 device_shell `input -d ${it.displayId} ...` 做输入。"
            },
            onFailure = { "虚拟屏创建失败:${it.message}" },
        )
    }

    private suspend fun handleOpen(args: Map<String, String>): String {
        val pkg = args["package"]?.trim().orEmpty()
        if (pkg.isBlank()) return "错误:open 需要 package 参数"
        val explicitDisplayId = args["display"]?.toIntOrNull()
        val displayId = explicitDisplayId ?: manager.lastDisplayId
        if (displayId < 0) {
            val ensured = handleEnsure(args)
            if (!ensured.startsWith("虚拟屏已就绪")) return ensured
        }
        val id = if (displayId >= 0) displayId else manager.lastDisplayId
        return if (client.openApp(pkg, id)) {
            val actualId = explicitDisplayId ?: manager.lastDisplayId
            "已在虚拟屏 $actualId 打开 $pkg(等待 1-2 秒渲染后可用 shot 截图)"
        } else {
            "打开失败:无法解析 $pkg 的启动活动(应用未安装或被系统限制)"
        }
    }

    private suspend fun handleShot(args: Map<String, String>): String {
        val displayId = args["display"]?.toIntOrNull() ?: manager.lastDisplayId
        val bytes =
            client.screenshot(displayId)
                ?: return "截图失败:虚拟屏未创建或服务端未就绪(先执行 action=ensure)"
        return runCatching {
            val dir = File(context.filesDir, "vd").apply { mkdirs() }
            val file = File(dir, "shot_${System.currentTimeMillis()}.jpg")
            file.writeBytes(bytes)
            "截图已保存:${file.absolutePath}(${bytes.size / 1024}KB,displayId=$displayId)"
        }.getOrElse { "截图保存失败:${it.message}" }
    }

    private suspend fun handleRead(args: Map<String, String>): String {
        val displayId = args["display"]?.toIntOrNull() ?: manager.lastDisplayId
        val info = automationManager.readScreenOnDisplay(displayId)
            ?: return "读取失败:虚拟屏语义读屏需要 Shizuku 或 Root 通道"
        return buildString {
            appendLine(info.toSummary(50))
            if (info.nodes.isNotEmpty()) {
                appendLine("---")
                appendLine("可交互控件:")
                info.nodes.filter { it.isClickable || it.isEditable }.take(20).forEachIndexed { index, node ->
                    appendLine("[$index] ${node.toShortString()}")
                }
            }
        }
    }

    private suspend fun handleClose(args: Map<String, String>): String {
        val displayId = args["display"]?.toIntOrNull() ?: manager.lastDisplayId
        return if (client.destroy(displayId)) "虚拟屏 $displayId 已销毁" else "没有可销毁的虚拟屏"
    }

    private suspend fun handleStatus(): String {
        val proxy = manager.existingLiveProxy()
        val channel = manager.channelState()
        return buildString {
            appendLine("权限通道:$channel")
            appendLine("服务端:${if (proxy != null) "在线" else "未运行(首次调用自动启动)"}")
            append("虚拟屏:${if (manager.lastDisplayId >= 0) "displayId=${manager.lastDisplayId}" else "未创建"}")
        }
    }

    private suspend fun handleInput(args: Map<String, String>): String {
        val displayId = args["display"]?.toIntOrNull() ?: run {
            client.ensureCompatibilityDisplay().getOrElse {
                return "错误:虚拟屏刷新失败:${it.message ?: "服务端未就绪"}"
            }.displayId
        }
        if (displayId < 0) {
            return "错误:虚拟屏未创建，请先执行 virtual_screen action=ensure"
        }
        val action = args["action"]?.trim()?.lowercase().orEmpty()
        val command = VirtualDisplayInputCommand.build(displayId, action, args)
        return when {
            command == null -> "错误:输入参数无效(action=$action)"
            command.waitMs != null -> {
                delay(command.waitMs)
                "虚拟屏 $displayId 已等待 ${command.waitMs}ms"
            }
            else -> executeInputCommand(displayId, command)
        }
    }

    private suspend fun executeInputCommand(displayId: Int, command: VirtualDisplayInputCommand): String {
        return when (val check = DeviceCommandPolicy.validate(command.shellCommand)) {
            is DeviceCommandPolicy.Check.Invalid -> "错误:输入命令被策略拒绝:${check.reason}"
            DeviceCommandPolicy.Check.Valid -> {
                val result = manager.exec(command.shellCommand)
                if (result.exitCode == 0) {
                    "虚拟屏 $displayId ${command.description}成功"
                } else {
                    "虚拟屏 $displayId ${command.description}失败(exit=${result.exitCode}):${result.output.take(500)}"
                }
            }
        }
    }
}

internal data class VirtualDisplayInputCommand(
    val shellCommand: String,
    val description: String,
    val waitMs: Long? = null,
) {
    companion object {
        private val KEY_REGEX = Regex("^[A-Za-z0-9_]+$")

        fun build(displayId: Int, action: String, args: Map<String, String>): VirtualDisplayInputCommand? {
            return when (action) {
                "tap" -> buildTap(displayId, args)
                "swipe" -> buildSwipe(displayId, args)
                "text" -> buildText(displayId, args)
                "key" -> buildKey(displayId, args)
                "wait" -> buildWait(args)
                else -> null
            }
        }

        private fun buildTap(displayId: Int, args: Map<String, String>): VirtualDisplayInputCommand? {
            val x = args["x"]?.toIntOrNull()
            val y = args["y"]?.toIntOrNull()
            return if (x != null && y != null) command(displayId, "tap $x $y", "点击($x,$y)") else null
        }

        private fun buildSwipe(displayId: Int, args: Map<String, String>): VirtualDisplayInputCommand? {
            val x1 = args["x1"]?.toIntOrNull()
            val y1 = args["y1"]?.toIntOrNull()
            val x2 = args["x2"]?.toIntOrNull()
            val y2 = args["y2"]?.toIntOrNull()
            val duration = args["duration_ms"]?.toLongOrNull()?.coerceIn(50L, 5_000L) ?: 400L
            return if (listOf(x1, y1, x2, y2).any { it == null }) {
                null
            } else {
                command(displayId, "swipe ${x1!!} ${y1!!} ${x2!!} ${y2!!} $duration", "滑动")
            }
        }

        /**
     * 虚拟屏文本输入的可用性检查：null = 可用；否则返回**给模型看的原因**。
     *
     * 为什么要单独暴露：`build()` 返回 null 的原因有「文本空/超长/含特殊字符」多种，
     * 调用方只回「参数无效」时，模型分不清是格式错还是文字里有引号，只能盲试。
     */
    fun textRejectionReason(text: String): String? {
        if (text.isBlank()) return "文本为空"
        if (text.length > 500) return "文本过长(${text.length} > 500 字)；请拆成多段输入"
        val bad = text.filter { it in SHELL_META || it == '\n' || it == '\r' || it == '\\' || it == '"' || it == '\'' }
        if (bad.isEmpty()) return null
        val names = bad.map { ch ->
            when (ch) {
                '\n', '\r' -> "换行"
                '\\' -> "反斜杠"
                '"' -> "双引号"
                '\'' -> "单引号"
                else -> "特殊符号 $ch"
            }
        }.distinct().joinToString("/")
        return "虚拟屏输入不支持$names；改用 display=foreground 走 screen_input，或先去掉这些字符"
    }

    private fun buildText(displayId: Int, args: Map<String, String>): VirtualDisplayInputCommand? {
            val text = args["text"]?.takeIf { it.isNotBlank() }?.takeIf { it.length <= 500 } ?: return null
            val unsafe = text.any { it in SHELL_META || it == '\n' || it == '\r' || it == '\\' || it == '"' || it == '\'' }
            return if (unsafe) null else command(displayId, "text ${text.replace(" ", "%s")}", "输入文本")
        }

        private fun buildKey(displayId: Int, args: Map<String, String>): VirtualDisplayInputCommand? {
            val key = args["key"]?.trim()?.uppercase()?.takeIf { KEY_REGEX.matches(it) } ?: return null
            return command(displayId, "keyevent $key", "按键($key)")
        }

        private fun buildWait(args: Map<String, String>): VirtualDisplayInputCommand? {
            val ms = args["ms"]?.toLongOrNull()?.coerceIn(50L, 10_000L) ?: return null
            return VirtualDisplayInputCommand("", "", waitMs = ms)
        }

        private fun command(displayId: Int, input: String, description: String) =
            VirtualDisplayInputCommand("input -d $displayId $input", description)

        private val SHELL_META = setOf(';', '|', '&', '>', '<', '`', '$', '(', ')', '{', '}')
    }
}
