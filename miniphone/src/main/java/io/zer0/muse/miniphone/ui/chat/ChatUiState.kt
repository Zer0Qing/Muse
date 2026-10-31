package io.zer0.muse.miniphone.ui.chat

import androidx.compose.runtime.Immutable
import io.zer0.muse.miniphone.api.MpActivityKind
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageStatus
import io.zer0.muse.miniphone.data.db.MpMessageType
import io.zer0.muse.miniphone.data.db.entity.MpMessageEntity
import io.zer0.muse.miniphone.ui.shell.UNTITLED_GROUP_NAME

/**
 * 聊天页要展示的全部状态。
 *
 * [messages] 是按时间正序(旧 → 新)的消息,列表渲染时再倒过来;
 * [items] 在消息之间插好了时间分隔,界面照它逐行画,不需要自己算相邻间隔。
 *
 * 群聊与私信共用这份状态:[isGroup] 决定顶栏叫什么、气泡旁要不要写发言人,
 * [members] 在群里才有内容(成员页与「谁说的」都读它)。私信里 [members] 恒为空。
 */
@Immutable
data class ChatUiState(
    val threadId: String = "",
    val characterId: String = "",
    val peerName: String = "",
    val peerAvatarUri: String? = null,
    /** 这条会话是不是群聊。群里 [characterId] 只是第一个成员,发言人要看每条消息自己的。 */
    val isGroup: Boolean = false,
    /** 建群时定下的群名;私信恒为空串。 */
    val groupTitle: String = "",
    /** 群成员(只含角色,不含本机用户),按入群先后。 */
    val members: List<ChatMemberUi> = emptyList(),
    /** 宿主的全部角色:群成员管理里「还能加谁」从这里来(再排掉已在群里的)。 */
    val characters: List<MpCharacter> = emptyList(),
    /** 自己的昵称,空时界面用占位章兜底;用来画右侧头像的首字。 */
    val selfName: String = "",
    val selfAvatarUri: String? = null,
    val messages: List<ChatMessageUi> = emptyList(),
    val isGenerating: Boolean = false,
    /**
     * 此刻正在打字的角色名与头像;null 表示没人在打字。
     *
     * 群里两个人轮流接话时,「正在输入」要跟着当前这一个换:只写一句「对方正在输入」
     * 会让人以为整个群同时回。私信里它等于对方自己。
     */
    val typingName: String? = null,
    val typingAvatarUri: String? = null,
    /**
     * 角色此刻在做什么;null 表示没在做事。
     *
     * 只在生成期间有值,文本落地后清空 —— 界面据此在气泡上方显示一行拟人化文案。
     */
    val activity: MpActivityKind? = null,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    /**
     * 当前语音识别设置能不能把录音转成文字。
     *
     * 进页时向宿主问一次;false 时输入栏把麦克风置灰,点了也只说一句为什么。
     * 默认 true 是「还没问出结果」:先按能用画,否则每个用户进页都会先看到
     * 一个灰麦克风再变亮,像刚坏过一下。
     */
    val voiceInputAvailable: Boolean = true,
) {
    /** 顶栏标题:群里是群名,私信是对方名字。 */
    val title: String get() = if (isGroup) groupTitle.ifBlank { UNTITLED_GROUP_NAME } else peerName

    /** 群人数:成员行加上本机用户。私信不计。 */
    val memberCount: Int get() = if (isGroup) members.size + 1 else 0

    /** 成员名字表:气泡旁写「谁说的」时按消息的 [ChatMessageUi.characterId] 查。 */
    val memberNames: Map<String, String> by lazy { members.associate { it.characterId to it.name } }

    /** 消息 + 时间分隔,正序。每条消息只会带来一次构建,界面反复重组不重算。 */
    val items: List<ChatItem> by lazy { buildChatItems(messages) }
}

/** 群里的一个成员。本机用户不在里面 —— 自己那侧用 [ChatUiState.selfName] 与头像。 */
@Immutable
data class ChatMemberUi(
    val characterId: String,
    val name: String,
    val avatarUri: String? = null,
)

