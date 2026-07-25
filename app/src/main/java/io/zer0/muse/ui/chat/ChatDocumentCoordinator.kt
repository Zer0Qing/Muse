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
 * 职责:
 *  - pickDocument: 选取文档(TXT/MD/PDF)后解析文本,追加到输入框
 *
 * 不持有 state,通过 [accessor] 读写。
 */
class ChatDocumentCoordinator(
    private val accessor: ChatStateAccessor,
    private val documentParser: DocumentParser,
) {

    private val tag = "ChatVM"

    companion object {
        /** 文档解析结果最大字符数(避免输入框爆炸)。 */
        private const val DOC_MAX_CHARS = 8000
    }

    /**
     * P5-E: 选取文档后解析文本,追加到输入框(以分隔符隔开)。
     * 支持 TXT / Markdown / PDF(原生 PdfRenderer)。
     *
     * 文本超过 8000 字时截断(避免输入框爆炸)。
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
                val truncated = if (text.length > DOC_MAX_CHARS) text.take(DOC_MAX_CHARS) + "\n…(已截断)" else text
                val current = accessor.snapshot.input
                val merged = if (current.isBlank()) truncated else "$current\n\n---\n\n$truncated"
                accessor.update { it.copy(input = merged) }
            } catch (t: Exception) {
                Logger.e(tag, "doc parse failed", t)
                reportError("文档解析失败: ${t.message}")
            }
        }
    }
}
