package io.zer0.muse.miniphone.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.zer0.muse.miniphone.data.db.entity.MpThreadMemberEntity
import kotlinx.coroutines.flow.Flow

/** 群聊成员的读写。私信会话没有成员行。 */
@Dao
interface MpThreadMemberDao {
    /** 一个群的成员,按入群先后(建群时按选择顺序)。 */
    @Query("SELECT * FROM mp_thread_member WHERE threadId = :threadId ORDER BY joinedAt ASC, characterId ASC")
    fun observeMembers(threadId: String): Flow<List<MpThreadMemberEntity>>

    @Query("SELECT * FROM mp_thread_member WHERE threadId = :threadId ORDER BY joinedAt ASC, characterId ASC")
    suspend fun membersOf(threadId: String): List<MpThreadMemberEntity>

    @Query("SELECT COUNT(*) FROM mp_thread_member WHERE threadId = :threadId")
    suspend fun countInThread(threadId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(members: List<MpThreadMemberEntity>)

    @Query("DELETE FROM mp_thread_member WHERE threadId = :threadId")
    suspend fun deleteInThread(threadId: String)

    /** 移出一个人;其余成员不动。 */
    @Query("DELETE FROM mp_thread_member WHERE threadId = :threadId AND characterId = :characterId")
    suspend fun deleteMember(threadId: String, characterId: String)

    /** 清空全部成员行(群会话由调用方一并删)。 */
    @Query("DELETE FROM mp_thread_member")
    suspend fun deleteAll()
}
