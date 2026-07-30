package io.zer0.muse.ui.chat

import android.content.Context
import android.net.Uri
import io.zer0.common.AppDispatchers
import io.zer0.common.Logger
import io.zer0.muse.doc.DocumentParser
import kotlinx.coroutines.launch

/**
 * v1.105 阶段 1: 从 ChatViewModel 抽离的文档解析 Coordinator。
 *
 * v1.136 T10: 改为"待发送文档"模型 — 解析结果不再直接拼进输入框,
 * 而是存入 [ChatUiState.pendingDocuments],由 InputBar 渲染为可移除的文档芯片,
 * 发送时在 [ChatViewModel.enqueueSend] 中合并到消息文本。
 * 这样用户可以:
 *  - 看到已添加的文档(文件名 + 字数)
 *  - 在发送前移除文档
 *  - 在输入框中单独编辑自己的消息,不影响文档内容
 *
 * 职责:
 *  - pickDocument: 选取文档(TXT/MD/PDF/DOCX/XLSX/PPTX/EPUB)后解析文本,存入 pendingDocuments
 *
 * 不持有 state,通过 [accessor] 读写。
 */
class ChatDocumentCoordinator(
    private val accessor: ChatStateAccessor,
    private val documentParser: DocumentParser,
) {

    private val tag = "ChatVM"

    companion object {
        /**
         * 文档解析结果最大字符数(避免输入框爆炸和超长消息)。
         * v1.136 T10: 从 4000 提升到 32000,适配现代 LLM 更大的上下文窗口。
         */
        private const val DOC_MAX_CHARS = 32000
    }

    /**
     * P5-E: 选取文档后解析文本,存入 pendingDocuments(不再直接拼进输入框)。
     * 支持 TXT / Markdown / CSV / JSON / XML / HTML / PDF / DOCX / XLSX / PPTX / EPUB。
     *
     * 文本超过 [DOC_MAX_CHARS] 字时截断(避免超长消息)。
     */
    fun pickDocument(uri: Uri, context: Context, reportError: (String) -> Unit) {
        if (accessor.snapshot.isStreaming) return
        accessor.coroutineScope.launch(AppDispatchers.io) {
            try {
                val text = documentParser.parse(uri, context)
                if (text.isBlank()) {
                    reportError("文档内容为空或不支持的格式")
                    return@launch
                }
                val truncated = if (text.length > DOC_MAX_CHARS) {
                    val remain = text.length - DOC_MAX_CHARS
                    text.take(DOC_MAX_CHARS) + "\n\n…(文档过长，已截断 $remain 字)"
                } else text
                // v1.136 T10: 从 URI 提取文件名(回退到 "文档")
                val fileName = queryDisplayName(context, uri) ?: "文档"
                val doc = PendingDocument(
                    name = fileName,
                    content = truncated,
                    charCount = truncated.length,
                )
                accessor.update { it.copy(pendingDocuments = it.pendingDocuments + doc) }
            } catch (t: Exception) {
                Logger.e(tag, "doc parse failed", t)
                reportError("文档解析失败: ${t.message}")
            }
        }
    }

    /** 从 SAF URI 查询显示名(ContentResolver.query)。 */
    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
            }
        }.getOrNull()
    }
}

/**
 * v1.136 T10: 待发送文档(已解析为纯文本)。
 *
 * @param name 文件名(如 "report.pdf")
 * @param content 解析后的纯文本内容(已截断至 [ChatDocumentCoordinator.DOC_MAX_CHARS])
 * @param charCount 文本字符数(用于芯片显示)
 */
data class PendingDocument(
    val name: String,
    val content: String,
    val charCount: Int,
)
