package io.zer0.muse.data.agentbot

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 对外发布的发件箱。
 *
 * ## 为什么发布也要落库
 *
 * 主 Bot 的一条回复可能由多个 item（文本 + 贴纸 + 产物卡）组成。
 * 发布到一半进程被杀，用户会看到半条消息；重试整批又会重复。
 * outbox 记的是"哪一条 item 已经发出去了"，恢复时只重投未发布的那些。
 *
 * ## 与 messages 表的关系
 *
 * outbox 是**意图**，messages 是**事实**。两者必须在同一个 Room 事务里更新：
 * 只写 outbox 会让用户看不到消息，只写 messages 会让恢复逻辑以为还没发。
 */
@Serializable
@Entity(
    tableName = "bot_publications",
    indices = [
        Index(value = ["publicationId", "itemIndex"], unique = true),
        Index(value = ["botId", "status"]),
        Index(value = ["messageId"]),
    ],
)
data class PublicationOutboxEntity(
    @PrimaryKey val id: String,
    /** 一次 bot_say 批次。同批多个 item 共享它。 */
    @ColumnInfo(defaultValue = "") val publicationId: String = "",
    @ColumnInfo(defaultValue = "") val botId: String = "",
    @ColumnInfo(defaultValue = "") val originSessionId: String = "",
    /** 触发这次发布的内部事件 id 列表（逗号分隔）。 */
    @ColumnInfo(defaultValue = "") val causeEventIds: String = "",
    /** 生成时的用户输入版本；新输入到达时用它判断"这批是否已过期"。 */
    @ColumnInfo(defaultValue = "0") val requestRevision: Long = 0L,
    @ColumnInfo(defaultValue = "1") val taskRevision: Int = 1,
    /** 批次内序号，从 0 起。 */
    @ColumnInfo(defaultValue = "0") val itemIndex: Int = 0,
    /** 见 [PublicationKind]。 */
    @ColumnInfo(defaultValue = "TEXT") val kind: String = PublicationKind.TEXT,
    /** 内容引用（正文/资源 id 由发布服务解析）。 */
    @ColumnInfo(defaultValue = "") val payloadRef: String = "",
    /** 见 [PublicationStatus]。 */
    @ColumnInfo(defaultValue = "PENDING") val status: String = PublicationStatus.PENDING,
    /** 落进 messages 后回填的消息 id。 */
    @ColumnInfo(defaultValue = "") val messageId: String = "",
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0L,
)

/**
 * 允许对外发布的类型 —— 封闭集合。
 *
 * 这不是"支持的格式列表"，是**输出边界**：模型正文、工具结果、内部日志
 * 都不在这个集合里，因此它们没有任何路径进入公共消息。
 */
object PublicationKind {
    const val TEXT = "TEXT"
    const val STICKER = "STICKER"
    const val ARTIFACT = "ARTIFACT"
    const val APPROVAL_NOTICE = "APPROVAL_NOTICE"

    val all: Set<String> = setOf(TEXT, STICKER, ARTIFACT, APPROVAL_NOTICE)

    /**
     * 内部信息永远不得作为对外发布类型（V21）。
     *
     * 这四类在审查报告里被点名过：子线程输出、内部事件、审核草稿、模型原始回执。
     * 它们可以进审计日志、可以进产物，但**不能出现在用户读到的消息里**。
     *
     * 这里的集合是给门禁用的：有测试断言 [all] 与 [INTERNAL_ONLY] 无交集。
     */
    val INTERNAL_ONLY: Set<String> = setOf(
        "MODEL_RAW_TEXT",
        "TOOL_RESULT",
        "INTERNAL_EVENT",
        "REVIEW_DRAFT",
    )
}

object PublicationStatus {
    const val PENDING = "PENDING"
    const val COMMITTED = "COMMITTED"
    /** 因新输入到达而放弃（未发布的旧批次）。 */
    const val SUPERSEDED = "SUPERSEDED"
    const val FAILED = "FAILED"
}