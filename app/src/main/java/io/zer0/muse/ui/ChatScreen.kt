package io.zer0.muse.ui

import android.Manifest
import android.content.pm.PackageManager
import android.view.KeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import io.zer0.muse.ui.common.media.WindowWidthClass
import io.zer0.muse.ui.common.state.MuseLoadingState
import io.zer0.muse.ui.common.surface.MuseListItem
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import compose.icons.TablerIcons
import compose.icons.tablericons.AlertCircle
import compose.icons.tablericons.ArrowLeft
import compose.icons.tablericons.Edit
import compose.icons.tablericons.GitMerge
import compose.icons.tablericons.MessageCircle
import compose.icons.tablericons.Search
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.sample
import androidx.compose.ui.Alignment
import androidx.compose.ui.zIndex
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.window.Popup
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.ui.common.media.DesktopShortcuts
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.media.rememberDesktopShortcutsEnabled
import io.zer0.muse.ui.common.media.rememberWindowWidthClass
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.artifact.ArtifactEntity
import io.zer0.muse.data.knowledge.KnowledgeDocDao
import io.zer0.muse.data.knowledge.KnowledgeDocEntity
import io.zer0.muse.ui.chat.ToolApprovalCard
import io.zer0.muse.ui.chat.TokenStatsBar
import io.zer0.muse.ui.chat.buildQuotedContent
import io.zer0.muse.ui.chat.SlashCommand
import io.zer0.muse.ui.chat.PendingQueueBar
import io.zer0.muse.ui.speech.SpeechInput
import io.zer0.muse.ui.speech.TtsControllerWidget
import io.zer0.muse.ui.speech.VoiceConversationMode
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.taskcard.AgentPlan
import io.zer0.muse.ui.taskcard.AgentPlanStepStatus
import io.zer0.muse.perf.MessagePaginator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import io.zer0.muse.tools.BrowserManager
import io.zer0.muse.ui.chat.BrowserStatusCapsule
/** P6-B3: 翻译支持的目标语言列表(常用语言,中文名便于 LLM 理解)。 */
internal val TranslationLanguages = listOf(
    "中文", "English", "日本語", "한국어", "Français", "Deutsch", "Español", "Русский", "العربية", "Português",
)


/** Phase 8.10: 音量键滚动单次位移(px),M21。 */
private const val VOLUME_SCROLL_DISTANCE_PX = 200f

/** v1.79 (M-S12): 消息分组时间间隔(5 分钟),超过此间隔显示头像和时间戳。 */
private const val MESSAGE_GROUP_INTERVAL_MS = 5 * 60 * 1000L

/**
 * Phase 8.10: 拦截音量键上/下键事件,转为滚动操作。
 * - VOLUME_UP → direction = -1(向上滚)
 * - VOLUME_DOWN → direction = +1(向下滚)
 * 返回 true 表示事件已消费,系统不再调音量调节。
 *
 * 通过 [androidx.compose.ui.input.key.KeyEvent.nativeKeyEvent] 拿到底层
 * [android.view.KeyEvent],访问 keyCode/action(Compose 包装层 type/key 属性
 * 在不同版本可用性不一致,nativeKeyEvent 稳定可靠)。
 */
private fun Modifier.onVolumeKeyEvent(onScroll: (Float) -> Unit): Modifier = this.onKeyEvent { event ->
    val native = event.nativeKeyEvent
    if (native.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
    val direction = when (native.keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> -1f
        KeyEvent.KEYCODE_VOLUME_DOWN -> 1f
        else -> return@onKeyEvent false
    }
    onScroll(direction)
    true
}

/**
 * 聊天页 — 顶部 Tab 导航化,移除 Drawer 架构。
 *
 * ChatScreen 作为 HomeScreen 的 Tab 1 内容嵌入,不再需要 Drawer。
 * 保留所有核心聊天功能:
 *  - 模型切换、设置入口
 *  - 音量键滚动、MessageBubble、InputBar
 *  - 错误显示、滚动到底按钮、自动滚动
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    onOpenAssistants: () -> Unit = {},
    /** v0.27: 从任务列表 push 进入时传入返回回调;为 null 时(Tab 模式)不显示返回按钮。 */
    onBack: (() -> Unit)? = null,
    /** v1.24: Agent Tab 模式 — 隐藏自带 TopAppBar,由 HomeScreen 统一提供三点菜单。 */
    isAgentMode: Boolean = false,
    viewModel: ChatViewModel = koinInject(),
    /**
     * HTML/SVG 代码块全屏预览回调。
     * 由 [MessageBubble] 内 HTML/SVG 代码块右上角"预览"按钮触发,
     * 参数为完整 HTML 源码(SVG 已包装为完整 HTML)。
     * MainActivity 的 NavGraph 中注入导航逻辑,跳转到 [HtmlPreviewScreen]。
     */
    onHtmlPreview: (String) -> Unit = {},
    /** 加号菜单 → 技能入口。 */
    onOpenSkills: () -> Unit = {},
    /** B0-07: 打开提示词模板管理页。 */
    onOpenPromptTemplateManager: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val conversationTree by viewModel.conversationTree.collectAsStateWithLifecycle()
    // B2-01: 消息列表独立 collect,输入框等高频 state 变化不再带动整个消息列表重组。
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    // v1.0.20 (Task 3): 高频字段用 derivedStateOf 包裹,收窄重组范围。
    //  state 是 StateFlow<ChatUiState>,每次 copy(如 input 每次按键、visionProgress 每完成一张图)
    //  都会发射新对象,导致读取 state 的所有 Composable lambda 重组。
    //  把低频变化的高频字段派生为独立 State,lambda 只在派生值真正变化时才重组:
    //   - isStreaming: 流式开始/结束才变(每轮对话 2 次)
    //   - isWaitingFirstToken: 首 token 前后变(每轮对话 2 次)
    //   - visionProgress: 每完成一张图变(每轮 N 次,N=图片数)
    //   - currentInput: 用户每次按键都变(高频,但仅 RichInputBar 关心)
    val isStreaming by remember { derivedStateOf { state.isStreaming } }
    val isWaitingFirstToken by remember { derivedStateOf { state.isWaitingFirstToken } }
    val visionProgress by remember { derivedStateOf { state.visionProgress } }
    val currentInput by remember { derivedStateOf { state.input } }
    // P2-13: 桌面端快捷键总开关(Expanded 窗口 + 物理键盘)
    val desktopShortcutsEnabled = rememberDesktopShortcutsEnabled()
    // P2-1: 大屏(Expanded)下消息列表居中限宽 720dp
    val widthClass = rememberWindowWidthClass()
    // Phase 3 3E: 定时消息横幅
    val pendingMessageManager: io.zer0.muse.data.schedule.PendingMessageManager = koinInject()
    val pendingMessages by pendingMessageManager.pendingMessagesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val pendingScope = rememberCoroutineScope()
    // A3 (H1): 系统关闭动画(reduced-motion)时消息段级淡入降级为立即显示
    val reducedMotion = MuseMotion.isReducedMotion()
    // v1.45: 用 ViewModel 中缓存的滚动位置初始化 LazyListState,切页/后台后恢复位置
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = state.listFirstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = state.listFirstVisibleItemScrollOffset,
    )
    // v1.45: 滚动位置变化时同步缓存到 ViewModel
    // v1.100: 加 distinctUntilChanged + sample(100ms) 降频,避免滚动时高频
    // 写入 ViewModel state 导致 ChatScreen 全量重组(滚动 → 写 state → 重组 → 重测量的隐性循环)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .sample(100L)
            .distinctUntilChanged()
            .collect { (index, offset) ->
                viewModel.onListScrollPositionChanged(index, offset)
            }
    }
    val context = LocalContext.current
    // Phase 8.5 修复: 用于在 IO 线程读 clipboard(原实现在主线程同步读 primaryClip,
    // 是一次跨进程 IPC,Android 10+ 后台访问受限时可能 ANR)
    val ioScope = rememberCoroutineScope()
    // ── A1: 会话内查找 — 状态 / 命中计算 / 跳转 ──
    // 就地查找条开关、查询词、当前命中在命中列表中的索引(切换会话时随 rememberSaveable 重置)
    var showInChatSearch by rememberSaveable { mutableStateOf(false) }
    var inChatQuery by rememberSaveable { mutableStateOf("") }
    var currentMatchIndex by rememberSaveable { mutableStateOf(0) }
    // ── H10: 手动压缩参数对话框 — 保留条数 / 附加指令状态 ──
    var showCompressDialog by rememberSaveable { mutableStateOf(false) }
    var compressKeepText by rememberSaveable { mutableStateOf("") }
    var compressInstruction by rememberSaveable { mutableStateOf("") }
    // 命中列表:当前会话已加载消息中,内容含查询词(忽略大小写)的消息 id;空查询返回空。
    // 仅覆盖已加载消息(OBSERVE_LIMIT 内),更早历史需先上滑加载更多。
    val findMatches = remember(messages, inChatQuery) {
        val q = inChatQuery.trim()
        if (q.isEmpty()) {
            emptyList()
        } else {
            messages.filter { it.content.isNotBlank() && it.content.contains(q, ignoreCase = true) }
                .map { it.id.toString() }
        }
    }
    // 跳转:复用 v2.x setTargetMessage 管线(滚动定位 + 文本高亮 + 2.5s 窗口);
    // 负索引/越界取模回绕,支持上一条/下一条循环跳转。
    fun jumpToMatch(index: Int) {
        if (findMatches.isEmpty()) return
        val size = findMatches.size
        val safe = ((index % size) + size) % size
        currentMatchIndex = safe
        viewModel.setTargetMessage(findMatches[safe], inChatQuery.trim())
    }
    // 查询词变化(或流式期间命中数变化)时自动跳到第一条命中;空查询清空高亮状态
    LaunchedEffect(inChatQuery, findMatches.size) {
        if (!showInChatSearch) return@LaunchedEffect
        if (findMatches.isEmpty()) {
            viewModel.setTargetMessage(null, null)
        } else {
            currentMatchIndex = 0
            viewModel.setTargetMessage(findMatches[0], inChatQuery.trim())
        }
    }
    // 阶段 5: 模型切换底部面板展开状态
    val sheetState = remember { ChatSheetState() }
    val knowledgeDao: KnowledgeDocDao = koinInject()
    val knowledgeDocs by knowledgeDao.observeAllUser().collectAsStateWithLifecycle(initialValue = emptyList())
    // v1.95: 注入 SettingsRepository 用于读取/保存 ASR 提示状态
    val settings: SettingsRepository = koinInject()
