package io.zer0.muse.ui.chat

import io.zer0.ai.core.MessageRole
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.ui.theme.MuseDateFormats
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class ExportFormat { MARKDOWN, PLAIN_TEXT, JSON }

@Serializable
data class ExportMessage(
    val role: String,
    val content: String,
    val mood: String? = null,
    val reasoning: String? = null,
    val createdAt: Long = 0,
)

@Serializable
data class ExportSession(
    val title: String,
    val model: String = "",
    val exportedAt: String = "",
    val messages: List<ExportMessage> = emptyList(),
)

/**
 * v1.105 阶段 1: 从 ChatViewModel 抽离的会话导出 Coordinator。
 *
 * 职责:
 *  - exportSessionAsMarkdown: 导出当前会话为 Markdown / PlainText / HTML / JSON
 *  - exportSessionAsJson: 导出为 JSON 格式(完整数据结构)
 *  - exportSessionAsPlainText: 导出为纯文本格式
 *  - markdownToHtml: 极简 Markdown → HTML 转换(内部工具方法)
 *
 * 纯只读 + 写 StringBuilder,不持有 state,通过 [accessor] 读快照。
 */
class ChatExportCoordinator(
    private val accessor: ChatStateAccessor,
    private val settings: SettingsRepository,
    private val sessionRepository: SessionRepository,
) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /**
     * v0.29 P0-3: 导出当前会话为 Markdown 文本(用于分享/导出)。
     */
    suspend fun exportSessionAsMarkdown(): String {
        val state = accessor.snapshot
        val tpl = settings.shareTemplateFlow.first()
        val title = if (tpl.customTitle.isNotBlank()) tpl.customTitle
            else state.sessions.find { it.id == state.currentSessionId }?.title?.takeIf { it.isNotBlank() } ?: "muse 对话"
        val sb = StringBuilder()
        sb.append("# ").append(title).append("\n\n")

        val meta = mutableListOf<String>()
        if (tpl.includeTimestamp) {
            val now = java.text.SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL, java.util.Locale.getDefault())
                .format(java.util.Date())
            meta.add("导出时间: $now")
        }
        if (tpl.includeModelName) {
            val model = state.providers.firstOrNull { it.id == state.activeProviderId }
                ?.models?.firstOrNull { it.id == state.selectedModelId }
            val modelName = model?.name ?: model?.id ?: "未知模型"
            meta.add("模型: $modelName")
        }
        meta.forEach { sb.append("> ").append(it).append("  \n") }
        if (meta.isNotEmpty()) sb.append("\n")

        val allMessages = loadAllMessages(state)

        var totalChars = 0
        allMessages.forEach { msg ->
            val role = if (msg.role == MessageRole.USER) "用户" else "助手"
            sb.append("**").append(role).append("**: ").append(msg.content).append("\n\n")
            totalChars += msg.content.length
            if (tpl.includeMoodBlock && !msg.mood.isNullOrBlank()) {
                sb.append("<mood>\n").append(msg.mood).append("\n</mood>\n\n")
            }
            if (tpl.includeReasoning && !msg.reasoning.isNullOrBlank()) {
                sb.append("### 思考过程\n\n").append(msg.reasoning).append("\n\n")
            }
        }

        if (tpl.includeTokenCount) {
            val estTokens = totalChars / 3
            sb.append("> 估算 Token: ~").append(estTokens).append("  \n\n")
        }

        return when (tpl.format) {
            "plain_text" -> sb.toString()
                .replace(Regex("""\*\*"""), "")
                .replace(Regex("""^> """, RegexOption.MULTILINE), "")
                .replace(Regex("""^# """, RegexOption.MULTILINE), "")
                .replace(Regex("""^### """, RegexOption.MULTILINE), "")
                .replace(Regex("""</?mood>"""), "")
            "html" -> markdownToHtml(sb.toString())
            else -> sb.toString()
        }
    }

    /**
     * 功能4: 导出当前会话为 JSON 格式。
     */
    suspend fun exportSessionAsJson(): String {
        val state = accessor.snapshot
        val title = state.sessions.find { it.id == state.currentSessionId }?.title?.takeIf { it.isNotBlank() } ?: "muse 对话"
        val model = state.providers.firstOrNull { it.id == state.activeProviderId }
            ?.models?.firstOrNull { it.id == state.selectedModelId }
        val modelName = model?.name ?: model?.id ?: ""
        val now = java.text.SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL, java.util.Locale.getDefault())
            .format(java.util.Date())

        val allMessages = loadAllMessages(state)
        val exportMessages = allMessages.map { msg ->
            ExportMessage(
                role = msg.role.name,
                content = msg.content,
                mood = msg.mood,
                reasoning = msg.reasoning,
                createdAt = msg.createdAt,
            )
        }

        val export = ExportSession(
            title = title,
            model = modelName,
            exportedAt = now,
            messages = exportMessages,
        )
        return json.encodeToString(export)
    }

    /**
     * 功能4: 导出当前会话为纯文本格式。
     */
    suspend fun exportSessionAsPlainText(): String {
        val state = accessor.snapshot
        val title = state.sessions.find { it.id == state.currentSessionId }?.title?.takeIf { it.isNotBlank() } ?: "muse 对话"
        val sb = StringBuilder()
        sb.append(title).append("\n")
        sb.append("=".repeat(title.length)).append("\n\n")

        val allMessages = loadAllMessages(state)
        allMessages.forEach { msg ->
            val role = if (msg.role == MessageRole.USER) "用户" else "助手"
            sb.append("[$role]\n").append(msg.content).append("\n\n")
        }
        return sb.toString()
    }

    /**
     * 功能4: 统一导出入口 — 根据 format 返回对应内容的 MIME 类型和文本。
     */
    suspend fun exportSession(format: ExportFormat): Pair<String, String> {
        return when (format) {
            ExportFormat.MARKDOWN -> "text/markdown" to exportSessionAsMarkdown()
            ExportFormat.PLAIN_TEXT -> "text/plain" to exportSessionAsPlainText()
            ExportFormat.JSON -> "application/json" to exportSessionAsJson()
        }
    }

    /**
     * 导出当前会话为单文件 HTML(内联 CSS + base64 图片 + highlight.js CDN)。
     *
     * 委托至 [io.zer0.muse.data.export.ConversationExporter.exportToHtml],
     * 消息列表与标题的加载逻辑与 [exportSessionAsMarkdown] 一致。
     */
    suspend fun exportSessionAsHtml(): String {
        val state = accessor.snapshot
        val title = resolveTitle(state)
        val messages = loadAllMessages(state)
        return io.zer0.muse.data.export.ConversationExporter.exportToHtml(messages, title)
    }

    /**
     * 导出当前会话为 PDF 文件(Android 原生 PdfDocument 渲染,A4 分页)。
     *
     * 委托至 [io.zer0.muse.data.export.ConversationExporter.exportToPdf],
     * 返回的文件位于 cacheDir/export/,通过 FileProvider 暴露给分享 Intent。
     */
    suspend fun exportSessionAsPdf(context: android.content.Context): java.io.File {
        val state = accessor.snapshot
        val title = resolveTitle(state)
        val messages = loadAllMessages(state)
        return io.zer0.muse.data.export.ConversationExporter.exportToPdf(context, messages, title)
    }

    /** 解析当前会话标题(空标题回退为 "muse 对话")。 */
    private fun resolveTitle(state: io.zer0.muse.ui.ChatUiState): String =
        state.sessions.find { it.id == state.currentSessionId }
            ?.title?.takeIf { it.isNotBlank() } ?: "muse 对话"

    private suspend fun loadAllMessages(state: io.zer0.muse.ui.ChatUiState): List<io.zer0.ai.core.UIMessage> {
        val sessionId = if (state.isAgentMode) state.agentSessionId else state.currentSessionId
        return if (sessionId != null) {
            sessionRepository.observeMessages(sessionId).first()
        } else {
            state.messages
        }
    }

    /** v0.32: 极简 Markdown → HTML 转换(分享模板 html 格式用,不引入额外依赖)。 */
    private fun markdownToHtml(md: String): String {
        val body = md
            .replace(Regex("""^# (.+)$""", RegexOption.MULTILINE), "<h1>$1</h1>")
            .replace(Regex("""^### (.+)$""", RegexOption.MULTILINE), "<h3>$1</h3>")
            .replace(Regex("""\*\*(.+?)\*\*"""), "<strong>$1</strong>")
            .replace(Regex("""^&gt; (.+)$""", RegexOption.MULTILINE), "<blockquote>$1</blockquote>")
            .replace(Regex("""^> (.+)$""", RegexOption.MULTILINE), "<blockquote>$1</blockquote>")
            .replace(Regex("""<mood>\n([\s\S]*?)\n</mood>"""), "<pre><code>mood\n$1\n</code></pre>")
            .replace("\n\n", "</p><p>")
        return "<!DOCTYPE html><html><body><p>$body</p></body></html>"
    }
}
