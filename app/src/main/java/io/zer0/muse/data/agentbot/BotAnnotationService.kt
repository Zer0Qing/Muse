package io.zer0.muse.data.agentbot

import io.zer0.common.Logger
import java.security.MessageDigest
import java.util.UUID

/**
 * Bot 标注的业务层：挂标注、读标注、失效判定。
 *
 * ## 三条它专门守着的规则
 *
 * 1. **不覆盖旧 reaction 列**：只写 `bot_annotations` 表，`messages.reaction` 一个字节不碰。
 * 2. **正文变更后标注失效**：靠 `targetContentHash` 比对，不把旧位置套到新内容上。
 * 3. **同类型标注是"改"不是"加"**：同一条消息上重复给同一种标注，替换而不是堆叠 ——
 *    否则用户会看到一串重复的表情。
 */
class BotAnnotationService(
    private val dao: BotAnnotationDao,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * 给一条消息挂标注。
     *
     * 已存在同类型标注时替换内容而不是新增一条。
     *
     * @param contentHash 目标消息当前正文的哈希；用于后续失效判定
     */
    suspend fun annotate(
        botId: String,
        originSessionId: String,
        targetMessageId: String,
        kind: String,
        assetRef: String,
        contentHash: String,
        actorType: String = BotAnnotationEntity.ACTOR_BOT,
        targetSentenceId: String = "",
    ): BotAnnotationEntity? {
        if (targetMessageId.isBlank()) {
            Logger.w("BotAnnotationService", "拒绝标注：缺少目标消息")
            return null
        }
        if (kind !in AnnotationKind.all) {
            Logger.w("BotAnnotationService", "拒绝标注：未知类型 $kind")
            return null
        }

        val existing = dao.findActive(targetMessageId, actorType, kind)
        val entity = if (existing != null) {
            // 改而不是加：同一条消息上不该出现两个同类型的标注。
            existing.copy(
                assetRef = assetRef,
                targetContentHash = contentHash,
                targetSentenceId = targetSentenceId,
                createdAt = now(),
            )
        } else {
            BotAnnotationEntity(
                id = UUID.randomUUID().toString(),
                botId = botId,
                actorType = actorType,
                originSessionId = originSessionId,
                targetMessageId = targetMessageId,
                targetSentenceId = targetSentenceId,
                targetContentHash = contentHash,
                kind = kind,
                assetRef = assetRef,
                status = AnnotationStatus.ACTIVE,
                createdAt = now(),
            )
        }
        dao.upsert(entity)
        return entity
    }

    /**
     * 读一条消息的标注，顺带做失效判定。
     *
     * [currentContentHash] 与标注里的哈希不一致时，把该标注标 STALE 并不返回 ——
     * 用户看到的应该是"这个表情没了"，而不是"表情挂在了错误的位置"。
     */
    suspend fun activeAnnotations(messageId: String, currentContentHash: String): List<BotAnnotationEntity> {
        val active = dao.activeForMessage(messageId)
        val (valid, stale) = active.partition { annotation ->
            annotation.targetContentHash.isBlank() || annotation.targetContentHash == currentContentHash
        }
        stale.forEach { annotation ->
            dao.setStatus(annotation.id, AnnotationStatus.STALE)
        }
        return valid
    }

    /** 消息被编辑：把它的全部标注标为失效。 */
    suspend fun invalidateForMessage(messageId: String): Int = dao.markStaleForMessage(messageId)

    /** 用户删掉自己挂的标注。 */
    suspend fun remove(annotationId: String): Boolean = dao.setStatus(annotationId, AnnotationStatus.REMOVED) > 0

    /** 会话删除时清掉它的标注。 */
    suspend fun clearSession(sessionId: String) {
        if (sessionId.isBlank()) return
        dao.deleteBySession(sessionId)
    }

    companion object {
        /**
         * 正文哈希。用 SHA-256 前 16 字节足够区分 —— 这里只做"变没变"的判断，
         * 不是安全边界。
         */
        fun hashOf(content: String): String {
            if (content.isEmpty()) return ""
            val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
            return digest.take(16).joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}
