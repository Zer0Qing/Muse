package io.zer0.muse.data.chat.rewrite

/** 新旧对话链路灰度开关。默认全部关闭，开启后只允许在本地诊断或受控灰度中使用。 */
data class ConversationRebuildFlags(
    /** 正式切换：新事件链成为默认事实记录路径。 */
    val shadowEventsEnabled: Boolean = true,
    /** 正式切换：回合完成时走幂等 MessageCommit 事务。 */
    val useCommitSeq: Boolean = true,
    /** 正式切换：新提交同时写入结构化 message_parts。 */
    val useMessageParts: Boolean = true,
    /** 当前 projection 仍由旧 UI 兼容读取；完成接线前不伪装开启。 */
    val useMessageProjection: Boolean = false,
    /** 正式切换：新 ConversationService/MessageCommit 作为主提交路径。 */
    val useNewConversationService: Boolean = true,
    /** 记忆中心星座布局已在当前分支直接接入。 */
    val useMemoryConstellationUi: Boolean = true,
    /** 毛坯房页面仍按页面逐个接线，暂不全局打开。 */
    val useRenovatedPageUi: Boolean = false,
)

/** 进程内灰度开关；不持久化，不改变普通用户默认行为。 */
object ConversationRebuildFlagStore {
    @Volatile
    var current: ConversationRebuildFlags = ConversationRebuildFlags()
}
