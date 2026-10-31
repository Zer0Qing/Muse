package io.zer0.muse.miniphone.ui.chat

import android.net.Uri
import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.api.MpReplyEvent
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageStatus
import io.zer0.muse.miniphone.data.db.MpMessageType
import io.zer0.muse.miniphone.data.db.dao.MpThreadDao
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 发消息与收回复。
 *
 * 用户的话先落库再问角色:请求失败也不会从列表里消失;空白输入不产生任何动作。
 * 落库时先标「发送中」,拿到回复改成「已发出」,一轮下来一句都没落就改成「发送失败」——
 * 界面上可以指着那条重发一次,不用重新敲字。
 *
 * 角色回复走 [replyQueue] 的单消费者 —— 生成期间可以继续发消息,但一轮应答
 * 永远只有一条 `respond` 在跑。
 *
 * 一条用户消息由几个角色接话由 [respondersFor] 定:私信恒为会话那个角色;
 * 群聊按点名、最近说过话、稳定随机三层挑最多 [MAX_GROUP_RESPONDERS] 个人(见
 * [selectGroupResponders])。两个人**串行**说:后一个开口时前一个的话已经落库,
 * 也就在他收到的历史里(见 [chatHistoryOf])。中间留一段停顿,[pauseBetweenResponders]
 * 可注入,测试用它把节奏钉成确定的。
 *
 * 同一条消息只认领一轮:[claimedRounds] 挡住重试与多处调用;上一轮还在跑时又提交了
 * 一模一样的一句话,当作重复提交,不再另起一轮。
 *
 * @param writer 落库的唯一入口
 * @param media 图片的存盘与落库:发图只负责「选中了哪几张」
 * @param state 界面状态;本类只往里写「生成中」「谁在打字」「角色在做什么」,标题与头像由 ViewModel 管
 * @param pauseBetweenResponders 两个角色之间的停顿;默认 600–1200ms,注入成空实现可以让测试不等待
 */