// v1.x: 会话级浏览器注册表 — 胶囊观察当前会话的浏览器实例(每个会话独立 WebView)
val browserRegistry: io.zer0.muse.tools.BrowserManagerRegistry = koinInject()
val activeBrowserSessions by browserRegistry.activeSessionIds.collectAsState()
val currentBrowserManager = remember(activeBrowserSessions, state.currentSessionId) {
    val sid = state.currentSessionId
    if (sid != null && sid in activeBrowserSessions) browserRegistry.getIfActive(sid) else null
}
    // v1.0.75 fix (用户反馈): Markdown 格式工具条整条移除,不再有富文本开关

    // v1.0.4 (P3-4): 性能模式 — 通过 MessagePaginator 对 messages 做内存级分页,
    // LazyColumn 只渲染最近 N 条,上滑到顶时扩展下一页(纯本地内存分页);
    // 全部展开后再上滑才触发 DB loadMoreHistory。
    // 关闭时 visibleMessages == messages,行为与原有逻辑完全一致。
    val performanceMode = state.chatPreferences.performanceMode
    var paginatorPageCount by rememberSaveable { mutableStateOf(1) }
    // B7-03: 会话内未读状态
    // 切换会话 / 关闭性能模式时重置分页计数
    LaunchedEffect(state.currentSessionId) {
        paginatorPageCount = 1
    }
    LaunchedEffect(performanceMode) {
        if (!performanceMode) paginatorPageCount = 1
    }
    var savedPaginatorScrollOffset by remember { mutableStateOf(0) }
    val visibleMessages by produceState(
        initialValue = if (isAgentMode && !state.isAgentMode && !state.isSwitchingSession) emptyList() else messages,
        messages, paginatorPageCount, performanceMode, isAgentMode, state.isAgentMode, state.isSwitchingSession,
    ) {
        // 门禁:Agent Tab 模式下但 ViewModel 还没切换到 Agent 模式时,显示空白。
        // 避免 HorizontalPager 动画期间目标页已 compose 但 setAgentMode 尚未执行时闪现旧对话内容。
        // v1.137 B2: isSwitchingSession=true 时保持上一帧(不清空),消除空列表闪屏。
        if (isAgentMode && !state.isAgentMode && !state.isSwitchingSession) {
            value = emptyList()
            return@produceState
        }
        if (!performanceMode) {
            value = messages
            return@produceState
        }
        val allIds = messages.map { it.id.toString() }
        if (allIds.isEmpty()) {
            value = emptyList()
            return@produceState
        }
        val pageSize = MessagePaginator.DEFAULT_PAGE_SIZE * paginatorPageCount
        // 取首页(最新 N 条 ID),再反查 UIMessage 保留顺序
        val visibleIds = MessagePaginator.createFlow(allIds, pageSize = pageSize).first()
        val msgById = messages.associateBy { it.id.toString() }
        value = visibleIds.mapNotNull { msgById[it] }
    }


    // v1.0.74 fix (前端审计 1.1): 消息区在 LazyColumn 的起始全局索引。
    // 消息前后有条件插入的额外 item(agent_mode_hint/load_more/shimmer/subagent_task_list 等),
    // 所有"消息局部索引 ↔ 全局索引"换算必须加这个偏移,否则 isAtBottom/滚动定位全错位。
    val messageStartIndex by remember { derivedStateOf {
        var idx = 0
        val showAgentHint =
            !state.weakToolHint.isNullOrEmpty() || !state.agentModeHint.isNullOrEmpty()
        if (showAgentHint) idx++
        if (state.isLoadingMore) idx++
        idx
    } }

    // v0.48: 派生状态 — isAtBottom 判断列表是否在底部(用户没往上滚)
    // v1.52: 收紧阈值 — 仅当最后一项的底部在视口内才算"在底部",
    //        避免"部分可见=在底部"导致流式增量把用户拉回底部。
    // v1.0.4 (P3-4): 性能模式下用 visibleMessages(实际渲染列表)判断,而非 messages。
    val isAtBottom by remember {
        derivedStateOf {
            if (visibleMessages.isEmpty()) return@derivedStateOf true
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
            val viewportEnd = listState.layoutInfo.viewportEndOffset
            // 最后一项必须是消息区最后一条(全局索引 = messageStartIndex + 局部 lastIndex),
            // 且其底部在视口底部附近。
            // v1.0.74 fix (前端审计 1.1): 原实现 lastVisible.index == visibleMessages.lastIndex
            // 用全局索引比局部索引,agent_hint/load_more 等额外 item 存在时恒 false。
            lastVisible.index == messageStartIndex + visibleMessages.lastIndex &&
                (lastVisible.offset + lastVisible.size) <= viewportEnd + 150
        }
    }

    // v1.53-A1: 上滑加载更多历史消息 — 到达列表顶部(firstVisibleItemIndex==0)且满足条件时触发。
    // 触发后 ViewModel 设置 lastHistoryLoadCount,下面的 LaunchedEffect 据此调整滚动位置,
    // 跳过新插入的条数,使 firstVisibleItemIndex 变为 lastHistoryLoadCount(>0),
    // 从而避免在顶部重复触发(用户需再次主动上滑才继续加载)。
    var savedScrollOffset by remember { mutableStateOf(0) }
    val loadMoreTrigger by remember {
        derivedStateOf {
            state.hasMoreHistory &&
                !state.isLoadingMore &&
                !state.isStreaming &&
                messages.isNotEmpty() &&
                listState.firstVisibleItemIndex == 0 &&
                // v1.0.4 (P3-4): 性能模式下仅当 visibleMessages 已覆盖全部 messages 时才触发 DB 加载,
                // 否则由 paginatorLoadMoreTrigger 先扩展内存分页
                (!performanceMode || visibleMessages.size >= messages.size)
        }
    }
    LaunchedEffect(loadMoreTrigger) {
        if (loadMoreTrigger) {
            // 记录当前 offset,加载完成后跳到新位置时保持视觉位置
            savedScrollOffset = listState.firstVisibleItemScrollOffset
            viewModel.loadMoreHistory()
        }
    }
    // B7-03: 到底自动标记已读
    LaunchedEffect(isAtBottom, messages.lastOrNull()?.id) {
        if (isAtBottom) viewModel.markSessionRead()
    }
    // v1.0.4 (P3-4): 性能模式内存分页触发 — 到达顶部且 messages 还有未渲染的更早消息时,
    // 扩展 paginatorPageCount(纯本地内存分页,不查 DB)。扩展后通过 scrollToItem 保持视觉位置不跳。
    val paginatorLoadMoreTrigger by remember {
        derivedStateOf {
            performanceMode &&
                messages.size > visibleMessages.size &&
                !state.isStreaming &&
                listState.firstVisibleItemIndex == 0
        }
    }
    LaunchedEffect(paginatorLoadMoreTrigger) {
        if (paginatorLoadMoreTrigger) {
            // 记录当前 offset,加载后跳到新位置保持视觉位置
            savedPaginatorScrollOffset = listState.firstVisibleItemScrollOffset
            val previousSize = visibleMessages.size
            paginatorPageCount++
            // 等待 visibleMessages 重新计算并增长(produceState 异步更新)
            withTimeoutOrNull(1000L) {
                snapshotFlow { visibleMessages.size }
                    .filter { it > previousSize }
                    .first()
            }
            // 加载完成后跳到新位置(原来在顶部的消息现在在 addedCount 位置)
            val addedCount = visibleMessages.size - previousSize
            if (addedCount > 0) {
                // v1.0.74 fix (前端审计 1.1): 加消息区起始偏移,agent_hint/load_more 存在时定位不错位
                listState.scrollToItem(messageStartIndex + addedCount, savedPaginatorScrollOffset)
            }
            savedPaginatorScrollOffset = 0
        }
    }
    // v1.53-A1: 加载完成后调整滚动位置,保持视觉位置不跳动
    // (原来在顶部的消息现在在 lastHistoryLoadCount 位置)
    // v1.0.4 (P3-4): 性能模式下,DB 加载更多后同步扩展 paginatorPageCount,
    // 让 visibleMessages 包含新加载的旧消息(否则 visibleMessages 仍是最新 N 条,看不到新加载的更老消息)。
    LaunchedEffect(state.lastHistoryLoadCount) {
        if (state.lastHistoryLoadCount > 0) {
            if (performanceMode) {
                paginatorPageCount++
                // 等待 visibleMessages 重新计算并覆盖全部 messages
                val targetSize = messages.size
                withTimeoutOrNull(1000L) {
                    snapshotFlow { visibleMessages.size }
                        .filter { it >= targetSize }
                        .first()
                }
            }
            // v1.0.74 fix (前端审计 1.1): 加消息区起始偏移,agent_hint/load_more 存在时定位不错位
            listState.scrollToItem(messageStartIndex + state.lastHistoryLoadCount, savedScrollOffset)
            viewModel.clearHistoryLoadCount()
            savedScrollOffset = 0
        }
    }
    // v1.0.4 (P1): 草稿恢复 toast 反馈 — 进入有草稿的会话时显示"草稿已恢复"
    // (InputBar 已显示「草稿」小标签,本 toast 是更强的瞬时反馈,避免用户没注意到标签)
    LaunchedEffect(state.hasDraft, state.currentSessionId) {
        if (state.hasDraft && state.input.isNotBlank()) {
            MuseToast.show(context.getString(R.string.chat_draft_restored))
        }
    }

    // v2.x: 从搜索结果点击消息跳转 — 滚动到目标消息并短暂高亮。
    //
    // 触发条件:state.targetMessageId 非空(由 SearchScreen onOpenMessage →
    // MainActivity switchSession + setTargetMessage 设置)。
    //
    // 流程:
    //  1. 等 visibleMessages 包含目标消息(超时 5s,覆盖 switchSession 异步加载)
    //     — switchSession 协程完成时 currentSessionId 变化,本 LaunchedEffect 重新触发(旧协程取消)
    //  2. 性能模式下若 messages 含目标消息但 visibleMessages 未覆盖,临时扩展 paginatorPageCount
    //  3. scrollToItem 到对应索引(瞬时,无动画,避免长会话动画卡顿)
    //  4. 延迟 2.5s 后调 clearHighlightedMessage 停止高亮
    //  5. 调 consumeTargetMessage 清空 targetMessageId,避免重复触发
    //
    // 限制:若目标消息在更早的历史中(超出 OBSERVE_LIMIT=200 条),需用户手动上滑加载更多,
    //       本 LaunchedEffect 会超时放弃滚动定位(仅清空 targetMessageId,不触发高亮)。
    //
    // 高亮实现:MessageBubble 的 highlightText 参数,见下方 itemsIndexed 内的调用。
    LaunchedEffect(state.targetMessageId, state.currentSessionId) {
        val targetId = state.targetMessageId ?: return@LaunchedEffect
        if (targetId.isBlank()) return@LaunchedEffect

        // 性能模式下:若 messages 已含目标消息,临时扩展 paginatorPageCount
        // 让 visibleMessages 覆盖全部 messages(跳转场景需看到目标消息)
        if (performanceMode && messages.any { it.id.toString() == targetId }) {
            val pageSize = MessagePaginator.DEFAULT_PAGE_SIZE
            val requiredPages = (messages.size + pageSize - 1) / pageSize
            if (paginatorPageCount < requiredPages) {
                paginatorPageCount = requiredPages
            }
        }

        // 等待 visibleMessages 包含目标消息
        // (超时 5s:switchSession 异步加载 + 性能模式下 visibleMessages 异步计算)
        val targetIndex = withTimeoutOrNull(5000L) {
            snapshotFlow { visibleMessages }
                .filter { list -> list.any { it.id.toString() == targetId } }
                .first()
                .indexOfFirst { it.id.toString() == targetId }
        }
        if (targetIndex == null || targetIndex < 0) {
            // 超时未找到:仅清空 targetMessageId,不触发高亮
            // (消息可能已被删除 / 不在最近 OBSERVE_LIMIT 条内 / 不在当前会话)
            viewModel.consumeTargetMessage()
            return@LaunchedEffect
        }
        // 瞬时滚动到目标消息(长会话用动画会卡顿,且跳转场景需即时定位)
        // v1.0.74 fix (前端审计 1.1): 加消息区起始偏移
        listState.scrollToItem(messageStartIndex + targetIndex)
        // 高亮窗口期 2.5s 后清空 highlightedMessageId + searchHighlightQuery
        delay(2500L)
        viewModel.clearHighlightedMessage()
        // 清空 targetMessageId,避免重复触发
        viewModel.consumeTargetMessage()
    }
    // v1.28: 上次消息数量,用于区分"用户发消息"和"流式增量"
    // v1.45: 用 rememberSaveable 保存,避免切页/后台后重置导致误滚到底部
    var lastMessageCount by rememberSaveable { mutableStateOf(0) }
    // 审计修复 (8.5): 多选删除确认对话框
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // 审计修复 (2.3): 首次组合标记 — 初始 lastMessageCount=0 会把首次进入非空会话
    // 误判为"新消息"触发自动滚底,覆盖 v1.45 恢复位置 / 搜索定位。
    var firstCompositionDone by rememberSaveable { mutableStateOf(false) }

    // v1.52: 滑动跟手优化 — 用户主动上滑后锁定 userScrolledUp=true,
    // 流式增量不再自动拉回底部,直到用户点击"滚到底"按钮或手动滑回底部才解锁。
    // 这解决了"助手发消息时用户上滑被一直拽回生成开始处"的问题。
    var userScrolledUp by rememberSaveable { mutableStateOf(false) }
    // 程序滚动标志:animateScrollToItem 期间设 true,避免在滚动结束回调里误判为用户滚动
    val isProgrammaticScroll = remember { mutableStateOf(false) }

    // 监听滚动结束:仅在"用户触发的滚动"结束时更新锁定状态
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .filter { !it } // 仅在滚动结束( false )时触发
            .collect {
                if (!isProgrammaticScroll.value) {
                    // H-S4: 加一帧延迟(~16ms),避免 isScrollInProgress 跳变瞬间 layoutInfo 仍是滚动中快照
                    delay(16)
                    // 用户滚动结束:根据当前位置更新锁定状态
                    userScrolledUp = !isAtBottom
                }
            }
    }

    // v1.0.30: 预计算流式跟随偏移量（不能在 snapshotFlow 内调 @Composable）
    val density = LocalDensity.current
    val streamFollowOffsetPx = with(density) { 120.dp.roundToPx() }

    // 新消息到来时自动滚到底部(v0.31: 受 chatPrefs.autoScrollToBottom 控制)
    // v0.48: 仅当用户已在底部(isAtBottom)时才自动滚动,用户主动上翻查看历史时不打断
    // v1.28: 用户发消息时(消息数增加)用瞬时滚动(snap),避免动画导致页面跳动;
    //        流式增量用平滑滚动(animate),仅当已在底部时跟随
    // v1.52: 改为 userScrolledUp 标志控制 — 用户上滑一次即停止跟随,
    //        不再依赖 isAtBottom 派生(避免内容增长导致 isAtBottom 误判)。
    // M-CS1: 用 snapshotFlow + sample(100ms) 降频,避免流式 content 变化频繁
    //        取消重启 LaunchedEffect 导致抖动。
    LaunchedEffect(Unit) {
        snapshotFlow {
            Triple(
                messages.size,
                messages.lastOrNull()?.content,
                state.chatPreferences.autoScrollToBottom,
            )
        }
            .distinctUntilChanged()
            .sample(300L)
            .collect { (size, _, autoScroll) ->
                if (size == 0) return@collect
                if (!autoScroll) return@collect
                // 审计修复 (2.3): 首次进入非空会话不自动滚底,
                // 让 v1.45 恢复位置 / 搜索定位生效;只记录基线。
                if (!firstCompositionDone) {
                    firstCompositionDone = true
                    lastMessageCount = size
                    return@collect
                }
                // 快照 visibleMessages,避免 produceState 异步更新导致 guard 与调用之间变空
                val msgs = visibleMessages
                if (msgs.isEmpty()) return@collect
                val targetIndex = msgs.size - 1
                val isUserSendMessage = size > lastMessageCount &&
                    messages.lastOrNull()?.role == MessageRole.USER
                if (isUserSendMessage) {
                    // 用户刚发消息:瞬时滚到底部,并解锁跟随
                    userScrolledUp = false
                    // v1.0.74 fix (前端审计 1.1): 加消息区起始偏移
                    listState.scrollToItem(messageStartIndex + targetIndex)
                } else if (!userScrolledUp) {
                    // v1.0.30: 流式跟随 — 加偏移让消息底部（新文字出现处）保持在可见区
                    isProgrammaticScroll.value = true
                    try {
                        // v1.0.74 fix (前端审计 1.1): 加消息区起始偏移
                        listState.animateScrollToItem(
                            messageStartIndex + targetIndex,
                            scrollOffset = streamFollowOffsetPx,
                        )
                    } finally {
                        isProgrammaticScroll.value = false
                    }
                }
                lastMessageCount = size
            }
    }

    // v0.36 性能优化:modelName 与会话级模型名无关,提到 ChatScreen 作用域避免每个 item 重复查找。
    val modelName = remember(state.providers, state.activeProviderId, state.selectedModelId) {
        state.providers
            .firstOrNull { it.id == state.activeProviderId }?.models
            ?.firstOrNull { it.id == state.selectedModelId }?.name
    }
    // Phase 6: 助手模型指示器 — 解析当前助手绑定的模型简称,用于顶部栏 badge
    val assistantModelShortName = remember(state.currentAssistant, state.providers) {
        state.currentAssistant?.modelId?.let { modelId ->
            state.providers.flatMap { it.models }
                .firstOrNull { it.id == modelId }?.name
                ?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: modelId
        }
    }

    // v0.51: 观察一次性 toast(模型切换提示等),非空时弹 Toast 并立即清空,避免重组重复弹
    LaunchedEffect(state.toast) {
        state.toast?.let { msg ->
            // M-S7: toast 用 runCatching 包裹,避免弹 toast 异常导致 LaunchedEffect 中断
            runCatching { MuseToast.show(msg) }
            viewModel.clearToast()
        }
    }

    // v1.49: 移除 Vosk 离线识别后,语音输入改为两条路径:
    //  - 有 API Key(DashScope/Step):长按录音 + 松开识别 + 上滑取消(走 ChatViewModel API 路径)
    //  - 无 API Key(SYSTEM):长按麦克风 → 松开 → 弹出系统语音识别 Intent → 回调填文本
    val onSpeechResult: (String) -> Unit = { text ->
        if (text.isNotBlank()) {
            val current = viewModel.state.value.input
            val merged = if (current.isBlank()) text else "$current $text"
            viewModel.updateInput(merged)
        }
    }

    // SYSTEM 路径:系统语音识别 Intent launcher(无 API Key 时的 fallback)
    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val text = SpeechInput.parseResult(result.resultCode, result.data?.extras)
        if (text != null) {
            onSpeechResult(text)
        }
    }

    // P6-B2: RECORD_AUDIO 运行时权限申请(API 录音路径需要,SYSTEM Intent 路径由系统处理)
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.startStreamingAsr()
        } else {
            viewModel.reportError(context.getString(R.string.chat_err_mic_permission))
        }
    }

    // P5-E: 文档选择 launcher — 选完后由 ViewModel 读取并解析
    val documentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { viewModel.pickDocument(it, context) }
    }

    // v1.0.30: 发送后自动收起键盘
    val focusManager = LocalFocusManager.current

    // v1.135: 媒体选择 launcher — 同时支持图片和视频。
    // 照片按钮点击后走统一视觉媒体选择器;视频会被提取关键帧降级为图片发送。
    // 前端修复 (持久化-11): OCR 标记改 rememberSaveable,进程重建后选图回调仍按原意图处理
    var imagePickAsOcr by rememberSaveable { mutableStateOf(false) }
    val visualMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        uri?.let {
            when (context.contentResolver.getType(it)?.startsWith("video/")) {
                true -> viewModel.pickVideo(it, context)
                else -> viewModel.pickImage(it, context, imagePickAsOcr)
            }
        }
    }

    // v1.0.74: 聊天背景(聊天/Agent 共用) — 设置页可自定义,背景图铺在消息层下面
    val chatBackground by org.koin.compose.koinInject<io.zer0.muse.data.SettingsRepository>()
        .chatBackgroundFlow
        .collectAsState(initial = null)

    Box(modifier = Modifier.fillMaxSize()) {
        // 背景图(自定义聊天背景)
        if (!chatBackground.isNullOrBlank()) {
            io.zer0.muse.ui.SmartImage(
                model = chatBackground,
                contentDescription = "聊天背景",
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    Scaffold(
        // v1.0.72 fix: 显式清零内容区 insets — 嵌套在 HomeScreen 时,
        //   默认 systemBars insets 会让 Agent 模式(topBar 为空)内容区顶部多出
        //   状态栏高度留白。状态栏/导航栏 padding 由各层自行处理(topBar/InputBar)。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            // v1.24: Agent Tab 模式下隐藏自带顶部栏,减少双层导航栏的间距感
            if (!isAgentMode) {
                // v1.0.72: Telegram 风格顶栏 — 三个独立"岛"(返回/标题/三点菜单)
                // v1.0.72 fix: 去掉全宽背景遮罩 — 三岛直接悬浮在消息列表上,
                //   消息可以滚动到岛后面(与 Telegram 一致),背景透明无遮罩块。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        // v1.0.75 fix (用户反馈): 8dp → 4dp,三岛更贴近状态栏
                        .padding(horizontal = MusePaddings.screen, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                        // ── 左岛:返回按钮(独立圆角胶囊) ──
                        if (onBack != null) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                // v1.0.75 fix (用户反馈): 52dp → 40dp,左右按钮缩小,让位给中间标题
                                modifier = Modifier.size(40.dp),
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize().clickable(onClick = onBack),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = TablerIcons.ArrowLeft,
                                        contentDescription = stringResource(R.string.action_back),
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                                    )
                                }
                            }
                        } else {
                            Spacer(Modifier.width(40.dp))
                        }

                        // ── 中岛:标题(独立圆角胶囊,weight 1f 居中) ──
                        val currentSession = remember(state.sessions, state.currentSessionId) {
                            state.sessions.find { it.id == state.currentSessionId }
                        }
                        val sessionTitle = currentSession?.title?.takeIf { it.isNotBlank() } ?: "muse"
                        val sessionCd = stringResource(R.string.chat_session_cd, sessionTitle)
                        val rawModelName = modelName ?: state.providers
                            .firstOrNull { it.id == state.activeProviderId }?.models
                            ?.firstOrNull()?.name
                            ?: stringResource(R.string.chat_model_not_configured)
                        val currentModelName = rawModelName.substringAfterLast("/").takeIf { it.isNotBlank() } ?: rawModelName
                        val sessionTitleInteractionSource = remember { MutableInteractionSource() }
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            // v1.0.75 fix (用户反馈): 44dp → 48dp,中岛加高放大,与缩小后的左右岛(40dp)拉开层级
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    // v1.136 T1: 点击=切换会话,长按=更换助手
                                    .combinedClickable(
                                        interactionSource = sessionTitleInteractionSource,
                                        indication = null,
                                        onClick = { sheetState.showSessionSheet = true },
                                        onLongClick = { sheetState.showAssistantSwitchSheet = true },
                                    )
                                    .semantics { contentDescription = sessionCd },
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Text(
                                        text = sessionTitle,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                        ),
                                        color = MaterialTheme.colorScheme.onBackground,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    // 关系时长副标题:陪伴 X 天 · 模型名
                                    // v1.0.74 fix: 浏览器胶囊激活时隐藏副标题,避免三行撑高中岛(布局乱)
                                    if (currentSession != null && currentBrowserManager == null) {
                                        val days = (System.currentTimeMillis() - currentSession!!.createdAt) / (24 * 60 * 60 * 1000)
                                        val daysText = if (days <= 0L) {
                                            stringResource(R.string.chat_companion_days_zero)
                                        } else {
                                            stringResource(R.string.chat_companion_days, days.toInt())
                                        }
                                        Text(
                                            text = "$daysText · $currentModelName",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    // AI 调用浏览器时显示状态胶囊:点击可全屏查看实时页面
                                    BrowserStatusCapsule(
                                        manager = currentBrowserManager,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                            }
                        }

                        // ── 右岛:三点菜单(独立圆角胶囊,收纳"选择供应商"/"压缩上下文") ──
                        // v1.0.72: 菜单项改为圆形胶囊样式(自定义 Popup,替代原生 DropdownMenu)
                        val modelCd = stringResource(R.string.chat_model_cd, currentModelName)
                        var showTopMenu by remember { mutableStateOf(false) }
                        Box(
                            // v1.0.75 fix (用户反馈): 52dp → 40dp,左右按钮缩小,让位给中间标题
                            modifier = Modifier.size(40.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clickable(enabled = !isStreaming) { showTopMenu = true }
                                        .semantics { contentDescription = modelCd },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.MoreVert,
                                        contentDescription = stringResource(R.string.chat_top_menu_cd),
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                                    )
                                }
                            }
                            // 自定义胶囊菜单(Popup 定位,两项竖排圆形胶囊)
                            if (showTopMenu) {
                                Popup(
                                    onDismissRequest = { showTopMenu = false },
                                    alignment = Alignment.TopEnd,
                                    offset = IntOffset(0, 4),
                                ) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.surface,
                                        shape = RoundedCornerShape(20.dp),
                                        shadowElevation = 8.dp,
                                        tonalElevation = 4.dp,
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(6.dp),
                                            horizontalAlignment = Alignment.End,
                                        ) {
                                            // 选择供应商(胶囊)
                                            TopMenuCapsule(
                                                icon = Icons.Outlined.AutoAwesome,
                                                text = stringResource(R.string.chat_select_provider),
                                                enabled = !isStreaming,
                                                onClick = {
                                                    showTopMenu = false
                                                    sheetState.showModelSheet = true
                                                },
                                            )
                                            // 压缩上下文(胶囊) — H10: 点击弹参数对话框(保留条数/附加指令/token 估算)
                                            TopMenuCapsule(
                                                icon = TablerIcons.GitMerge,
                                                text = stringResource(R.string.chat_update_compress),
                                                enabled = !isStreaming && !state.isCompressing && messages.size >= 2,
                                                onClick = {
                                                    showTopMenu = false
                                                    compressKeepText = ""
                                                    compressInstruction = ""
                                                    showCompressDialog = true
                                                },
                                            )
                                            // 会话内查找(A1): 呼出就地查找条(顶栏菜单第三项)
                                            TopMenuCapsule(
                                                icon = TablerIcons.Search,
                                                text = stringResource(R.string.chat_find_in_conversation),
                                                enabled = messages.isNotEmpty(),
                                                onClick = {
                                                    showTopMenu = false
                                                    showInChatSearch = true
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
            }
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth()) {
            // 工具审批卡：固定显示在输入栏上方（紧跟用户操作位置，不随消息流滚动）
            state.pendingToolApprovals.forEach { approval ->
                ToolApprovalCard(
                    toolName = approval.toolName,
                    argumentsPreview = approval.argumentsPreview,
                    onApprove = { viewModel.approveToolCall(approval.toolCallId) },
                    onDeny = { reason -> viewModel.denyToolCall(approval.toolCallId, reason) },
                    onPersistPolicy = { policy ->
                        viewModel.persistToolPolicy(approval.toolCallId, policy)
                    },
                    onAllowThisSession = {
                        viewModel.allowToolForSession(approval.toolCallId)
                    },
                    referenceImageOverride = approval.referenceImageOverride,
                    onReferenceImageChange = { dataUri ->
                        viewModel.setToolApprovalReferenceImage(approval.toolCallId, dataUri)
                    },
                )
            }
            // v1.97: 计算工具/任务进度 — 优先用活跃 agentPlan,否则用 toolCallHistory
            val latestPlan = state.agentPlans.values.maxByOrNull { it.createdAt }
            val activePlan = latestPlan?.takeIf { it.steps.isNotEmpty() && !it.isAllSettled }
            val toolCallTotal = activePlan?.totalSteps ?: state.toolCallHistory.size
            val toolCallCompleted = activePlan?.completedSteps
                ?: state.toolCallHistory.count { it.isSuccess }
            // B2: 待发送队列条(流式中排队,逐条预览/编辑/删除/单独发送)
            if (state.sendQueue.isNotEmpty()) {
                PendingQueueBar(
                    queue = state.sendQueue,
                    onSend = viewModel::sendPendingSend,
                    onEdit = viewModel::editPendingSend,
                    onRemove = viewModel::removePendingSend,
                    onClear = viewModel::clearPendingQueue,
                )
            }
            // I3: 输入区独立错误边界,输入渲染数据构建失败只降级输入条
            RegionErrorBoundary(
                regionName = "input",
                data = { state.sendQueue },
            ) {
            RichInputBar(
                // v1.0.20 (Task 3): input/isStreaming 读派生值,避免其他字段变化触发 bottomBar 重组
                text = currentInput,
                isStreaming = isStreaming,
                isWaitingFirstToken = isWaitingFirstToken,
                isDrawMode = state.isDrawMode,
                isWebSearchEnabled = state.webSearchEnabled,
                isDeepThinkingEnabled = state.deepThinkingEnabled,
                // v1.0.47 P5-6: 深度思考级别胶囊(激活时显示,点击循环)
                deepThinkingLevel = state.deepThinkingLevel,
                onCycleDeepThinkingLevel = viewModel::cycleDeepThinkingLevel,
                imageGenParams = state.imageGenParams,
                onImageGenParamsChange = viewModel::updateImageGenParams,
                // v1.0.75 fix: 格式工具条已移除,不再传 formatEnabled
                showExpandButton = state.chatPreferences.showExpandButton,
                onTextChanged = viewModel::updateInput,
                // v1.0.47 P5: 硬件键盘上/下箭头遍历输入历史
                onNavigateInputHistory = viewModel::navigateInputHistory,
                // v1.97: 斜杠命令拦截 — / 开头的输入走 executeSlashCommand,不发送给 LLM
                onSend = {
                    val text = currentInput
                    if (SlashCommand.isSlashCommand(text)) {
                        viewModel.executeSlashCommand(text)
                    } else {
                        viewModel.send()
                        focusManager.clearFocus()
                    }
                },
                onStop = viewModel::stop,
                onInterject = viewModel::interject,
                onEnqueuePending = viewModel::enqueuePendingSend,
                replyingTo = state.replyingTo?.let { r ->
                    // v1.0.72 fix: 引用块用最新消息对象 — 流式消息内容实时更新,
                    // 引用时捕获的旧对象可能 content 为空(第一条消息引用 UI 为空的根因)
                    messages.find { it.id == r.id } ?: r
                },
                onClearReply = { viewModel.setReplyingTo(null) },
                replyQuoteOverride = state.replyQuoteOverride,
                onEditReply = { viewModel.setReplyQuoteOverride(it) },
                onPickDocument = {
                    runCatching {
                        documentLauncher.launch(arrayOf(
                            "text/*",
                            "application/pdf",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "application/msword",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "application/vnd.ms-excel",
                            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                            "application/epub+zip",
                        ))
                    }.onFailure { viewModel.reportError(context.getString(R.string.chat_err_open_file_picker, it.message)) }
                },
                onToggleDrawMode = viewModel::toggleDrawMode,
                onToggleWebSearch = viewModel::toggleWebSearch,
                onToggleDeepThinking = viewModel::toggleDeepThinking,
                // v1.24: Agent 模式在加号工具栏显示"重启上下文",普通会话不显示
                showRestartContext = isAgentMode,
                onRestartContext = viewModel::restartContext,
                // v1.25: 委托给助手入口
                assistants = state.assistants,
                onDelegateToAssistant = { sheetState.showDelegateSheet = DelegateSheetMode.Input },
                // v0.29 P1-6: 知识库 @mention 文档选择 sheet
                onPickKnowledge = { sheetState.showKnowledgeSheet = true },
                onOpenPromptTemplates = { sheetState.showPromptTemplateSheet = true },
                // 加号菜单 → 技能入口
                onOpenSkills = onOpenSkills,
                // v0.31: 回车键发送开关传给 InputBar
                enterToSend = state.chatPreferences.enterToSend,
                // Phase 8.5:快捷消息
                quickMessages = state.quickMessages,
                // Phase 8.6: 多模态图片输入
                pendingImages = state.pendingImages,
                onPickImage = { asOcr ->
                    imagePickAsOcr = asOcr
                    runCatching {
                        visualMediaLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                        )
                    }.onFailure { viewModel.reportError(context.getString(R.string.chat_err_open_image_picker, it.message)) }
                },
                // v0.53: 工具菜单中最近相册图片点击直接加入待发送
                onPickGalleryImage = { uri ->
                    viewModel.pickImage(uri, context, asOcr = false)
                },
                onRemovePendingImage = viewModel::removePendingImage,
                // v1.136 T10: 待发送文档芯片
                pendingDocuments = state.pendingDocuments,
                onRemovePendingDocument = viewModel::removePendingDocument,
                onInsertQuickMessage = { qm ->
                    // Phase 8.5 修复: clipboard 读取切到 IO 线程,避免主线程 IPC ANR
                    ioScope.launch {
                        val clipboard = withContext(Dispatchers.IO) {
                            runCatching {
                                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager
                                cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: ""
                            }.getOrDefault("")
                        }
                        viewModel.insertQuickMessage(qm, clipboard)
                    }
                },
                // Phase 9.3 (M2): ASR 录音
                // v1.49: 移除 Vosk 后,两条路径:
                //  - API 模式(有 apiKey):长按录音 + 松开识别 + 上滑取消
                //  - SYSTEM 模式(无 apiKey):长按麦克风松开后弹系统语音识别 Intent
                isRecording = state.asrState.isRecording,
                asrStatus = state.asrState.status,
                recordingAmplitudes = state.asrState.amplitudes,
                onStartRecording = {
                    if (viewModel.shouldUseApiRecording()) {
                        // API 路径:先检查 RECORD_AUDIO 权限,未授权则申请
                        val granted = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            viewModel.startStreamingAsr()
                            true
                        } else {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            false
                        }
                    } else {
                        // SYSTEM 路径:检查系统语音识别服务是否可用,可用则长按松开后弹 Intent
                        if (!SpeechInput.isAvailable(context)) {
                            // v1.98: 移除弹窗提示,静默处理
                            Logger.w("ChatScreen", "系统语音服务不可用")
                            false
                        } else {
                            // 返回 false:不进入"录音中"状态(系统 Intent 会接管 UI)
                            // 实际 launch 在松手时触发,避免长按期间反复 launch
                            true
                        }
                    }
                },
                onStopRecording = {
                    if (viewModel.shouldUseApiRecording()) {
                        viewModel.stopStreamingAsr()
                    } else {
                        // v1.95: 仅首次使用提示,后续直接调起系统 Intent
                        ioScope.launch {
                            val shown = settings.asrTipShownFlow.first()
                            if (!shown) {
                                sheetState.asrTipDialogShown = true
                                settings.saveAsrTipShown(true)
                            } else {
                                resultOf {
                                    speechLauncher.launch(SpeechInput.createIntent(context.getString(R.string.speech_speak_prompt)))
                                }.onError { msg, _ ->
                                    // v1.98: 移除弹窗提示,静默处理
                                    Logger.w("ChatScreen", "启动语音识别失败: $msg")
                                }
                            }
                        }
                    }
                },
                onCancelRecording = {
                    if (viewModel.shouldUseApiRecording()) viewModel.cancelStreamingAsr()
                    // SYSTEM 路径无取消概念(尚未 launch Intent)
                },
                // v1.97: 仅在用户配置了 ASR API 后才显示麦克风 UI
                showMic = viewModel.shouldUseApiRecording(),
                // v1.97: 工具/任务进度 pill(优先用 plan 进度,否则用 toolCallHistory)
                toolCallCompleted = toolCallCompleted,
                toolCallTotal = toolCallTotal,
                onShowToolCalls = { sheetState.showToolCallSheet = true },
                // 功能2: 草稿标记
                hasDraft = state.hasDraft,
                // 语音对话模式入口:点击进入全屏连续对话
                onOpenVoiceConversation = { sheetState.showVoiceConversation = true },
                // v1.0.29: Agent Tab 不主动呼出输入法
                autoFocus = !isAgentMode,
                // v1.0.47 P5-3: Token 估算(默认关闭,设置页开启后输入栏底部显示 Token 统计条)
                tokenEstimateEnabled = state.tokenEstimateEnabled,
                historyTokens = state.contextTokenCount,
                contextWindow = state.contextMaxTokens,
                // v1.0.47 P5-2: 长文本粘贴转文件(默认开启,粘贴超阈值文本时提示转为附件)
                pasteAsFileEnabled = state.pasteAsFileEnabled,
                pasteAsFileThreshold = state.pasteAsFileThreshold,
                onAddPastedTextAsDocument = viewModel::addPastedTextAsDocument,
            )
            } // I3: 输入区错误边界收尾
            }
        },
        containerColor = if (chatBackground.isNullOrBlank()) {
            MaterialTheme.colorScheme.background
        } else {
            androidx.compose.ui.graphics.Color.Transparent
        },
    ) { innerPadding ->
        val scrollToBottomScope = rememberCoroutineScope()
        // P2-13: 桌面端快捷键拦截 — Ctrl+Shift+C 复制最后一条 AI 回复
        // Enter/Shift+Enter 由 InputBar 自身处理(已在 v0.31 实现 enterToSend 逻辑),
        // 此处不重复拦截,避免破坏既有用户设置("回车发送" / "Shift+回车发送")。
        val copyLastReplyClipboardScope = rememberCoroutineScope()
        val copyLastReply: () -> Unit = {
            val lastAssistant = messages.lastOrNull {
                it.role == MessageRole.ASSISTANT && it.content.isNotBlank()
            }
            if (lastAssistant != null) {
                copyLastReplyClipboardScope.launch {
                    val clipboard = withContext(Dispatchers.IO) {
                        context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                    }
                    clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText("Muse Reply", lastAssistant.content)
                    )
                    MuseToast.show(context.getString(R.string.chat_copied_toast))
                }
            }
            // 无 AI 回复时静默不操作(避免引入未本地化的 toast 字符串)
        }
        // v1.0.72: 顶部悬浮岛高度 — 提示/横幅 overlay 让位,避免被三岛遮挡
        val topInset = innerPadding.calculateTopPadding()
        Box(
            modifier = Modifier
                .fillMaxSize()
                // v1.0.72 fix: 去掉 top padding — 内容区延伸到顶部三岛后面,
                //   消息列表可以滚动到悬浮岛后面(Telegram 效果),底部 padding 保留
                .padding(bottom = innerPadding.calculateBottomPadding())
                // P2-13: 桌面端快捷键 — Ctrl+Shift+C 复制最后一条 AI 回复
                // 仅在物理键盘 + Expanded 窗口下生效,避免与软键盘 IME Action 冲突
                .onKeyEvent { event ->
                    if (!desktopShortcutsEnabled) return@onKeyEvent false
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    if (event.key == DesktopShortcuts.COPY_LAST_REPLY &&
                        event.isCtrlPressed && event.isShiftPressed
                    ) {
                        copyLastReply()
                        true
                    } else {
                        false
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // B7-01: 多选操作条
            if (state.selectionMode) {
                // A4: 选中消息文本 — 批量复制/导出共用同一格式
                val selectedText = visibleMessages
                    .filter { it.id.toString() in state.selectedMessageIds }
                    .joinToString("\n\n") { "${it.role}: ${it.content}" }
                ChatSelectionBar(
                    count = state.selectedMessageIds.size,
                    onSelectAll = { viewModel.selectAllMessages(visibleMessages.map { it.id.toString() }) },
                    onCopy = {
                        if (selectedText.isNotBlank()) {
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("muse-selected", selectedText))
                        }
                    },
                    // 审计修复 (8.5): 删除前弹确认 — 原实现直删,误触即丢整段对话不可恢复
                    onDelete = { showDeleteConfirm = true },
                    onExport = {
                        if (selectedText.isNotBlank()) {
                            val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, selectedText)
                            }
                            context.startActivity(android.content.Intent.createChooser(sendIntent, null))
                        }
                    },
                    onExit = { viewModel.setSelectionMode(false) },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        // v1.0.72: 让位悬浮三岛(不遮挡)
                        .padding(top = topInset)
                        .fillMaxWidth()
                        .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap)
                        .zIndex(10f),
                )
                // 审计修复 (8.5): 多选删除确认对话框
                if (showDeleteConfirm) {
                    MuseDialog(
                        onDismissRequest = { showDeleteConfirm = false },
                        title = stringResource(R.string.chat_delete_selected_title),
                        content = {
                            Text(stringResource(R.string.chat_delete_selected_message, state.selectedMessageIds.size))
                        },
                        confirmText = stringResource(R.string.action_delete),
                        destructive = true,
                        onConfirm = {
                            showDeleteConfirm = false
                            viewModel.deleteSelectedMessages()
                        },
                    )
                }
                // H10: 手动压缩参数对话框(保留条数 / 附加指令 / token 估算)
                if (showCompressDialog) {
                    CompressContextDialog(
                        defaultKeepRecent = 10,
                        totalMessages = messages.size,
                        estimateTokens = { keep -> messages.takeLast(keep).sumOf { it.content.length / 2 } },
                        onDismiss = { showCompressDialog = false },
                        onConfirm = { keep, instruction ->
                            showCompressDialog = false
                            viewModel.manualCompress(
                                updateMemoryFirst = true,
                                keepRecent = keep,
                                instruction = instruction.ifBlank { null },
                            )
                        },
                    )
                }
            }
            // Phase 3 3E: 定时消息横幅
            io.zer0.muse.ui.chat.ScheduledMessageBanner(
                pendingMessages = pendingMessages,
                onCancel = { msgId ->
                    pendingScope.launch { pendingMessageManager.cancelMessage(msgId) }
                },
            )
            // 空状态与消息列表 Crossfade 过渡,避免硬切换
            // v1.0.4 (P3-4): 用 visibleMessages 判空,性能模式下 visibleMessages 反映实际渲染状态
            // v1.0.48: 修复 Agent Tab 进入时闪烁空状态 — HorizontalPager 动画期间目标页已 compose
            //   但 setAgentMode 尚未执行(settledPage 触发),此时 visibleMessages=emptyList() 会闪现
            //   "今天想聊点什么"引导。新增 loading 中间态:isAgentMode && !state.isAgentMode 期间
            //   显示 loading 而非空状态,等 ViewModel 切换完成后再渲染消息或真正的空状态。
            val isAgentTabLoading = isAgentMode && !state.isAgentMode
            val chatScreenState = when {
                isAgentTabLoading -> 2  // Agent Tab 加载中
                visibleMessages.isEmpty() -> 1  // 真正空会话
                else -> 0  // 有消息
            }
            Crossfade(
                targetState = chatScreenState,
                animationSpec = tween(300),
                label = "chatState",
                modifier = Modifier.fillMaxSize(),
            ) { screenState ->
                // v1.0.72: 当前会话"不参考记忆"标志(空白引导页开关用)
                val currentSessionIgnoreMemory = remember(state.sessions, state.currentSessionId, state.agentSessionId) {
                    val sid = if (state.isAgentMode) state.agentSessionId else state.currentSessionId
                    state.sessions.firstOrNull { it.id == sid }?.ignoreMemory ?: false
                }
                if (screenState == 2) {
                    // Agent Tab 加载中 — 显示 loading,不闪空状态引导
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        MuseLoadingState()
                    }
                } else if (screenState == 1) {
                    // 空状态引导 — 居中轻量提示 + 建议 prompt 胶囊(不遮罩,点击填入输入框)
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyChatGuide(
                            onPickPrompt = { prompt ->
                                viewModel.updateInput(prompt)
                            },
                            assistant = state.currentAssistant,
                            // v1.0.72: 此条对话不参考记忆
                            ignoreMemory = currentSessionIgnoreMemory,
                            onToggleIgnoreMemory = { ignore ->
                                viewModel.setSessionIgnoreMemory(ignore)
                            },
                            modifier = Modifier.padding(horizontal = MusePaddings.largeGap),
                        )
                    }
                } else {
                // Phase 8.10: 音量键滚动
                // 拦截 VOLUME_UP/DOWN → listState.scrollBy,长聊天阅读体验提升
                val volumeScrollScope = rememberCoroutineScope()
                // H-S6: latestPlan 在 LazyColumn 外缓存(remember 不能在 LazyListScope 内调用)
                val latestPlan = remember(state.agentPlans) {
                    state.agentPlans.values.maxByOrNull { it.createdAt }
                }
                // v1.137: 构建 messageId → plan 映射,让每条助手消息能找到关联自己的计划卡。
                // 计划卡固定在创建它的消息上随消息滚动,不再"跳"到最后一条助手消息。
                val plansByMessageId = remember(state.agentPlans) {
                    state.agentPlans.values
                        .filter { it.messageId != null }
                        .associateBy { it.messageId!! }
                }
                // M-UI3: 将最新计划卡关联到最近一条助手消息,随消息一起滚动
                val lastAssistantId by remember {
                    derivedStateOf { visibleMessages.lastOrNull { it.role == MessageRole.ASSISTANT }?.id }
                }
                // I3: 聊天区独立错误边界,消息列表渲染数据构建失败只降级该区域
                RegionErrorBoundary(
                    regionName = "chat",
                    data = { visibleMessages },
                ) {
                // P2-1: Box 包裹消息列表,Expanded 模式下居中限宽 720dp
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                // B6-02: 全屏情绪皮肤(在消息列表背后)
                MoodSkinOverlay(visibleMessages.lastOrNull { it.role == MessageRole.ASSISTANT }?.moodSkin)
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (widthClass == WindowWidthClass.Expanded) {
                                Modifier.widthIn(max = 720.dp)
                            } else {
                                Modifier
                            }
                        )
                        // M-CS4: 横向 padding 替换为 MusePaddings.screen
                        .padding(horizontal = MusePaddings.screen)
                        // v0.31: 音量键滚动受 chatPrefs.volumeKeyScroll 开关控制
                        .then(
                            if (state.chatPreferences.volumeKeyScroll) {
                                Modifier.onVolumeKeyEvent { direction ->
                                    volumeScrollScope.launch {
                                        listState.scrollBy(direction * VOLUME_SCROLL_DISTANCE_PX)
                                    }
                                }
                            } else {
                                Modifier
                            }
                        ),
                    // M-CS5: 消息间距用 MusePaddings.messageGap 令牌(iOS 风格呼吸感)
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.messageGap),
                    // v1.0.72: 顶部让位给悬浮三岛(滚动到底时消息在岛下方,
                    //   滚到顶时消息可进入岛后面),底部保留常规间距
                    contentPadding = PaddingValues(top = innerPadding.calculateTopPadding(), bottom = MusePaddings.screen),
                ) {
                    // v1.0.47 P6: Agent Mode 提示卡片 — 会话锁定/弱工具降级/Agent Mode 提示。
                    // v1.0.54: 去掉"Agent 模式已锁定会话"提示(用户反馈不需要),仅保留降级/提示。
                    val showAgentHint =
                        !state.weakToolHint.isNullOrEmpty() ||
                            !state.agentModeHint.isNullOrEmpty()
                    if (showAgentHint) {
                        item(key = "agent_mode_hint") {
                            AgentModeHintCard(
                                isSessionLocked = false,
                                weakToolHint = state.weakToolHint,
                                agentModeHint = state.agentModeHint,
                                onDismissWeakToolHint = viewModel::dismissWeakToolHint,
                                onDismissAgentModeHint = viewModel::dismissAgentModeHint,
                            )
                        }
                    }
                    // v1.0.4 (P1): 历史加载更多顶部占位 — 上滑触发 loadMoreHistory 后,
                    // 在 LazyColumn 顶部插入一条 shimmer 占位条,让用户看到"正在加载"反馈。
                    // 加载完成后 lastHistoryLoadCount > 0,scrollToItem 跳过新插入条数保持视觉位置不跳。
                    if (state.isLoadingMore) {
                        item(key = "load_more") { HistoryLoadMorePlaceholder() }
                    }
                    itemsIndexed(
                        // v1.0.4 (P3-4): 性能模式下渲染 visibleMessages(最近 N 条);
                        // 非性能模式下 visibleMessages == messages,行为不变。
                        visibleMessages,
                        key = { _, it -> it.id },
                        // v1.100: contentType 让 LazyColumn 复用同类型 item 的 measure cache
                        contentType = { _, it -> it.role.name },
                    ) { index, msg ->
                        // 日期分隔线: 相邻消息跨天时插入细线 + 居中日期文字
                        // v1.0.4 (P3-4): prevMsg 取自 visibleMessages,与渲染顺序一致
                        val prevMsg = visibleMessages.getOrNull(index - 1)
                        val showDateSeparator = prevMsg != null &&
                            !isSameDay(prevMsg.createdAt, msg.createdAt)
                        // v1.100: 用 derivedStateOf 收窄 state 读取范围,避免每次 messages 变化
                        // 都重新计算所有可见 item 的 isLast。只有最后一条消息变化时才重组。
                        // v1.0.4 (P3-4): isLast 基于 visibleMessages,性能模式下指"已渲染列表的最后一条"
                        val isLast by remember { derivedStateOf { msg.id == visibleMessages.lastOrNull()?.id } }
                        // v1.0.53: 当前消息对应的分支组信息(直接来自 ConversationTree)
                        val branchInfo by remember(msg.id) {
                            derivedStateOf { conversationTree.branchInfoFor(msg.id) }
                        }
                        // v1.100: expandedState 用 derivedStateOf 包裹,只有该 msg 对应的
                        // 展开状态变化时才重组,避免其他消息的折叠操作波及本 item。
                        val expandedState by remember(msg.id) {
                            derivedStateOf { state.messageExpandedStates[msg.id.toString()] }
                        }
                        // v1.100: taskCard 同样用 derivedStateOf 隔离,只有该 msg 对应的
                        // taskCard 变化时才重组,避免其他工具调用更新波及本 item。
                        val taskCard by remember(msg.id) {
                            derivedStateOf { state.taskCards[msg.id.toString()] }
                        }
                        // v1.100: isTranslating/isSpeaking 精确到 msg.id,用 derivedStateOf 收窄
                        val isTranslating by remember(msg.id) {
                            derivedStateOf { state.isTranslating && state.translatingMessageId == msg.id }
                        }
                        val isSpeaking by remember(msg.id) {
                            derivedStateOf { state.isSpeaking && state.speakingMessageId == msg.id }
                        }
                        // v1.43: 观察该消息关联的产物卡片列表
                        // H-S1: 用 produceState 以 msg.id 为 key,避免重组时反复重建 Flow + 反复查库
                        val artifacts by produceState(initialValue = emptyList<ArtifactEntity>(), msg.id) {
                            viewModel.observeArtifactsByMessage(msg.id.toString()).collect { value = it }
                        }
                        // v0.48: 消息分组 — 上一条同 role 且时间间隔 < 5 分钟 → 压缩头像和时间戳
                        // v1.0.30: assistant 消息始终显示头像，不参与分组压缩
                        val showAvatar = msg.role == MessageRole.ASSISTANT || prevMsg == null
                            || prevMsg.role != msg.role
                            || (msg.createdAt - prevMsg.createdAt) > MESSAGE_GROUP_INTERVAL_MS
                        val showTimestamp = showAvatar // 头像和时间戳同步显示
                        // v0.36 性能优化:缓存 item 级 lambda,避免父重组导致整个 MessageBubble 失效。
                        val onEdit = remember(msg.id, msg.role) {
                            {
                                if (msg.role == MessageRole.USER) {
                                    sheetState.editingUserMessage = msg
                                } else {
                                    sheetState.editingMessage = msg
                                }
                            }
                        }
                        val onQuote = remember(msg.id) { { viewModel.setReplyingTo(msg) } }
                        val onTranslate = remember(msg.id) { { lang: String -> viewModel.translateMessage(msg.id, lang) } }
                        val onToggleFavorite = remember(msg.id) { { viewModel.toggleFavorite(msg.id) } }
                        val onToggleTts = remember(msg.id) { { viewModel.toggleTts(msg.id, msg.content) } }
                        val onToggleTaskCardExpand = remember(msg.id) { { viewModel.toggleTaskCardExpand(msg.id.toString()) } }
                        val onCancelTask = remember(msg.id) { { viewModel.stop() } }
                        val onRetryTaskCardStep = remember(msg.id) { { stepId: String -> viewModel.retryFailedStep(msg.id.toString(), stepId) } }
                        val onShareSession = remember(viewModel, ioScope) {
                            { sheetState.showExportSheet = true }
                        }
                        // v1.58: 从此消息分叉对话
                        val onFork = remember(msg.id) { { viewModel.forkSessionFromMessage(msg.id) } }
                        // 消息项动画:新增/移除/重排时平滑过渡(MuseAnimation 令牌)
                        // A3 (H1): reduced-motion 时降级为 0 时长(立即显示,不播放淡入)
                        Column(modifier = Modifier.animateItem(
                            fadeInSpec = if (reducedMotion) tween(0) else tween(
                                MuseAnimation.SLOW_MS,
                                easing = MuseAnimation.EaseOutCubic,
                            ),
                            placementSpec = if (reducedMotion) tween(0) else tween(
                                MuseAnimation.NORMAL_MS,
                                easing = MuseAnimation.EaseOutCubic,
                            ),
                        )) {
                        // 日期分隔线渲染在消息上方
                        if (showDateSeparator) {
                            DateSeparator(timestamp = msg.createdAt)
                        }
                        // B7-06: 消息左右滑快捷操作。
                        // v1.0.72: 取消"右滑编辑"(用户决策,没什么用),仅保留"左滑引用"。
                        val messageSwipeState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                when (value) {
                                    SwipeToDismissBoxValue.EndToStart -> {
                                        onQuote()
                                        false
                                    }
                                    else -> false
                                }
                            },
                        )
                        SwipeToDismissBox(
                            state = messageSwipeState,
                            // v1.0.72: 右滑编辑已取消,仅保留左滑引用
                            enableDismissFromStartToEnd = false,
                            enableDismissFromEndToStart = !state.selectionMode,
                            backgroundContent = {
                                val direction = messageSwipeState.dismissDirection
                                val icon = when (direction) {
                                    SwipeToDismissBoxValue.EndToStart -> TablerIcons.MessageCircle
                                    else -> null
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize(),
                                    contentAlignment = when (direction) {
                                        SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                                        else -> Alignment.Center
                                    },
                                ) {
                                    if (icon != null) {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = MusePaddings.screen),
                                        )
                                    }
                                }
                            },
                        ) {
                        MessageBubble(
                            msg = msg,
                            // v1.0.20 (Task 3): isStreaming 读派生值,避免每条消息因 input 按键重组
                            isStreaming = isStreaming,
                            isLastAssistant = isLast && msg.role == MessageRole.ASSISTANT,
                            // v2.x: 从搜索结果跳转时,state.highlightedMessageId 命中本消息 →
                            // 传 searchHighlightQuery 让 MessageBubble 高亮匹配文本;否则 null
                            highlightText = if (msg.id.toString() == state.highlightedMessageId) state.searchHighlightQuery else null,
                            isTranslating = isTranslating,
                            // H11: 译文消息携带源消息内容(原文对照折叠),源消息缺失时不传
                            translationSourceContent = msg.translationSourceId?.let { srcId ->
                                messages.find { it.id.toString() == srcId }?.content
                            },
                            // v2.3: debug 模式性能摘要(仅最后一条 assistant 消息)
                            debugInfo = if (isLast && msg.role == MessageRole.ASSISTANT) state.debugInfo else null,
                            onEdit = onEdit,
                            onQuote = onQuote,
                            onRegenerate = viewModel::regenerateLastAssistant,
                            onContinue = viewModel::continueGeneration,
                            selectionMode = state.selectionMode,
                            selected = msg.id.toString() in state.selectedMessageIds,
                            onToggleSelection = { viewModel.toggleMessageSelection(msg.id) },
                            onEnterMultiSelect = { viewModel.setSelectionMode(true) },
                            onTranslate = onTranslate,
                            onToggleFavorite = onToggleFavorite,
                            // 阶段 J: 复制消息内容到剪贴板(iOS 风格长按 → 复制)
                            // M-S8: clipboard 写切到 IO 线程,避免主线程 IPC
                            onCopyMessage = { text ->
                                ioScope.launch {
                                    val clipboard = withContext(Dispatchers.IO) {
                                        context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                            as android.content.ClipboardManager
                                    }
                                    clipboard.setPrimaryClip(
                                        android.content.ClipData.newPlainText("Muse Message", text)
                                    )
                                    MuseToast.show(context.getString(R.string.chat_copied_toast))
                                }
                            },
                            // Phase 8.7: TTS 朗读(仅 AI 消息)
                            isSpeaking = isSpeaking,
                            onToggleTts = onToggleTts,
                            // Phase 8.8: 任务卡
                            taskCard = taskCard,
                            // v1.201: 委派链路(仅最后一条 AI 消息传入,避免历史消息重复显示)
                            delegationChain = if (isLast && msg.role == MessageRole.ASSISTANT) state.delegationChain else null,
                            // Phase 10.1: 任务卡交互回调
                            onToggleTaskCardExpand = onToggleTaskCardExpand,
                            onRetryTaskCardStep = onRetryTaskCardStep,
                            // R-UI-09: 任务卡取消按钮 -> 停止当前生成
                            onCancelTask = onCancelTask,
                            // v1.25: 长按菜单「委托给助手」
                            onDelegate = { sheetState.showDelegateSheet = DelegateSheetMode.Message(msg) },
                            // v0.29 P0-3: 分享整段对话(导出 Markdown → 系统 share sheet)
                            onShareSession = onShareSession,
                            onFork = onFork,
                            // v1.48: 长按菜单"删除消息"
                            onDeleteMessage = { viewModel.deleteMessage(msg.id) },
                            // v0.29 P0-4: AI 消息底部显示模型名 + token 估算
                            modelName = modelName,
                            // v0.31: 聊天行为偏好传给 MessageBubble
                            chatPrefs = state.chatPreferences,
                            // v0.48: 消息分组参数 + AI 头像来源
                            showAvatar = showAvatar,
                            showTimestamp = showTimestamp,
                            assistant = state.currentAssistant,
                            // v1.43: 产物卡片列表与点击查看
                            artifacts = artifacts,
                            onArtifactClick = viewModel::selectArtifact,
                            // v1.45: mood/reasoning 展开状态由 ViewModel 集中管理
                            isMoodExpanded = expandedState?.isMoodExpanded,
                            isReasoningExpanded = expandedState?.isReasoningExpanded,
                            isReflectionExpanded = expandedState?.isReflectionExpanded,
                            onToggleMoodExpanded = { viewModel.toggleMessageMoodExpanded(msg.id.toString()) },
                            onToggleReasoningExpanded = { viewModel.toggleMessageReasoningExpanded(msg.id.toString()) },
                            onToggleReflectionExpanded = { viewModel.toggleMessageReflectionExpanded(msg.id.toString()) },
                            // v1.137: 计划卡按 messageId 关联到创建它的助手消息,随该消息滚动。
                            // 旧计划(无 messageId)回退到 lastAssistantId 兜底,保持向后兼容。
                            agentPlan = if (msg.role == MessageRole.ASSISTANT) {
                                plansByMessageId[msg.id.toString()]
                                    ?: if (msg.id == lastAssistantId && latestPlan?.messageId == null) latestPlan else null
                            } else null,
                            // HTML/SVG 代码块全屏预览
                            onHtmlPreview = onHtmlPreview,
                            // v1.138: 视觉辅助 UI — 分析中进度 + 已完成标签
                            // v1.0.16: 进度只在正在分析的那条消息上显示(messageId 匹配),避免所有 USER 消息同时显示"分析中"
                            // v1.0.20 (Task 3): visionProgress 读派生值,避免每条消息因 input 按键重组
                            visionAssistProgress = if (
                                msg.role == MessageRole.USER &&
                                visionProgress?.messageId == msg.id.toString()
                            ) visionProgress else null,
                            visionAssisted = if (msg.role == MessageRole.USER) msg.id.toString() in state.visionAssistedMessageIds else false,
                            // v1.0.53: 最后一条标记 + 分支切换数据
                            isLast = isLast,
                            branchIndex = branchInfo?.selectIndex ?: 0,
                            branchCount = branchInfo?.branchCount ?: 1,
                            onBranchPrevious = {
                                branchInfo?.let { info ->
                                    if (msg.role == MessageRole.USER) {
                                        viewModel.selectUserVariant(info.groupId, info.selectIndex - 1)
                                    } else {
                                        viewModel.selectAssistantVariant(info.parentGroupId ?: info.groupId, info.groupId, info.selectIndex - 1)
                                    }
                                }
                            },
                            onBranchNext = {
                                branchInfo?.let { info ->
                                    if (msg.role == MessageRole.USER) {
                                        viewModel.selectUserVariant(info.groupId, info.selectIndex + 1)
                                    } else {
                                        viewModel.selectAssistantVariant(info.parentGroupId ?: info.groupId, info.groupId, info.selectIndex + 1)
                                    }
                                }
                            },
                            tokenStats = if (isLast && msg.role == MessageRole.ASSISTANT && state.tokenEstimateEnabled) {
                                {
                                    TokenStatsBar(
                                        messageText = msg.content,
                                        historyTokens = state.contextTokenCount,
                                        contextWindow = state.contextMaxTokens,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = MusePaddings.tightGap),
                                    )
                                }
                            } else {
                                null
                            },
                        )
                        }
                        }
                    }
                    // 任务 2B: 等待首 token 阶段用 shimmer 骨架屏占位(替代旧 LoadingDots "思考中"文字)
                    // v1.0.3: 改用 isWaitingFirstToken 触发,首 token 到达后立即消失,避免"loading → 大量文字"断层
                    // v1.0.4: 视觉分析期间显示"正在分析图片 2/4…"
                    // v1.0.4 (P0): OCR 识别 / 工具调用恢复 也复用 ShimmerBubble,统一所有"短暂等待"反馈
                    // v1.0.20 (Task 3): isStreaming/isWaitingFirstToken/visionProgress 读派生值
                    val showShimmer = state.isOcrProcessing ||
                        (isStreaming && isWaitingFirstToken)
                    if (showShimmer) {
                        // H-S5: 显式提供稳定 key
                        item(key = "shimmer") {
                            // 优先级:工具恢复 > 视觉分析 > OCR 识别 > 默认"思考中"
                            val vp = visionProgress
                            val progressText = when {
                                state.toolProgressMessage != null -> state.toolProgressMessage
                                vp?.isActive == true ->
                                    "正在分析图片 ${vp.index}/${vp.total}…"
                                state.isOcrProcessing -> stringResource(R.string.ocr_processing_hint)
                                else -> null
                            }
                            ShimmerBubble(progressText = progressText)
                        }
                    }
                    // P5-G: 图片生成中占位卡片(比纯文字 LoadingDots 更有反馈感)
                    if (state.isGeneratingImage) {
                        // H-S5: 显式提供稳定 key
                        item(key = "image_placeholder") { ImageGenerationPlaceholder() }
                    }
                    // v1.0.4 (P1): 视频生成中占位卡片(与图片生成对称)
                    if (state.isGeneratingVideo) {
                        item(key = "video_placeholder") { VideoGenerationPlaceholder() }
                    }
                    // 工具审批卡片:待审批的工具调用显示审批/拒绝按钮
                    // v1.202: 后台子 Agent 任务列表卡片(非阻塞委派进度展示)
                    // 渲染当前会话活跃的子 agent 线程 + 待处理任务,提供取消入口。
                    // 数据由 ChatViewModel 订阅 SubagentThreadStore + DeferredResultStore 后写入 UiState。
                    item(key = "subagent_task_list") {
                        io.zer0.muse.ui.taskcard.SubagentTaskListCard(
                            activeThreads = state.activeSubagentThreads,
                            pendingTasks = state.pendingSubagentTasks,
                            onCancel = { taskId -> viewModel.cancelSubagentTask(taskId) },
                        )
                    }
                }
                }
                }
                } // I3: 聊天区错误边界收尾
            }

            // v1.0.4 (P3-4): 性能模式指示器 — 仅当开启性能模式且 visibleMessages 未覆盖全部
            // messages 时显示"已显示 X / Y 条",让用户感知到分页加载的存在。
            // 滚到顶部会自动扩展 paginatorPageCount,X 增大;全部展开后 X == Y,指示器隐藏。
            if (performanceMode && visibleMessages.size < messages.size) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = MuseShapes.extraLarge,
                    tonalElevation = 2.dp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = MusePaddings.contentGap),
                ) {
                    Text(
                        text = stringResource(
                            R.string.chat_performance_indicator,
                            visibleMessages.size,
                            messages.size,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = MusePaddings.itemGap, vertical = MusePaddings.tinyGap),
                    )
                }
            }

            // v1.0.29: 滚动到底部按钮 — 改为 GPT 风格小圆形透明按钮,
            // 仅在用户主动上滑(userScrolledUp)后显示,位于输入栏上方。
            AnimatedVisibility(
                visible = userScrolledUp && visibleMessages.isNotEmpty(),
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = MusePaddings.screen)
                    .navigationBarsPadding(),
            ) {
                Box(
                    modifier = Modifier
                        .size(MuseIconSizes.touchTarget)
                        .clickable {
                            userScrolledUp = false
                            isProgrammaticScroll.value = true
                            scrollToBottomScope.launch {
                                val msgs = visibleMessages
                                if (msgs.isEmpty()) return@launch
                                try {
                                    // v1.0.74 fix (前端审计 1.1): 加消息区起始偏移
                                    listState.animateScrollToItem(messageStartIndex + msgs.size - 1)
                                } finally {
                                    isProgrammaticScroll.value = false
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        shape = CircleShape,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowDownward,
                                contentDescription = stringResource(R.string.chat_scroll_to_bottom_cd),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(MuseIconSizes.iconSmall),
                            )
                        }
                    }
                }
            }

            // 断点续传(工具中断恢复)Banner:检测到本会话有未完成的工具调用时显示
            // 用户上次流式被中断(手动停止/进程被杀),tool_calls 队列未执行完毕。
            // 提供两个操作:
            //  - 恢复执行:调 viewModel.resumePendingToolCalls 依次执行 pending 工具,
            //    结果作为 TOOL 消息回填,再触发 launchStream 让 LLM 继续
            //  - 丢弃:调 viewModel.discardPendingToolCalls 清空 pending 记录,Banner 隐藏
            AnimatedVisibility(
                // v1.0.20 (Task 3): isStreaming 读派生值,避免 input 按键触发 Banner 重组
                visible = state.pendingToolCallCount > 0 && !isStreaming,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = topInset),
            ) {
                val pendingCd = stringResource(R.string.chat_pending_tools_cd)
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MuseShapes.medium,
                    tonalElevation = 3.dp,
                    modifier = Modifier
                        .padding(MusePaddings.itemGap)
                        .semantics { contentDescription = pendingCd },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(MusePaddings.itemGap),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                    ) {
                        Icon(
                            imageVector = TablerIcons.AlertCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.chat_pending_tools_banner_title,
                                    state.pendingToolCallCount,
                                ),
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(R.string.chat_pending_tools_banner_subtitle),
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(
                            onClick = {
                                state.currentSessionId?.let { viewModel.resumePendingToolCalls(it) }
                            },
                        ) {
                            Text(stringResource(R.string.chat_pending_tools_resume))
                        }
                        TextButton(
                            onClick = {
                                state.currentSessionId?.let { viewModel.discardPendingToolCalls(it) }
                            },
                        ) {
                            Text(stringResource(R.string.chat_pending_tools_discard))
                        }
                    }
                }
            }

            // v1.0.4 (P2): 压缩会话历史 Banner — /compact 期间持续显示,
            // (原仅顶部 IconButton 替换为转圈,对话区无反馈,用户不知道压缩是否在运行)
            AnimatedVisibility(
                visible = state.isCompressing,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = topInset),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MuseShapes.medium,
                    tonalElevation = 3.dp,
                    modifier = Modifier.padding(MusePaddings.itemGap),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(MusePaddings.itemGap),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Text(
                            text = stringResource(R.string.chat_compressing_banner),
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            // v1.0.4 (P2): 委派链路顶部 Banner — 当前有 RUNNING 子任务时显示进度,
            // 避免用户必须滚到末尾才能在 TaskCard 内看到委派链路信息
            val runningDelegateCount = state.delegationChain.count {
                it.status == io.zer0.muse.ui.taskcard.DelegationNodeStatus.RUNNING
            }
            AnimatedVisibility(
                visible = runningDelegateCount > 0 && !state.isCompressing,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = topInset),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MuseShapes.medium,
                    tonalElevation = 3.dp,
                    modifier = Modifier.padding(MusePaddings.itemGap),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(MusePaddings.itemGap),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                    ) {
                        Icon(
                            imageVector = TablerIcons.GitMerge,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                        Text(
                            text = stringResource(R.string.chat_delegation_banner, runningDelegateCount),
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            // v0.49: 多错误列表展示(每条带重试/关闭按钮,AnimatedVisibility 过渡)
            // v1.131: 红色网络离线 banner 从底部移到顶部,避免遮挡输入栏
            AnimatedVisibility(
                visible = state.errors.isNotEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = topInset),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(MusePaddings.itemGap),
                    ) {
                        state.errors.forEach { err ->
                            // L-S4: forEach 内加 key,提供稳定标识
                            key(err.id) {
                                val errorCd = stringResource(R.string.chat_error_cd, err.message)
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    shape = MuseShapes.medium,
                                    tonalElevation = 3.dp,
                                    modifier = Modifier.semantics {
                                        contentDescription = errorCd
                                    },
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(MusePaddings.itemGap),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                                    ) {
                                        Text(
                                            text = err.message,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f),
                                        )
                                        // 重试:基于上一条 user 消息重新生成 assistant 回复
                                        // M-S11: 仅网络/未知类错误显示重试按钮(API_KEY/RATE_LIMIT 重试无意义)
                                        if (err.type == ChatErrorType.NETWORK || err.type == ChatErrorType.UNKNOWN) {
                                            TextButton(onClick = { viewModel.regenerateLastAssistant() }) {
                                                Text(stringResource(R.string.chat_retry))
                                            }
                                        }
                                        TextButton(onClick = { viewModel.dismissError(err.id) }) {
                                            Text(stringResource(R.string.action_close))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // v1.4: TTS 悬浮控制器(底部右下角,InputBar 上方)
            // 仅当 TTS 正在播放/暂停时显示(Idle 时由 AnimatedVisibility 自动隐藏)
            TtsControllerWidget(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = MusePaddings.screen, bottom = MusePaddings.screen)
                    .navigationBarsPadding(),
            )
            // A1: 会话内查找条 — 顶层悬浮(消息列表之上、顶栏三岛之下,自带背景 Surface)
            if (showInChatSearch) {
                InChatFindBar(
                    query = inChatQuery,
                    onQueryChange = { inChatQuery = it },
                    matchCount = findMatches.size,
                    currentIndex = if (findMatches.isEmpty()) 0 else currentMatchIndex + 1,
                    onPrev = { jumpToMatch(currentMatchIndex - 1) },
                    onNext = { jumpToMatch(currentMatchIndex + 1) },
                    onClose = {
                        showInChatSearch = false
                        inChatQuery = ""
                        currentMatchIndex = 0
                        viewModel.setTargetMessage(null, null)
                    },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(horizontal = MusePaddings.screen, vertical = 4.dp),
                )
            }
        } // Box

        ChatSheetHost(
            sheetState = sheetState,
            viewModel = viewModel,
            knowledgeDocs = knowledgeDocs,
            speechLauncher = speechLauncher,
            ioScope = ioScope,
            onOpenPromptTemplateManager = onOpenPromptTemplateManager,
        )
        } // Scaffold
    } // 背景 Box(v1.0.74 自定义聊天背景)
} // ChatScreen

