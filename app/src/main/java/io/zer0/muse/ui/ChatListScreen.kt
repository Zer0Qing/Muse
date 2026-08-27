package io.zer0.muse.ui

import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.ui.common.state.MuseLoadingState
import io.zer0.muse.ui.common.state.MuseErrorStateBox
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import io.zer0.muse.ui.common.form.MuseTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import compose.icons.TablerIcons
import compose.icons.tablericons.*
import io.zer0.muse.R
import io.zer0.muse.transformer.InternalMarkupSanitizer
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.knowledge.KnowledgeDocDao
import io.zer0.muse.data.session.FolderEntity
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.museAnimateItem
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.MuseDivider
import io.zer0.muse.ui.theme.MuseCornerRadius
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MuseHaptics
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.memory.fact.FactDao
import io.zer0.memory.fact.FactEntity
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalContext
import io.zer0.common.Logger
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.notification.MuseNotificationTarget
import io.zer0.muse.schedule.GreetingHintGenerator

/** v1.x: 问候语个性化提醒通知 ID(与其它通知 ID 错开)。 */
private const val GREETING_NOTIFY_ID = 1010

/**
 * 任务中心页 —— 按设计稿重构为 iOS / MANUS 风格任务首页。
 *
 * 视觉结构:
 *  - 顶部大标题问候语 + 记忆数量副标题
 *  - 全局输入条:输入任何想法,右侧绿色圆形发送按钮
 *  - 已置顶:白色圆角卡片,状态圆点 + 标题 + 副标题 + 时间 + 箭头
 *  - 文件夹:文件夹图标 + 名称 + 数量
 *  - 最近:非置顶会话或空状态提示
 *  - 知识库:入口卡片,显示文档数量
 *
 * 所有原有功能保留:
 *  - 置顶、归档、文件夹、重命名、删除
 *  - 滑动删除(左滑) / 归档(右滑)
 *  - 长按菜单
 */
