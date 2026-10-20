package io.zer0.muse.ui.chat
import android.content.Context
import android.net.Uri
import io.zer0.common.AppDispatchers
import io.zer0.common.Logger
import io.zer0.common.Result
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.doc.DocumentParser
import io.zer0.muse.rag.RagConfig
import io.zer0.muse.rag.SessionAttachmentService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

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
    private val settings: SettingsRepository,
) {

    private val tag = "ChatVM"

    companion object {
        /**
         * 文档解析结果最大字符数(避免输入框爆炸和超长消息)。
         * v1.136 T10: 从 4000 提升到 32000,适配现代 LLM 更大的上下文窗口。
         * v1.0.47 P5-2: 改为 public,供 [io.zer0.muse.ui.ChatViewModel.addPastedTextAsDocument] 复用截断阈值。
         */
        const val DOC_MAX_CHARS = 32000
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
                val fileName = queryDisplayName(context, uri) ?: context.getString(R.string.chat_doc_default_name)
                val mime = context.contentResolver.getType(uri).orEmpty()
                val sizeBytes = queryFileSize(context, uri)

                // v2.6.6: 音频/视频不再强行解析成文本。
                //
                // 旧实现对所有文件都走 documentParser，音频/视频没有可提取文本，
                // 直接报“空或不支持”，用户根本发不出去。
                // 现在二进制附件保留原始 Uri + MIME，随消息原样发给支持多模态的模型。
                if (mime.startsWith("audio/") || mime.startsWith("video/")) {
                    val mediaDoc = PendingDocument(
                        name = fileName,
                        content = "",
                        charCount = 0,
                        uri = uri.toString(),
                        mimeType = mime,
                        sizeBytes = sizeBytes,
                    )
                    accessor.update { it.copy(pendingDocuments = it.pendingDocuments + mediaDoc) }
                    return@launch
                }

                // 文档解析配置（二进制分支已提前返回，到这里一定是文本类）
                val ragConfig = runCatching { settings.getRagConfig() }.getOrNull() ?: RagConfig()
                val result = documentParser.parseResult(
                    uri,
                    context,
                    ragConfig.documentParserType,
                    ragConfig.cloudParserEndpoint,
                    ragConfig.mineruEndpoint,
                    ragConfig.mineruToken,
                )
                if (result is Result.Error) {
                    reportError(result.message)
                    return@launch
                }
                val text = result.getOrNull()
                if (text.isNullOrBlank()) {
                    reportError(context.getString(R.string.chat_doc_empty_or_unsupported))
                    return@launch
                }
                val truncated = if (text.length > DOC_MAX_CHARS) {
                    val remain = text.length - DOC_MAX_CHARS
                    text.take(DOC_MAX_CHARS) + context.getString(R.string.chat_doc_truncated, remain)
                } else {
                    text
                }
                // 文件名已在函数开头解析（二进制分支也要用），这里直接复用。
                val doc = PendingDocument(
                    name = fileName,
                    content = truncated,
                    charCount = truncated.length,
                    uri = uri.takeIf { mime.startsWith("application/pdf") }?.toString(),
                    mimeType = mime,
                    sizeBytes = sizeBytes,
                )
                accessor.update { it.copy(pendingDocuments = it.pendingDocuments + doc) }

                // v1.0.92: 会话附件检索(已转正,设置 → 知识库检索 → 会话附件检索,默认开)。
                // 把刚解析出的文档索引进检索库,让 knowledge_search 能命中附件内容;
                // 索引随会话清理。任何失败都只记日志 —— 绝不能因为检索索引而影响“发送文档”本身。
                runCatching {
                    if (settings.ragConfigFlow.first().sessionAttachmentEnabled) {
                        val st = accessor.snapshot
                        val sid = if (st.isAgentMode) st.agentSessionId else st.currentSessionId
                        if (!sid.isNullOrBlank()) {
                            GlobalContext.get().get<SessionAttachmentService>()
                                .indexSessionAttachment(
                                    sessionId = sid,
                                    name = fileName,
                                    content = truncated,
                                )
                            Logger.i(tag, "会话附件已提交索引 | sessionId=$sid | name=$fileName")
                        }
                    }
                }.onFailure { Logger.w(tag, "会话附件索引接入失败(不影响文档发送)", it) }
            } catch (t: Exception) {
                Logger.e(tag, "doc parse failed", t)
                reportError(context.getString(R.string.chat_doc_parse_failed, t.message ?: ""))
            }
        }
    }

    /** 从 SAF URI 查询显示名(ContentResolver.query)。 */
    /**
     * 读取文件大小（字节）。读不到返回 0（仅用于展示，不影响发送）。
     */
    private fun queryFileSize(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
    }.getOrNull() ?: 0L

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
    /**
     * 原始文件 Uri（字符串形式）。
     *
     * 文本类附件（txt/md/pdf）解析成 [content] 后即可丢弃原始文件；
     * 但音频/视频这类二进制附件必须保留引用，以便原样传给支持多模态的模型。
     * null 表示纯文本附件（如“粘贴长文本转附件”生成的）。
     */
    val uri: String? = null,
    /** MIME 类型（如 audio/mpeg、video/mp4、application/pdf）。未知时为空串。 */
    val mimeType: String = "",
    /** 原始文件字节数；未知时为 0。 */
    val sizeBytes: Long = 0L,
) {
    /** 是否为二进制附件（音频/视频）——这类不走文本注入，需要原样直传。 */
    val isBinary: Boolean
        get() = mimeType.startsWith("audio/") || mimeType.startsWith("video/")
}