/**
 * v1.0.72: 顶栏三点菜单的胶囊选项(圆形胶囊:图标 + 文字)。
 */
@Composable
private fun TopMenuCapsule(
    icon: ImageVector,
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = if (enabled) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
        modifier = Modifier
            .padding(vertical = 3.dp)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                },
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                },
            )
        }
    }
}

/**
 * H10: 手动压缩参数对话框 — 保留条数 / 附加指令 / 保留区 token 估算。
 * 确认后以 (keepRecent, instruction) 回调,由 ChatScreen 转发 manualCompress。
 */
@Composable
private fun CompressContextDialog(
    defaultKeepRecent: Int,
    totalMessages: Int,
    estimateTokens: (Int) -> Int,
    onDismiss: () -> Unit,
    onConfirm: (keepRecent: Int, instruction: String) -> Unit,
) {
    var keepText by rememberSaveable { mutableStateOf("") }
    var instructionText by rememberSaveable { mutableStateOf("") }
    val keep = keepText.toIntOrNull()?.coerceIn(1, (totalMessages - 1).coerceAtLeast(1)) ?: defaultKeepRecent
    val estimated = estimateTokens(keep)
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.chat_compress_dialog_title),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap)) {
                OutlinedTextField(
                    value = keepText,
                    onValueChange = { keepText = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text(stringResource(R.string.chat_compress_keep_label)) },
                    placeholder = { Text(stringResource(R.string.chat_compress_keep_placeholder, defaultKeepRecent)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.chat_compress_token_estimate, estimated),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = instructionText,
                    onValueChange = { instructionText = it },
                    label = { Text(stringResource(R.string.chat_compress_instruction_label)) },
                    placeholder = { Text(stringResource(R.string.chat_compress_instruction_placeholder)) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmText = stringResource(R.string.chat_compress_confirm),
        onConfirm = { onConfirm(keep, instructionText.trim()) },
    )
}
