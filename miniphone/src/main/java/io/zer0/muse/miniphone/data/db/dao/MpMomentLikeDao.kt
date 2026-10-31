package io.zer0.muse.miniphone.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.entity.MpMomentLikeEntity
import kotlinx.coroutines.flow.Flow

/** 动态点赞的读写。 */
@Dao
interface MpMomentLikeDao {
    @Query("SELECT * FROM mp_moment_like WHERE momentId = :momentId ORDER BY createdAt ASC")
    suspend fun likesOf(momentId: String): List<MpMomentLikeEntity>

    /** 一批动态的点赞:时间线一页只查这一次,再在内存里按动态归组。 */
    @Query("SELECT * FROM mp_moment_like WHERE momentId IN (:momentIds) ORDER BY createdAt ASC")
    suspend fun likesOfMomentIds(momentIds: List<String>): List<MpMomentLikeEntity>

    @Query("SELECT COUNT(*) FROM mp_moment_like WHERE momentId = :momentId")
    fun observeLikeCount(momentId: String): Flow<Int>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM mp_moment_like " +
            "WHERE momentId = :momentId AND likerType = :likerType AND likerId = :likerId)",
    )
    suspend fun isLiked(momentId: String, likerType: MpActorType, likerId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(like: MpMomentLikeEntity)

    @Query(
        "DELETE FROM mp_moment_like " +
            "WHERE momentId = :momentId AND likerType = :likerType AND likerId = :likerId",
    )
    suspend fun delete(momentId: String, likerType: MpActorType, likerId: String)

    @Query("DELETE FROM mp_moment_like WHERE momentId = :momentId")
    suspend fun deleteAllOf(momentId: String)
}
