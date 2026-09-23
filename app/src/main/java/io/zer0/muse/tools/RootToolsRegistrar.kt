package io.zer0.muse.tools

import io.zer0.muse.automation.executors.RootExecutor

/**
 * Root-level tool registrar.
 *
 * Exposes high-privilege capabilities from [RootExecutor] as AI-callable tools.
 * All tools are registered as [ToolRiskLevel.HIGH] and require explicit approval
 * via the existing [ToolApprovalRouter] + audit infrastructure.
 *
 * Tools:
 * - settings_get / settings_put — read/write Android settings (requires root)
 * - am_start — launch activity with extras (requires root)
 * - list_packages — list installed apps (requires root)
 * - logcat_tail — read recent logs (requires root)
 * - input_inject — inject text via root shell (requires root)
 */
class RootToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val rootExecutor: RootExecutor,
) {
    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "settings_get",
                description = "Read an Android system/secure/global setting value by name. " +
                    "Useful for checking airplane mode, wifi state, location mode, etc. " +
                    "Format: namespace:name (e.g., secure:location_mode). " +
                    "Requires root permission.",
                parameters = mapOf(
                    "name" to "Required. Setting name, optionally prefixed with namespace (global/secure/system). e.g., secure:location_mode",
                ),
                required = setOf("name"),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val name = args["name"]?.takeIf { it.isNotBlank() } ?: return@register "Error: name is required"
            val value = rootExecutor.settingsGet(name)
            if (value != null) "Setting '$name' = $value" else "Setting '$name' not found or error"
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "settings_put",
                description = "Write an Android system/secure/global setting value. " +
                    "Requires root permission. Use with caution — may affect device behavior.",
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
            val success = rootExecutor.settingsPut(namespace, name, value)
            if (success) "Setting '$namespace:$name' updated to '$value'" else "Failed to update setting"
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "am_start",
                description = "Launch an activity via am start command. " +
                    "Can include extras as key=value pairs separated by |. " +
                    "Requires root permission.",
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
            val success = rootExecutor.amStart(pkg, cls, extras)
            if (success) "Activity started: $pkg${cls?.let { "/$it" }.orEmpty()}" else "Failed to start activity"
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "list_packages",
                description = "List installed packages. Optionally filter by substring. " +
                    "Useful for discovering app package names.",
                parameters = mapOf(
                    "filter" to "Optional. Substring to filter package names",
                ),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val filter = args["filter"]
            val packages = rootExecutor.listPackages(filter)
            if (packages.isEmpty()) "No packages found" else "Found ${packages.size} packages:\n${packages.take(50).joinToString("\n")}"
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "logcat_tail",
                description = "Read the last N lines of logcat output. " +
                    "Useful for debugging app crashes or system events.",
                parameters = mapOf(
                    "lines" to "Optional. Number of lines to read, default 100",
                    "max_chars" to "Optional. Max output characters, default 10000",
                ),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val lines = args["lines"]?.toIntOrNull() ?: 100
            val maxChars = args["max_chars"]?.toIntOrNull() ?: 10_000
            val output = rootExecutor.logcatTail(lines, maxChars)
            "Logcat output (${output.length} chars):\n$output"
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "input_inject",
                description = "Inject text into the currently focused input field via root shell. " +
                    "Alternative to accessibility-based input for rooted devices.",
                parameters = mapOf(
                    "text" to "Required. Text to inject",
                ),
                required = setOf("text"),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val text = args["text"] ?: return@register "Error: text is required"
            val success = rootExecutor.inputInject(text)
            if (success) "Text injected: ${text.take(50)}${if (text.length > 50) "..." else ""}" else "Failed to inject text"
        }
    }
}
