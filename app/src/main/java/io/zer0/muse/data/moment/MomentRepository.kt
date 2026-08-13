package io.zer0.muse.data.moment

import androidx.room.withTransaction
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * v1.0.72: AI 朋友圈仓库 — 封装 [MomentDao],暴露 CRUD + 评论 + 点赞。
 *
 * v1.0.73: 多助手支持 — 动态/评论/点赞都带发布者身份(user/assistant),
 * 点赞走 ai_moment_likes 表(用户 + 助手互赞互评)。
 */
class MomentRepository(
    private val dao: MomentDao,
    // 审计修复 (5.1): 注入 db 供事务使用(点赞读-改-写原子化)
    private val db: io.zer0.muse.data.session.MuseDb,
) {

    private val TAG = "MomentRepo"

    /** 全部动态(时间倒序)。 */
    fun observeMoments(limit: Int = 100): Flow<List<MomentEntity>> = flow {
        emit(dao.getAll(limit))
    }

    /** 一次性取全部动态。 */
    suspend fun getAll(limit: Int = 100): List<MomentEntity> = withContext(Dispatchers.IO) {
        resultOf { dao.getAll(limit) }
            .onError { msg, t -> Logger.w(TAG, "读取动态失败: $msg", t) }
            .getOrNull() ?: emptyList()
    }

    /** 某条动态的评论。 */
    suspend fun getComments(momentId: String): List<MomentCommentEntity> = withContext(Dispatchers.IO) {
        resultOf { dao.getComments(momentId) }
            .onError { msg, t -> Logger.w(TAG, "读取评论失败: $msg", t) }
            .getOrNull() ?: emptyList()
    }

    /** 审计修复 (6.6): 批量取多条动态的评论(map: momentId → comments)。 */
    suspend fun getCommentsBatch(momentIds: List<String>): Map<String, List<MomentCommentEntity>> =
        withContext(Dispatchers.IO) {
            if (momentIds.isEmpty()) return@withContext emptyMap()
            val all = resultOf { dao.getCommentsFor(momentIds) }
                .onError { msg, t -> Logger.w(TAG, "批量读评论失败: $msg", t) }
                .getOrNull() ?: emptyList()
            all.groupBy { it.momentId }
        }

    /** 某条动态的点赞记录。 */
    suspend fun getLikes(momentId: String): List<MomentLikeEntity> = withContext(Dispatchers.IO) {
        resultOf { dao.getLikes(momentId) }
            .onError { msg, t -> Logger.w(TAG, "读取点赞失败: $msg", t) }
            .getOrNull() ?: emptyList()
    }

    /** 用户发动态(可带多图)。 */
    suspend fun insertUserMoment(content: String, images: List<String> = emptyList()): MomentEntity? =
        withContext(Dispatchers.IO) {
            val cleanImages = images.filter { it.isNotBlank() }
            val moment = MomentEntity(
                id = UUID.randomUUID().toString(),
                content = content,
                type = "life_share",
                source = "user",
                createdAt = System.currentTimeMillis(),
                senderType = "user",
                senderName = "我",
                imageUrl = cleanImages.firstOrNull(),
                imagesJson = momentImagesJson(cleanImages),
            )
            try {
                dao.insertMoment(moment)
                moment
            } catch (t: Throwable) {
                Logger.w(TAG, "用户发动态失败: ${t.message}")
                null
            }
        }

    /** 插入动态(调度器/手动生成用,按发布者身份)。 */
    suspend fun insertMoment(
        content: String,
        type: String,
        mood: String?,
        source: String,
        imageUrl: String? = null,
        images: List<String> = emptyList(),
        senderType: String = "assistant",
        senderName: String = "Muse",
        senderId: String? = null,
        senderAvatar: String? = null,
    ): MomentEntity = withContext(Dispatchers.IO) {
        val cleanImages = images.filter { it.isNotBlank() }
        val moment = MomentEntity(
            id = UUID.randomUUID().toString(),
            content = content,
            type = type,
            mood = mood,
            source = source,
            createdAt = System.currentTimeMillis(),
            imageUrl = imageUrl ?: cleanImages.firstOrNull(),
            imagesJson = momentImagesJson(cleanImages),
            senderType = senderType,
            senderName = senderName,
            senderId = senderId,
            senderAvatar = senderAvatar,
        )
        resultOf { dao.insertMoment(moment) }
            .onError { msg, t -> Logger.w(TAG, "插入动态失败: $msg", t) }
        moment
    }

    /** 插入评论(带发送者身份)。 */
    suspend fun insertComment(
        momentId: String,
        sender: String,
        content: String,
        senderId: String? = null,
        senderName: String? = null,
    ): MomentCommentEntity = withContext(Dispatchers.IO) {
        val comment = MomentCommentEntity(
            id = UUID.randomUUID().toString(),
            momentId = momentId,
            sender = sender,
            content = content,
            createdAt = System.currentTimeMillis(),
            senderId = senderId,
            senderName = senderName,
        )
        resultOf { dao.insertComment(comment) }
            .onError { msg, t -> Logger.w(TAG, "插入评论失败: $msg", t) }
        comment
    }

    /** 点赞/取消点赞(按点赞者身份)。返回 (更新后的动态, 是否已点赞)。
     *  v1.0.74 fix: 仅 user 场景同步 likedByUser 字段;assistant 点赞走 [likeBy],
     *  此前 toggleLike 被 assistant 调用会把用户"已赞"状态写错。 */
    suspend fun toggleLike(
        moment: MomentEntity,
        likerType: String,
        likerId: String,
        likerName: String,
    ): Pair<MomentEntity, Boolean> = withContext(Dispatchers.IO) {
        // 审计修复 (5.1): 读-改-写放同一 Room 事务,likes 以 DB 内 count 为准。
        // 原实现基于调用方传入的过期 moment.likes 快照计算,并发双击/与调度器 likeBy
        // 竞争时计数错乱;且 resultOf 吞错后仍返回"成功"副本,用户看到"点了没反应"。
        // 注:withTransaction 是 RoomDatabase 的扩展,必须经由注入的 db 调用。
        db.withTransaction {
            val alreadyLiked = dao.hasLiked(moment.id, likerType, likerId)
            val liked = alreadyLiked == 0
            if (liked) {
                dao.addLike(
                    MomentLikeEntity(
                        momentId = moment.id,
                        likerType = likerType,
                        likerId = likerId,
                        likerName = likerName,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            } else {
                dao.removeLike(moment.id, likerType, likerId)
            }
            // 点赞数以 DB 实际记录为准,不依赖传入快照
            val dbLikes = dao.countLikes(moment.id)
            val newLikedByUser = if (likerType == "user") liked else moment.likedByUser
            dao.setLiked(moment.id, dbLikes, newLikedByUser)
            moment.copy(likes = dbLikes, likedByUser = newLikedByUser) to liked
        }
    }

    /** 助手给动态点赞(不重复)。 */
    suspend fun likeBy(
        moment: MomentEntity,
        likerType: String,
        likerId: String,
        likerName: String,
    ): MomentEntity = withContext(Dispatchers.IO) {
        val alreadyLiked = resultOf { dao.hasLiked(moment.id, likerType, likerId) }.getOrNull() ?: 0
        if (alreadyLiked > 0) return@withContext moment
        val newLikes = moment.likes + 1
        resultOf {
            dao.addLike(
                MomentLikeEntity(
                    momentId = moment.id,
                    likerType = likerType,
                    likerId = likerId,
                    likerName = likerName,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            dao.setLiked(moment.id, newLikes, moment.likedByUser)
        }.onError { msg, t -> Logger.w(TAG, "助手点赞失败: $msg", t) }
        moment.copy(likes = newLikes)
    }

    /** 删除动态(级联评论 + 点赞记录)。 */
    suspend fun deleteMoment(id: String) = withContext(Dispatchers.IO) {
        resultOf {
            dao.deleteMoment(id)
            dao.deleteComments(id)
            // v1.0.74 fix: 此前漏删点赞记录,表数据持续膨胀、统计语义被破坏
            dao.deleteLikes(id)
        }.onError { msg, t -> Logger.w(TAG, "删除动态失败: $msg", t) }

    }

    /** 今天已生成的条数。 */
    suspend fun countToday(): Int = withContext(Dispatchers.IO) {
        val dayStart = java.time.LocalDate.now()
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        val dayEnd = dayStart + 86_400_000L
        resultOf { dao.countBetween(dayStart, dayEnd) }.getOrNull() ?: 0
    }

    /** v1.0.74: 用户动态收到的全部赞/评(消息中心,按时间倒序)。 */
    suspend fun getUserMessages(): List<MomentMessage> = withContext(Dispatchers.IO) {
        resultOf {
            val userMoments = dao.getUserMoments(50)
            val messages = mutableListOf<MomentMessage>()
            userMoments.forEach { m ->
                dao.getLikes(m.id).filter { it.likerType == "assistant" }.forEach { l ->
                    messages += MomentMessage(
                        id = "like_${l.momentId}_${l.likerId}",
                        type = "like",
                        momentId = m.id,
                        momentContent = m.content,
                        actorName = l.likerName,
                        createdAt = l.createdAt,
                    )
                }
                dao.getComments(m.id).filter { it.sender == "assistant" }.forEach { c ->
                    messages += MomentMessage(
                        id = "comment_${c.id}",
                        type = "comment",
                        momentId = m.id,
                        momentContent = m.content,
                        actorName = c.senderName?.takeIf { it.isNotBlank() } ?: "Muse",
                        content = c.content,
                        createdAt = c.createdAt,
                    )
                }
            }
            messages.sortedByDescending { it.createdAt }
        }.onError { msg, t ->
            Logger.w(TAG, "读取消息失败: ${t?.message ?: msg}")
        }.getOrNull() ?: emptyList()
    }
}