// 屏幕级组合函数:问候/输入条/搜索/置顶/文件夹/最近/知识库多分区分支,
// 各分区已抽成 LazyListScope 扩展(recentSectionItems/searchResultSection 等),
// 剩余分支为分区固有结构,按 GenerationHandler 先例豁免复杂度检测。
@Suppress("CyclomaticComplexMethod")
@Composable
fun ChatListScreen(
    sessions: List<SessionEntity>,
    folders: List<FolderEntity>,
    currentSessionId: String?,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    /** v1.0.27: 从任务页输入框直接发送并创建新会话。 */
    onCreateWithText: (String) -> Unit = {},
    onDelete: (String) -> Unit,
    onRename: (SessionEntity) -> Unit,
    /** v1.48: 重命名(带新名字),修复旧实现传 session.title 导致重命名失效的 bug。 */
    onRenameTo: (SessionEntity, String) -> Unit = { s, _ -> onRename(s) },
    onTogglePinned: (String) -> Unit,
    /** B7-05: 置顶会话拖拽排序后的新 id 顺序(松手即持久化)。 */
    onReorderPinned: (List<String>) -> Unit = {},
    onMoveSessionToFolder: (String, String?) -> Unit,
    onCreateFolder: (String) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onToggleFolderExpanded: (String, Boolean) -> Unit,
    assistants: List<AssistantEntity> = emptyList(),
    currentAssistant: AssistantEntity? = null,
    /** v0.45: 已归档会话列表(历史数据保留,当前 UI 不再展示归档入口)。 */
    archivedSessions: List<SessionEntity> = emptyList(),
    /** v0.45: 归档会话(主列表项长按菜单用)。 */
    onArchive: (String) -> Unit = {},
    /** v0.45: 取消归档(归档列表项长按菜单用)。 */
    onUnarchive: (String) -> Unit = {},
    onOpenScheduledTasks: () -> Unit = {},
    onOpenQuickNotes: () -> Unit = {},
    /** v1.0.27: 打开快速翻译页。 */
    onOpenQuickTranslate: () -> Unit = {},
    /** v1.0.27: 打开知识库页。 */
    onOpenKnowledgeBase: () -> Unit = {},
    /** v2.0: 打开最近删除页。 */
    onOpenRecentlyDeleted: () -> Unit = {},
    /** Phase 1 WS5: 打开助手/角色管理页面(情感空状态 CTA 用)。 */
    onOpenAssistants: () -> Unit = {},
    /** v1.72: 会话列表首次加载标志(避免闪空状态) */
    isSessionsLoading: Boolean = false,
    /** v1.0.62: 会话列表加载失败信息(null=正常)。 */
    sessionsError: String? = null,
    /** v1.0.62: 会话列表加载失败重试回调。 */
    onRetryLoadSessions: () -> Unit = {},
    modifier: Modifier = Modifier,
    /** 元事实 DAO,用于首页显示记忆数量。 */
    factDao: FactDao = koinInject(),
    /** 知识库文档 DAO,用于首页显示文档数量。 */
    knowledgeDocDao: KnowledgeDocDao = koinInject(),
    /** v1.x: 问候语个性化提醒通知(每天一次) */
    settings: SettingsRepository = koinInject(),
    /** v1.x: 通知管理器 */
    notificationManager: MuseNotificationManager = koinInject(),
    /** v1.x: 问候语个性化提醒生成器(LLM,失败回退规则版) */
    greetingHintGenerator: GreetingHintGenerator = koinInject(),
) {
    // I3: 列表区独立错误边界,会话列表渲染数据构建失败只降级该区域
    RegionErrorBoundary(
        regionName = "list",
        data = { sessions },
    ) {
    val scope = rememberCoroutineScope()

    // v1.69: 文件夹分组 UI — 新建文件夹对话框状态
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    // 首页数据:记忆数量与知识库文档数量
    var memoryCount by remember { mutableStateOf(0) }
    var docCount by remember { mutableStateOf(0) }
    // 问候语匹配用的近期记忆(主作用域,取最近 100 条;避免子助手角色扮演记忆混入提醒)
    var greetingFacts by remember { mutableStateOf<List<FactEntity>>(emptyList()) }
    // v1.x: LLM 生成的个性化问候后缀(当天缓存,无则回退规则版)
    var greetingHint by remember { mutableStateOf<String?>(null) }
    // v1.x: 每日总结由 Worker 写入 DataStore,生成后首页可实时接收
    val dailySummary by settings.dailySummaryFlow.collectAsStateWithLifecycle(initialValue = null)
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        // 审计修复 (8.8): 去掉内层 scope.launch — 原实现内层协程属于 rememberCoroutineScope,
        // 不随 LaunchedEffect 取消(离开组合/翻页后仍在跑),且双重启动无意义。
        runCatching { memoryCount = factDao.count() }
        runCatching { docCount = knowledgeDocDao.countUserVisible() }
        // 必须先拿到局部 facts 再生成,不能在同一协程里读取刚刚 set 的 Compose 状态。
        val facts = runCatching { factDao.getAll("main").take(100) }.getOrElse {
            Logger.w("ChatListScreen", "读取问候语记忆失败: ${it.message}")
            emptyList()
        }
        greetingFacts = facts
        var resolvedGreetingHint: String? = null
        // v1.x: 个性化问候 — 缓存优先(当天),未命中则 LLM 生成,失败回退规则版。
        runCatching {
            val today = java.time.LocalDate.now().toString()
            val cached = settings.getGreetingHintCache()
            if (cached?.startsWith("$today|") == true) {
                resolvedGreetingHint = cached.substringAfter("|").takeIf { it.isNotBlank() }
                greetingHint = resolvedGreetingHint
            } else {
                greetingHintGenerator.generate(facts)?.let { hint ->
                    resolvedGreetingHint = hint
                    greetingHint = hint
                    settings.saveGreetingHintCache("$today|$hint")
                }
            }
        }.onFailure { e -> Logger.w("ChatListScreen", "问候语生成失败: ${e.message}") }
        // v1.x: 问候语个性化提醒通知 — 有近期事项且今天未通知过时,发一条通知让用户知道助手在关注他(每天最多一次)。
        runCatching {
            // 同一 LaunchedEffect 内状态更新尚未回流,使用局部结果保证首次加载也能通知。
            val hint = resolvedGreetingHint ?: GreetingHelper.getMemoryHint(facts)
            if (hint != null) {
                val today = java.time.LocalDate.now().toString()
                val lastNotify = settings.getLastGreetingNotifyDate()
                if (lastNotify != today) {
                    val titles = context.resources.getStringArray(R.array.greeting_notify_titles)
                    notificationManager.notifyReminder(
                        title = titles.random(),
                        message = hint,
                        notificationId = GREETING_NOTIFY_ID,
                        target = MuseNotificationTarget.Home,
                    )
                    settings.saveLastGreetingNotifyDate(today)
                }
            }
        }.onFailure { e -> Logger.w("ChatListScreen", "问候语提醒通知失败: ${e.message}") }
    }

    // v0.36 性能优化:缓存排序结果,避免每次重组都重新计算。
    val displayedSessions by remember(sessions) {
        mutableStateOf(
            sessions
                .distinctBy { it.id }
                .sortedWith(
                    compareByDescending<SessionEntity> { it.pinned }.thenByDescending { it.updatedAt },
                ),
        )
    }
    val pinned = remember(displayedSessions) { displayedSessions.filter { it.pinned } }
    val expandedFolderIds = remember(folders) { folders.filter { it.expanded }.map { it.id }.toSet() }
    val recent = remember(displayedSessions, expandedFolderIds) {
        displayedSessions.filter { !it.pinned && (it.folderId == null || it.folderId !in expandedFolderIds) }
    }
    // B7-05: 拖拽期间的乐观顺序,收到 DB flow 更新后自动以 sessions 为准
    var pinnedOrder by remember(sessions) { mutableStateOf(pinned.map { it.id }) }
    val orderedPinned = remember(pinned, pinnedOrder) {
        val byId = pinned.associateBy { it.id }
        pinnedOrder.mapNotNull { byId[it] } + pinned.filterNot { it.id in pinnedOrder }
    }

    MusePageScaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
                .padding(horizontal = MusePaddings.screen),
        ) {
            // v1.72: 首次加载时显示 loading,避免 DB emit 前闪"还没有任务"空状态
            if (isSessionsLoading) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.sectionGap),
                ) {
                    repeat(5) {
                        io.zer0.muse.ui.common.surface.SessionCardSkeleton()
                    }
                }
            } else if (sessionsError != null) {
                MuseErrorStateBox(
                    message = sessionsError,
                    onRetry = onRetryLoadSessions,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    // 前端修复 (性能-1): 移除全局 spacedBy,section 间距改为各区域
                    // 首个 item 手动 padding(见 TaskSectionTitle / knowledge 入口),
                    // 这样消息行可在 LazyColumn 顶层平铺且行间保持 0 间距,
                    // 维持原"整组一张卡片"的外观(首末项圆角由 SectionGroupRow 提供)。
                    contentPadding = PaddingValues(bottom = 88.dp),
                ) {
                    // 问候标题
                    item(key = "greeting") {
                        GreetingHeader(
                            memoryCount = memoryCount,
                            facts = greetingFacts,
                            personalizedHint = greetingHint,
                            dailySummaryText = dailySummary?.text,
                            dailySummaryDate = dailySummary?.date,
                            assistantName = currentAssistant?.name
                        )
                    }

                    // 全局输入条
                    item(key = "input") {
                        TaskInputBar(
                            onSend = { text ->
                                if (text.isNotBlank()) {
                                    onCreateWithText(text.trim())
                                }
                            },
                            modifier = Modifier.padding(top = MusePaddings.sectionGap + 4.dp),
                        )
                    }

                    // 已置顶(标题 + 每条会话独立 item,平铺懒加载)
                    if (pinned.isNotEmpty()) {
                        pinnedSectionItems(
                            pinned = orderedPinned,
                            folders = folders,
                            onSelect = onSelect,
                            onDelete = onDelete,
                            onRenameTo = onRenameTo,
                            onTogglePinned = onTogglePinned,
                            onReorderPinned = onReorderPinned,
                            onMoveSessionToFolder = onMoveSessionToFolder,
                            onArchive = onArchive,
                        )
                    }

                    // 文件夹(标题 + 每个文件夹独立 item,平铺懒加载)
                    if (folders.isNotEmpty()) {
                        foldersSectionItems(
                            folders = folders,
                            sessions = displayedSessions,
                            onSelect = onSelect,
                            onDelete = onDelete,
                            onRenameTo = onRenameTo,
                            onTogglePinned = onTogglePinned,
                            onMoveSessionToFolder = onMoveSessionToFolder,
                            onArchive = onArchive,
                            onSelectFolder = { folder -> onToggleFolderExpanded(folder.id, !folder.expanded) },
                            onRenameFolder = onRenameFolder,
                            onDeleteFolder = onDeleteFolder,
                        )
                    }

                    // 最近(标题 + 每条会话独立 item,平铺懒加载)
                    recentSectionItems(
                        recent = recent,
                        folders = folders,
                        onSelect = onSelect,
                        onDelete = onDelete,
                        onRenameTo = onRenameTo,
                        onTogglePinned = onTogglePinned,
                        onMoveSessionToFolder = onMoveSessionToFolder,
                        onArchive = onArchive,
                        onCreate = onCreate,
                    )

                    // 知识库
                    item(key = "section_knowledge") {
                        KnowledgeEntryCard(
                            docCount = docCount,
                            onClick = onOpenKnowledgeBase,
                            modifier = Modifier.padding(top = MusePaddings.sectionGap),
                        )
                    }
                }
            }
        }
    }

    // v1.69: 新建文件夹对话框
    if (showCreateFolderDialog) {
        MuseDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = stringResource(R.string.chat_list_new_folder),
            content = {
                MuseTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    placeholder = { Text(stringResource(R.string.chat_list_folder_name_placeholder)) },
                    singleLine = true,
                )
            },
            confirmText = stringResource(R.string.chat_list_create),
            onConfirm = {
                if (newFolderName.isNotBlank()) {
                    onCreateFolder(newFolderName.trim())
                }
                showCreateFolderDialog = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showCreateFolderDialog = false },
        )
    }
    } // I3: 列表区错误边界收尾
}

