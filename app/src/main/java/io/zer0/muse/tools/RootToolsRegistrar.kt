package io.zer0.muse.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import io.zer0.muse.automation.core.AutomationManager

internal fun formatSettingsPutResult(tier: String, namespace: String, name: String): String = "[$tier] Setting '$namespace:$name' updated"

internal fun formatInputInjectResult(tier: String, textLength: Int, viaClipboard: Boolean): String =
    "[$tier] Text injected${if (viaClipboard) " via clipboard paste" else ""} ($textLength chars)"

/**
 * v2.x 自动化一期:设备级工具注册器(原 Root-level,现改为分层执行)。
 *
 * 暴露高权限设备能力为 AI 可调用工具;执行统一走 [AutomationManager.execTiered] ——
 * **Shizuku 优先、Root 兜底**,两档均未授权时返回明确错误。
 * 所有工具 [ToolRiskLevel.HIGH],走既有审批链与审计基础设施。
 *
 * Tools:
 * - settings_get / settings_put — 读写系统设置
 * - network_toggle — svc 开关 wifi/数据
 * - am_start — 启动 Activity(含 extras)
 * - list_packages — 列出已安装包
 * - logcat_tail — 读取最近日志
 * - input_inject — 向焦点输入框注入文本(带剪贴板粘贴兜底)
 */
class RootToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val manager: AutomationManager,
    private val context: Context,
) {
    private val builder get() = manager.root

    init {
        registerAll()
    }

    private fun tierUnavailable(): String = "设备命令通道不可用:需要 Shizuku 或 Root 授权(设置 → 权限配置向导),当前两档均未就绪"

    /** 执行一条设备命令;[tier] 前缀便于模型与用户区分走的是哪档。 */
    private suspend fun runCommand(command: String): Pair<String, io.zer0.muse.automation.executors.ShellExecutor.ExecDetail>? =
        manager.execTiered(command)

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "settings_get",
                description = "按名称读取 Android 系统设置(system / secure / global)。" +
                    "可查飞行模式、Wi-Fi 状态、定位模式等。" +
                    "格式为 namespace:name,例如 secure:location_mode。" +
                    "需要 Shizuku 或 Root 授权。",
                parameters = mapOf(
                    "name" to
                        "必填。设置名,可带命名空间前缀(global / secure / system)," +
                        "例如 secure:location_mode",
                ),
                required = setOf("name"),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val name = args["name"]?.takeIf { it.isNotBlank() } ?: return@register "Error: name is required"
            val cmd = builder.buildSettingsGet(name) ?: return@register "Error: invalid setting name '$name'"
            val result = runCommand(cmd) ?: return@register tierUnavailable()
            val (tier, detail) = result
            if (detail.exitCode == 0) {
                "[$tier] $name = ${detail.output.trim()}"
            } else {
                formatRootFailureOutput(tier, "读取失败", detail.output, detail.exitCode)
            }
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "network_toggle",
                description = "通过 svc 命令开关 Wi-Fi 或移动数据。" +
                    "需要 Shizuku 或 Root 授权。谨慎使用,可能中断网络连接。",
                parameters = mapOf(
                    "service" to "Required. Service to toggle: wifi or data",
                    "enabled" to "Required. true to enable, false to disable",
                ),
                required = setOf("service", "enabled"),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val service = args["service"]?.takeIf { it.isNotBlank() } ?: return@register "Error: service is required"
            val enabled = args["enabled"]?.trim()?.lowercase()?.toBooleanStrictOrNull()
                ?: return@register "Error: enabled must be true or false"
            val cmd = builder.buildSvcToggle(service, enabled)
                ?: return@register "Error: unsupported service '$service' (only wifi/data)"
            val result = runCommand(cmd) ?: return@register tierUnavailable()
            val (tier, detail) = result
            if (detail.exitCode == 0) {
                "[$tier] $service ${if (enabled) "enabled" else "disabled"}"
            } else {
                formatRootFailureOutput(tier, "切换失败", detail.output, detail.exitCode)
            }
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "settings_put",
                description = "写入 Android 系统设置(system / secure / global)。" +
                    "需要 Shizuku 或 Root 授权。谨慎使用,会改变设备行为。",
                parameters = mapOf(
                    "namespace" to "Required. Setting namespace: global, secure, or system",
                    "name" to "Required. Setting name",
                    "value" to "Required. Value to set",
                ),
                required = setOf("namespace", "name", "value"),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val namespace = args["namespace"]?.takeIf { it.isNotBlank() } ?: return@register "Error: namespace is required"
            val name = args["name"]?.takeIf { it.isNotBlank() } ?: return@register "Error: name is required"
            val value = args["value"]?.takeIf { it.isNotBlank() } ?: return@register "Error: value is required"
            val cmd = builder.buildSettingsPut(namespace, name, value)
                ?: return@register "Error: invalid settings parameters"
            val result = runCommand(cmd) ?: return@register tierUnavailable()
            val (tier, detail) = result
            if (detail.exitCode == 0) {
                formatSettingsPutResult(tier, namespace, name)
            } else {
                formatRootFailureOutput(tier, "写入失败", detail.output, detail.exitCode)
            }
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "am_start",
                description = "通过 am start 命令启动 Activity。" +
                    "extras 用 key=value 形式,多项以 | 分隔。" +
                    "需要 Shizuku 或 Root 授权。",
                parameters = mapOf(
                    "package" to "Required. Package name, e.g., com.android.settings",
                    "class" to "Optional. Activity class name, e.g., .Settings\$WifiSettingsActivity",
                    "extras" to "Optional. Key-value extras separated by |, e.g., title=Hello|count=5",
                ),
                required = setOf("package"),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val pkg = args["package"]?.takeIf { it.isNotBlank() } ?: return@register "Error: package is required"
            val cls = args["class"]
            val extras = args["extras"]
            val cmd = builder.buildAmStart(pkg, cls, extras) ?: return@register "Error: invalid package/class/extras"
            val result = runCommand(cmd) ?: return@register tierUnavailable()
            val (tier, detail) = result
            if (detail.exitCode == 0) {
                "[$tier] Activity started: $pkg${cls?.let { "/$it" }.orEmpty()}"
            } else {
                formatRootFailureOutput(tier, "启动失败", detail.output, detail.exitCode)
            }
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "list_packages",
                description = "列出已安装的包名,可按子串过滤。用于查找应用的包名。" +
                    "需要 Shizuku 或 Root 授权。",
                parameters = mapOf(
                    "filter" to "Optional. Substring to filter package names",
                ),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val filter = args["filter"]
            val cmd = builder.buildListPackages(filter) ?: return@register "Error: invalid filter"
            val result = runCommand(cmd) ?: return@register tierUnavailable()
            val (tier, detail) = result
            if (detail.exitCode != 0) {
                formatRootFailureOutput(tier, "列表失败", detail.output, detail.exitCode)
            } else {
                val packages = detail.output.lineSequence()
                    .mapNotNull { line -> Regex("package:(.+)").find(line)?.groupValues?.get(1) }
                    .distinct()
                    .sorted()
                    .toList()
                if (packages.isEmpty()) {
                    "No packages found"
                } else {
                    formatRootPackageListOutput(tier, packages)
                }
            }
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "logcat_tail",
                description = "读取最近 N 行 logcat。用于排查应用崩溃或系统事件。" +
                    "需要 Shizuku 或 Root 授权。",
                parameters = mapOf(
                    "lines" to "Optional. Number of lines to read, default 100",
                ),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val lines = (args["lines"]?.toIntOrNull() ?: 100).coerceIn(1, 2_000)
            val result = runCommand(builder.buildLogcatTail(lines)) ?: return@register tierUnavailable()
            val (tier, detail) = result
            formatRootLogcatOutput(tier, detail.output)
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "input_inject",
                description = "向当前焦点输入框注入文本。" +
                    "遇到特殊字符时比无障碍输入更可靠。" +
                    "需要 Shizuku 或 Root 授权。",
                parameters = mapOf(
                    "text" to "Required. Text to inject",
                ),
                required = setOf("text"),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val text = args["text"] ?: return@register "Error: text is required"
            val cmd = builder.buildInputInject(text) ?: return@register "Error: empty text"
            val result = runCommand(cmd) ?: return@register tierUnavailable()
            val (tier, detail) = result
            if (detail.exitCode == 0) {
                formatInputInjectResult(tier, text.length, viaClipboard = false)
            } else {
                // 兜底:写入剪贴板 + PASTE 按键(部分 ROM 的 input text 对特殊字符不可靠)
                val pasteResult = runCatching {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("muse-inject", text))
                    manager.execTiered("input keyevent 279")
                }.getOrNull()
                if (pasteResult != null && pasteResult.second.exitCode == 0) {
                    formatInputInjectResult(tier, text.length, viaClipboard = true)
                } else {
                    formatRootFailureOutput(tier, "注入失败", detail.output, detail.exitCode)
                }
            }
        }
    }
}

internal fun formatRootLogcatOutput(tier: String, output: String): String = "[$tier] Logcat output (${output.length} chars):\n$output"

internal fun formatRootPackageListOutput(tier: String, packages: List<String>): String =
    "[$tier] Found ${packages.size} packages:\n${packages.joinToString("\n")}"

internal fun formatRootFailureOutput(tier: String, action: String, output: String, exitCode: Int): String =
    "[$tier] $action: ${output.ifBlank { "exit=$exitCode" }}"
