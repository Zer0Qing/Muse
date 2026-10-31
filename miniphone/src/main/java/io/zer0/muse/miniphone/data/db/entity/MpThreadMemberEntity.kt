package io.zer0.muse.miniphone.data.db.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * 群聊的一个成员。
 *
 * 一行只有一个角色,主键是(会话, 角色):同一个角色进同一个群只留一行,重复写入等于覆盖。
 * 本机用户不在表里 —— 群里只有角色是「别人」,自己那侧由界面按本机资料画。
 *
 * [joinedAt] 是入群时间,同时充当展示顺序:同一批建群时按选择顺序递增,
 * 群成员页因此稳定地按入群先后排队,不依赖角色 id 的字母序。
 */
@Entity(
    tableName = "mp_thread_member",
    primaryKeys = ["threadId", "characterId"],
    indices = [Index("threadId")],
)
data class MpThreadMemberEntity(
    val threadId: String,
    val characterId: String,
    val joinedAt: Long,
)
