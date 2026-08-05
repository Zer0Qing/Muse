package io.zer0.muse.tools

import android.content.Context
import io.zer0.muse.R

/**
 * P1-3b 拆域：剪贴板工具注册器。
 *
 * 从 ToolRegistry.kt 抽出的 clipboard_read / clipboard_write，
 * 只依赖 Context 与系统剪贴板服务。
 */
class ClipboardToolsRegistrar(
    private val context: Context,
    private val toolRegistry: ToolRegistry,
) {
    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "clipboard_read",
                description = "读取系统剪贴板文本内容。",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) {
            readClipboard().content
        }
        // 结构化结果版优先（registerOutcome 通道优先于 String 通道）
        toolRegistry.registerOutcome(
            ToolRegistry.ToolDef(
                name = "clipboard_read",
                description = "读取系统剪贴板文本内容。",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) {
            readClipboard()
        }
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "clipboard_write",
                description = "写入文本到系统剪贴板。",
                parameters = mapOf(
                    "text" to "必填,要写入剪贴板的文本",
                    "label" to "可选,剪贴板标签,如 muse_copy",
                ),
                required = setOf("text"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args ->
            val text = args["text"] ?: return@register context.getString(R.string.tool_missing_param_text)
            val label = (args["label"]?.takeIf { it.isNotBlank() } ?: "muse_tool").take(100)
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
            context.getString(R.string.tool_clipboard_written, text.length)
        }
    }

    private suspend fun readClipboard(): ToolOutcome {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        val clip = cm.primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        val content = if (text.isBlank()) {
            context.getString(R.string.tool_clipboard_empty)
        } else {
            context.getString(R.string.tool_clipboard_content, text)
        }
        return ToolOutcome.ok(
            content,
            details = mapOf(
                "hasText" to text.isNotBlank(),
                "length" to text.length,
            ),
        )
    }
}
