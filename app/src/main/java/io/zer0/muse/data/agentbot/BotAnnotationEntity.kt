package io.zer0.muse.data.agentbot

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Bot 对某条消息的标注（表情回应 / 贴纸 / 引用定位）。
 *
 * ## 为什么另建表而不复用 messages.reaction 列
 *
 * 那一列是**用户**的反应，单值、由用户操作写入。主 Bot 的标注是另一件事：
 * 它可能有多条、可能带贴纸资源、可能指向句子而不是整条消息。
 * 塞进同一列会让"用户改了反应"和"Bot 改了标注"互相覆盖。
 *
 * 旧 reaction 列无损保留 —— 这条是计划 §7.6 的硬要求。
 *
 * ## targetContentHash 的作用
 *
 * 消息正文被编辑后，原来的标注位置就失效了。存内容哈希，
 * 读的时候比对：不一致就当作失效，不把旧偏移套到新内容上。
 */
@Serializable
@Entity(
    tableName = "bot_annotations",
    indices = [
        Index(value = ["targetMessageId"]),
        Index(value = ["botId", "originSessionId"]),
        Index(value = ["status"]),
    ],
)
data class BotAnnotationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(defaultValue = "") val botId: String = "",
    /** 谁做的：bot / user。用户也可以给 Bot 的消息挂标注。 */
    @ColumnInfo(defaultValue = "bot") val actorType: String = ACTOR_BOT,
    @ColumnInfo(defaultValue = "") val originSessionId: String = "",
    /** 目标消息。 */
    @ColumnInfo(defaultValue = "") val targetMessageId: String = "",
    /**
     * 句子级定位用的稳定 segment id。
     *
     * 空串表示整条消息。句子级由宿主划分 segment 后填入，
     * **不使用字符偏移** —— 偏移在正文变更后会指向错误位置。
     */
    @ColumnInfo(defaultValue = "") val targetSentenceId: String = "",
    /** 目标内容哈希。正文变了，这条标注即失效。 */
    @ColumnInfo(defaultValue = "") val targetContentHash: String = "",
    /** 见 [AnnotationKind]。 */
    @ColumnInfo(defaultValue = "REACTION") val kind: String = AnnotationKind.REACTION,
    /** 资源引用（贴纸 id / 表情字符）。 */
    @ColumnInfo(defaultValue = "") val assetRef: String = "",
    /** 见 [AnnotationStatus]。 */
    @ColumnInfo(defaultValue = "ACTIVE") val status: String = AnnotationStatus.ACTIVE,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0L,
) {
    companion object {
        const val ACTOR_BOT = "bot"
        const val ACTOR_USER = "user"
    }
}

/** 标注类型。封闭集合：不认识的类型读出来按失效处理，不猜。 */
object AnnotationKind {
    /** 表情回应（emoji 字符）。 */
    const val REACTION = "REACTION"

    /** 贴纸（assetRef 指向贴纸库条目）。 */
    const val STICKER = "STICKER"

    /** 引用定位（点一下跳到被引用的那条）。 */
    const val QUOTE = "QUOTE"

    val all: Set<String> = setOf(REACTION, STICKER, QUOTE)
}

/** 标注状态。 */
object AnnotationStatus {
    const val ACTIVE = "ACTIVE"

    /** 目标被编辑，哈希对不上 —— 失效但保留记录，便于用户理解"那个表情为什么没了"。 */
    const val STALE = "STALE"

    /** 用户手动删除。 */
    const val REMOVED = "REMOVED"
}
