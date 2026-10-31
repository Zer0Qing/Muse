package io.zer0.muse.miniphone.ui.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.api.MpProfile
import io.zer0.muse.miniphone.data.chat.MpGroupActions
import io.zer0.muse.miniphone.data.db.dao.MpMessageDao
import io.zer0.muse.miniphone.data.db.dao.MpThreadDao
import io.zer0.muse.miniphone.data.db.dao.MpThreadMemberDao
import io.zer0.muse.miniphone.data.db.entity.MpThreadMemberEntity
import io.zer0.muse.miniphone.data.media.MpImageStore
import io.zer0.muse.miniphone.ui.shell.UNKNOWN_CHARACTER_NAME
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 单个会话的聊天页状态。
 *
 * 这里只做两件事:把协作方产出的状态聚成 [uiState],把界面的调用转给它该去的一方。
 * 打开线程与翻页在 [ChatPagingController],发送与收回复在 [ChatSendController],
 * 图片存盘在 [ChatMediaWriter],三处共用的落库路径在 [ChatMessageWriter]。
 * 界面只读 [uiState],不碰 DAO,也不自己判断「该不该发网络请求」。
 *
 * @param imageStore 图片落盘器:选中的图先复制进私有目录,库里只存本地路径
 * @param groupStore 群成员管理(改名、加人、踢人、解散)的写入口
 * @param clock 拼消息行用的两个可替换来源:现在几点、下一批 id
 * @param pauseBetweenResponders 群里两个角色之间的停顿;默认 600–1200ms,测试注入空实现就不必等
 */
