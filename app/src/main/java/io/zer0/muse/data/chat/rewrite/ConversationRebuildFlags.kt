package io.zer0.muse.data.chat.rewrite

/** 新旧对话链路灰度开关。默认全部关闭，开启后只允许在本地诊断或受控灰度中使用。 */
data class ConversationRebuildFlags(
    val shadowEventsEnabled: Boolean = false,
    val useCommitSeq: Boolean = false,
    val useMessageParts: Boolean = false,
    val useMessageProjection: Boolean = false,
    val useNewConversationService: Boolean = false,
    val useMemoryConstellationUi: Boolean = false,
    val useRenovatedPageUi: Boolean = false,
)

/** 进程内灰度开关；不持久化，不改变普通用户默认行为。 */
object ConversationRebuildFlagStore {
    @Volatile
    var current: ConversationRebuildFlags = ConversationRebuildFlags()
}
