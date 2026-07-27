package io.zer0.muse.ui.groupchat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.zer0.muse.R
import io.zer0.muse.data.ChatPreferences
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.groupchat.GroupChatEntity
import io.zer0.muse.data.groupchat.GroupChatMessageEntity
import io.zer0.muse.data.groupchat.GroupChatRepository
import io.zer0.muse.data.AgentTeam
import io.zer0.muse.schedule.GroupChatScheduler
import io.zer0.common.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 群聊 UI 状态。
 *
 * @param chats 群聊列表(按 updatedAt 降序)
 * @param currentChat 当前选中的群聊实体(null 表示未选中)
 * @param currentMessages 当前群聊的消息列表(按时间升序)
 * @param inputText 输入框文本
 * @param isAgentResponding 是否正在等待 Agent 轮转回复
 * @param assistants 全部助手列表(新建群聊成员选择用)
 * @param teams 全部协作团队(新建群聊关联团队用)
 */
/**
 * 群聊消息展开状态 — 用于切页后保持 mood / reasoning 折叠状态。
 */
data class GroupChatMessageExpandedState(
    val isMoodExpanded: Boolean? = null,
    val isReasoningExpanded: Boolean? = null,
)

data class GroupChatUiState(
    val chats: List<GroupChatEntity> = emptyList(),
    /** v1.72: 群聊列表首次加载标志(避免闪空状态) */
    val isChatsLoading: Boolean = true,
    val currentChat: GroupChatEntity? = null,
    val currentMessages: List<GroupChatMessageEntity> = emptyList(),
    val inputText: String = "",
    val pendingImages: List<String> = emptyList(),
    val isAgentResponding: Boolean = false,
    /** v1.104: 当前正在发言的 Agent(用于"谁在思考"指示),null=无人在发言 */
    val currentSpeaker: AssistantEntity? = null,
    val assistants: List<AssistantEntity> = emptyList(),
    val teams: List<AgentTeam> = emptyList(),
    /** v1.45: 聊天偏好(控制 mood/reasoning 显示与默认展开)。 */
    val chatPreferences: ChatPreferences = ChatPreferences(),
    /** v1.45: 列表滚动位置缓存(切页/后台后恢复)。 */
    val listFirstVisibleItemIndex: Int = 0,
    val listFirstVisibleItemScrollOffset: Int = 0,
    /** v1.45: 消息级展开状态缓存(mood/reasoning 折叠状态不因切页丢失)。 */
    val messageExpandedStates: Map<String, GroupChatMessageExpandedState> = emptyMap(),
    /** M7: 群聊删除标志,详情页据此自动返回。 */
    val chatDeleted: Boolean = false,
    /** v1.126: 操作错误提示(null=无错误) */
    val errorMessage: String? = null,
    /** v1.53-GC: 是否还有更早的历史消息可加载(上滑加载更多用)。 */
    val hasMoreHistory: Boolean = false,
    /** v1.53-GC: 是否正在加载更多历史消息(防止重复触发 + UI 顶部加载指示器)。 */
    val isLoadingMore: Boolean = false,
    /**
     * v1.53-GC: 最近一次"加载更多"插入的历史条数。
     *
     * UI 监听该字段变化(>0)后,通过 [androidx.compose.foundation.lazy.LazyListState.scrollToItem]
     * 跳过新插入的条数,保持用户视觉位置不跳动(原来在顶部的消息现在在该 index),
     * 然后调 [GroupChatViewModel.clearHistoryLoadCount] 清空。
     */
    val lastHistoryLoadCount: Int = 0,
    /**
     * ActivityHub: 当前群聊各 agent 的活动状态列表(参考 openhanako ActivityHub)。
     *
     * 由 [GroupChatActivityHub.activities] StateFlow 派生,仅包含当前群聊的活动。
     * UI 据此渲染 [AgentActivityBar] 中的状态 chip(IDLE 状态被过滤不显示)。
     */
    val activities: List<AgentActivity> = emptyList(),
)

