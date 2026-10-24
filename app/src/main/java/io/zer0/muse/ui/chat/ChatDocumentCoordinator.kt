package io.zer0.muse.ui.chat

import android.content.Context
import android.net.Uri
import io.zer0.common.AppDispatchers
import io.zer0.common.FileSizeFormatter
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.doc.DocumentParser
import io.zer0.muse.rag.SessionAttachmentService
import io.zer0.muse.workspace.WorkspaceManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * v1.105 阶段 1: 从 ChatViewModel 抽离的文档解析 Coordinator。
 *
 * v2.6.13: 附件改为「原件落工作区 + 消息里只留引用」。
 *
 * 旧链路是「解压/解析 → 全文塞进 prompt」：zip 直接吐乱码，docx/xlsx 解压后
 * 几千字进消息体，用户看到的是一坨文本气泡，模型看到的是被截断的残篇。
 * 现在的链路：
 *  1. 原件（字节原样）导入工作区 `attachments/` 下，文件名冲突自动加序号；
 *  2. 消息里只放一行引用（相对路径 + 大小），模型要用就用文件工具按需读取；
 *  3. 压缩包/二进制**不解压**，也不再往上下文里塞任何提取文本；
 *  4. 纯文本类附件仍会读一次正文，但只用于芯片字数与会话附件检索索引，
 *     **不进 prompt**（这两件事都不再依赖「把正文写进消息」）。
 *
 * 职责:
 *  - pickDocument: 选取文件后落盘 + 生成引用行，存入 pendingDocuments
 *  - addPastedText: 大段粘贴文本同样落工作区，走同一条引用路径
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
         * v2.6.13: 仅用于「粘贴文本落盘」的长度上限与会话检索索引的截断,
         * 不再用作消息正文长度。
         */
        const val DOC_MAX_CHARS = 32000

        /** 附件在工作区内的落盘目录（相对工作区根）。 */
        const val ATTACHMENT_DIR = "attachments"

        /**
         * 会读正文做字数/检索的纯文本扩展名。
         *
         * 只有这些走 [DocumentParser] 的读文本分支；docx/xlsx/pptx/pdf/epub/zip
         * 一律不解压不解析 —— 原件已在工作区，模型要内容自己去读。
         */
        private val PLAIN_TEXT_EXTENSIONS = setOf(
            "txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "xml",
            "yaml", "yml", "log", "ini", "toml", "html", "htm", "kt", "py", "js", "ts",
        )
    }

    /**
     * 选取文件后：原件导入工作区，消息里只留一行引用。
     *
     * 音频/视频保持原有链路（保留 Uri + MIME 原样发给多模态模型），不走工作区引用。
     */
    fun pickDocument(uri: Uri, context: Context, reportError: (String) -> Unit) {
        if (accessor.snapshot.isStreaming) return
        accessor.coroutineScope.launch(AppDispatchers.io) {
            runCatching { importAttachment(uri, context, reportError) }
                .onFailure { t ->
                    Logger.e(tag, "attachment import failed", t)
                    reportError(context.getString(R.string.chat_attachment_import_failed, t.message ?: ""))
                }
        }
    }

    /** 附件导入的具体步骤（拆出 [pickDocument] 以降低圈复杂度）。 */
    private suspend fun importAttachment(uri: Uri, context: Context, reportError: (String) -> Unit) {
        val fileName = queryDisplayName(context, uri) ?: context.getString(R.string.chat_doc_default_name)
        val mime = context.contentResolver.getType(uri).orEmpty()
        val sizeBytes = queryFileSize(context, uri)

        // v2.6.6: 音频/视频不再强行解析成文本。
        //
        // 音频/视频没有可提取文本，解析只会报「空或不支持」，用户根本发不出去。
        // 二进制多媒体保留原始 Uri + MIME，随消息原样发给支持多模态的模型。
        if (mime.startsWith("audio/") || mime.startsWith("video/")) {
            accessor.update {
                it.copy(
                    pendingDocuments = it.pendingDocuments + PendingDocument(
                        name = fileName,
                        content = "",
                        charCount = 0,
                        uri = uri.toString(),
                        mimeType = mime,
                        sizeBytes = sizeBytes,
                    ),
                )
            }
        } else {
            importAsWorkspaceReference(uri, context, fileName, mime, reportError)
        }
    }

    /** 非多媒体附件的路径：原件落工作区 + 消息里只留一行引用。 */
    private suspend fun importAsWorkspaceReference(
        uri: Uri,
        context: Context,
        fileName: String,
        mime: String,
        reportError: (String) -> Unit,
    ) {
        val workspace = resolveWorkspace()
        val stored = workspace?.let { storeOriginal(it, uri, fileName, context, reportError) }
        if (workspace == null) {
            reportError(context.getString(R.string.chat_attachment_import_failed, "workspace unavailable"))
        }
        if (stored == null) return

        // 纯文本才读正文：仅用于芯片字数 + 会话附件检索，不写进消息。
        val extracted = if (isPlainText(mime, fileName)) {
            runCatching { documentParser.parseResult(uri, context).getOrNull() }.getOrNull()
        } else {
            null
        }

        accessor.update {
            it.copy(
                pendingDocuments = it.pendingDocuments + PendingDocument(
                    name = fileName,
                    content = context.getString(
                        R.string.chat_attachment_workspace_ref,
                        stored.relativePath,
                        FileSizeFormatter.format(stored.bytes),
                    ),
                    charCount = extracted?.length ?: 0,
                    workspacePath = stored.relativePath,
                    mimeType = mime,
                    sizeBytes = stored.bytes,
                ),
            )
        }

        indexForSessionSearch(fileName, extracted)
    }

    /** 把原件写入工作区 `attachments/`；失败时报错并返回 null。 */
    private suspend fun storeOriginal(
        workspace: WorkspaceManager,
        uri: Uri,
        fileName: String,
        context: Context,
        reportError: (String) -> Unit,
    ): WorkspaceManager.FileImportResult.Success? {
        // 落盘目录按需创建（importFile 要求目标目录已存在）。
        if (workspace.mkdir(ATTACHMENT_DIR) is WorkspaceManager.OpResult.Error) {
            reportError(context.getString(R.string.chat_attachment_import_failed, ATTACHMENT_DIR))
            return null
        }
        return when (val imported = workspace.importFile(uri, ATTACHMENT_DIR, fileName)) {
            is WorkspaceManager.FileImportResult.Success -> imported
            is WorkspaceManager.FileImportResult.Error -> {
                reportError(context.getString(R.string.chat_attachment_import_failed, imported.message))
                null
            }
        }
    }

    /**
     * v1.0.92: 会话附件检索（设置 → 知识库检索 → 会话附件检索，默认开）。
     *
     * v2.6.13: 只有拿到纯文本正文时才索引；压缩包/办公文档不再解压取全文。
     * 任何失败都只记日志 —— 绝不能因为检索索引而影响「发送附件」本身。
     */
    private suspend fun indexForSessionSearch(fileName: String, extracted: String?) {
        runCatching {
            val text = extracted?.take(DOC_MAX_CHARS)
            if (text.isNullOrBlank() || !settings.ragConfigFlow.first().sessionAttachmentEnabled) return
            val st = accessor.snapshot
            val sid = if (st.isAgentMode) st.agentSessionId else st.currentSessionId
            if (sid.isNullOrBlank()) return
            GlobalContext.get().get<SessionAttachmentService>()
                .indexSessionAttachment(sessionId = sid, name = fileName, content = text)
            Logger.i(tag, "会话附件已提交索引 | sessionId=$sid | name=$fileName")
        }.onFailure { Logger.w(tag, "会话附件索引接入失败(不影响文档发送)", it) }
    }

    /**
     * v2.6.13: 大段粘贴文本作为附件 —— 同样落工作区，消息里只留引用。
     *
     * 与 [pickDocument] 共用一条通道：先写工作区 `attachments/`，再把引用行放进
     * pendingDocuments。这样「粘贴成长文」与「上传 txt」对模型是同一件事：
     * 都能通过 workspace_read 读到原文，而不是把几千字塞进这一轮上下文。
     */
    fun addPastedText(text: String, context: Context, reportError: (String) -> Unit) {
        if (text.isBlank()) return
        val stamp = java.text.SimpleDateFormat("MMdd_HHmm", java.util.Locale.getDefault())
            .format(java.util.Date())
        val fileName = context.getString(R.string.chat_pasted_doc_name, stamp)
        accessor.coroutineScope.launch(AppDispatchers.io) {
            try {
                val workspace = resolveWorkspace()
                if (workspace == null) {
                    // 工作区不可用时退回旧行为：正文直接进消息，至少不让用户白粘贴。
                    val fallback = text.take(DOC_MAX_CHARS)
                    accessor.update {
                        it.copy(
                            pendingDocuments = it.pendingDocuments + PendingDocument(
                                name = fileName,
                                content = fallback,
                                charCount = fallback.length,
                            ),
                        )
                    }
                    return@launch
                }
                if (workspace.mkdir(ATTACHMENT_DIR) is WorkspaceManager.OpResult.Error) {
                    reportError(context.getString(R.string.chat_attachment_import_failed, ATTACHMENT_DIR))
                    return@launch
                }
                val write = workspace.writeFile("$ATTACHMENT_DIR/$fileName", text)
                if (write is WorkspaceManager.OpResult.Error) {
                    reportError(context.getString(R.string.chat_attachment_import_failed, write.message))
                    return@launch
                }
                val bytes = text.toByteArray(Charsets.UTF_8).size.toLong()
                accessor.update {
                    it.copy(
                        pendingDocuments = it.pendingDocuments + PendingDocument(
                            name = fileName,
                            content = context.getString(
                                R.string.chat_attachment_workspace_ref,
                                "$ATTACHMENT_DIR/$fileName",
                                FileSizeFormatter.format(bytes),
                            ),
                            charCount = text.length,
                            workspacePath = "$ATTACHMENT_DIR/$fileName",
                            mimeType = "text/plain",
                            sizeBytes = bytes,
                        ),
                    )
                }
            } catch (t: Exception) {
                Logger.e(tag, "pasted text import failed", t)
                reportError(context.getString(R.string.chat_attachment_import_failed, t.message ?: ""))
            }
        }
    }

    /** 工作区管理器：Koin 单例；测试/降级环境拿不到时返回 null。 */
    private fun resolveWorkspace(): WorkspaceManager? = runCatching {
        GlobalContext.get().get<WorkspaceManager>()
    }.getOrNull()

    /** 是否纯文本类附件（决定要不要读正文做字数与检索）。 */
    private fun isPlainText(mime: String, fileName: String): Boolean {
        if (mime.startsWith("text/")) return true
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in PLAIN_TEXT_EXTENSIONS
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
 * v1.136 T10: 待发送附件。
 *
 * v2.6.13: [content] 对普通附件不再是文件正文，而是一行引用
 * （「附件已存入工作区：attachments/x.zip（12.3 MB）」）。模型要看内容就用文件工具
 * 去读 [workspacePath]；压缩包不会被解压，二进制不会被当文本灌进上下文。
 *
 * @param name 文件名(如 "report.pdf")
 * @param content 发送时拼进消息的内容（普通附件 = 引用行；音频/视频 = 空串）
 * @param charCount 纯文本附件的字符数（仅展示；未读正文时为 0）
 * @param uri 原始文件 Uri（字符串形式）。仅音频/视频保留，用于原样传给多模态模型。
 * @param mimeType MIME 类型。未知时为空串。
 * @param sizeBytes 原始文件字节数；未知时为 0。
 * @param workspacePath 原件在工作区内的相对路径；不在工作区（多媒体）时为 null。
 */
data class PendingDocument(
    val name: String,
    val content: String,
    val charCount: Int,
    val uri: String? = null,
    val mimeType: String = "",
    val sizeBytes: Long = 0L,
    val workspacePath: String? = null,
) {
    /** 是否为二进制附件（音频/视频）——这类不走文本注入，需要原样直传。 */
    val isBinary: Boolean
        get() = mimeType.startsWith("audio/") || mimeType.startsWith("video/")
}
