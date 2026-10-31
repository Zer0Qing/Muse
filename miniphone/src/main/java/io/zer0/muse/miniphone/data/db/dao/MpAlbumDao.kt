package io.zer0.muse.miniphone.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.zer0.muse.miniphone.data.db.entity.MpAlbumEntity
import kotlinx.coroutines.flow.Flow

/** 相册的读写。 */
@Dao
interface MpAlbumDao {
    /** [includeHidden] 为 false 时只返回没隐藏的图。 */
    @Query(
        "SELECT * FROM mp_album WHERE (:includeHidden = 1 OR hidden = 0) " +
            "ORDER BY createdAt DESC, id DESC",
    )
    fun observeAll(includeHidden: Boolean): Flow<List<MpAlbumEntity>>

    @Query(
        "SELECT * FROM mp_album WHERE (:includeHidden = 1 OR hidden = 0) " +
            "ORDER BY createdAt DESC, id DESC LIMIT :limit OFFSET :offset",
    )
    suspend fun page(includeHidden: Boolean, limit: Int, offset: Int): List<MpAlbumEntity>

    @Query(
        "SELECT * FROM mp_album WHERE favorite = 1 AND (:includeHidden = 1 OR hidden = 0) " +
            "ORDER BY createdAt DESC, id DESC",
    )
    suspend fun favorites(includeHidden: Boolean): List<MpAlbumEntity>

    @Query("SELECT * FROM mp_album WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): MpAlbumEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(album: MpAlbumEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(albums: List<MpAlbumEntity>): List<Long>

    @Query("UPDATE mp_album SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    @Query("UPDATE mp_album SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("DELETE FROM mp_album WHERE id = :id")
    suspend fun delete(id: Long)
}
