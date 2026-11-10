package io.zer0.muse.miniphone.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.zer0.muse.miniphone.data.db.entity.MpForumPostEntity
import kotlinx.coroutines.flow.Flow

/** 论坛发帖读写：时间线倒序翻页、按作者看主页、点赞名单整体覆盖。 */
@Dao
interface MpForumPostDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(post: MpForumPostEntity)

    @Query("SELECT * FROM mp_forum_post WHERE id = :id LIMIT 1")
    suspend fun get(id: String): MpForumPostEntity?

    @Query("SELECT * FROM mp_forum_post ORDER BY createdAt DESC, id DESC LIMIT :limit OFFSET :offset")
    suspend fun pageTimeline(limit: Int, offset: Int): List<MpForumPostEntity>

    @Query("SELECT * FROM mp_forum_post ORDER BY createdAt DESC, id DESC")
    fun observeTimeline(): Flow<List<MpForumPostEntity>>

    @Query("SELECT * FROM mp_forum_post WHERE authorId = :authorId ORDER BY createdAt DESC, id DESC")
    fun observeByAuthor(authorId: String): Flow<List<MpForumPostEntity>>

    @Query("UPDATE mp_forum_post SET likeIdsJson = :likeIdsJson WHERE id = :id")
    suspend fun setLikes(id: String, likeIdsJson: String)

    @Query("DELETE FROM mp_forum_post WHERE id = :id")
    suspend fun delete(id: String)
}
