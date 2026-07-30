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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import io.zer0.muse.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import io.zer0.muse.ui.common.form.MuseCapsuleTab
import io.zer0.muse.ui.common.state.MuseLoadingState
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.semiLarge

/**
 * v0.45: 独立全局搜索页。
 *
 * 从首页右上角搜索按钮进入,右滑入场。功能:
 *  - 顶部搜索框(自动聚焦)
 *  - 无输入:大面积空白空状态 + 热门搜索建议 chip
 *  - 有输入(v2.x: 顶部 Tab 切换"会话/消息内容"):
 *      - Tab=会话:展示会话标题/最后预览匹配结果
 *      - Tab=消息内容:展示消息内容匹配结果(FTS4/LIKE + 关键词黄色高亮),
 *        点击跳转对应会话并传 messageId 用于滚动定位 + 短暂高亮
 *
 * 数据源:
 *  - Tab=会话:对 state.sessions 做内存 contains 过滤
 *  - Tab=消息内容:走 [ChatViewModel.searchMessageContent](SessionRepository.searchMessageContentFlow,
 *    FTS4 snippet + LIKE 兜底)
 *
 * v2.x: 新增 [onOpenMessage] 回调,Tab=消息内容 点击消息项时触发,
 * MainActivity 据此 switchSession + setTargetMessage 后回到 HOME,
 * ChatScreen 监听 targetMessageId 滚动定位 + 短暂高亮。
 */
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    /**
     * v2.x: Tab=消息内容 点击消息项跳转回调。
     * @param sessionId 目标会话 id
     * @param messageId 目标消息 id(用于 ChatScreen 滚动定位)
     * @param query 搜索关键词(用于 MessageBubble 内文本高亮)
     */
    onOpenMessage: (sessionId: String, messageId: String, query: String) -> Unit = { _, _, _ -> },
    viewModel: ChatViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query = state.searchQuery
    // v2.x: 当前搜索 Tab(0=会话, 1=消息内容)
    val searchTab = state.searchTab
    val focusRequester = remember { FocusRequester() }

    // 自动聚焦搜索框
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // 输入变化时(去抖 300ms)更新查询并触发搜索
    // v2.x: 根据 searchTab 切换走 searchSessions() 或 searchMessageContent()
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

    Scaffold(
        topBar = {
            // iOS 风格搜索栏:Surface 凹槽 + 搜索图标 + BasicTextField + 取消文字
            Surface(
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MusePaddings.screen, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        shape = MuseShapes.semiLarge,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.weight(1f),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            BasicTextField(
                                value = query,
                                onValueChange = { viewModel.updateSearchQuery(it) },
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(focusRequester),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Normal,
                                ),
                                decorationBox = { innerTextField ->
                                    Box {
                                        if (query.isEmpty()) {
                                            Text(
                                                text = stringResource(R.string.search_placeholder),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        innerTextField()
                                    }
                                },
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.search_cancel),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.clickable { onBack() },
                    )
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
            // v2.x: 有输入时显示胶囊 Tab 切换(会话 / 消息内容)
            if (query.isNotBlank()) {
                MuseCapsuleTab(
                    tabs = listOf(
                        stringResource(R.string.search_tab_sessions),
                        stringResource(R.string.search_tab_message_content),
                    ),
                    selectedIndex = searchTab,
                    onSelect = { viewModel.switchSearchTab(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MusePaddings.screen, vertical = 4.dp),
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
                // Tab=会话:会话标题/预览匹配 + 消息内容匹配
                SearchResults(
                    query = query,
                    sessions = state.sessions,
                    messageResults = state.searchResults,
                    isSearching = state.isSearching,
                    onOpenSession = onOpenSession,
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
            // 热门搜索建议 chip(基于会话/消息内容,不含设置项)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SuggestionChip(stringResource(R.string.search_suggestion_today), onSuggestionClick)
                SuggestionChip(stringResource(R.string.search_suggestion_summary), onSuggestionClick)
                SuggestionChip(stringResource(R.string.search_suggestion_report), onSuggestionClick)
                SuggestionChip(stringResource(R.string.search_suggestion_idea), onSuggestionClick)
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

/** Tab=会话 搜索结果:会话标题/预览匹配 + 消息内容匹配(参考图样式)。 */
@Composable
private fun SearchResults(
    query: String,
    sessions: List<io.zer0.muse.data.session.SessionEntity>,
    messageResults: List<io.zer0.muse.data.session.SearchResult>,
    isSearching: Boolean,
    onOpenSession: (String) -> Unit,
    onOpenMessage: (sessionId: String, messageId: String, query: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // M-SS1: 用 remember 缓存过滤结果,避免每次 recomposition 重复计算(原实现每次都重新 filter)
    val matchedSessions = remember(sessions, query) {
        sessions.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.lastMessagePreview.contains(query, ignoreCase = true)
        }.take(20)
    }

    val hasAny = matchedSessions.isNotEmpty() || messageResults.isNotEmpty()

    // 搜索中且无结果:居中 loading
    if (!hasAny && isSearching) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MuseLoadingState()
                Text(
                    text = stringResource(R.string.search_searching),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        return
    }

    // 无匹配结果
    if (!hasAny && !isSearching) {
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
        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = MusePaddings.screen,
            vertical = 8.dp,
        ),
    ) {
        // 会话结果 section
        if (matchedSessions.isNotEmpty()) {
            item(key = "section_sessions") { SectionTitle(stringResource(R.string.search_section_sessions)) }
            items(matchedSessions, key = { "session_${it.id}" }) { session ->
                SessionResultRow(
                    title = session.title.ifBlank { stringResource(R.string.search_new_session) },
                    preview = session.lastMessagePreview,
                    updatedAt = session.updatedAt,
                    onClick = { onOpenSession(session.id) },
                )
            }
        }
        // 消息内容 section(FTS 结果)
        if (messageResults.isNotEmpty()) {
            item(key = "section_messages") { SectionTitle(stringResource(R.string.search_section_messages)) }
            items(messageResults, key = { "msg_${it.messageId}" }) { result ->
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
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

/** 会话结果项:左侧聊天气泡图标 + 标题 + 副标题(预览/来源 · 相对时间)。 */
@Composable
private fun SessionResultRow(
    title: String,
    preview: String,
    updatedAt: Long,
    onClick: () -> Unit,
) {
    val timeText = remember(updatedAt) { formatSearchRelativeTime(updatedAt) }
    val subtitle = if (preview.isNotBlank()) {
        "$preview · $timeText"
    } else {
        timeText
    }
    Surface(
        onClick = onClick,
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = MuseElevation.card,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.ChatBubbleOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
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
                MuseLoadingState()
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
        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = MusePaddings.screen,
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
        item(key = "section_messages_content") { SectionTitle(stringResource(R.string.search_section_message_content)) }
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
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = MuseElevation.card,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
        ) {
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    modifier = Modifier.padding(top = 6.dp),
                )
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

/** v2.x: 格式化时间戳为相对时间(刚刚 / N分钟前 / 今天 HH:mm / 昨天 / MM-dd)。 */
private fun formatSearchTimestamp(timestamp: Long): String =
    formatSearchRelativeTime(timestamp)

/** 搜索列表相对时间格式化(今天 HH:mm / 昨天 / MM-dd)。 */
private fun formatSearchRelativeTime(timestamp: Long): String {
    if (timestamp <= 0) return ""
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val dayMillis = TimeUnit.DAYS.toMillis(1)
    val timeSdf = SimpleDateFormat(MuseDateFormats.TIME_SHORT, Locale.getDefault())
    val dateSdf = SimpleDateFormat(MuseDateFormats.DATE_SHORT, Locale.getDefault())
    val calNow = java.util.Calendar.getInstance().apply { timeInMillis = now }
    val calTarget = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
    val isSameDay = calNow.get(java.util.Calendar.YEAR) == calTarget.get(java.util.Calendar.YEAR) &&
        calNow.get(java.util.Calendar.DAY_OF_YEAR) == calTarget.get(java.util.Calendar.DAY_OF_YEAR)
    val isYesterday = calNow.get(java.util.Calendar.YEAR) == calTarget.get(java.util.Calendar.YEAR) &&
        calNow.get(java.util.Calendar.DAY_OF_YEAR) - calTarget.get(java.util.Calendar.DAY_OF_YEAR) == 1
    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "刚刚"
        diff < TimeUnit.HOURS.toMillis(1) -> "${diff / TimeUnit.MINUTES.toMillis(1)} 分钟前"
        isSameDay -> "今天 ${timeSdf.format(Date(timestamp))}"
        isYesterday -> "昨天 ${timeSdf.format(Date(timestamp))}"
        diff < dayMillis * 7 -> "${diff / dayMillis} 天前"
        else -> dateSdf.format(Date(timestamp))
    }
}
