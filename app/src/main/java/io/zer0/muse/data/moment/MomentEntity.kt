package io.zer0.muse.data.moment

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.serialization.Serializable

/**
 * v1.0.72: AI 朋友圈动态 — Room 实体。
 *
 * AI 的\"生活动态\":基于记忆/情绪/时节生成,用户可点赞、评论、删除。
 */
@Serializable
@Entity(tableName = "ai_moments")
data class MomentEntity(
    @PrimaryKey val id: String,
    /** 动态正文。 */
    val content: String,
    /** 动态类型: life_share(生活分享) / mood_diary(陪伴日记) / event(事件记录) / seasonal(应景随笔)。 */
    @ColumnInfo(defaultValue = "life") val type: String = "life",
    /** 生成时的情绪标签。 */
    val mood: String? = null,
    /** 点赞数。 */
    @ColumnInfo(defaultValue = "0") val likes: Int = 0,
    /** 用户是否点过赞。 */
    @ColumnInfo(defaultValue = "0") val likedByUser: Boolean = false,
    /** 来源: scheduled(定时) / manual(手动) / event(事件)。 */
    @ColumnInfo(defaultValue = "scheduled") val source: String = "scheduled",
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0,
)

/** v1.0.72: 朋友圈评论(AI 回复 + 用户评论统一存)。 */
@Serializable
@Entity(tableName = "ai_moment_comments")
data class MomentCommentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "momentId") val momentId: String,
    /** 发送者: user / assistant。 */
    val sender: String,
    val content: String,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0,
)

/** v1.0.72: 朋友圈 DAO。 */
@Dao
interface MomentDao {

    /** 全部动态(按时间倒序,最新在前)。 */
    @Query("SELECT * FROM ai_moments ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getAll(limit: Int = 100): List<MomentEntity>

    /** 单条动态。 */
    @Query("SELECT * FROM ai_moments WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MomentEntity?

    /** 某条动态的评论(按时间升序)。 */
    @Query("SELECT * FROM ai_moment_comments WHERE momentId = :momentId ORDER BY createdAt ASC")
    suspend fun getComments(momentId: String): List<MomentCommentEntity>

    /** 插入动态(同 id 替换)。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMoment(moment: MomentEntity)

    /** 插入评论。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertComment(comment: MomentCommentEntity)

    /** 点赞/取消点赞。 */
    @Query("UPDATE ai_moments SET likes = :likes, likedByUser = :likedByUser WHERE id = :id")
    suspend fun setLiked(id: String, likes: Int, likedByUser: Boolean)

    /** 删除动态(级联评论)。 */
    @Query("DELETE FROM ai_moments WHERE id = :id")
    suspend fun deleteMoment(id: String)

    /** 删除动态的评论。 */
    @Query("DELETE FROM ai_moment_comments WHERE momentId = :momentId")
    suspend fun deleteComments(momentId: String)

    /** 删除单条评论。 */
    @Query("DELETE FROM ai_moment_comments WHERE id = :id")
    suspend fun deleteComment(id: String)

    /** 未读数 = 最近 [since] 之后生成的动态数。 */
    @Query("SELECT COUNT(*) FROM ai_moments WHERE createdAt > :since")
    suspend fun countNewerThan(since: Long): Int

    /** 动态总数。 */
    @Query("SELECT COUNT(*) FROM ai_moments")
    suspend fun countAll(): Int

    /** 今天已生成的条数。 */
    @Query("SELECT COUNT(*) FROM ai_moments WHERE createdAt >= :dayStart AND createdAt < :dayEnd")
    suspend fun countBetween(dayStart: Long, dayEnd: Long): Int
}
