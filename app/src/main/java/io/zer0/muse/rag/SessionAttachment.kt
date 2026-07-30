package io.zer0.muse.rag

/**
 * v1.0.47 P7-2: 会话级附件索引状态。
 *
 * 描述一个会话附件的索引生命周期:
 *  - [QUEUED]:   已入队,等待分块
 *  - [CHUNKING]: 正在分块
 *  - [EMBEDDING]: 正在生成向量
 *  - [READY]:    索引完成,可被 kb_search 检索
 *  - [FAILED]:   索引失败(content 为空/embedding 失败等)
 *  - [DROPPED]:  会话结束已清理
 */
enum class SessionAttachmentStatus {
    QUEUED,
    CHUNKING,
    EMBEDDING,
    READY,
    FAILED,
    DROPPED,
}

/**
 * v1.0.47 P7-2: 会话级附件索引数据类。
 *
 * 会话附件添加时,自动 chunk + embed 到临时索引,使 AI 可通过 kb_search 检索附件内容。
 * 与全局知识库(KnowledgeBase)的区别:
 *  - 生命周期绑定会话:会话结束时可选保留或清理([DROPPED])
 *  - docId 前缀 "session-{sessionId}-":与全局 KB 文档区分,避免污染全局检索
 *  - 不写入 KnowledgeDoc 表,仅写入 KnowledgeChunk 表(共享 chunk 基础设施)
 *
 * @property id 附件记录 ID(唯一)
 * @property sessionId 所属会话 ID
 * @property docId 索引文档 ID(前缀 "session-{sessionId}-",用于 chunk 表关联)
 * @property name 附件显示名(文件名)
 * @property status 索引状态
 * @property totalChunks 分块总数(READY 后填充)
 * @property embeddedChunks 已索引块数(进度跟踪)
 * @property errorMessage 失败原因(FAILED 时填充)
 * @property createdAt 创建时间戳
 */
data class SessionAttachment(
    val id: String,
    val sessionId: String,
    val docId: String,
    val name: String,
    val status: SessionAttachmentStatus,
    val totalChunks: Int = 0,
    val embeddedChunks: Int = 0,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    /** 进度百分比(0-100),未开始或失败时为 0。 */
    val progressPercent: Int
        get() = if (totalChunks == 0) 0 else (embeddedChunks * 100 / totalChunks).coerceIn(0, 100)

    companion object {
        /** 会话附件 docId 前缀,用于在 chunk 表中区分全局 KB 与会话附件。 */
        const val DOC_ID_PREFIX = "session-attach-"

        /** 生成会话附件的 docId。 */
        fun buildDocId(sessionId: String, attachmentId: String): String =
            "$DOC_ID_PREFIX$sessionId-$attachmentId"

        /** 判断 docId 是否属于会话附件。 */
        fun isSessionAttachmentDocId(docId: String): Boolean = docId.startsWith(DOC_ID_PREFIX)

        /** 从 docId 解析出 sessionId(用于会话结束时清理)。 */
        fun extractSessionIdFromDocId(docId: String): String? {
            if (!isSessionAttachmentDocId(docId)) return null
            val rest = docId.removePrefix(DOC_ID_PREFIX)
            return rest.substringBefore('-', missingDelimiterValue = "")
                .takeIf { it.isNotBlank() }
        }
    }
}
