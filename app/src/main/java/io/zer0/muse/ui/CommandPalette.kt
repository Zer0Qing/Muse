package io.zer0.muse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import compose.icons.TablerIcons
import compose.icons.tablericons.Plus
import compose.icons.tablericons.Search
import compose.icons.tablericons.Settings
import compose.icons.tablericons.X
import io.zer0.muse.R
import io.zer0.muse.data.session.SearchResult
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.ui.common.surface.MuseDialogWindowEffect
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

/**
 * C1 全局命令面板 — 桌面 Ctrl+K / 首页搜索按钮唤起的统一入口。
 *
 * 两种模式自动识别:
 *  - 以 `/` 开头:命令模式,列出可执行动作(新建对话/设置/完整搜索),Enter 执行。
 *  - 其他:即时搜索模式,复用 [ChatViewModel.search]/[searchMessageContent]
 *    (FTS4 + LIKE 回退),同时展示会话结果与消息结果;点击复用既有
 *    [ChatViewModel.switchSession]/[openMessageFromSearch] 跳转链路。
 *
 * 与独立搜索页并存(深度三域搜索保留,面板内提供 /search 入口跳转)。
 * 键盘:↑/↓ 选择、Enter 执行、Esc 关闭。
 */
// 屏幕级 Dialog:键盘分发 + 命令/搜索双模式分派为固有分支结构,复杂度仅超阈值 1。
@Suppress("CyclomaticComplexMethod")
@Composable
internal fun CommandPalette(
    onDismiss: () -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenChat: () -> Unit,
    viewModel: ChatViewModel = koinInject(),
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedIndex by remember { mutableStateOf(0) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    // 防抖搜索(复用 ChatViewModel 的 search/searchMessageContent,与 SearchScreen 同链路)
    LaunchedEffect(query) {
        if (query.isNotBlank() && !query.startsWith("/")) {
            delay(300)
            viewModel.updateSearchQuery(query)
            viewModel.search()
            viewModel.searchMessageContent()
        } else if (query.isBlank()) {
            viewModel.clearSearch()
        }
    }

    val commands = listOf(
        CommandEntry("/new", R.string.command_palette_new_chat, TablerIcons.Plus, onNewChat),
        CommandEntry("/settings", R.string.command_palette_settings, TablerIcons.Settings, onOpenSettings),
        CommandEntry("/search", R.string.command_palette_full_search, TablerIcons.Search, onOpenSearch),
    )
    // 命令模式过滤
    val isCommandMode = query.startsWith("/")
    val visibleCommands = if (isCommandMode) {
        commands.filter { it.command.startsWith(query, ignoreCase = true) || query == "/" }
    } else emptyList()
    // 搜索结果可能按消息命中返回同一会话多次,命令面板只展示一次会话入口。
    val sessionResults = remember(state.searchResults) {
        state.searchResults.distinctBy { it.sessionId }
    }
    val messageResults = remember(state.messageResults) {
        state.messageResults.distinctBy { it.messageId }
    }
    val totalItems = visibleCommands.size + sessionResults.size + messageResults.size

    // 执行选中条目:统一解析目标后执行(命令 / 会话跳转 / 消息跳转)。
    @Suppress("CyclomaticComplexMethod")
    fun execute() {
        val action = resolveSelectionAction(
            selectedIndex = selectedIndex,
            visibleCommands = visibleCommands,
            sessionResults = sessionResults,
            messageResults = messageResults,
            query = query,
            viewModel = viewModel,
            onOpenChat = onOpenChat,
        )
        if (action != null) {
            onDismiss()
            action()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        MuseDialogWindowEffect(forceFullScreen = true)
        Box(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.largeGap)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionUp -> {
                                if (totalItems > 0) selectedIndex = (selectedIndex - 1 + totalItems) % totalItems
                                true
                            }
                            Key.DirectionDown -> {
                                if (totalItems > 0) selectedIndex = (selectedIndex + 1) % totalItems
                                true
                            }
                            Key.Enter -> {
                                if (totalItems > 0) execute() else Unit
                                true
                            }
                            Key.Escape -> {
                                onDismiss()
                                true
                            }
                            else -> false
                        }
                    },
                shape = MuseShapes.semiLarge,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
            ) {
                Column {
                    PaletteSearchField(
                        query = query,
                        isCommandMode = isCommandMode,
                        onQueryChange = { newValue ->
                            query = newValue
                            selectedIndex = 0
                        },
                        onClear = {
                            query = ""
                            focusManager.clearFocus()
                        },
                        onExecute = { execute() },
                    )
                    PaletteResultList(
                        query = query,
                        isCommandMode = isCommandMode,
                        visibleCommands = visibleCommands,
                        sessionResults = sessionResults,
                        messageResults = messageResults,
                        selectedIndex = selectedIndex,
                        onSelect = { index ->
                            selectedIndex = index
                            execute()
                        },
                    )
                }
            }
        }
    }
}

