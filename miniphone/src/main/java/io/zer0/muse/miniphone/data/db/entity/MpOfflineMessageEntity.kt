package io.zer0.muse.miniphone.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 离线消息：用户发出但还没等到角色应答的那一条。
 *
 * 与 [MpMessageEntity] 分开存：主线消息落库后就算「已发出」，
 * 这里只记「还欠一次应答」，应答到达或明确失败就删掉，不作为聊天正文展示。
 * 进程被回收后靠它恢复待应答状态，而不是让那一行永远停在「发送中」。
 */
@Entity(
    tableName = "mp_offline_message",
    indices = [Index("threadId"), Index("createdAt")],
)
data class MpOfflineMessageEntity(
    @PrimaryKey val id: String,
    val threadId: String,
    val characterId: String,
    val content: String,
    val createdAt: Long,
)
