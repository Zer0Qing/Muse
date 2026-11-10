package io.zer0.muse.miniphone.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 角色心声（内心独白）。与主线消息分离存放，不污染会话记录；
 * [messageId] 关联它伴随的那条角色消息（可空，独立心声时为 null）。
 */
@Entity(
    tableName = "mp_inner_voice",
    indices = [Index("threadId"), Index("createdAt")],
)
data class MpInnerVoiceEntity(
    @PrimaryKey val id: String,
    val threadId: String,
    val characterId: String,
    val messageId: String?,
    val text: String,
    val createdAt: Long,
)
