package io.zer0.muse.miniphone.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.api.MpProfile
import io.zer0.muse.miniphone.data.chat.MpConversationActions
import io.zer0.muse.miniphone.data.chat.MpGroupActions
import io.zer0.muse.miniphone.data.chat.canCreateGroup
import io.zer0.muse.miniphone.data.chat.groupTitleOf
import io.zer0.muse.miniphone.data.db.dao.MpThreadDao
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import io.zer0.muse.miniphone.data.settings.MpMomentAutoStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * 外壳的唯一状态来源。
 *
 * 五条流(会话表、角色、本机资料、自动发朋友圈开关、角色主动私信开关)在这里合成一份 [ShellUiState];
 * 界面拿到的是已合并好的数据,不持有 DAO,也不在 Composable 里查库。
 *
 * 会话行上的写动作(置顶、清未读、删会话、清空)转给 [MpConversationActions]:
 * 列表本身是会话表的流,写完不需要手动刷新。建群转给 [MpGroupActions],
 * 群名在这里拼好 —— 宿主给的角色名单在这份状态里已经有了,写入层不必再问一次。
 *
 * 订阅方全部离场后保留 [KEEP_ALIVE_MS] 再停上游:切到聊天页再回来时,
 * 不必等四条上游重新跑一遍。
 */
class MiniPhoneShellViewModel(
    private val threadDao: MpThreadDao,
    private val store: MpConversationActions,
    private val groupStore: MpGroupActions,
    host: MiniPhoneHost,
    private val settings: MpMomentAutoStore,
) : ViewModel() {

    /** 宿主版本名:整个停留期间不变,进页时问一次就够。 */
    private val appVersionName: String = host.appVersionName()

    /** 建会话的闸:通讯录连点两下也不能建出两条会话。 */
    private val openThreadMutex = Mutex()

    /** 最近一次收到的角色名单:建群时用它把选中的 id 换成群名用的名字。 */
    private var charactersById: Map<String, MpCharacter> = emptyMap()

    val uiState: StateFlow<ShellUiState> = combine(
        combine(threadDao.observeThreads(), host.observeCharacters(), host.observeProfile(), ::assembleWithCharacterCache),
        settings.enabledFlow,
        settings.chatProactiveEnabledFlow,
    ) { state, autoMomentEnabled, chatProactiveEnabled ->
        state.copy(
            autoMomentEnabled = autoMomentEnabled,
            chatProactiveEnabled = chatProactiveEnabled,
            appVersionName = appVersionName,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(KEEP_ALIVE_MS),
        initialValue = ShellUiState(),
    )

    /**
     * 通讯录点一个角色时用:已有会话就直接给 id,没有就开一个空会话。
     *
     * 「按角色找」是唯一的判定依据 —— 同一个人在会话列表里永远只对应一条 1:1 会话,
     * 重复点不会多出一条空白对话。整个「先查后写」在 [openThreadMutex] 里做:
     * 连点时后一次会等前一次写完再去查,查到的是刚建好的那条。
     *
     * @return 会话 id;角色 id 为空时返回 null(界面不该拿一个空角色去开会话)
     */
    suspend fun openOrCreateThread(characterId: String): String? {
        if (characterId.isBlank()) return null
        return openThreadMutex.withLock { existingOrNewThread(characterId) }
    }

    private suspend fun existingOrNewThread(characterId: String): String {
        threadDao.findThreadByCharacter(characterId)?.let { existing -> return existing.id }
        val created = MpThreadEntity(id = UUID.randomUUID().toString(), characterId = characterId)
        threadDao.upsert(created)
        return created.id
    }

    /**
     * 建一个群并返回它的会话 id;选不够两个人(或角色 id 全空)时返回 null。
     *
     * 群名按选择顺序拼接(末尾缀「我」)。名字从最近一次的角色名单里取:
     * 名单还没到、或某个角色已经被删,就退化成一句提示而不是空名字 ——
     * 群名一旦写进库就不再重算,这里宁可写一个能看懂的名字。
     *
     * @param characterIds 选中的角色 id,顺序就是群里的顺序
     */
    suspend fun createGroup(characterIds: List<String>): String? {
        val members = characterIds.map(String::trim).filter(String::isNotEmpty).distinct()
        if (!canCreateGroup(members.size)) return null
        val names = members.map { id ->
            charactersById[id]?.name?.takeIf(String::isNotBlank) ?: UNKNOWN_CHARACTER_NAME
        }
        return groupStore.createGroup(members, groupTitleOf(names))
    }

    /** 合成状态,顺便把角色名单留在 [charactersById] 上(建群要用名字,不能再去问宿主一次)。 */
    private fun assembleWithCharacterCache(
        threads: List<MpThreadEntity>,
        characters: List<MpCharacter>,
        profile: MpProfile,
    ): ShellUiState {
        charactersById = characters.associateBy { it.id }
        return assembleShellState(threads, characters, profile)
    }

    /**
     * 切「自动发朋友圈」。
     *
     * 只写设置:后台 Worker 每轮都会先读这个开关,关着就整轮跳过、一次网络都不发。
     * 定时任务本身不取消 —— 重新打开时立刻恢复,不必等宿主再调一次注册。
     */
    fun setAutoMomentEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setEnabled(enabled) }
    }

    /**
     * 切「角色主动私信」。
     *
     * 同样只写设置:Worker 每轮先读这个开关,关着就整轮跳过。默认关,所以用户不开就永远不会被打扰。
     */
    fun setChatProactiveEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setChatProactiveEnabled(enabled) }
    }

    /** 置顶 / 取消置顶一条会话。 */
    fun setThreadPinned(threadId: String, pinned: Boolean) {
        viewModelScope.launch { store.setPinned(threadId, pinned) }
    }

    /** 未读清零:会话行上的「标记已读」。 */
    fun markThreadRead(threadId: String) {
        viewModelScope.launch { store.markRead(threadId) }
    }

    /** 删一个会话及其全部消息。 */
    fun deleteThread(threadId: String) {
        viewModelScope.launch { store.deleteThread(threadId) }
    }

    /** 清空聊天记录(所有会话与消息);朋友圈等其它数据不动。 */
    fun clearConversations() {
        viewModelScope.launch { store.clearAll() }
    }

    private companion object {
        const val KEEP_ALIVE_MS: Long = 5_000L
    }
}
