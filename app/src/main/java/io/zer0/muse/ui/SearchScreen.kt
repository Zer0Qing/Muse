package io.zer0.muse.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import io.zer0.muse.R
import io.zer0.muse.data.quicknote.QuickNoteDao
import io.zer0.muse.data.quicknote.QuickNoteEntity
import io.zer0.muse.ui.common.LoadingState
import io.zer0.muse.ui.common.SegmentedControl
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.ui.translate.TranslateHistoryDao
import io.zer0.muse.ui.translate.TranslateHistoryEntity

/**
 * v0.45: 独立全局搜索页。
 *
 * 从首页右上角搜索按钮进入,右滑入场。功能:
 *  - 顶部搜索框(自动聚焦)
 *  - 无输入:大面积空白空状态 + 热门搜索建议 chip
 *  - 有输入(v2.x: 顶部 Tab 切换"会话/消息内容"):
 *      - Tab=会话:分"会话/消息/翻译/快速记录"四段
 *        (会话/消息走 FTS4 + buildSnippet;翻译/快速记录走对应 DAO 的 LIKE 搜索)
 *      - Tab=消息内容:展示 [ChatUiState.messageResults](FTS4 snippet + 高亮片段),
 *        点击跳转对应会话并传 messageId 用于滚动定位 + 短暂高亮
 *
 * 数据源:
 *  - Tab=会话 消息搜索复用 [ChatViewModel.search](走 SessionRepository.searchMessages / FTS4)
 *  - Tab=消息内容 走 [ChatViewModel.searchMessageContent](走 SessionRepository.searchMessageContentFlow,
 *    FTS4 snippet + LIKE 兜底)
 *  - 会话标题匹配:对 state.sessions 做内存 contains 过滤
 *  - 翻译历史:[TranslateHistoryDao.search](LIKE 匹配 source_text / translated_text)
 *  - 快速记录:[QuickNoteDao.search](LIKE 匹配 title / content)
 *
 * v2.x: 新增 [onOpenMessage] 回调,Tab=消息内容 点击消息项时触发,
 * MainActivity 据此 switchSession + setTargetMessage 后回到 HOME,
 * ChatScreen 监听 targetMessageId 滚动定位 + 短暂高亮。
 *
 * v2.2: 拆分全局搜索与设置搜索 — 本页只搜对话/翻译/快速记录,不再搜设置项
 *      (设置项搜索由 SettingsScreen 内置搜索框承担,避免结果混淆)。
 */
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    /**
     * v2.2: 点击翻译结果项打开快速翻译页(可继续编辑/翻译)。
     */
    onOpenQuickTranslate: () -> Unit = {},
    /**
     * v2.2: 点击快速记录结果项打开快速记录页。
     */
    onOpenQuickNotes: () -> Unit = {},
    /**
     * v2.x: Tab=消息内容 点击消息项跳转回调。
     * @param sessionId 目标会话 id
     * @param messageId 目标消息 id(用于 ChatScreen 滚动定位)
     * @param query 搜索关键词(用于 MessageBubble 内文本高亮)
     */
    onOpenMessage: (sessionId: String, messageId: String, query: String) -> Unit = { _, _, _ -> },
    viewModel: ChatViewModel = koinViewModel(),
    translateHistoryDao: TranslateHistoryDao = koinInject(),
    quickNoteDao: QuickNoteDao = koinInject(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query = state.searchQuery
    // v2.x: 当前搜索 Tab(0=会话, 1=消息内容)
    val searchTab = state.searchTab
    val focusRequester = remember { FocusRequester() }

    // v2.2: 翻译历史 / 快速记录 搜索结果(独立于 ChatViewModel,本页本地维护)
    var translateResults by remember { mutableStateOf<List<TranslateHistoryEntity>>(emptyList()) }
    var quickNoteResults by remember { mutableStateOf<List<QuickNoteEntity>>(emptyList()) }
    var isSearchingExtra by remember { mutableStateOf(false) }

    // 自动聚焦搜索框
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // 输入变化时(去抖 300ms)更新查询并触发搜索
    // v2.x: 根据 searchTab 切换走原 search() 或 searchMessageContent()
    LaunchedEffect(query, searchTab) {
        if (query.isNotBlank()) {
            delay(300)
            viewModel.updateSearchQuery(query)
            if (searchTab == 1) {
                viewModel.searchMessageContent()
            } else {
                viewModel.search()
            }
        } else {
            viewModel.clearSearch()
        }
    }

    // v2.2: 翻译历史 + 快速记录 搜索(去抖 300ms,Tab=会话 时触发)
    // 设置项不再纳入全局搜索(由 SettingsScreen 内置搜索框承担)
    LaunchedEffect(query, searchTab) {
        if (query.isNotBlank() && searchTab == 0) {
            delay(350) // 略晚于会话搜索,避免同时打 DB
            isSearchingExtra = true
            runCatching {
                val t = translateHistoryDao.search(query, limit = 20)
                val n = quickNoteDao.search(query, null, limit = 20)
                translateResults = t
                quickNoteResults = n
            }
            isSearchingExtra = false
        } else {
            translateResults = emptyList()
            quickNoteResults = emptyList()
            isSearchingExtra = false
        }
    }

    Scaffold(
        topBar = {
            // iOS 风格搜索栏(替代 Material TopAppBar)
            Surface(
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 搜索图标
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                    // 搜索输入框(胶囊形)
                    OutlinedTextField(
                        value = query,
                        onValueChange = { viewModel.updateSearchQuery(it) },
                        placeholder = { Text(stringResource(R.string.search_placeholder)) },
                        singleLine = true,
                        shape = MuseShapes.semiLarge,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                    )
                    // Cancel 文字按钮
                    TextButton(onClick = onBack) {
                        Text(
                            "Cancel",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // v2.x: 有输入时显示 Tab 切换(会话 / 消息内容)
            if (query.isNotBlank()) {
                SegmentedControl(
                    options = listOf("会话", "消息内容"),
                    selectedIndex = searchTab,
                    onSelectedChange = { viewModel.switchSearchTab(it) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (query.isBlank()) {
                // 无输入:大面积空白空状态
                EmptySearchState(
                    onSuggestionClick = { suggestion ->
                        viewModel.updateSearchQuery(suggestion)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (searchTab == 0) {
                // Tab=会话:会话标题/预览匹配 + 消息 + 翻译 + 快速记录(v2.2: 移除设置段)
                SearchResults(
                    query = query,
                    sessions = state.sessions,
                    messageResults = state.searchResults,
                    isSearching = state.isSearching,
                    translateResults = translateResults,
                    quickNoteResults = quickNoteResults,
                    isSearchingExtra = isSearchingExtra,
                    onOpenSession = onOpenSession,
                    onOpenQuickTranslate = onOpenQuickTranslate,
                    onOpenQuickNotes = onOpenQuickNotes,
                    // 任务 2:Tab=会话 消息结果也支持点击跳转 messageId + 滚动定位
                    onOpenMessage = onOpenMessage,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // Tab=消息内容:展示 FTS4 snippet 结果,点击跳转传 messageId
                MessageSearchResults(
                    query = query,
                    messageResults = state.messageResults,
                    isSearching = state.isSearchingMessages,
                    onOpenMessage = onOpenMessage,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** 无输入空状态:居中灰色搜索图标 + 提示 + 建议词 chip。 */
@Composable
private fun EmptySearchState(
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                modifier = Modifier.size(80.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.search_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(24.dp))
            // 热门搜索建议 chip
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SuggestionChip(stringResource(R.string.search_index_proactive_message), onSuggestionClick)
                SuggestionChip(stringResource(R.string.search_index_theme), onSuggestionClick)
                SuggestionChip(stringResource(R.string.search_suggestion_backup), onSuggestionClick)
                SuggestionChip(stringResource(R.string.search_index_pin_lock), onSuggestionClick)
            }
        }
    }
}

@Composable
private fun SuggestionChip(
    label: String,
    onClick: (String) -> Unit,
) {
    // L-SS1: 触摸目标至少 48dp(原 vertical padding 仅 6dp,触摸区不足),用 heightIn(min = touchTarget) 保证
    Surface(
        shape = MuseShapes.semiLarge,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier
            .heightIn(min = MuseIconSizes.touchTarget)
            .clickable { onClick(label) },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = MusePaddings.itemGap),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 搜索结果:会话 + 消息 + 翻译 + 快速记录 四段(v2.2: 移除设置段)。 */
@Composable
private fun SearchResults(
    query: String,
    sessions: List<io.zer0.muse.data.session.SessionEntity>,
    messageResults: List<io.zer0.muse.data.session.SearchResult>,
    isSearching: Boolean,
    translateResults: List<TranslateHistoryEntity>,
    quickNoteResults: List<QuickNoteEntity>,
    isSearchingExtra: Boolean,
    onOpenSession: (String) -> Unit,
    onOpenQuickTranslate: () -> Unit,
    onOpenQuickNotes: () -> Unit,
    // 任务 2:消息结果项点击跳转传 messageId 用于滚动定位 + 高亮
    onOpenMessage: (sessionId: String, messageId: String, query: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // M-SS1: 用 remember 缓存过滤结果,避免每次 recomposition 重复计算(原实现每次都重新 filter)
    val matchedSessions = remember(sessions, query) {
        sessions.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.lastMessagePreview.contains(query, ignoreCase = true)
        }.take(10)
    }

    val hasAny = matchedSessions.isNotEmpty() ||
        messageResults.isNotEmpty() ||
        translateResults.isNotEmpty() ||
        quickNoteResults.isNotEmpty()

    // v1.0.4 (P2): 搜索中且无结果时,居中显示大号 loading + "正在搜索…"文案
    // (原仅列表顶部 20dp 小圈,体验偏弱,用户分不清是搜索中还是无结果)
    if (!hasAny && (isSearching || isSearchingExtra)) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LoadingState()
                Text(
                    text = stringResource(R.string.search_searching),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        return
    }

    if (!hasAny && !isSearching && !isSearchingExtra) {
        // 无匹配结果
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.search_no_result),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.search_try_other),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 16.dp,
            vertical = 8.dp,
        ),
    ) {
        if (isSearching) {
            item(key = "loading") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    // v1.0.4 (P2): 加文案,避免单纯小圈太弱
                    Text(
                        text = stringResource(R.string.search_searching),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // 会话 section
        if (matchedSessions.isNotEmpty()) {
            item(key = "section_sessions") { SectionTitle(stringResource(R.string.search_section_sessions)) }
            items(matchedSessions, key = { "session_${it.id}" }) { session ->
                ResultRow(
                    title = session.title.ifBlank { stringResource(R.string.search_new_session) },
                    subtitle = session.lastMessagePreview,
                    icon = Icons.Outlined.ChatBubbleOutline,
                    onClick = { onOpenSession(session.id) },
                )
            }
        }
        // 消息 section(FTS5 结果)
        if (messageResults.isNotEmpty()) {
            item(key = "section_messages") { SectionTitle(stringResource(R.string.search_section_messages)) }
            items(messageResults, key = { "msg_${it.messageId}" }) { result ->
                // 任务 2:消息结果项展示会话名 + 时间 + 前后 2 句上下文(关键词高亮),
                // 点击跳转传 messageId 用于 ChatScreen 滚动定位 + 短暂高亮
                MessageSearchResultRow(
                    sessionTitle = result.sessionTitle,
                    content = result.content,
                    fallbackSnippet = result.contentSnippet,
                    query = query,
                    timestamp = result.createdAt,
                    onClick = { onOpenMessage(result.sessionId, result.messageId, query) },
                )
            }
        }
        // v2.2: 翻译历史 section(LIKE 匹配 source_text / translated_text)
        if (translateResults.isNotEmpty()) {
            item(key = "section_translate") { SectionTitle("翻译记录") }
            items(translateResults, key = { "translate_${it.id}" }) { item ->
                ResultRow(
                    title = item.sourceText,
                    subtitle = item.translatedText,
                    icon = Icons.Outlined.Translate,
                    onClick = onOpenQuickTranslate,
                )
            }
        }
        // v2.2: 快速记录 section(LIKE 匹配 title / content)
        if (quickNoteResults.isNotEmpty()) {
            item(key = "section_quick_notes") { SectionTitle("快速记录") }
            items(quickNoteResults, key = { "note_${it.id}" }) { item ->
                ResultRow(
                    title = item.title.ifBlank { stringResource(R.string.search_new_session) },
                    subtitle = item.content,
                    icon = Icons.Outlined.Lightbulb,
                    onClick = onOpenQuickNotes,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}

@Composable
private fun ResultRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        shape = MuseShapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

// ── v2.x: Tab=消息内容 搜索结果展示 ──────────────────────────────────

/**
 * v2.x: Tab=消息内容 搜索结果列表。
 *
 * 展示 [SearchResult] 列表(会话标题 + 内容片段(高亮)+ 时间),
 * 点击调用 [onOpenMessage] 跳转对应会话并传 messageId 用于滚动定位 + 短暂高亮。
 *
 * 片段高亮:[SearchResult.contentSnippet] 由 FTS4 snippet() 生成,匹配 token 以 [ ] 包裹,
 * [buildHighlightedSnippet] 解析后给匹配部分加半透明黄色背景。
 *
 * 空结果 + 搜索中:居中 loading;空结果 + 非搜索中:无匹配提示。
 */
@Composable
private fun MessageSearchResults(
    query: String,
    messageResults: List<io.zer0.muse.data.session.SearchResult>,
    isSearching: Boolean,
    onOpenMessage: (sessionId: String, messageId: String, query: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 无结果 + 搜索中:居中大号 loading + 文案
    if (messageResults.isEmpty() && isSearching) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LoadingState()
                Text(
                    text = stringResource(R.string.search_searching),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        return
    }

    // 无结果 + 非搜索中:无匹配提示
    if (messageResults.isEmpty() && !isSearching) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.search_no_result),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.search_try_other),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 16.dp,
            vertical = 8.dp,
        ),
    ) {
        // 搜索中时顶部显示小号 loading + 文案(结果已有但仍在追加)
        if (isSearching) {
            item(key = "loading") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.search_searching),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item(key = "section_messages_content") { SectionTitle("消息内容") }
        items(messageResults, key = { "msg_content_${it.messageId}" }) { result ->
            // 任务 2:Tab=消息内容结果项 — 优先用原文提取前后 2 句上下文 + 关键词高亮
            MessageSearchResultRow(
                sessionTitle = result.sessionTitle,
                content = result.content,
                fallbackSnippet = result.contentSnippet,
                query = query,
                timestamp = result.createdAt,
                onClick = { onOpenMessage(result.sessionId, result.messageId, query) },
            )
        }
    }
}

/**
 * 任务 2:统一的消息搜索结果行(Tab=会话/消息内容 共用)。
 *
 * 展示:
 *  - 顶部行:会话名(左侧,权重 1f + 省略号)+ 时间(右侧,labelSmall)
 *  - 底部:前后 2 句上下文,匹配关键词半透明黄色背景 + 加粗高亮
 *
 * 上下文来源优先级:
 *  1. [content](原文)非空 → 用 [extractContext] 提取前后 2 句,用 [buildHighlightedText] 高亮 query
 *  2. 否则(FTS4 snippet 路径)→ 用 [fallbackSnippet],用 [buildHighlightedSnippet] 解析 [xxx] 标记
 *
 * @param sessionTitle 会话标题
 * @param content 消息原文(可能为空 — FTS4 snippet 路径不返回原文时)
 * @param fallbackSnippet FTS4 snippet 含 [xxx] 标记的片段(content 为空时用)
 * @param query 搜索关键词(用于高亮)
 * @param timestamp 消息创建时间戳
 * @param onClick 点击回调(跳转对应会话 + 滚动到 messageId)
 */
@Composable
private fun MessageSearchResultRow(
    sessionTitle: String,
    content: String,
    fallbackSnippet: String,
    query: String,
    timestamp: Long,
    onClick: () -> Unit,
) {
    // 任务 2:优先用原文提取前后 2 句上下文,无原文时用 FTS4 snippet([xxx] 标记)
    val displayText = remember(content, fallbackSnippet, query) {
        if (content.isNotBlank()) extractContext(content, query, sentencesAround = 2)
        else fallbackSnippet
    }
    // 任务 2:高亮 — 原文可用时直接用 query 高亮;否则解析 FTS4 snippet 的 [xxx] 标记
    val annotatedText = remember(displayText, content, query) {
        if (content.isNotBlank()) buildHighlightedText(displayText, query)
        else buildHighlightedSnippet(displayText)
    }
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        shape = MuseShapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.ChatBubbleOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                // 顶部行:会话名 + 时间(右侧)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = sessionTitle.ifBlank { stringResource(R.string.search_new_session) },
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = remember(timestamp) { formatSearchTimestamp(timestamp) },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                // 任务 2:前后 2 句上下文 + 关键词高亮(maxLines=3 容纳更长上下文)
                if (annotatedText.isNotEmpty()) {
                    Text(
                        text = annotatedText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * 任务 2:从 [content] 原文提取匹配 [query] 所在句的前后 [sentencesAround] 句上下文。
 *
 * 按 [。！？.!?\n] 切句(保留分隔符),找到匹配所在句后取前后各 [sentencesAround] 句拼接。
 * 匹配位置找不到时回退取前 (2*sentencesAround+1) 句。
 *
 * 用于搜索结果项展示上下文,让用户在点击前判断是否为目标消息。
 */
/** 句末标点切句正则(中文 。！？ + ASCII .!? + 换行),零宽断言保留分隔符在句尾。 */
private val SENTENCE_SPLIT_REGEX = "(?<=[。！？.!?\n])".toRegex()

private fun extractContext(content: String, query: String, sentencesAround: Int = 2): String {
    if (content.isBlank()) return ""
    // 按句末标点切句,保留分隔符在句尾
    val sentences = content.split(SENTENCE_SPLIT_REGEX).filter { it.isNotBlank() }
    if (sentences.isEmpty()) return content.take(200)

    val matchIdx = content.indexOf(query, ignoreCase = true)
    // 找到匹配所在句的索引(逐句累加长度,直到覆盖 matchIdx)
    var matchSentenceIdx = 0
    if (matchIdx >= 0) {
        var pos = 0
        for ((i, s) in sentences.withIndex()) {
            if (matchIdx < pos + s.length) {
                matchSentenceIdx = i
                break
            }
            pos += s.length
            matchSentenceIdx = i
        }
    }
    val startIdx = (matchSentenceIdx - sentencesAround).coerceAtLeast(0)
    val endIdx = (matchSentenceIdx + sentencesAround + 1).coerceAtMost(sentences.size)
    val context = sentences.subList(startIdx, endIdx).joinToString("").trim()
    return context.ifBlank { content.take(200) }
}

/**
 * 任务 2:把 [text] 中所有匹配 [query] 的子串(大小写不敏感)用半透明黄色背景 + 加粗高亮。
 *
 * 用于搜索结果项展示原文上下文时高亮关键词。
 */
private fun buildHighlightedText(text: String, query: String): AnnotatedString {
    if (query.isBlank()) return buildAnnotatedString { append(text) }
    return buildAnnotatedString {
        var idx = 0
        while (idx < text.length) {
            val found = text.indexOf(query, idx, ignoreCase = true)
            if (found < 0) {
                append(text.substring(idx))
                break
            }
            if (found > idx) {
                append(text.substring(idx, found))
            }
            withStyle(SpanStyle(background = Color(0x66FFEB3B), fontWeight = FontWeight.Bold)) {
                append(text.substring(found, found + query.length))
            }
            idx = found + query.length
        }
    }
}

/**
 * v2.x: 把 FTS4 snippet 的 [xxx] 标记转换为 AnnotatedString,
 * 匹配部分加半透明黄色背景(Color(0x66FFEB3B))。
 *
 * snippet() 生成片段格式:"前缀[匹配]后缀",可能含多个 [xxx] 段。
 * 解析时把 [ ] 标记剥离,在原匹配文本上应用 SpanStyle。
 */
private fun buildHighlightedSnippet(snippet: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < snippet.length) {
        val start = snippet.indexOf('[', i)
        if (start < 0) {
            // 剩余全部为普通文本
            append(snippet.substring(i))
            break
        }
        if (start > i) {
            append(snippet.substring(i, start))
        }
        val end = snippet.indexOf(']', start)
        if (end < 0) {
            // 未闭合的 '[' 原样输出,避免吞字符
            append(snippet.substring(start))
            break
        }
        val matched = snippet.substring(start + 1, end)
        withStyle(SpanStyle(background = Color(0x66FFEB3B))) {
            append(matched)
        }
        i = end + 1
    }
}

/** v2.x: 格式化时间戳为 "MM-dd HH:mm"(本地时区,列表项时间显示用)。 */
private fun formatSearchTimestamp(timestamp: Long): String {
    return java.text.SimpleDateFormat(
        MuseDateFormats.DATE_TIME_SHORT,
        java.util.Locale.getDefault(),
    ).format(java.util.Date(timestamp))
}
