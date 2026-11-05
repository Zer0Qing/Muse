package io.zer0.memory.fact

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * v20(M3): 画像快照 DAO。
 *
 * 读写都按 (scope, space_id) 边界走,不提供跨空间全量读取 ——
 * 画像注入的是「当前空间里的对话」,混入其他空间画像等于场景串味。
 */
@Dao
interface ProfileSnapshotDao {

    /** upsert 语义:同 (scope, space_id, section) 覆盖。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProfileSnapshotEntity): Long

    @Query(
        """
        SELECT * FROM profile_snapshots
        WHERE scope = :scope AND space_id = :spaceId
        """,
    )
    suspend fun getByScopeAndSpace(scope: String, spaceId: String): List<ProfileSnapshotEntity>

    @Query(
        """
        SELECT * FROM profile_snapshots
        WHERE scope = :scope AND space_id = :spaceId AND section = :section
        """,
    )
    suspend fun getOne(scope: String, spaceId: String, section: String): ProfileSnapshotEntity?

    /** 用户覆写/清除覆写:manual_content 传 null 恢复自动版。 */
    @Query(
        """
        UPDATE profile_snapshots SET manual_content = :manualContent
        WHERE scope = :scope AND space_id = :spaceId AND section = :section
        """,
    )
    suspend fun setManualContent(scope: String, spaceId: String, section: String, manualContent: String?): Int

    /** 仅续期「检查过」时间戳(指纹未变时避免重写正文)。 */
    @Query(
        """
        UPDATE profile_snapshots SET last_refresh_at = :refreshedAt
        WHERE scope = :scope AND space_id = :spaceId AND section = :section
        """,
    )
    suspend fun touchRefreshedAt(scope: String, spaceId: String, section: String, refreshedAt: String): Int

    /** 删除助手/重置时整段清理。 */
    @Query("DELETE FROM profile_snapshots WHERE scope = :scope")
    suspend fun deleteAllByScope(scope: String): Int

    @Query("DELETE FROM profile_snapshots")
    suspend fun deleteAll(): Int

    @Query("SELECT COUNT(*) FROM profile_snapshots WHERE scope = :scope AND space_id = :spaceId")
    suspend fun countFor(scope: String, spaceId: String): Int
}