/** 聊天列表里某一侧的展示身份:名字 + 头像。自己与对方各一份。 */
@Immutable
data class ChatPersona(
    val name: String,
    val avatarUri: String? = null,
)

/** 一条消息在界面上的样子。 */
@Immutable
data class ChatMessageUi(
    val id: String,
    /** 发言人:角色消息是发言那个角色,用户自己发的在群里是空串。 */
    val characterId: String = "",
    val sender: MpActorType,
    val type: MpMessageType,
    val content: String,
    /** 图片消息的本地文件路径;文本/系统消息为 null。 */
    val mediaUri: String? = null,
    /** 语音消息的时长(毫秒);0 表示未知,非语音消息恒为 0。 */
    val voiceDurationMs: Long = 0L,
    val createdAt: Long,
    /** 发送状态:自己发的消息才会停在「发送中」或「发送失败」,其余恒为「已发出」。 */
    val status: MpMessageStatus = MpMessageStatus.SENT,
) {
    /** 系统提示:居中小字,不带头像也不分左右。 */
    val isSystem: Boolean get() = type == MpMessageType.SYSTEM

    /** 图片:气泡位置换成一张图,正文只当列表预览文案。 */
    val isImage: Boolean get() = type == MpMessageType.IMAGE

    /** 语音:气泡位置换成一条语音条,正文只当列表预览文案。 */
    val isVoice: Boolean get() = type == MpMessageType.VOICE

    /** 自己发的:靠右。 */
    val isMine: Boolean get() = sender == MpActorType.USER && !isSystem

    /** 发送失败:气泡旁边给一个能点的标记。 */
    val hasFailed: Boolean get() = status == MpMessageStatus.FAILED

    /** 还在路上:只给一行浅字,不遮住正文。 */
    val isSending: Boolean get() = status == MpMessageStatus.SENDING

    /**
     * 能不能重发。
     *
     * 只认自己发的文本:重发走的是「再要一次回复」那条链,图片和语音都拿不出可重发的正文,
     * 对角色说的话也不存在「重发」这个动作。
     */
    val canResend: Boolean get() = isMine && type == MpMessageType.TEXT
}

/** 列表里的一行:要么是消息,要么是时间分隔。 */
sealed interface ChatItem {
    /** `LazyColumn` 的稳定键。 */
    val key: String

    @Immutable
    data class Time(
        override val key: String,
        val at: Long,
    ) : ChatItem

    @Immutable
    data class Message(
        override val key: String,
        val message: ChatMessageUi,
    ) : ChatItem
}

/** 相邻两条消息超过这个间隔就插一行时间。 */
internal const val CHAT_TIME_GAP_MS: Long = 5 * 60_000L

/** 一页消息条数。 */
internal const val CHAT_PAGE_SIZE: Int = 30

/**
 * 把正序消息摊成渲染行:第一条前面必插时间,之后每条与前一条差满 [CHAT_TIME_GAP_MS] 才插。
 *
 * 时间行的键里带上消息下标,同一毫秒的两条消息也不会撞键。
 */
internal fun buildChatItems(messages: List<ChatMessageUi>): List<ChatItem> {
    if (messages.isEmpty()) return emptyList()
    val items = ArrayList<ChatItem>(messages.size + messages.size / 4)
    var previousAt: Long? = null
    messages.forEachIndexed { index, message ->
        val previous = previousAt
        if (previous == null || message.createdAt - previous >= CHAT_TIME_GAP_MS) {
            items += ChatItem.Time(key = "time-${message.createdAt}-$index", at = message.createdAt)
        }
        items += ChatItem.Message(key = "message-${message.id}", message = message)
        previousAt = message.createdAt
    }
    return items
}

/** 库里的一行 → 界面的一行。 */
internal fun MpMessageEntity.toChatMessageUi(): ChatMessageUi = ChatMessageUi(
    id = id,
    characterId = characterId,
    sender = sender,
    type = type,
    content = content,
    mediaUri = mediaUri,
    voiceDurationMs = voiceDurationMs ?: 0L,
    createdAt = createdAt,
    status = status,
)
