package io.zer0.muse.data.moment

import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * v1.0.72: AI 朋友圈仓库 — 封装 [MomentDao],暴露 CRUD + 评论。
 */
class MomentRepository(
    private val dao: MomentDao,
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

    /** 插入动态(调度器/手动生成用)。 */
    suspend fun insertMoment(content: String, type: String, mood: String?, source: String): MomentEntity =
        withContext(Dispatchers.IO) {
            val moment = MomentEntity(
                id = UUID.randomUUID().toString(),
                content = content,
                type = type,
                mood = mood,
                source = source,
                createdAt = System.currentTimeMillis(),
            )
            resultOf { dao.insertMoment(moment) }
                .onError { msg, t -> Logger.w(TAG, "插入动态失败: $msg", t) }
            moment
        }

    /** 插入评论。 */
    suspend fun insertComment(momentId: String, sender: String, content: String): MomentCommentEntity =
        withContext(Dispatchers.IO) {
            val comment = MomentCommentEntity(
                id = UUID.randomUUID().toString(),
                momentId = momentId,
                sender = sender,
                content = content,
                createdAt = System.currentTimeMillis(),
            )
            resultOf { dao.insertComment(comment) }
                .onError { msg, t -> Logger.w(TAG, "插入评论失败: $msg", t) }
            comment
        }

    /** 点赞/取消点赞。 */
    suspend fun toggleLike(moment: MomentEntity): MomentEntity = withContext(Dispatchers.IO) {
        val liked = !moment.likedByUser
        val newLikes = (moment.likes + if (liked) 1 else -1).coerceAtLeast(0)
        resultOf { dao.setLiked(moment.id, newLikes, liked) }
            .onError { msg, t -> Logger.w(TAG, "点赞失败: $msg", t) }
        moment.copy(likes = newLikes, likedByUser = liked)
    }

    /** 删除动态(级联评论)。 */
    suspend fun deleteMoment(id: String) = withContext(Dispatchers.IO) {
        resultOf {
            dao.deleteMoment(id)
            dao.deleteComments(id)
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
}