/**
 * 群聊 ViewModel — 管理群聊列表 / 消息流 / Agent 轮转调度。
 *
 * 职责:
 *  - 观察群聊列表(observeChats)与助手列表(AssistantRepository.observeAll)
 *  - 切换当前群聊时分页加载消息流(getPagedMessages 首屏 Flow + loadMoreHistory 上滑加载更多)
 *  - 用户发消息后调用 [GroupChatScheduler.triggerAgentRoundRobin] 串行触发各 Agent 发言
 *  - 创建 / 删除群聊
 *
 * v1.53-GC: 消息列表改分页加载,复用单聊 v1.53 方案(窗口查询 + Repository reversed +
 * ViewModel loadMoreHistory)。首屏加载 [INITIAL_PAGE_SIZE] 条,上滑到顶触发
 * [loadMoreHistory] 取更早 [LOAD_MORE_PAGE_SIZE] 条;新消息通过 Flow 增量追加到列表尾部。
 *
 * @param groupChatRepository 群聊仓库
 * @param scheduler 群聊调度器(触发 Agent 轮转)
 * @param assistantRepository 助手仓库(加载成员候选列表)
 * @param settings 设置仓库(读取团队配置 + 用户名)
 * @param activityHub 群聊活动状态管理器(参考 openhanako ActivityHub),
 *        订阅其 [GroupChatActivityHub.activities] 派生当前群聊的活动列表到 UI state。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupChatViewModel(
    private val groupChatRepository: GroupChatRepository,
    private val scheduler: GroupChatScheduler,
    private val assistantRepository: AssistantRepository,
    private val settings: SettingsRepository,
    private val activityHub: GroupChatActivityHub,
    private val appContext: Context,
) : ViewModel() {

    companion object {
        private const val TAG = "GroupChatViewModel"
        /** v1.126: 发送消息最小间隔(ms),防止快速点击重复发送 */
        private const val SEND_DEBOUNCE_MS = 500L
        /** v1.53-GC: 群聊消息分页 — 首屏页大小(初始加载条数)。 */
        private const val INITIAL_PAGE_SIZE = GroupChatRepository.INITIAL_PAGE_SIZE
        /** v1.53-GC: 群聊消息分页 — 上滑加载更多页大小。 */
        private const val LOAD_MORE_PAGE_SIZE = GroupChatRepository.LOAD_MORE_PAGE_SIZE
    }

    private val _state = MutableStateFlow(GroupChatUiState())
    private val json = Json { ignoreUnknownKeys = true }
    val state: StateFlow<GroupChatUiState> = _state.asStateFlow()

    /** v1.126: 上次发送时间戳,用于 debounce */
    private var lastSendTimestamp = 0L

    /** 当前选中的群聊 id(用于 flatMapLatest 切换消息流)。 */
    private val currentChatId = MutableStateFlow<String?>(null)

    init {
        // H-GC2 修复: 原先用 appScope.launch 收集 Flow,ViewModel 销毁后收集器永不取消,
        // 造成 Flow 泄漏与 StateFlow 旧订阅残留。改用 viewModelScope,随 ViewModel 清理自动取消。
        // 观察群聊列表
        viewModelScope.launch {
            groupChatRepository.observeChats().collect { chats ->
                _state.update { it.copy(chats = chats, isChatsLoading = false) }
            }
        }
        // 观察助手列表(新建群聊成员选择用)
        // v1.111: assistants 加载后同步更新 currentSpeaker(切页重建时 scheduler 可能已有活跃生成)
        viewModelScope.launch {
            assistantRepository.observeAll.collect { assistants ->
                val activeGen = scheduler.activeGroupGeneration.value
                val speaker = activeGen?.currentSpeakerId?.let { id ->
                    assistants.firstOrNull { it.id == id }
                }
                _state.update { it.copy(assistants = assistants, currentSpeaker = speaker) }
            }
        }
        // v1.53-GC: 观察当前群聊最近一页消息(Flow),增量追加新消息到 currentMessages。
        // v1.0.21: 重写 collect 块 — 移除 isEmpty() 守卫(会导致首条消息丢失),
        //   用 it.currentMessages 替代陈旧快照 current(避免覆盖并发修改),
        //   currentMessages 为空时直接用 recentWindow 作为首屏(Flow 接管初始加载)。
        viewModelScope.launch {
            currentChatId.flatMapLatest { chatId ->
                if (chatId != null) {
                    groupChatRepository.getPagedMessages(chatId, INITIAL_PAGE_SIZE)
                } else {
                    flowOf(emptyList())
                }
            }.collect { recentWindow ->
                if (recentWindow.isEmpty()) return@collect
                // 同 chatId 守卫:防切换群聊时旧消息与新 Flow 串扰
                val windowChatId = recentWindow.first().chatId
                if (windowChatId != currentChatId.value) return@collect
                _state.update { state ->
                    val current = state.currentMessages
                    if (current.isEmpty()) {
                        // 首屏:直接用 recentWindow(Flow 接管初始加载,与 loadInitialPage 互补)
                        state.copy(currentMessages = recentWindow)
                    } else {
                        // v1.0.22: 乐观消息去重 — recentWindow 已包含对应真实消息时,
                        //   移除乐观消息(id 以 "optimistic-" 前缀标识)避免重复显示。
                        //   匹配条件:senderType + body 一致 且 timestamp 接近(±2s,
                        //   覆盖 scheduler 异步写入 DB 的延迟)。
                        val hasOptimistic = current.any { it.id.startsWith("optimistic-") }
                        val dedupedCurrent = if (hasOptimistic) {
                            val realMessages = recentWindow.filterNot { it.id.startsWith("optimistic-") }
                            val filtered = current.filter { optMsg ->
                                if (!optMsg.id.startsWith("optimistic-")) return@filter true
                                val matched = realMessages.any { real ->
                                    real.senderType == optMsg.senderType &&
                                        real.body == optMsg.body &&
                                        kotlin.math.abs(real.timestamp - optMsg.timestamp) < 2000L
                                }
                                !matched
                            }
                            // 去重后为空(全部乐观消息都已匹配到真实消息)则直接用 recentWindow
                            if (filtered.isEmpty() && recentWindow.isNotEmpty()) recentWindow else filtered
                        } else {
                            current
                        }
                        // 同 chatId 二次守卫
                        val lastLoaded = dedupedCurrent.last()
                        if (lastLoaded.chatId != windowChatId) return@update state
                        // 增量合并:仅追加比 dedupedCurrent 最新一条 (timestamp, id) 更新的消息
                        val newMessages = recentWindow.filter { msg ->
                            msg.timestamp > lastLoaded.timestamp ||
                                (msg.timestamp == lastLoaded.timestamp && msg.id > lastLoaded.id)
                        }
                        if (newMessages.isNotEmpty() || dedupedCurrent.size != current.size) {
                            state.copy(currentMessages = dedupedCurrent + newMessages)
                        } else {
                            state
                        }
                    }
                }
            }
        }
        // 观察当前群聊元数据(标题刷新用)
        viewModelScope.launch {
            currentChatId.flatMapLatest { chatId ->
                if (chatId != null) groupChatRepository.observeChat(chatId) else flowOf(null)
            }.collect { chat ->
                _state.update { it.copy(currentChat = chat) }
            }
        }
        // 加载团队列表(从内存缓存读,零阻塞)
        viewModelScope.launch {
            settings.multiAgentConfigFlow.collect { config ->
                _state.update { it.copy(teams = config.teams) }
            }
        }
        // 加载聊天偏好
        viewModelScope.launch {
            settings.chatPreferencesFlow.collect { prefs ->
                _state.update { it.copy(chatPreferences = prefs) }
            }
        }
        // v1.111: 订阅群聊生成状态(切页后重建 ViewModel 时恢复 isAgentResponding / currentSpeaker)
        viewModelScope.launch {
            scheduler.activeGroupGeneration.collect { gen ->
                if (gen == null) {
                    _state.update { it.copy(isAgentResponding = false, currentSpeaker = null) }
                } else if (gen.chatId == currentChatId.value) {
                    // 只在当前群聊匹配时显示生成状态(避免其他群聊的生成干扰当前页)
                    val speaker = gen.currentSpeakerId?.let { id ->
                        _state.value.assistants.firstOrNull { it.id == id }
                    }
                    _state.update {
                        it.copy(
                            isAgentResponding = gen.isResponding,
                            currentSpeaker = speaker,
                        )
                    }
                } else {
                    // 其他群聊在生成,当前页不显示
                    _state.update { it.copy(isAgentResponding = false, currentSpeaker = null) }
                }
            }
        }
        // ActivityHub: 订阅活动状态,派生当前群聊的活动列表到 UI(渲染 AgentActivityBar chip)。
        // 只取当前群聊的活动;其他群聊的活动不展示,避免跨群聊串扰。
        viewModelScope.launch {
            activityHub.activities.collect { allActivities ->
                val chatId = currentChatId.value
                val current = if (chatId != null) allActivities[chatId] ?: emptyList() else emptyList()
                _state.update { it.copy(activities = current) }
            }
        }
    }

    /**
     * v1.45: 缓存列表滚动位置,切页/后台后恢复。
     */
    fun onListScrollPositionChanged(index: Int, offset: Int) {
        // L-GC6 修复: 用 _state.update {} 原子更新,替代 _state.value = _state.value.copy(...)
        // 避免读-改-写竞态(高频滚动时并发更新可能丢失中间状态)
        _state.update {
            it.copy(
                listFirstVisibleItemIndex = index,
                listFirstVisibleItemScrollOffset = offset,
            )
        }
    }

    /**
     * v1.45: 切换指定消息 mood 块的展开/折叠状态。
     */
    fun toggleMessageMoodExpanded(messageId: String) {
        _state.update { current ->
            val currentState = current.messageExpandedStates[messageId] ?: GroupChatMessageExpandedState()
            val default = current.chatPreferences.moodExpandedByDefault
            val newExpanded = !(currentState.isMoodExpanded ?: default)
            current.copy(
                messageExpandedStates = current.messageExpandedStates +
                    (messageId to currentState.copy(isMoodExpanded = newExpanded)),
            )
        }
    }

    /**
     * v1.45: 切换指定消息 reasoning 块的展开/折叠状态。
     */
    fun toggleMessageReasoningExpanded(messageId: String) {
        _state.update { current ->
            val currentState = current.messageExpandedStates[messageId] ?: GroupChatMessageExpandedState()
            val default = current.chatPreferences.reasoningExpandedByDefault
            val newExpanded = !(currentState.isReasoningExpanded ?: default)
            current.copy(
                messageExpandedStates = current.messageExpandedStates +
                    (messageId to currentState.copy(isReasoningExpanded = newExpanded)),
            )
        }
    }

    /**
     * 选中群聊,切换消息流观察并触发分页首屏加载。
     *
     * v1.53-GC: 改为分页加载 — 立即清空旧消息(避免跨群聊串扰),随后异步加载最近
     * [INITIAL_PAGE_SIZE] 条;新消息由 init 中的 [getPagedMessages] Flow 增量追加。
     *
     * @param chatId 群聊 id
     */
    fun selectChat(chatId: String) {
        currentChatId.value = chatId
        // 立即清空旧消息 + 分页状态,避免新群聊首屏加载完成前残留旧群聊内容
        // ActivityHub: 同时清空活动列表,避免旧群聊的 chip 残留(新群聊的活动由 subscriber 重新派生)
        _state.update {
            it.copy(
                currentMessages = emptyList(),
                hasMoreHistory = false,
                isLoadingMore = false,
                lastHistoryLoadCount = 0,
                activities = emptyList(),
            )
        }
        loadInitialPage(chatId)
    }

    /**
     * v1.53-GC: 分页首屏加载 — 取最近 [INITIAL_PAGE_SIZE] 条消息,并据总数设置 hasMoreHistory。
     *
     * @param chatId 群聊 id
     */
    private fun loadInitialPage(chatId: String) {
        viewModelScope.launch {
            try {
                val total = groupChatRepository.countMessages(chatId)
                if (total == 0) {
                    _state.update {
                        it.copy(
                            currentMessages = emptyList(),
                            hasMoreHistory = false,
                            isLoadingMore = false,
                            lastHistoryLoadCount = 0,
                        )
                    }
                    return@launch
                }
                val limit = minOf(INITIAL_PAGE_SIZE, total)
                val messages = groupChatRepository.getRecentMessagesPaged(chatId, limit)
                _state.update {
                    it.copy(
                        currentMessages = messages,
                        hasMoreHistory = total > messages.size,
                        isLoadingMore = false,
                        lastHistoryLoadCount = 0,
                    )
                }
            } catch (e: Exception) {
                Logger.e(TAG, "群聊首屏分页加载失败 chatId=$chatId", e)
                _state.update { it.copy(isLoadingMore = false, hasMoreHistory = false) }
            }
        }
    }

    /**
     * v1.53-GC: 上滑加载更多历史消息。
     *
     * 取当前 currentMessages 最早一条消息的 (timestamp, id) 作为双锚点,从 DB 取早于该锚点的
     * [LOAD_MORE_PAGE_SIZE] 条消息,前置插入到 currentMessages。
     *
     * - isLoadingMore=true 时跳过(防止重复触发)
     * - hasMoreHistory=false 时跳过(已加载完)
     * - currentMessages 为空时跳过(首屏尚未加载完成)
     * - 加载完成后设置 [GroupChatUiState.lastHistoryLoadCount],UI 监听后调整滚动位置保持视觉位置
     */
    fun loadMoreHistory() {
        val state = _state.value
        if (state.isLoadingMore || !state.hasMoreHistory) return
        val chatId = currentChatId.value ?: return
        val firstMsg = state.currentMessages.firstOrNull() ?: return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingMore = true) }
            try {
                val older = groupChatRepository.getOlderMessages(
                    chatId = chatId,
                    beforeTimestamp = firstMsg.timestamp,
                    beforeId = firstMsg.id,
                    limit = LOAD_MORE_PAGE_SIZE,
                )
                if (older.isEmpty()) {
                    _state.update { it.copy(hasMoreHistory = false, isLoadingMore = false) }
                    return@launch
                }
                // 重新读取最新 state.currentMessages,防止加载期间 Flow 追加的新消息被覆盖
                val current = _state.value.currentMessages
                _state.update {
                    it.copy(
                        currentMessages = older + current,
                        hasMoreHistory = older.size >= LOAD_MORE_PAGE_SIZE,
                        isLoadingMore = false,
                        lastHistoryLoadCount = older.size,
                    )
                }
            } catch (e: Exception) {
                Logger.e(TAG, "群聊加载更多历史失败 chatId=$chatId", e)
                _state.update { it.copy(isLoadingMore = false, hasMoreHistory = false) }
            }
        }
    }

    /**
     * v1.53-GC: 清空 [GroupChatUiState.lastHistoryLoadCount]。
     *
     * UI 在 [GroupChatDetailScreen] 监听 lastHistoryLoadCount 变化(>0)后,调
     * [androidx.compose.foundation.lazy.LazyListState.scrollToItem] 跳过新插入的条数,
     * 保持视觉位置不跳动,然后调本方法清空(避免重组时重复跳转)。
     */
    fun clearHistoryLoadCount() {
        if (_state.value.lastHistoryLoadCount != 0) {
            _state.update { it.copy(lastHistoryLoadCount = 0) }
        }
    }

    /**
     * 更新输入框文本。
     */
    fun updateInput(text: String) {
        _state.update { it.copy(inputText = text) }
    }

    /**
     * 添加待发送图片(base64 字符串)。
     */
    fun addPendingImage(base64Image: String) {
        _state.update { it.copy(pendingImages = it.pendingImages + base64Image) }
    }

    /**
     * 移除指定待发送图片。
     */
    fun removePendingImage(base64Image: String) {
        _state.update { it.copy(pendingImages = it.pendingImages - base64Image) }
    }

    /**
     * 清空所有待发送图片。
     */
    fun clearPendingImages() {
        _state.update { it.copy(pendingImages = emptyList()) }
    }

    /**
     * 发送用户消息并触发 Agent 轮转回复。
     *
     * v1.111: 轮转运行在 [GroupChatScheduler.launchRoundRobin] 的 appScope 中,
     * 切页/后台不中断。ViewModel 只负责前置检查 + 清空输入框 + 委托给 scheduler。
     *
     * @param text 消息正文
     */
    fun sendMessage(text: String) {
        val chatId = currentChatId.value ?: return
        if (text.isBlank() && _state.value.pendingImages.isEmpty()) return
        // v1.126: debounce — 防止快速点击重复发送
        val now = System.currentTimeMillis()
        if (now - lastSendTimestamp < SEND_DEBOUNCE_MS) return
        lastSendTimestamp = now
        // v1.111: 用 scheduler 的全局状态检查(替代 _state.isAgentResponding,切页后 _state 会重置)
        if (scheduler.hasActiveGeneration(chatId)) return

        val images = _state.value.pendingImages
        // 立即清空输入框(常见交互:点发送后输入框立即清空)
        _state.update { it.copy(inputText = "", pendingImages = emptyList()) }
        // v1.0.21: 乐观 UI 更新 — 立即把用户消息追加到 currentMessages,
        //   不依赖 Room Flow 回流(原实现因 collect 守卫/竞态导致消息发送后不显示)
        // v1.0.22: 乐观消息 id 加 "optimistic-" 前缀,collect 合并时据此识别并去重,
        //   避免 scheduler 写入 DB 的真实消息回流后被追加导致重复显示
        val imageJson = if (images.isNotEmpty()) {
            json.encodeToString(images)
        } else "[]"
        val optimisticMsg = GroupChatMessageEntity(
            id = "optimistic-" + java.util.UUID.randomUUID().toString(),
            chatId = chatId,
            senderType = "user",
            senderId = "local_user",
            senderName = "我",
            body = text,
            imageBase64Json = imageJson,
            timestamp = now,
        )
        _state.update { it.copy(currentMessages = it.currentMessages + optimisticMsg) }
        // v1.111: 委托给 scheduler,轮转运行在 appScope
        scheduler.launchRoundRobin(chatId, text, images)
    }

    /**
     * v1.111: 手动停止当前群聊生成。
     */
    fun stopGeneration() {
        val chatId = currentChatId.value ?: return
        scheduler.stop(chatId)
    }

    /**
     * v2.x: 指定 AI 重新生成其最后一条消息。
     */
    fun regenerateAgentMessage(assistantId: String) {
        val chatId = currentChatId.value ?: return
        if (scheduler.hasActiveGeneration(chatId)) return
        scheduler.regenerateAgentMessage(chatId, assistantId)
    }

    /**
     * v2.x: 发起表决。
     */
    fun launchVote(topic: String) {
        val chatId = currentChatId.value ?: return
        if (scheduler.hasActiveGeneration(chatId)) return
        scheduler.launchVote(chatId, topic)
    }

    /**
     * v2.x: 结论总结器。
     */
    fun launchSummary(summarizerId: String? = null) {
        val chatId = currentChatId.value ?: return
        if (scheduler.hasActiveGeneration(chatId)) return
        scheduler.launchSummary(chatId, summarizerId)
    }

    /**
     * v2.x: 发送悄悄话给指定 AI。
     */
    fun sendWhisper(targetAssistantId: String, text: String) {
        val chatId = currentChatId.value ?: return
        if (text.isBlank()) return
        if (scheduler.hasActiveGeneration(chatId)) return
        scheduler.launchWhisper(chatId, targetAssistantId, text)
    }

    // ── v2.x 群聊上下文管理:群共享文档 + AI 专属上下文 ──

    /**
     * v2.x: 添加群共享文档。
     *
     * @param title 文档标题
     * @param content 文档正文
     */
    fun addSharedDoc(title: String, content: String) {
        val chatId = currentChatId.value ?: return
        if (title.isBlank() || content.isBlank()) return
        viewModelScope.launch {
            try {
                groupChatRepository.addSharedDoc(chatId, title.trim(), content)
            } catch (e: Exception) {
                Logger.e(TAG, "添加群共享文档失败", e)
                _state.update { it.copy(errorMessage = appContext.getString(R.string.err_group_chat_update_failed)) }
            }
        }
    }

    /**
     * v2.x: 删除群共享文档。
     *
     * @param docId 文档 id
     */
    fun removeSharedDoc(docId: String) {
        val chatId = currentChatId.value ?: return
        viewModelScope.launch {
            try {
                groupChatRepository.removeSharedDoc(chatId, docId)
            } catch (e: Exception) {
                Logger.e(TAG, "删除群共享文档失败", e)
                _state.update { it.copy(errorMessage = appContext.getString(R.string.err_group_chat_update_failed)) }
            }
        }
    }

    /**
     * v2.x: 设置某个群成员的专属上下文(仅该 AI 可见)。
     *
     * @param assistantId 成员 AI id
     * @param contextText 专属上下文文本(空字符串则清除)
     */
    fun setMemberPrivateContext(assistantId: String, contextText: String) {
        val chatId = currentChatId.value ?: return
        viewModelScope.launch {
            try {
                groupChatRepository.setMemberPrivateContext(chatId, assistantId, contextText)
            } catch (e: Exception) {
                Logger.e(TAG, "设置成员专属上下文失败", e)
                _state.update { it.copy(errorMessage = appContext.getString(R.string.err_group_chat_update_failed)) }
            }
        }
    }

    /**
     * 创建新群聊。
     *
     * @param name 群聊名称
     * @param memberIds 成员助手 id 列表
     * @param teamId 可选关联团队 id
     * @return 新创建的群聊 id
     */
    suspend fun createChat(name: String, memberIds: List<String>, teamId: String? = null): String {
        return groupChatRepository.createChat(name, memberIds, teamId)
    }

    /**
     * v1.126: 清空错误提示。
     */
    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    /**
     * 删除群聊(级联删除其全部消息)。
     *
     * @param chatId 群聊 id
     */
    fun deleteChat(chatId: String) {
        viewModelScope.launch {
            try {
                groupChatRepository.deleteChat(chatId)
                // 若删除的是当前群聊,清空选中状态并通知详情页返回
                if (currentChatId.value == chatId) {
                    currentChatId.value = null
                    // M7: 设置删除标志,详情页据此自动返回
                    // v1.53-GC: 分页模式下 Flow 不再全量重发,手动清空消息 + 分页状态
                    _state.update {
                        it.copy(
                            chatDeleted = true,
                            currentMessages = emptyList(),
                            hasMoreHistory = false,
                            isLoadingMore = false,
                            lastHistoryLoadCount = 0,
                        )
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "删除群聊失败", e)
                _state.update { it.copy(errorMessage = appContext.getString(R.string.err_group_chat_delete_failed)) }
            }
        }
    }

    /**
     * v1.77: 删除群聊中的单条消息。
     *
     * @param messageId 消息 id
     */
    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            try {
                groupChatRepository.deleteMessage(messageId)
                // v1.53-GC: 分页模式下 Flow 只增量追加新消息、不重发全量,
                // 需手动从 currentMessages 移除已删消息(避免残留)
                _state.update { state ->
                    state.copy(currentMessages = state.currentMessages.filterNot { it.id == messageId })
                }
            } catch (e: Exception) {
                Logger.e(TAG, "删除消息失败", e)
                _state.update { it.copy(errorMessage = appContext.getString(R.string.err_group_chat_delete_msg_failed)) }
            }
        }
    }

    /**
     * 切换群聊置顶状态。
     *
     * @param chatId 群聊 id
     */
    fun togglePin(chatId: String) {
        viewModelScope.launch {
            try {
                groupChatRepository.togglePin(chatId)
            } catch (e: Exception) {
                Logger.e(TAG, "切换置顶失败", e)
                _state.update { it.copy(errorMessage = appContext.getString(R.string.err_group_chat_operation_failed)) }
            }
        }
    }

    /**
     * v1.97: 更新群聊信息(名称/描述/成员)。
     *
     * @param chatId 群聊 id
     * @param name 新名称(null 表示不改)
     * @param description 新描述(null 表示不改)
     * @param memberIds 新成员列表(null 表示不改)
     */
    fun updateChat(
        chatId: String,
        name: String? = null,
        description: String? = null,
        memberIds: List<String>? = null,
        discussionMode: String? = null,
        autoMaxRounds: Int? = null,
        hostId: String? = null,
    ) {
        viewModelScope.launch {
            try {
                groupChatRepository.updateChat(chatId, name, description, memberIds, discussionMode, autoMaxRounds, hostId)
            } catch (e: Exception) {
                Logger.e(TAG, "更新群聊失败", e)
                _state.update { it.copy(errorMessage = appContext.getString(R.string.err_group_chat_update_failed)) }
            }
        }
    }

    /**
     * 解析群聊成员 id 列表。
     */
    fun parseMemberIds(chat: GroupChatEntity): List<String> =
        groupChatRepository.parseMemberIds(chat)

    /**
     * v2.x: 解析群聊的共享文档列表(供 UI 渲染)。
     */
    fun parseSharedDocs(chat: GroupChatEntity): List<io.zer0.muse.data.groupchat.GroupSharedDoc> =
        groupChatRepository.parseSharedDocs(chat)

    /**
     * v2.x: 解析群聊成员的专属上下文 Map(供 UI 渲染)。
     */
    fun parseMemberPrivateContext(chat: GroupChatEntity): Map<String, String> =
        groupChatRepository.parseMemberPrivateContext(chat)

    /**
     * 取指定群聊的最新一条消息(用于列表页预览)。
     */
    suspend fun getLatestMessage(chatId: String): GroupChatMessageEntity? =
        groupChatRepository.getLatestMessage(chatId)
}
