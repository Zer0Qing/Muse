package io.zer0.muse.miniphone.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.zer0.muse.miniphone.data.db.entity.MpMomentEntity
import kotlinx.coroutines.flow.Flow

/** 动态时间线的读写。 */
@Dao
interface MpMomentDao {
    /** 倒序翻页(新 → 旧):[offset] = 已加载条数。 */
    @Query("SELECT * FROM mp_moment ORDER BY createdAt DESC, id DESC LIMIT :limit OFFSET :offset")
    suspend fun pageTimeline(limit: Int, offset: Int): List<MpMomentEntity>

    /** 只要某个作者的一页动态(新 → 旧):「我的朋友圈」用它把别人的动态拦在查询层。 */
    @Query(
        "SELECT * FROM mp_moment WHERE authorId = :authorId " +
            "ORDER BY createdAt DESC, id DESC LIMIT :limit OFFSET :offset",
    )
    suspend fun pageTimelineByAuthor(authorId: String, limit: Int, offset: Int): List<MpMomentEntity>

    @Query("SELECT * FROM mp_moment ORDER BY createdAt DESC, id DESC")
    fun observeTimeline(): Flow<List<MpMomentEntity>>

    /** 只观察某个角色发的动态(角色主页用)。 */
    @Query("SELECT * FROM mp_moment WHERE authorId = :authorId ORDER BY createdAt DESC, id DESC")
    fun observeByAuthor(authorId: String): Flow<List<MpMomentEntity>>

    @Query("SELECT * FROM mp_moment WHERE id = :momentId LIMIT 1")
    suspend fun getMoment(momentId: String): MpMomentEntity?

    /** 某个角色最近发的一条动态;没发过则 null。 */
    @Query("SELECT * FROM mp_moment WHERE authorId = :authorId ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun lastMomentByAuthor(authorId: String): MpMomentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(moment: MpMomentEntity)

    @Query("DELETE FROM mp_moment WHERE id = :momentId")
    suspend fun delete(momentId: String)

    @Query("SELECT COUNT(*) FROM mp_moment")
    suspend fun count(): Int
}
