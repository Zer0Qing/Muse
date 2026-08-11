package io.zer0.muse.data.moment

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * v1.0.72: AI 朋友圈动态 — Room 实体。
 *
 * 动态来源: AI 定时生成(基于记忆/情绪/时节)+ 用户手动发布。
 * 用户可点赞、评论;AI 会评论回复;有生图配置时 AI 动态带配图。
 * v1.0.73: 新增 sender 字段(用户/助手)+ imageUrl 配图。
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
    /** 来源: scheduled(定时) / manual(手动) / user(用户发布)。 */
    @ColumnInfo(defaultValue = "scheduled") val source: String = "scheduled",
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0,
    /** v1.0.73: 发布者类型: user(用户) / assistant(AI 助手)。 */
    @ColumnInfo(defaultValue = "assistant") val senderType: String = "assistant",
    /** v1.0.73: 发布者显示名。 */
    @ColumnInfo(defaultValue = "Muse") val senderName: String = "Muse",
    /** v1.0.73: 发布者头像标识(助手 id / 首字符)。 */
    val senderAvatar: String? = null,
    /** v1.0.73: AI 配图 URL(生图模型生成,可为空)。 */
    val imageUrl: String? = null,
    /** v1.0.73: 发布者 id(assistantId;用户发布为 null)。 */
    val senderId: String? = null,
    /** v1.0.73: 多图(JSON 数组;优先于 [imageUrl] 单图)。 */
    @ColumnInfo(defaultValue = "[]") val imagesJson: String = "[]",
)

/** v1.0.73: 多图解析辅助(手动 JSON 构建,避免 serializer 泛型冲突)。 */
fun MomentEntity.images(): List<String> = runCatching {
    val arr = io.zer0.common.AppJson.parseToJsonElement(imagesJson).jsonArray
    arr.mapNotNull { it.jsonPrimitive.contentOrNull }
}.getOrDefault(emptyList()).filter { it.isNotBlank() }

/** v1.0.73: 多图序列化辅助。 */
fun momentImagesJson(images: List<String>): String = runCatching {
    kotlinx.serialization.json.buildJsonArray {
        images.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
    }.toString()
}.getOrDefault("[]")

/**
 * v1.0.74: 朋友圈消息(用户动态收到的赞/评,运行时组装)。
 */
@kotlinx.serialization.Serializable
data class MomentMessage(
    val id: String,
    /** like / comment。 */
    val type: String,
    val momentId: String,
    /** 动态内容摘要(展示用)。 */
    val momentContent: String,
    /** 谁赞的/评的。 */
    val actorName: String,
    /** 头像 URL(助手头像)。 */
    val actorAvatar: String? = null,
    /** 评论内容(like 为空)。 */
    val content: String = "",
    val createdAt: Long = 0,
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
    /** v1.0.73: 评论者 id(assistantId;用户为 null)。 */
    val senderId: String? = null,
    /** v1.0.73: 评论者显示名(助手名/我)。 */
    val senderName: String? = null,
)

/** v1.0.73: 点赞记录 — 支持用户 + 多个助手互相点赞。 */
@Serializable
@Entity(
    tableName = "ai_moment_likes",
    primaryKeys = ["momentId", "likerType", "likerId"],
)
data class MomentLikeEntity(
    @ColumnInfo(name = "momentId") val momentId: String,
    /** 点赞者类型: user / assistant。 */
    @ColumnInfo(name = "likerType") val likerType: String,
    /** 点赞者 id(assistantId;用户为 "user")。 */
    @ColumnInfo(name = "likerId") val likerId: String,
    /** 点赞者显示名。 */
    @ColumnInfo(name = "likerName") val likerName: String,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0,
)

/** v1.0.72: 朋友圈 DAO。 */
@Dao
interface MomentDao {

    /** 全部动态(按时间倒序,最新在前)。 */
    @Query("SELECT * FROM ai_moments ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getAll(limit: Int = 100): List<MomentEntity>

    /** 用户发布的动态(按时间倒序)。 */
    @Query("SELECT * FROM ai_moments WHERE senderType = 'user' ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getUserMoments(limit: Int = 100): List<MomentEntity>

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

    /** 删除动态的点赞记录。 */
    @Query("DELETE FROM ai_moment_likes WHERE momentId = :momentId")
    suspend fun deleteLikes(momentId: String)

    /** 清空全部动态。 */
    @Query("DELETE FROM ai_moments")
    suspend fun deleteAllMoments()

    /** 清空全部评论。 */
    @Query("DELETE FROM ai_moment_comments")
    suspend fun deleteAllComments()

    /** 清空全部点赞记录。 */
    @Query("DELETE FROM ai_moment_likes")
    suspend fun deleteAllLikes()

    /** 删除单条评论。 */
    @Query("DELETE FROM ai_moment_comments WHERE id = :id")
    suspend fun deleteComment(id: String)

    /** 未读数 = 最近 [since] 之后生成的动态数。 */
    @Query("SELECT COUNT(*) FROM ai_moments WHERE createdAt > :since")
    suspend fun countNewerThan(since: Long): Int

    /** 动态总数。 */
    @Query("SELECT COUNT(*) FROM ai_moments")
    suspend fun countAll(): Int

    /** 全部评论。 */
    @Query("SELECT * FROM ai_moment_comments")
    suspend fun getAllComments(): List<MomentCommentEntity>

    /** 全部点赞记录。 */
    @Query("SELECT * FROM ai_moment_likes")
    suspend fun getAllLikes(): List<MomentLikeEntity>

    /** 今天已生成的条数。 */
    @Query("SELECT COUNT(*) FROM ai_moments WHERE createdAt >= :dayStart AND createdAt < :dayEnd")
    suspend fun countBetween(dayStart: Long, dayEnd: Long): Int

    // ── v1.0.73: 点赞记录(用户 + 助手互赞) ──

    /** 某条动态的全部点赞记录。 */
    @Query("SELECT * FROM ai_moment_likes WHERE momentId = :momentId ORDER BY createdAt ASC")
    suspend fun getLikes(momentId: String): List<MomentLikeEntity>

    /** 某条动态的点赞数。 */
    @Query("SELECT COUNT(*) FROM ai_moment_likes WHERE momentId = :momentId")
    suspend fun countLikes(momentId: String): Int

    /** 某人是否点过赞。 */
    @Query("SELECT COUNT(*) FROM ai_moment_likes WHERE momentId = :momentId AND likerType = :likerType AND likerId = :likerId")
    suspend fun hasLiked(momentId: String, likerType: String, likerId: String): Int

    /** 添加点赞。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addLike(like: MomentLikeEntity)

    /** 取消点赞。 */
    @Query("DELETE FROM ai_moment_likes WHERE momentId = :momentId AND likerType = :likerType AND likerId = :likerId")
    suspend fun removeLike(momentId: String, likerType: String, likerId: String)
}
