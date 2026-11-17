package io.zer0.muse.data.runtime

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface RuntimeInstallCardDao {

    /** 写入或更新一张卡（按 messageId 幂等）。 */
    @Upsert
    suspend fun upsert(card: RuntimeInstallCardEntity)

    @Query("SELECT * FROM runtime_install_cards WHERE messageId = :messageId")
    suspend fun findByMessageId(messageId: String): RuntimeInstallCardEntity?

    /** 最近一张还没被"以后再说"收起来的卡；没有则 null。 */
    @Query("SELECT * FROM runtime_install_cards WHERE dismissed = 0 ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latestUndismissed(): RuntimeInstallCardEntity?

    @Query("UPDATE runtime_install_cards SET dismissed = 1, updatedAt = :now WHERE messageId = :messageId")
    suspend fun markDismissed(messageId: String, now: Long)

    /** 运行环境已就绪后卡片就没有存在意义了，直接删掉，避免表里堆历史行。 */
    @Query("DELETE FROM runtime_install_cards WHERE messageId = :messageId")
    suspend fun deleteByMessageId(messageId: String)
}
