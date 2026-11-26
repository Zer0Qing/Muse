package io.zer0.memory.dream

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MemoryDreamDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRevision(entity: MemoryDreamRevisionEntity)

    @Query(
        "SELECT * FROM memory_dream_revisions WHERE revisionId = :revisionId AND assistantId = :assistantId AND scope = :scope AND spaceId = :spaceId",
    )
    suspend fun findRevision(revisionId: String, assistantId: String, scope: String, spaceId: String): MemoryDreamRevisionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(entity: MemoryDreamStateEntity)

    @Query("SELECT * FROM memory_dream_states WHERE assistantId = :assistantId AND scope = :scope AND spaceId = :spaceId")
    suspend fun state(assistantId: String, scope: String, spaceId: String): MemoryDreamStateEntity?

    /** 删除助手时清掉它的 Dream 状态；返回删除行数。 */
    @Query("DELETE FROM memory_dream_states WHERE assistantId = :assistantId")
    suspend fun deleteStatesByAssistant(assistantId: String): Int

    /** 删除助手时清掉它的 revision 快照，避免回滚到已删助手的内容。 */
    @Query("DELETE FROM memory_dream_revisions WHERE assistantId = :assistantId")
    suspend fun deleteRevisionsByAssistant(assistantId: String): Int

    /**
     * 删除某个空间的 Dream 状态（Space 合并后两边都要清）。
     *
     * 保留状态里的 lastRevisionId/activeRevisionId 会指向已经不成立的快照，
     * 下一次回滚就会拿合并前的正文盖回去。
     */
    @Query("DELETE FROM memory_dream_states WHERE spaceId = :spaceId")
    suspend fun deleteStatesBySpace(spaceId: String): Int

    /** 删除某个空间的 Dream revision 快照（Space 合并后两边都要清）。 */
    @Query("DELETE FROM memory_dream_revisions WHERE spaceId = :spaceId")
    suspend fun deleteRevisionsBySpace(spaceId: String): Int

    /** 重置全部记忆时清空 Dream 状态。 */
    @Query("DELETE FROM memory_dream_states")
    suspend fun deleteAllStates(): Int

    /** 重置全部记忆时清空 revision 快照。 */
    @Query("DELETE FROM memory_dream_revisions")
    suspend fun deleteAllRevisions(): Int
}
