package io.zer0.muse.miniphone.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageStatus
import io.zer0.muse.miniphone.data.db.MpMessageType

/**
 * 一条消息。会话是私信还是群聊由 [io.zer0.muse.miniphone.data.db.entity.MpThreadEntity.isGroup] 决定,
 * 消息行本身不为两者分表。
 *
 * [characterId] 是**这条消息的发言人**:
 * - 私信:恒为会话里的那个角色,用户发的也一样(会话里只有一个对方);
 * - 群聊:角色发的消息写发言的那个角色 id;用户自己发的写**空串** —— 一条消息只能对上一个人,
 *   群里谁说的要靠它区分,而自己不对应任何角色,写会话第一个成员会把「我说的」记成「他说的」。
 * 因此读库里这张表时,不能假设 [characterId] 一定是个存在的角色。
 *
 * [createdAt] 为毫秒时间戳,同一毫秒内插入的多条按 [id] 兜底排序。
 * [status] 是发送状态:用户的消息先以「发送中」落库,拿到回复后改成「已发出」,失败改成「发送失败」。
 * 列默认值写进建表语句,老库升级时也是靠它把历史数据补成「已发出」。
 */
@Entity(
    tableName = "mp_message",
    indices = [Index("threadId"), Index("createdAt")],
)
data class MpMessageEntity(
    @PrimaryKey val id: String,
    val threadId: String,
    val characterId: String,
    val sender: MpActorType,
    val type: MpMessageType,
    val content: String,
    val mediaUri: String? = null,
    val voiceDurationMs: Long? = null,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "0") val status: MpMessageStatus = MpMessageStatus.SENT,
)
