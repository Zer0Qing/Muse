package io.zer0.muse.miniphone.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.zer0.muse.miniphone.data.db.entity.MpMomentCommentEntity
import kotlinx.coroutines.flow.Flow

/** 动态评论的读写。 */
@Dao
interface MpMomentCommentDao {
    @Query("SELECT * FROM mp_moment_comment WHERE momentId = :momentId ORDER BY createdAt ASC, id ASC")
    fun observeComments(momentId: String): Flow<List<MpMomentCommentEntity>>

    /** 一次性取某条动态的全部评论:只需要读一眼时不必开一条观察流。 */
    @Query("SELECT * FROM mp_moment_comment WHERE momentId = :momentId ORDER BY createdAt ASC, id ASC")
    suspend fun commentsOf(momentId: String): List<MpMomentCommentEntity>

    /** 一批动态的评论:时间线一页只查这一次,再在内存里按动态归组。 */
    @Query("SELECT * FROM mp_moment_comment WHERE momentId IN (:momentIds) ORDER BY createdAt ASC, id ASC")
    suspend fun commentsOfMomentIds(momentIds: List<String>): List<MpMomentCommentEntity>

    @Query("SELECT COUNT(*) FROM mp_moment_comment WHERE momentId = :momentId")
    suspend fun count(momentId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(comment: MpMomentCommentEntity)

    @Query("DELETE FROM mp_moment_comment WHERE id = :commentId")
    suspend fun delete(commentId: String)

    /** 一次删一批:删一条评论时,它下面挂着的回复也要跟着走。 */
    @Query("DELETE FROM mp_moment_comment WHERE id IN (:commentIds)")
    suspend fun deleteByIds(commentIds: List<String>)

    @Query("DELETE FROM mp_moment_comment WHERE momentId = :momentId")
    suspend fun deleteAllOf(momentId: String)
}