class MiniPhoneChatViewModel(
    private val threadId: String,
    private val messageDao: MpMessageDao,
    private val threadDao: MpThreadDao,
    private val memberDao: MpThreadMemberDao,
    private val host: MiniPhoneHost,
    imageStore: MpImageStore,
    groupStore: MpGroupActions,
    clock: ChatWriteClock = ChatWriteClock(),
    private val pauseBetweenResponders: suspend () -> Unit = { delay(responderGapMillis()) },
) : ViewModel() {

    private val mutableState = MutableStateFlow(ChatUiState(threadId = threadId))

    val uiState: StateFlow<ChatUiState> = mutableState.asStateFlow()

    /** 群成员管理:改名、加人、移出、解散。界面把这一份直接交给成员页。 */
    internal val groupAdmin: GroupAdminController = GroupAdminController(
        threadId = threadId,
        threadDao = threadDao,
        memberDao = memberDao,
        store = groupStore,
        characters = { peerCharacters },
        state = mutableState,
        scope = viewModelScope,
    )

    /** 宿主给的最后一份角色列表,补名字与头像用。 */
    private var peerCharacters: List<MpCharacter> = emptyList()

    /** 群成员行(库里那份),按入群先后;补上名字后才进状态。 */
    private var memberRows: List<MpThreadMemberEntity> = emptyList()

    /** 可见期间清未读的作业;离页时取消。 */
    private var markReadJob: Job? = null

    private val writer = ChatMessageWriter(
        threadId = threadId,
        messageDao = messageDao,
        threadDao = threadDao,
        now = clock.now,
        newId = clock.newId,
        onStored = { message -> mutableState.update { it.copy(messages = it.messages + message) } },
        onStatusChanged = { messageId, status ->
            mutableState.update { state ->
                state.copy(
                    messages = state.messages.map { message ->
                        if (message.id == messageId) message.copy(status = status) else message
                    },
                )
            }
        },
        onDeleted = { messageId ->
            mutableState.update { state ->
                state.copy(messages = state.messages.filterNot { message -> message.id == messageId })
            }
        },
    )

    private val media = ChatMediaWriter(
        imageStore = imageStore,
        writer = writer,
        threadId = threadId,
    )

    private val paging = ChatPagingController(
        threadId = threadId,
        messageDao = messageDao,
        threadDao = threadDao,
        writer = writer,
        state = mutableState,
        scope = viewModelScope,
        onThreadOpened = ::applyThreadInfo,
    )

    private val sender = ChatSendController(
        threadId = threadId,
        writer = writer,
        threadDao = threadDao,
        host = host,
        media = media,
        state = mutableState,
        scope = viewModelScope,
        pauseBetweenResponders = pauseBetweenResponders,
        groupCandidates = ::responderCandidates,
    )

    init {
        // 宿主的两条流:角色名单用来补名字与头像,本机资料用来补自己那侧的头像
        viewModelScope.launch {
            host.observeCharacters()
                .catch { emit(emptyList()) }
                .collect { characters ->
                    peerCharacters = characters
                    // 群成员管理要先把「不在群里的人」列出来,所以整份名单也要进状态
                    mutableState.update { it.copy(characters = characters) }
                    applyThreadInfo()
                }
        }
        // 群成员行:私信会话没有行,这条流直接是空的,不需要另走一条分支
        viewModelScope.launch {
            memberDao.observeMembers(threadId)
                .catch { emit(emptyList()) }
                .collect { rows ->
                    memberRows = rows
                    applyMembers()
                }
        }
        viewModelScope.launch {
            host.observeProfile()
                .catch { emit(MpProfile(nickname = "")) }
                .collect { profile ->
                    mutableState.update {
                        it.copy(
                            selfName = profile.nickname,
                            selfAvatarUri = profile.avatarUri?.takeIf(String::isNotBlank),
                        )
                    }
                }
        }
        // 语音输入能不能用只问一次:进页时问、整个停留期间不变,不必跟设置页实时联动
        viewModelScope.launch {
            val supported = host.supportsVoiceTranscription()
            mutableState.update { it.copy(voiceInputAvailable = supported) }
        }
        paging.open()
        sender.start()
    }

    /** 滚到顶时加载更早的一页。 */
    fun loadOlder() {
        paging.loadOlder()
    }

    /** 进页时清未读;同一个会话退出再进会复用本对象,界面那时会再调一次。 */
    fun markRead() {
        paging.markRead()
    }

    /**
     * 页面可见性。可见期间只要这个会话的消息条数变化(进页、来了新消息)就把未读清零:
     * 后台主动私信落进正在看的会话时,会话列表不该留下一个未读角标。
     *
     * 跟踪的是库里那份消息流,而不是界面窗口:后台写入不经过本页的写入回调,
     * 只有表本身变化才能看见它。用 Flow 收集而不是定时查询:没有新消息就零数据库写入。
     * 离页即取消 —— 在别处也不断清未读的话,用户会永远看不到后台消息的提示。
     */
    fun setVisible(visible: Boolean) {
        markReadJob?.cancel()
        markReadJob = if (visible) {
            viewModelScope.launch {
                paging.markRead()
                messageDao.observeMessages(threadId)
                    .map { messages -> messages.size }
                    .distinctUntilChanged()
                    .collect { paging.markRead() }
            }
        } else {
            null
        }
    }

    /** 发送一条文本。 */
    fun send(text: String) {
        sender.send(text)
    }

    /** 发送若干张图,按选择顺序各落一条。 */
    fun sendImages(uris: List<Uri>) {
        sender.sendImages(uris)
    }

    /** 重发一条自己发出的文本:同一行原地重走一次发送流程。 */
    fun resend(messageId: String) {
        sender.resend(messageId)
    }

    /** 本机删掉一条消息;会话本身不动。 */
    fun deleteMessage(messageId: String) {
        viewModelScope.launch { writer.delete(messageId) }
    }

    /**
     * 把用户录的一段 WAV 转成文字。
     *
     * 转写能力归宿主:模块负责采集与展示,这里只把字节递过去。
     * 失败返回 null,由输入栏提示「没听清」—— 录音本身不落库,也不发出去。
     */
    suspend fun transcribeVoice(wavBytes: ByteArray): String? = host.transcribeVoice(wavBytes)

    /**
     * 落一条角色发的图片。
     *
     * 本阶段没有任何地方真的生成图片,这个方法留给后续角色发图与测试用;
     * 界面里不提供任何演示入口,调用方自己负责保证 [localFile] 已经存在。
     */
    suspend fun insertCharacterImage(threadId: String, characterId: String, localFile: String) {
        media.storeCharacterImage(threadId, characterId, localFile)
    }

    override fun onCleared() {
        sender.close()
        super.onCleared()
    }

    /** 会话行读出来时:先补对方,再补群成员。两件事都靠角色名单,分两个函数读起来更直。 */
    private fun applyThreadInfo() {
        applyPeer()
        applyMembers()
    }

    /** 角色名单到了、或会话的角色 id 到位时,把名字与头像补进状态。 */
    private fun applyPeer() {
        val characterId = mutableState.value.characterId
        if (characterId.isEmpty()) return
        val peer = peerCharacters.firstOrNull { it.id == characterId }
        if (peer == null) {
            // 有名单却没这个人:角色被删了;名单还没到就保持现状
            if (peerCharacters.isNotEmpty()) mutableState.update { it.copy(peerName = UNKNOWN_CHARACTER_NAME) }
            return
        }
        mutableState.update {
            it.copy(
                peerName = peer.name.takeIf(String::isNotBlank) ?: UNKNOWN_CHARACTER_NAME,
                peerAvatarUri = peer.avatarUri?.takeIf(String::isNotBlank),
            )
        }
    }

    /**
     * 群成员的接话候选。
     *
     * 优先用界面上那一份(名字已经配好);成员流还没到就先问库 —— 刚点进群就发一句,
     * 也不该因为成员列表晚到一步就没人接话。
     */
    private suspend fun responderCandidates(): List<GroupResponderCandidate> {
        val rendered = mutableState.value.members
        if (rendered.isNotEmpty()) {
            return rendered.map { GroupResponderCandidate(characterId = it.characterId, name = it.name) }
        }
        val byId = peerCharacters.associateBy { it.id }
        val rows = memberRows.ifEmpty { memberDao.membersOf(threadId) }
        return rows.map { row ->
            GroupResponderCandidate(
                characterId = row.characterId,
                name = byId[row.characterId]?.name?.takeIf(String::isNotBlank) ?: UNKNOWN_CHARACTER_NAME,
            )
        }
    }

    /**
     * 把群成员行配上名字与头像。
     *
     * 成员行是库里的那几行,名字来自宿主名单:角色被删了就退化成占位名,但这一行要留着 ——
     * 群人数与历史消息里的发言人还得对上,列表不该因为一个人消失而变短。
     * 私信会话没有成员行,这里什么也不写。
     */
    private fun applyMembers() {
        if (!mutableState.value.isGroup) return
        val byId = peerCharacters.associateBy { it.id }
        val members = memberRows.map { row ->
            val character = byId[row.characterId]
            ChatMemberUi(
                characterId = row.characterId,
                name = character?.name?.takeIf(String::isNotBlank) ?: UNKNOWN_CHARACTER_NAME,
                avatarUri = character?.avatarUri?.takeIf(String::isNotBlank),
            )
        }
        mutableState.update { it.copy(members = members) }
    }
}
