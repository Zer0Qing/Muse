package io.zer0.memory.fact

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FactTombstoneDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(tombstone: FactTombstoneEntity): Long

    @Query("SELECT normalized_fact FROM fact_deletion_tombstones WHERE scope = :scope AND space_id = :spaceId ORDER BY id")
    suspend fun getNormalizedFacts(scope: String, spaceId: String): List<String>

    @Query("SELECT normalized_fact FROM fact_deletion_tombstones ORDER BY id")
    suspend fun getAllNormalizedFacts(): List<String>

    /** 指定 Space 下的全部删除标记（Space 合并时逐条搬走用）。 */
    @Query("SELECT * FROM fact_deletion_tombstones WHERE space_id = :spaceId ORDER BY id")
    suspend fun getAllForSpace(spaceId: String): List<FactTombstoneEntity>

    /**
     * 删除指定 Space 的删除标记（Space 合并收尾）。
     *
     * 合并不能直接 UPDATE space_id：唯一键是 (scope, space_id, normalized_fact)，
     * default 里已有同一条标记时 UPDATE 会撞键。所以固定顺序是
     * [insertIfAbsent] 逐条搬 → 再删源。
     */
    @Query("DELETE FROM fact_deletion_tombstones WHERE space_id = :spaceId")
    suspend fun deleteBySpace(spaceId: String): Int

    @Query(
        "DELETE FROM fact_deletion_tombstones WHERE scope = :scope AND space_id = :spaceId AND id NOT IN (" +
            "SELECT id FROM fact_deletion_tombstones WHERE scope = :scope AND space_id = :spaceId ORDER BY id DESC LIMIT :limit)",
    )
    suspend fun trimScopeToLimit(scope: String, spaceId: String, limit: Int): Int

    @Query("DELETE FROM fact_deletion_tombstones")
    suspend fun deleteAll(): Int
}