/** 顶部问候标题 + 记忆数量副标题。 */
@Composable
private fun GreetingHeader(
    memoryCount: Int,
    facts: List<FactEntity>,
    /** v1.x: LLM 生成的个性化后缀(非空时优先展示,未生成则回退规则版)。 */
    personalizedHint: String? = null,
    /** v1.x: 最近一天内的每日总结,优先于事件记忆提示展示。 */
    dailySummaryText: String? = null,
    dailySummaryDate: String? = null,
    assistantName: String? = null,
    modifier: Modifier = Modifier,
) {
    val name = assistantName ?: stringResource(R.string.assistant_repo_default_name)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
    ) {
        val dailySummaryHint = GreetingHelper.getDailySummaryHint(dailySummaryText, dailySummaryDate)
        Text(
            text = when {
                dailySummaryHint != null -> "${GreetingHelper.getTimeGreeting()}，$dailySummaryHint"
                personalizedHint != null -> "${GreetingHelper.getTimeGreeting()}，$personalizedHint"
                else -> GreetingHelper.buildGreeting(facts)
            },
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = GreetingHelper.getMemoryCountText(memoryCount, name),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 全局输入条:浅色圆角背景 + 占位文字 + 绿色圆形发送按钮。 */
@Composable
private fun TaskInputBar(
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 前端修复 (持久化-3): 输入内容改 rememberSaveable,旋转/进程重建不丢草稿
    var text by rememberSaveable { mutableStateOf("") }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            // v1.137 B5: 56dp → 48dp,降低任务页输入栏高度
            .height(48.dp),
        shape = MuseShapes.pill,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                decorationBox = { innerTextField ->
                    Box {
                        if (text.isEmpty()) {
                            Text(
                                text = stringResource(R.string.chat_list_input_hint),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            IconButton(
                onClick = {
                    if (text.isNotBlank()) {
                        onSend(text)
                        text = ""
                    }
                },
                modifier = Modifier.size(40.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = TablerIcons.Send,
                        contentDescription = stringResource(R.string.chat_list_send),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * 前端修复 (性能-1): section 标题 — 原 TaskSectionCard 的标题部分拆为独立
 * LazyColumn item。顶部自带 sectionGap 间距(原由 LazyColumn spacedBy 提供),
 * 使各区域首个 item 间距与改造前保持一致。
 */
@Composable
private fun TaskSectionTitle(
    title: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
    ) {
        ProvideTextStyle(MaterialTheme.typography.labelLarge) {
            Box(
                modifier = Modifier
                    .padding(start = 56.dp, top = MusePaddings.sectionGap, bottom = 6.dp)
                    .fillMaxWidth(),
            ) {
                title()
            }
        }
    }
}

/**
 * 前端修复 (性能-1): 分组卡片行容器 — 首项上圆角、末项下圆角、中间直角,
 * 背景 + 细边框模拟原"整组一张圆角卡片"外观。行间 0 间距由调用方保证。
 */
@Composable
private fun SectionGroupRow(
    index: Int,
    total: Int,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = when {
        total == 1 -> MuseShapes.extraLarge
        index == 0 -> RoundedCornerShape(
            topStart = MuseCornerRadius.CARD.dp,
            topEnd = MuseCornerRadius.CARD.dp,
        )
        index == total - 1 -> RoundedCornerShape(
            bottomStart = MuseCornerRadius.CARD.dp,
            bottomEnd = MuseCornerRadius.CARD.dp,
        )
        else -> RoundedCornerShape(0.dp)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        content()
    }
}

/**
 * 前端修复 (性能-1): 置顶会话区 — LazyColumn 顶层平铺。
 * 标题单独 item;每条会话独立 items(key=id) item,懒加载渲染。
 * 拖拽排序行为保持:行内局部偏移 + 松手后回调 onReorderPinned。
 */
private fun LazyListScope.pinnedSectionItems(
    pinned: List<SessionEntity>,
    folders: List<FolderEntity>,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRenameTo: (SessionEntity, String) -> Unit,
    onTogglePinned: (String) -> Unit,
    onReorderPinned: (List<String>) -> Unit,
    onMoveSessionToFolder: (String, String?) -> Unit,
    onArchive: (String) -> Unit,
) {
    item(key = "section_pinned_title") {
        TaskSectionTitle { Text(stringResource(R.string.chat_list_section_pinned)) }
    }
    itemsIndexed(pinned, key = { _, session -> "pinned_${session.id}" }) { index, session ->
        SectionGroupRow(index = index, total = pinned.size) {
            PinnedTaskRow(
                session = session,
                index = index,
                pinnedIds = pinned.map { it.id },
                folders = folders,
                onSelect = onSelect,
                onDelete = onDelete,
                onRenameTo = onRenameTo,
                onTogglePinned = onTogglePinned,
                onReorderPinned = onReorderPinned,
                onMoveSessionToFolder = onMoveSessionToFolder,
                onArchive = onArchive,
            )
            if (index != pinned.lastIndex) {
                MuseDivider()
            }
        }
    }
}

/**
 * 前端修复 (性能-1): 置顶单行(含拖拽把手) — 原 PinnedTasksCard forEachIndexed
 * 主体提取为独立行组件,index 语义与原来一致(组内位置),拖拽逻辑原样保留。
 */
@Composable
private fun PinnedTaskRow(
    session: SessionEntity,
    index: Int,
    pinnedIds: List<String>,
    folders: List<FolderEntity>,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRenameTo: (SessionEntity, String) -> Unit,
    onTogglePinned: (String) -> Unit,
    onReorderPinned: (List<String>) -> Unit,
    onMoveSessionToFolder: (String, String?) -> Unit,
    onArchive: (String) -> Unit,
) {
    var dragOffset by remember(session.id) { mutableStateOf(0f) }
    var dragging by remember(session.id) { mutableStateOf(false) }
    val rowHeightPx = with(LocalDensity.current) { 80.dp.toPx() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (dragging) 1f else 0f)
            .offset { IntOffset(0, if (dragging) dragOffset.roundToInt() else 0) },
    ) {
        TaskItem(
            session = session,
            folders = folders,
            onSelect = onSelect,
            onDelete = onDelete,
            onRenameTo = onRenameTo,
            onTogglePinned = onTogglePinned,
            onMoveSessionToFolder = onMoveSessionToFolder,
            onArchive = onArchive,
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = MusePaddings.itemGap)
                .size(44.dp)
                .clip(CircleShape)
                .background(if (dragging) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                .pointerInput(session.id, index, pinnedIds.size) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            dragging = true
                            dragOffset = 0f
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragOffset += dragAmount.y
                        },
                        onDragEnd = {
                            val target = (index + (dragOffset / rowHeightPx).roundToInt())
                                .coerceIn(0, pinnedIds.lastIndex)
                            dragging = false
                            dragOffset = 0f
                            if (target != index) {
                                val ids = pinnedIds.toMutableList()
                                val id = ids.removeAt(index)
                                ids.add(target, id)
                                onReorderPinned(ids)
                            }
                        },
                        onDragCancel = {
                            dragging = false
                            dragOffset = 0f
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = TablerIcons.GripVertical,
                contentDescription = stringResource(R.string.chat_list_reorder),
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun LazyListScope.recentSectionItems(
    recent: List<SessionEntity>,
    folders: List<FolderEntity>,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRenameTo: (SessionEntity, String) -> Unit,
    onTogglePinned: (String) -> Unit,
    onMoveSessionToFolder: (String, String?) -> Unit,
    onArchive: (String) -> Unit,
    onCreate: () -> Unit,
) {
    item(key = "section_recent_title") {
        TaskSectionTitle { Text(stringResource(R.string.chat_list_section_recent)) }
    }
    if (recent.isEmpty()) {
        item(key = "section_recent_empty") {
            SectionGroupRow(index = 0, total = 1) {
                EmptyPromptItem(onClick = onCreate)
            }
        }
    } else {
        itemsIndexed(recent, key = { _, session -> "recent_${session.id}" }) { index, session ->
            // E5 (H4): 最近会话区列表项入场/位移动画
            Box(museAnimateItem()) {
                SectionGroupRow(index = index, total = recent.size) {
                    TaskItem(
                        session = session,
                        folders = folders,
                        onSelect = onSelect,
                        onDelete = onDelete,
                        onRenameTo = onRenameTo,
                        onTogglePinned = onTogglePinned,
                        onMoveSessionToFolder = onMoveSessionToFolder,
                        onArchive = onArchive,
                    )
                    if (index != recent.lastIndex) {
                        MuseDivider()
                    }
                }
            }
        }
    }
}

/** 空状态提示项(最近列表无数据时显示)。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EmptyPromptItem(
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .combinedClickable(onClick = onClick)
            .padding(horizontal = MusePaddings.screen, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TaskStatusDot(status = TaskStatus.PENDING)
        Spacer(Modifier.width(MusePaddings.screen))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.chat_list_no_recent_title),
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Medium,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.chat_list_no_recent_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(MusePaddings.contentGap))
        Icon(
            imageVector = TablerIcons.ArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** 单个任务项:视觉行 + 左滑删除 + 右滑归档 + 长按菜单。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskItem(
    session: SessionEntity,
    folders: List<FolderEntity>,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRenameTo: (SessionEntity, String) -> Unit,
    onTogglePinned: (String) -> Unit,
    onMoveSessionToFolder: (String, String?) -> Unit,
    onArchive: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    var showActionSheet by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

    // 这些文案会在非 @Composable 回调中使用,提前获取避免编译错误。
    val archivedToast = stringResource(R.string.chat_list_filter_archived)
    val deletedToast = stringResource(R.string.chat_list_deleted_toast)
    val pinToast = stringResource(
        if (session.pinned) R.string.chat_list_unpin else R.string.chat_list_pin
    )
    val archiveToast = stringResource(R.string.chat_list_archive)
    val movedToast = stringResource(R.string.chat_list_move_ungrouped)

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onArchive(session.id)
                    MuseToast.show(archivedToast)
                    false
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    showDeleteConfirm = true
                    false
                }
                else -> false
            }
        },
    )

    if (showActionSheet) {
        TaskActionSheet(
            session = session,
            folders = folders,
            onDismiss = { showActionSheet = false },
            onTogglePinned = {
                onTogglePinned(session.id)
                MuseToast.show(pinToast)
            },
            onArchive = {
                onArchive(session.id)
                MuseToast.show(archiveToast)
            },
            onDelete = { showDeleteConfirm = true },
            onRename = { showRenameDialog = true },
            onMoveToFolder = { folderId ->
                onMoveSessionToFolder(session.id, folderId)
                MuseToast.show(movedToast)
            },
        )
    }

    if (showDeleteConfirm) {
        MuseDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = stringResource(R.string.chat_list_delete_session_title),
            content = {
                Text(
                    text = stringResource(
                        R.string.chat_list_delete_session_confirm,
                        session.title.ifBlank { stringResource(R.string.chat_new_session) }
                    ),
                )
            },
            confirmText = stringResource(R.string.action_delete),
            onConfirm = {
                onDelete(session.id)
                MuseToast.show(deletedToast)
                showDeleteConfirm = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showDeleteConfirm = false },
            destructive = true,
        )
    }

    if (showRenameDialog) {
        var newName by remember { mutableStateOf(session.title) }
        MuseDialog(
            onDismissRequest = { showRenameDialog = false },
            title = stringResource(R.string.chat_list_rename_session_title),
            content = {
                MuseTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                )
            },
            confirmText = stringResource(R.string.chat_list_confirm),
            onConfirm = {
                if (newName.isNotBlank()) {
                    onRenameTo(session, newName.trim())
                }
                showRenameDialog = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showRenameDialog = false },
        )
    }

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val color = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.primaryContainer
                SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                else -> Color.Transparent
            }
            val icon = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> TablerIcons.Archive
                SwipeToDismissBoxValue.EndToStart -> TablerIcons.Trash
                else -> null
            }
            val align = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                else -> Alignment.Center
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color),
                contentAlignment = align,
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = when (direction) {
                            SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .background(MaterialTheme.colorScheme.surface)
                .combinedClickable(
                    onClick = { onSelect(session.id) },
                    onLongClick = {
                        MuseHaptics.heavy(haptic)
                        showActionSheet = true
                    },
                )
                .padding(horizontal = MusePaddings.screen, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TaskStatusDot(session = session)
            Spacer(Modifier.width(MusePaddings.screen))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title.ifBlank { stringResource(R.string.chat_new_session) },
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val preview = InternalMarkupSanitizer.stripForDisplay(session.lastMessagePreview)
                Text(
                    text = if (preview.isNotBlank()) preview else formatTaskStatus(session),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(MusePaddings.contentGap))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = formatTime(session.updatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Icon(
                    imageVector = TablerIcons.ArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** 长按底部动作菜单。 */
@Composable
private fun TaskActionSheet(
    session: SessionEntity,
    folders: List<FolderEntity>,
    onDismiss: () -> Unit,
    onTogglePinned: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onMoveToFolder: (String?) -> Unit,
) {
    MuseBottomSheet(
        onDismissRequest = onDismiss,
        // 长按菜单内容与面板同宽，避免标题和操作项被额外横向压缩。
        horizontalPadding = 0.dp,
        bottomContentSpacing = MusePaddings.itemGap,
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 标题
            Text(
                text = session.title.ifBlank { stringResource(R.string.chat_new_session) },
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            // 置顶 / 取消置顶
            ActionSheetRow(
                icon = TablerIcons.Pinned,
                text = stringResource(
                    if (session.pinned) R.string.chat_list_unpin else R.string.chat_list_pin
                ),
                onClick = {
                    onTogglePinned()
                    onDismiss()
                },
            )
            // 重命名
            ActionSheetRow(
                icon = TablerIcons.Edit,
                text = stringResource(R.string.chat_list_rename),
                onClick = {
                    onRename()
                    onDismiss()
                },
            )
            // 归档
            ActionSheetRow(
                icon = TablerIcons.Archive,
                text = stringResource(R.string.chat_list_archive),
                onClick = {
                    onArchive()
                    onDismiss()
                },
            )
            // 删除
            ActionSheetRow(
                icon = TablerIcons.Trash,
                text = stringResource(R.string.action_delete),
                contentColor = MaterialTheme.colorScheme.error,
                onClick = {
                    onDelete()
                    onDismiss()
                },
            )
            // 移动到文件夹
            if (folders.isNotEmpty() || session.folderId != null) {
                Spacer(Modifier.height(8.dp))
                MuseDivider(startIndent = 0.dp)
                Spacer(Modifier.height(8.dp))
                ActionSheetRow(
                    icon = TablerIcons.ArrowRight,
                    text = stringResource(R.string.chat_list_move_ungrouped),
                    onClick = {
                        onMoveToFolder(null)
                        onDismiss()
                    },
                )
                folders.forEach { folder ->
                    if (folder.id != session.folderId) {
                        ActionSheetRow(
                            icon = TablerIcons.Folder,
                            text = stringResource(R.string.chat_list_move_to, folder.name),
                            onClick = {
                                onMoveToFolder(folder.id)
                                onDismiss()
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 底部菜单动作行。 */
@Composable
private fun ActionSheetRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MuseShapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
        )
    }
}

/**
 * 前端修复 (性能-1): 文件夹区 — LazyColumn 顶层平铺。
 * 标题单独 item;每个文件夹独立 items(key=id) item,懒加载渲染。
 */
private fun LazyListScope.foldersSectionItems(
    folders: List<FolderEntity>,
    sessions: List<SessionEntity>,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRenameTo: (SessionEntity, String) -> Unit,
    onTogglePinned: (String) -> Unit,
    onMoveSessionToFolder: (String, String?) -> Unit,
    onArchive: (String) -> Unit,
    onSelectFolder: (FolderEntity) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
) {
    item(key = "section_folders_title") {
        TaskSectionTitle { Text(stringResource(R.string.chat_list_section_folders)) }
    }
    folders.forEach { folder ->
        item(key = "folder_${folder.id}") {
            Box(museAnimateItem()) {
                SectionGroupRow(index = 0, total = 1) {
                    FolderItem(
                        folder = folder,
                        onSelectFolder = onSelectFolder,
                        onRenameFolder = onRenameFolder,
                        onDeleteFolder = onDeleteFolder,
                    )
                }
            }
        }
        if (folder.expanded) {
            val folderSessions = sessions.filter { it.folderId == folder.id }
            itemsIndexed(folderSessions, key = { _, session -> "folder_${folder.id}_session_${session.id}" }) { _, session ->
                TaskItem(
                    session = session,
                    folders = folders,
                    onSelect = onSelect,
                    onDelete = onDelete,
                    onRenameTo = onRenameTo,
                    onTogglePinned = onTogglePinned,
                    onMoveSessionToFolder = onMoveSessionToFolder,
                    onArchive = onArchive,
                )
            }
        }
    }
}

/** 单个文件夹项:点击展开/收起 + 长按重命名/删除。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderItem(
    folder: FolderEntity,
    onSelectFolder: (FolderEntity) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
) {
    var showFolderSheet by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showFolderSheet) {
        FolderActionSheet(
            folder = folder,
            onDismiss = { showFolderSheet = false },
            onRename = { showRenameDialog = true },
            onDelete = { showDeleteDialog = true },
        )
    }

    if (showRenameDialog) {
        var newName by remember { mutableStateOf(folder.name) }
        MuseDialog(
            onDismissRequest = { showRenameDialog = false },
            title = stringResource(R.string.chat_list_rename_folder_title),
            content = {
                MuseTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                )
            },
            confirmText = stringResource(R.string.chat_list_confirm),
            onConfirm = {
                if (newName.isNotBlank()) {
                    onRenameFolder(folder.id, newName.trim())
                }
                showRenameDialog = false
                showFolderSheet = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showRenameDialog = false },
        )
    }

    if (showDeleteDialog) {
        MuseDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = stringResource(R.string.chat_list_delete_folder_title),
            content = {
                Text(
                    text = stringResource(
                        R.string.chat_list_delete_folder_confirm,
                        folder.name
                    ),
                )
            },
            confirmText = stringResource(R.string.action_delete),
            onConfirm = {
                onDeleteFolder(folder.id)
                showDeleteDialog = false
                showFolderSheet = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showDeleteDialog = false },
            destructive = true,
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .combinedClickable(
                onClick = { onSelectFolder(folder) },
                onLongClick = { showFolderSheet = true },
            )
            .padding(horizontal = MusePaddings.screen, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = TablerIcons.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(MusePaddings.screen))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = folder.name,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Medium,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.width(MusePaddings.contentGap))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = folder.sessionCount.toString(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = TablerIcons.ArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 文件夹长按菜单。 */
@Composable
private fun FolderActionSheet(
    folder: FolderEntity,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    MuseBottomSheet(
        onDismissRequest = onDismiss,
        // 文件夹长按菜单与会话长按菜单保持一致的边缘布局。
        horizontalPadding = 0.dp,
        bottomContentSpacing = MusePaddings.itemGap,
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = folder.name,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            ActionSheetRow(
                icon = TablerIcons.Edit,
                text = stringResource(R.string.chat_list_rename),
                onClick = {
                    onRename()
                    onDismiss()
                },
            )
            ActionSheetRow(
                icon = TablerIcons.Trash,
                text = stringResource(R.string.action_delete),
                contentColor = MaterialTheme.colorScheme.error,
                onClick = {
                    onDelete()
                    onDismiss()
                },
            )
        }
    }
}

/** 知识库入口卡片。空状态显示添加提示，有文档时显示知识库名称。 */
@Composable
private fun KnowledgeEntryCard(
    docCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CardGroup(
        title = { Text(stringResource(R.string.chat_list_section_knowledge)) },
        modifier = modifier.fillMaxWidth(),
    ) {
        item(
            onClick = onClick,
            leadingContent = {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (docCount == 0) TablerIcons.Plus else TablerIcons.Book,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            },
            headlineContent = {
                Text(
                    text = stringResource(R.string.chat_list_knowledge_title),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium,
                    ),
                )
            },
            supportingContent = {
                Text(
                    text = if (docCount == 0) {
                        stringResource(R.string.chat_list_knowledge_empty)
                    } else {
                        stringResource(R.string.chat_list_knowledge_count, docCount)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                Icon(
                    imageVector = if (docCount == 0) TablerIcons.Plus else TablerIcons.ArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp),
                )
            },
        )
    }
}

/** 任务状态推断(后端暂无状态字段,由前端按活动度推断)。 */
private enum class TaskStatus { IN_PROGRESS, PENDING, COMPLETED }

private fun inferTaskStatus(session: SessionEntity, now: Long): TaskStatus {
    // 空会话 / 仅创建无消息 -> 待确认
    if (session.lastMessagePreview.isBlank() || session.messageCount <= 0) return TaskStatus.PENDING
    val dayMillis = TimeUnit.DAYS.toMillis(1)
    val threeDays = dayMillis * 3
    // 3 天内有更新且消息数 >=2 -> 进行中
    val age = (now - session.updatedAt).coerceAtLeast(0L)
    if (age < threeDays && session.messageCount >= 2) return TaskStatus.IN_PROGRESS
    // 超过 3 天未更新 -> 已完成
    if (age >= threeDays) return TaskStatus.COMPLETED
    // 其余 -> 待确认
    return TaskStatus.PENDING
}

@Composable
private fun TaskStatusDot(session: SessionEntity) {
    // 状态点只表达会话生命周期，不再把“未读”粗暴染成红色。
    // 未读数量已经由进入会话后的跳转提示表达，列表红点会让普通新消息看起来像错误。
    val status = remember(session.id, session.updatedAt, session.messageCount) {
        inferTaskStatus(session, System.currentTimeMillis())
    }
    TaskStatusDot(status = status)
}

@Composable
private fun TaskStatusDot(status: TaskStatus) {
    val (color, description) = when (status) {
        TaskStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primary to stringResource(R.string.chat_list_status_in_progress)
        TaskStatus.PENDING -> MaterialTheme.colorScheme.tertiary to stringResource(R.string.chat_list_status_pending)
        TaskStatus.COMPLETED -> MaterialTheme.colorScheme.outline to stringResource(R.string.chat_list_status_completed)
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
            .semantics { contentDescription = description },
    )
}


@Composable
private fun formatTaskStatus(session: SessionEntity): String {
    val status = inferTaskStatus(session, System.currentTimeMillis())
    return when (status) {
        TaskStatus.IN_PROGRESS -> stringResource(R.string.chat_list_status_in_progress)
        TaskStatus.PENDING -> stringResource(R.string.chat_list_status_pending)
        TaskStatus.COMPLETED -> stringResource(R.string.chat_list_status_completed)
    }
}

// H-CL1: SimpleDateFormat 提为文件级 lazy val 复用
private val chatListSdf by lazy {
    SimpleDateFormat(MuseDateFormats.DATE_TIME_SHORT, Locale.getDefault())
}


@Composable
private fun formatTime(timestamp: Long): String {
    if (timestamp <= 0) return ""
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val dayMillis = TimeUnit.DAYS.toMillis(1)
    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> stringResource(R.string.chat_list_time_just_now)
        diff < TimeUnit.HOURS.toMillis(1) -> stringResource(R.string.chat_list_time_minutes_ago, diff / TimeUnit.MINUTES.toMillis(1))
        diff < dayMillis -> stringResource(R.string.chat_list_time_hours_ago, diff / TimeUnit.HOURS.toMillis(1))
        diff < dayMillis * 2 -> stringResource(R.string.chat_list_time_yesterday)
        diff < dayMillis * 7 -> stringResource(R.string.chat_list_time_days_ago, diff / dayMillis)
        else -> chatListSdf.format(Date(timestamp))
    }
}
