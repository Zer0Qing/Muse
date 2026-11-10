package io.zer0.muse.miniphone.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一次通话的记录。挂断时落一行，记下打给谁、语音还是视频、何时开始、通了多久。
 *
 * [kind] 取 "voice" / "video"；[durationMs] 为接通后的通话时长，未接通（呼损）记 0。
 * 与消息表分离：通话是会话旁的一条时间线，不混进聊天正文。
 */
@Entity(
    tableName = "mp_call_session",
    indices = [Index("threadId"), Index("startedAt")],
)
data class MpCallSessionEntity(
    @PrimaryKey val id: String,
    val threadId: String,
    val characterId: String,
    val kind: String,
    val startedAt: Long,
    val durationMs: Long,
)
