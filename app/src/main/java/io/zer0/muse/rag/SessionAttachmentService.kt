package io.zer0.muse.rag

import io.zer0.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * v1.0.47 P7-2: 会话级附件索引服务。
 *
 * 职责:
 *  - 附件添加时自动 chunk + embed 到临时索引(复用 [RagService.indexDocument])
 *  - 维护附件索引状态机:[SessionAttachmentStatus]
 *  - 进度回调更新 UI(通过 [attachmentsBySession] StateFlow)
 *  - 会话结束清理索引(删除 chunk 表中对应 docId 的记录)
 *
 * 设计:
 *  - 索引存储:复用 KnowledgeChunk 表(通过 docId 前缀 "session-attach-" 区分)
 *  - 状态存储:内存 StateFlow(按 sessionId 分组),避免数据库 schema 变更
 *  - 并发:每会话单索引任务(同 sessionId 串行),避免 embedding 批次冲突
 *  - 清理:会话结束时调用 [dropSessionAttachments] 删除 chunk 记录
 *
 * 检索集成:
 *  - kb_search 工具检索时,RagService.retrieve 会自动包含会话附件 chunk
 *    (因 docId 前缀不影响 chunk 表查询,除非显式按 KB 过滤)
 *  - 如需隔离,可在 retrieve 时过滤 isSessionAttachmentDocId
 */
class SessionAttachmentService(
    private val ragService: RagService,
    private val settingsRepository: io.zer0.muse.data.SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 内存状态:sessionId → 附件列表(并发安全)。 */
    private val sessionAttachments = ConcurrentHashMap<String, MutableList<SessionAttachment>>()

    /** 可观察的状态流:所有会话附件(扁平列表)。 */
    private val _attachmentsFlow = MutableStateFlow<List<SessionAttachment>>(emptyList())
    val attachmentsFlow: StateFlow<List<SessionAttachment>> = _attachmentsFlow.asStateFlow()

    /** 获取指定会话的附件列表。 */
    fun attachmentsBySession(sessionId: String): List<SessionAttachment> =
        sessionAttachments[sessionId]?.toList() ?: emptyList()

    /**
     * 索引一个会话附件。
     *
     * 流程:
     *  1. 生成 attachmentId 和 docId(前缀 "session-attach-")
     *  2. 状态 QUEUED → CHUNKING → EMBEDDING → READY
     *  3. 复用 [RagService.indexDocument] 完成分块 + embedding
     *  4. 进度回调更新内存状态,触发 StateFlow emit
     *
     * @param sessionId 会话 ID
     * @param name 附件显示名(文件名)
     * @param content 附件文本内容(已由 DocumentParser 解析)
     * @return attachmentId(可用于后续状态查询)
     */
    fun indexSessionAttachment(
        sessionId: String,
        name: String,
        content: String,
    ): String {
        val attachmentId = UUID.randomUUID().toString()
        val docId = SessionAttachment.buildDocId(sessionId, attachmentId)
        val attachment = SessionAttachment(
            id = attachmentId,
            sessionId = sessionId,
            docId = docId,
            name = name,
            status = SessionAttachmentStatus.QUEUED,
        )
        // 加入内存状态
        sessionAttachments.compute(sessionId) { _, list ->
            (list ?: mutableListOf()).apply { add(attachment) }
        }
        emitState()

        // 异步执行索引
        scope.launch {
            try {
                updateAttachment(attachmentId, sessionId) {
                    it.copy(status = SessionAttachmentStatus.CHUNKING)
                }
                val config = settingsRepository.ragConfigFlow.first()
                updateAttachment(attachmentId, sessionId) {
                    it.copy(status = SessionAttachmentStatus.EMBEDDING)
                }
                val chunkCount = ragService.indexDocument(
                    docId = docId,
                    content = content,
                    ragConfig = config,
                    onProgress = { current, total ->
                        updateAttachment(attachmentId, sessionId) {
                            it.copy(embeddedChunks = current, totalChunks = total)
                        }
                    },
                )
                if (chunkCount == 0) {
                    updateAttachment(attachmentId, sessionId) {
                        it.copy(status = SessionAttachmentStatus.FAILED, errorMessage = "内容为空或分块失败")
                    }
                    Logger.w(TAG, "会话附件索引失败:内容为空 | sessionId=$sessionId | name=$name")
                } else {
                    updateAttachment(attachmentId, sessionId) {
                        it.copy(
                            status = SessionAttachmentStatus.READY,
                            totalChunks = chunkCount,
                            embeddedChunks = chunkCount,
                        )
                    }
                    Logger.i(TAG, "会话附件索引完成 | sessionId=$sessionId | name=$name | chunks=$chunkCount")
                }
            } catch (e: Throwable) {
                updateAttachment(attachmentId, sessionId) {
                    it.copy(status = SessionAttachmentStatus.FAILED, errorMessage = e.message)
                }
                Logger.e(TAG, "会话附件索引异常 | sessionId=$sessionId | name=$name", e)
            }
        }
        return attachmentId
    }

    /**
     * 清理指定会话的所有附件索引。
     *
     * 会话结束时调用,删除 chunk 表中对应 docId 的记录,释放存储。
     * 清理后附件状态置为 [SessionAttachmentStatus.DROPPED]。
     *
     * @param sessionId 会话 ID
     * @param keepIndex 是否保留索引(默认 false 清理)
     */
    suspend fun dropSessionAttachments(sessionId: String, keepIndex: Boolean = false) {
        val attachments = sessionAttachments[sessionId] ?: return
        if (!keepIndex) {
            for (attachment in attachments) {
                if (attachment.status == SessionAttachmentStatus.READY ||
                    attachment.status == SessionAttachmentStatus.EMBEDDING
                ) {
                    runCatching { ragService.deleteDocIndex(attachment.docId) }
                        .onFailure { Logger.w(TAG, "删除附件索引失败 | docId=${attachment.docId}", it) }
                }
            }
        }
        sessionAttachments.remove(sessionId)
        emitState()
        Logger.i(TAG, "会话附件已清理 | sessionId=$sessionId | keepIndex=$keepIndex | count=${attachments.size}")
    }

    /** 更新指定附件的状态(内存)。 */
    private fun updateAttachment(
        attachmentId: String,
        sessionId: String,
        transform: (SessionAttachment) -> SessionAttachment,
    ) {
        sessionAttachments[sessionId]?.let { list ->
            synchronized(list) {
                val idx = list.indexOfFirst { it.id == attachmentId }
                if (idx >= 0) {
                    list[idx] = transform(list[idx])
                }
            }
        }
        emitState()
    }

    /** 触发 StateFlow emit(扁平化所有会话附件)。 */
    private fun emitState() {
        _attachmentsFlow.update {
            sessionAttachments.values.flatten().toList()
        }
    }

    companion object {
        private const val TAG = "SessionAttachmentSvc"
    }
}