internal class ChatSendController(
    private val threadId: String,
    private val writer: ChatMessageWriter,
    private val threadDao: MpThreadDao,
    private val host: MiniPhoneHost,
    private val media: ChatMediaWriter,
    private val state: MutableStateFlow<ChatUiState>,
    private val scope: CoroutineScope,
    private val pauseBetweenResponders: suspend () -> Unit = { delay(responderGapMillis()) },
    private val groupCandidates: suspend () -> List<GroupResponderCandidate> = { emptyList() },
) {

    /** 待生成的应答:单消费者顺序消费,[Channel.UNLIMITED] 让发送不必等上一条回复。 */
    private val replyQueue = Channel<PendingReply>(Channel.UNLIMITED)

    /** 排队中的应答轮数,决定「对方正在输入…」何时收起。 */
    private var pendingReplies: Int = 0

    /** 已经认领过应答的用户消息 id:同一条消息不会因为重发或多处调用跑出两轮。 */
    private val claimedRounds: MutableSet<String> = mutableSetOf()

    /** 开始消费应答队列;ViewModel 接好状态之后调一次。 */
    fun start() {
        scope.launch { consumeReplies() }
    }

    /** ViewModel 销毁时收尾:关掉队列,消费循环随之结束。 */
    fun close() {
        replyQueue.close()
    }

    /**
     * 发送一条文本。
     *
     * 先落库再问角色:用户消息不依赖网络结果。空白输入不产生任何动作,
     * 界面上的置灰只是视觉提示。
     */
    fun send(text: String) {
        val content = text.trim()
        if (content.isEmpty()) return
        scope.launch {
            val thread = currentThread()
            val messageId = writer.append(
                ChatOutgoingMessage(
                    threadId = threadId,
                    characterId = storedCharacterIdOf(thread),
                    sender = MpActorType.USER,
                    type = MpMessageType.TEXT,
                    content = content,
                    status = MpMessageStatus.SENDING,
                ),
            )
            // 上一轮还没结束又提交了一模一样的一句:当成重复提交,不再起一轮
            if (isDuplicateSubmission(content, messageId)) {
                writer.updateStatus(messageId, MpMessageStatus.SENT)
                return@launch
            }
            val started = startRound(messageId, respondersFor(content, messageId))
            when (started) {
                // 页面正在销毁:队列已关,那一条在 startRound 里已标成发送失败
                RoundStart.STARTED, RoundStart.QUEUE_CLOSED -> Unit
                // 重复提交或没人可接:这一条已经落库,不必挂着「发送中」
                RoundStart.ALREADY_STARTED, RoundStart.NO_RESPONDER ->
                    writer.updateStatus(messageId, MpMessageStatus.SENT)
            }
        }
    }

    /**
     * 重发一条自己发出的文本。
     *
     * 同一行原处回到「发送中」再走一遍应答,成功后改回「已发出」—— 列表里不会多出
     * 一条一模一样的气泡。只接受自己发的文本,其余消息(角色的、图片、语音)直接忽略。
     * 已经答过一轮的消息不会被重发再跑一次:认领挡在前面,状态也不会停在「发送中」。
     */
    fun resend(messageId: String) {
        val message = state.value.messages.firstOrNull { it.id == messageId } ?: return
        if (!message.canResend) return
        scope.launch {
            if (startRound(messageId, respondersFor(message.content, messageId)) != RoundStart.STARTED) return@launch
            writer.updateStatus(messageId, MpMessageStatus.SENDING)
        }
    }

    /**
     * 发送若干张图,按选择顺序各落一条。
     *
     * 发图**不**请求角色回复:当前链路里模型还看不见图,硬答一句只会是无关的话,
     * 等接上多模态再打开这一步。存不下的那张由 [ChatMediaWriter] 跳过,不影响其余几张。
     */
    fun sendImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch { media.storePickedImages(uris, storedCharacterIdOf(currentThread())) }
    }

    /** 库里的会话行:发言人与群标记都以它为准,状态还没填上也拿得到。 */
    private suspend fun currentThread(): MpThreadEntity? = threadDao.getThread(threadId)

    /** 角色 id 从当前状态取;状态还没填上时问一次库,避免消息挂在空角色上。 */
    private suspend fun resolveCharacterId(): String =
        state.value.characterId.ifEmpty { threadDao.getThread(threadId)?.characterId.orEmpty() }

    /**
     * 这条用户消息该由谁接话。
     *
     * 私信只有会话那个角色一个人 —— 一对一不会被这次改动变成多人应答。
     * 群里交给 [selectGroupResponders]:点名、最近说过话、稳定随机,最多两个。
     */
    private suspend fun respondersFor(content: String, messageId: String): List<String> {
        val current = state.value
        if (!current.isGroup) return listOfNotNull(resolveCharacterId().takeIf(String::isNotEmpty))
        val members = current.members
            .map { GroupResponderCandidate(characterId = it.characterId, name = it.name) }
            // 刚进群、成员还没到界面上时,从库里补一次,不能因此让这条消息没人接
            .ifEmpty { groupCandidates() }
        return selectGroupResponders(
            members = members,
            text = content,
            recentSenderIds = recentSpeakerIdsOf(current.messages),
            seed = responderSeedOf(threadId, messageId),
        )
    }

    /** 是否是一次重复提交:同一句话已经有一条还在等回复。 */
    private fun isDuplicateSubmission(content: String, messageId: String): Boolean = state.value.messages.any { message ->
        message.isMine && message.id != messageId && message.content == content && message.status == MpMessageStatus.SENDING
    }

    /**
     * 认领一条消息的应答并排进队列。
     *
     * @return 认领成功并已入队;[RoundStart.ALREADY_STARTED] 表示这条消息已经有一轮;
     *   [RoundStart.QUEUE_CLOSED] 表示页面正在销毁,那一条已经标成「发送失败」
     */
    private suspend fun startRound(messageId: String, responders: List<String>): RoundStart {
        if (!claimedRounds.add(messageId)) return RoundStart.ALREADY_STARTED
        if (responders.isEmpty()) {
            claimedRounds.remove(messageId)
            return RoundStart.NO_RESPONDER
        }
        pendingReplies += 1
        state.update { it.copy(isGenerating = true) }
        if (replyQueue.trySend(PendingReply(messageId = messageId, responders = responders)).isSuccess) {
            return RoundStart.STARTED
        }
        // 队列已关(页面在销毁):这一条既拿不到回复也等不到下一轮,直接标失败
        claimedRounds.remove(messageId)
        writer.updateStatus(messageId, MpMessageStatus.FAILED)
        settleReply()
        return RoundStart.QUEUE_CLOSED
    }

    private suspend fun consumeReplies() {
        for (reply in replyQueue) {
            try {
                deliverRound(reply)
            } finally {
                settleReply()
            }
        }
    }

    /** 一轮应答结束(成功、失败、取消都算):退掉排队数,没人排队就收起输入提示。 */
    private fun settleReply() {
        pendingReplies -= 1
        state.update { it.copy(isGenerating = pendingReplies > 0, typingName = null, typingAvatarUri = null) }
    }

    /**
     * 一轮应答:让认领到的角色依次开口。
     *
     * 顺序很重要 —— 上一段落库之后才轮到下一个人,「两人同时说话」的撞车从这里就断掉了;
     * 中间那段停顿让两个气泡之间有打字的节奏,不是两条消息同时弹出来。
     * 谁在打字由 [showTyping] 写进状态,界面据此换掉「正在输入」那一行的名字与头像。
     */
    private suspend fun deliverRound(reply: PendingReply) {
        var delivered = 0
        reply.responders.forEachIndexed { index, characterId ->
            if (index > 0) pauseBetweenResponders()
            showTyping(characterId)
            // 宿主约定失败发 Failed;再兜一层,免得某个实现抛异常把整条队列带停
            delivered += runCatching { collectReply(characterId) }
                .onFailure { state.update { current -> current.copy(activity = null) } }
                .getOrDefault(0)
        }
        state.update { it.copy(typingName = null, typingAvatarUri = null) }
        if (delivered == 0) {
            // 这一轮再试还有意义:退回认领,重发能从原处再来一次
            claimedRounds.remove(reply.messageId)
            writer.updateStatus(reply.messageId, MpMessageStatus.FAILED)
            writer.append(
                ChatOutgoingMessage(
                    threadId = threadId,
                    characterId = reply.responders.firstOrNull().orEmpty(),
                    sender = MpActorType.SYSTEM,
                    type = MpMessageType.SYSTEM,
                    content = NO_REPLY_TEXT,
                ),
            )
        } else {
            writer.updateStatus(reply.messageId, MpMessageStatus.SENT)
        }
    }

    /** 该谁说下一句:名字与头像从成员表里取,私信退化成对方。 */
    private fun showTyping(characterId: String) {
        val member = state.value.members.firstOrNull { it.characterId == characterId }
        state.update { current ->
            current.copy(
                typingName = member?.name ?: current.peerName.takeIf(String::isNotEmpty),
                typingAvatarUri = if (member != null) member.avatarUri else current.peerAvatarUri,
            )
        }
    }

    /**
     * 收完一个角色的一轮生成,返回这次落了几条消息。
     *
     * 中途的 Activity 只换界面那一行文案,不进消息列表;结束后无论成败都收起,
     * 免得失败时那一行卡在界面上。历史在**每次请求前**现取:上一个人刚落的库就在里面。
     */
    private suspend fun collectReply(characterId: String): Int {
        var stored = 0
        try {
            host.respond(characterId, chatHistoryOf(state.value)).collect { event ->
                when (event) {
                    is MpReplyEvent.Activity -> state.update { it.copy(activity = event.kind) }
                    is MpReplyEvent.Text -> if (storeText(characterId, event.content)) stored += 1
                    is MpReplyEvent.Voice -> {
                        storeVoice(characterId, event)
                        stored += 1
                    }
                    // 失败这一轮什么都不落,由条数判断要不要补系统提示
                    MpReplyEvent.Failed -> Unit
                }
            }
        } finally {
            // 成功、失败、取消三条路径都收起那一行,不给界面上留半截提示
            state.update { it.copy(activity = null) }
        }
        return stored
    }

    /** 落一条角色文本;空白段不落(模型偶尔只给一个换行,没必要占一行)。 */
    private suspend fun storeText(characterId: String, content: String): Boolean {
        val text = content.trim()
        if (text.isEmpty()) return false
        writer.append(
            ChatOutgoingMessage(
                threadId = threadId,
                characterId = characterId,
                sender = MpActorType.CHARACTER,
                type = MpMessageType.TEXT,
                content = text,
            ),
        )
        return true
    }

    /**
     * 落一条角色语音:正文只存预览文案,音频引用与时长进各自字段。
     *
     * 用户侧发语音(录音 + 转写)还没接,接上后走同一条写入,只换 sender 与音频来源。
     */
    private suspend fun storeVoice(characterId: String, voice: MpReplyEvent.Voice) {
        writer.append(
            ChatOutgoingMessage(
                threadId = threadId,
                characterId = characterId,
                sender = MpActorType.CHARACTER,
                type = MpMessageType.VOICE,
                content = VOICE_PREVIEW_TEXT,
                mediaUri = voice.uri,
                voiceDurationMs = voice.durationMs,
            ),
        )
    }

    /** 一条消息的应答认领结果。 */
    private enum class RoundStart {
        /** 已认领并排进队列。 */
        STARTED,

        /** 这条消息此前已经认领过:不再跑第二轮。 */
        ALREADY_STARTED,

        /** 没有可接话的人。 */
        NO_RESPONDER,

        /** 队列已关,页面正在销毁。 */
        QUEUE_CLOSED,
    }

    /** 排队里的一条待生成应答;[messageId] 跟着它走,回复回来时靠它写回发送状态。 */
    private data class PendingReply(
        val messageId: String,
        val responders: List<String>,
    )

    private companion object {
        /** 角色没给出回复时落在列表里的系统提示。 */
        const val NO_REPLY_TEXT: String = "对方没有回应"

        /** 语音消息的正文:库里不存音频,只存这句预览文案,会话列表与未读预览都用它。 */
        const val VOICE_PREVIEW_TEXT: String = "[语音]"
    }
}

/** 两个角色之间的停顿下限与上限(毫秒)。 */
internal const val RESPONDER_GAP_MIN_MS: Long = 600L
internal const val RESPONDER_GAP_MAX_MS: Long = 1_200L

/** 掷一段停顿:上一个人说完到下一个人开口之间等多久。 */
internal fun responderGapMillis(): Long = Random.nextLong(RESPONDER_GAP_MIN_MS, RESPONDER_GAP_MAX_MS + 1)

/**
 * 用户消息落库时的 characterId。
 *
 * 私信里记会话那个角色 —— 会话里只有一个对方,这一列就当「这条属于哪个人」用。
 * 群里记空串:说话的是自己,不对应任何角色;记成第一个成员的话,历史消息里
 * 「我说的」会被读成「他说的」。谁接话由 [selectGroupResponders] 另行挑。
 */
internal fun storedCharacterIdOf(thread: MpThreadEntity?): String = if (thread?.isGroup == true) "" else thread?.characterId.orEmpty()
