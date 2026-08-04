package io.zer0.muse.data.groupchat

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * B5-02: 群聊生成账本。
 *
 * 每次群聊轮转启动时写入一条记录，记录当前模式、轮次、成员下标和有序成员列表；
 * 进程被杀后，启动恢复逻辑按账本从断点继续，而不是重头触发全部成员。
 */
@Entity(
    tableName = "group_chat_generation_ledger",
    foreignKeys = [
        ForeignKey(
            entity = GroupChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index("chatId"),
        Index("status"),
    ],
)
data class GroupChatGenerationLedgerEntity(
    @PrimaryKey val id: String,
    val chatId: String,
    val mode: String,
    val round: Int,
    val memberIndex: Int,
    @ColumnInfo(defaultValue = "[]") val memberIdsJson: String = "[]",
    @ColumnInfo(defaultValue = "running") val status: String = "running",
    @ColumnInfo(defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface GroupChatGenerationLedgerDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: GroupChatGenerationLedgerEntity)

    @Query("DELETE FROM group_chat_generation_ledger WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM group_chat_generation_ledger WHERE chatId = :chatId")
    suspend fun deleteByChatId(chatId: String)

    @Query("SELECT * FROM group_chat_generation_ledger WHERE id = :id")
    suspend fun getById(id: String): GroupChatGenerationLedgerEntity?

    @Query("SELECT * FROM group_chat_generation_ledger WHERE status != 'completed' ORDER BY updatedAt ASC")
    suspend fun getPending(): List<GroupChatGenerationLedgerEntity>
}
