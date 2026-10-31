package io.zer0.muse.miniphone.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一条会话:私信或群聊。
 *
 * [characterId] 对私信是对话的那个角色;对群聊是**第一个成员**的 id —— 群里要找人开口时
 * 用的就是它,成员全表在 [MpThreadMemberEntity]。
 *
 * [lastMessageAt] 只用于列表排序与展示,不参与未读判定。[pinned] 落库为 0/1,
 * 列上带默认值 0:老库升级与不带这一列的写入都得到「未置顶」。
 *
 * [isGroup] 与 [title] 是群聊带来的两列,私信行一律 `false` 与空串:老库升级后所有存量会话
 * 都落在「私信」这一侧,界面与查询不需要为它们另走一条分支。
 * [title] 只在群里被读 —— 群名不跟着成员名字实时变,建群那一刻拼接好就定下来。
 * [titleCustomized] 记「用户手动改过群名没有」:没改过的群在加人后按成员重算群名,
 * 改过的就一律保持原样 —— 不能把用户特意起的名子盖成名字拼接。
 */
@Entity(
    tableName = "mp_thread",
    indices = [Index("characterId"), Index("lastMessageAt")],
)
data class MpThreadEntity(
    @PrimaryKey val id: String,
    val characterId: String,
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    val unreadCount: Int = 0,
    val lastMessagePreview: String? = null,
    val lastMessageAt: Long = 0L,
    val muted: Boolean = false,
    @ColumnInfo(defaultValue = "0") val isGroup: Boolean = false,
    @ColumnInfo(defaultValue = "''") val title: String = "",
    @ColumnInfo(defaultValue = "0") val titleCustomized: Boolean = false,
)