/** 把选中索引解析为具体动作;null 表示无可执行项。 */
private fun resolveSelectionAction(
    selectedIndex: Int,
    visibleCommands: List<CommandEntry>,
    sessionResults: List<SearchResult>,
    messageResults: List<SearchResult>,
    query: String,
    viewModel: ChatViewModel,
    onOpenChat: () -> Unit,
): (() -> Unit)? {
    // 按展示顺序构建统一条目列表:命令 + 会话 + 消息,索引与 selectedIndex 对齐。
    // isCommandMode 时搜索未触发,会话/消息列表为空,列表即命令集。
    val entries = buildList {
        visibleCommands.forEach { add(it.action) }
        sessionResults.forEach { session ->
            add {
                viewModel.switchSession(session.sessionId)
                onOpenChat()
            }
        }
        messageResults.forEach { message ->
            add {
                viewModel.openMessageFromSearch(message.sessionId, message.messageId, query)
                onOpenChat()
            }
        }
    }
    return entries.getOrNull(selectedIndex)
}

/** 输入行:图标 + 输入框 + 清空按钮。 */
@Composable
private fun PaletteSearchField(
    query: String,
    isCommandMode: Boolean,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onExecute: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.contentGap, vertical = MusePaddings.itemGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
    ) {
        Icon(
            imageVector = if (isCommandMode) TablerIcons.Settings else TablerIcons.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(MuseIconSizes.iconSmall),
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onExecute() }),
            decorationBox = { innerTextField ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.command_palette_placeholder),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    innerTextField()
                }
            },
            modifier = Modifier
                .weight(1f)
                .padding(vertical = MusePaddings.tightGap),
        )
        if (query.isNotEmpty()) {
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = TablerIcons.X,
                    contentDescription = stringResource(R.string.command_palette_clear),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
            }
        }
    }
}

/** 结果列表:命令 / 会话 / 消息三段渲染。 */
@Composable
private fun PaletteResultList(
    query: String,
    isCommandMode: Boolean,
    visibleCommands: List<CommandEntry>,
    sessionResults: List<SearchResult>,
    messageResults: List<SearchResult>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp),
    ) {
        if (isCommandMode) {
            item(key = "commands_header") {
                PaletteSectionHeader(stringResource(R.string.command_palette_commands))
            }
            itemsIndexed(
                visibleCommands,
                key = { index, entry -> "command_${entry.command}_$index" },
            ) { index, entry ->
                CommandRow(
                    icon = entry.icon,
                    label = stringResource(entry.labelRes),
                    detail = entry.command,
                    selected = selectedIndex == index,
                    onClick = { onSelect(index) },
                )
            }
            if (visibleCommands.isEmpty()) {
                item(key = "commands_empty") {
                    PaletteEmpty(stringResource(R.string.command_palette_no_results))
                }
            }
        } else if (query.isNotBlank()) {
            if (sessionResults.isNotEmpty()) {
                item(key = "sessions_header") {
                    PaletteSectionHeader(stringResource(R.string.command_palette_sessions))
                }
                itemsIndexed(
                    sessionResults,
                    key = { index, session -> "s_${session.sessionId}_$index" },
                ) { index, session ->
                    CommandRow(
                        icon = TablerIcons.Search,
                        label = session.sessionTitle.ifBlank { session.sessionId },
                        detail = stringResource(R.string.command_palette_open_session),
                        selected = selectedIndex == visibleCommands.size + index,
                        onClick = { onSelect(visibleCommands.size + index) },
                    )
                }
            }
            if (messageResults.isNotEmpty()) {
                item(key = "messages_header") {
                    PaletteSectionHeader(stringResource(R.string.command_palette_messages))
                }
                itemsIndexed(
                    messageResults,
                    key = { index, message -> "m_${message.messageId}_$index" },
                ) { index, message ->
                    CommandRow(
                        icon = TablerIcons.Search,
                        label = message.sessionTitle.ifBlank { message.sessionId },
                        detail = message.contentSnippet,
                        selected = selectedIndex == visibleCommands.size + sessionResults.size + index,
                        onClick = { onSelect(visibleCommands.size + sessionResults.size + index) },
                    )
                }
            }
            if (sessionResults.isEmpty() && messageResults.isEmpty()) {
                item(key = "search_empty") {
                    PaletteEmpty(stringResource(R.string.command_palette_no_results))
                }
            }
        } else {
            // 空查询:显示可用命令提示
            item(key = "empty_hint") {
                PaletteEmpty(stringResource(R.string.command_palette_empty_hint))
            }
        }
    }
}

/** 命令面板条目(斜杠命令)。 */
private data class CommandEntry(
    val command: String,
    val labelRes: Int,
    val icon: ImageVector,
    val action: () -> Unit,
)

/** 结果区小节标题。 */
@Composable
private fun PaletteSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = MusePaddings.contentGap, vertical = MusePaddings.tightGap),
    )
}

/** 单行结果项(选中态高亮)。 */
@Composable
private fun CommandRow(
    icon: ImageVector,
    label: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                else Color.Transparent,
            )
            .padding(horizontal = MusePaddings.contentGap, vertical = MusePaddings.itemGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(MuseIconSizes.iconSmall),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(MusePaddings.tightGap))
    }
}

/** 空结果提示。 */
@Composable
private fun PaletteEmpty(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(MusePaddings.contentGap),
    )
}
