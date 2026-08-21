package io.zer0.muse.data.chat.rewrite

/** 新旧对话链路开关。当前提交/事件/parts 主路径默认开启，projection/UI 仍独立保留回滚开关。 */
data class ConversationRebuildFlags(
    /** 正式切换：新事件链成为默认事实记录路径。 */
    val shadowEventsEnabled: Boolean = true,
    /** 正式切换：回合完成时走幂等 MessageCommit 事务。 */
    val useCommitSeq: Boolean = true,
    /** 正式切换：新提交同时写入结构化 message_parts。 */
    val useMessageParts: Boolean = true,
    /** 新 projection 读路径默认开启；无 parts 的旧消息自动 legacy 派生。 */
    val useMessageProjection: Boolean = true,
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
