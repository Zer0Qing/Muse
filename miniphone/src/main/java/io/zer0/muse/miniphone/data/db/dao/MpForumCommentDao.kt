package io.zer0.muse.miniphone.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.zer0.muse.miniphone.data.db.entity.MpForumCommentEntity
import kotlinx.coroutines.flow.Flow

/** 论坛评论读写：按帖子看评论，删帖时级联清掉。 */
@Dao
interface MpForumCommentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(comment: MpForumCommentEntity)

    @Query("SELECT * FROM mp_forum_comment WHERE postId = :postId ORDER BY createdAt ASC")
    fun observeByPost(postId: String): Flow<List<MpForumCommentEntity>>

    @Query("SELECT COUNT(*) FROM mp_forum_comment WHERE postId = :postId")
    suspend fun countByPost(postId: String): Int

    @Query("DELETE FROM mp_forum_comment WHERE postId = :postId")
    suspend fun deleteByPost(postId: String)

    @Query("DELETE FROM mp_forum_comment WHERE id = :id")
    suspend fun delete(id: String)
}
