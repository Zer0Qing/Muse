package io.zer0.muse.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.zer0.muse.R
import io.zer0.muse.crash.MuseCrashHandler
import io.zer0.muse.data.analytics.AnalyticsSnapshot
import io.zer0.muse.data.analytics.LocalAnalyticsTracker
import io.zer0.muse.data.stats.DbIntegrityLogEntity
import io.zer0.muse.data.stats.IntegrityChecker
import io.zer0.muse.debug.DebugLogEntry
import io.zer0.muse.debug.DebugLogStore
import io.zer0.muse.ui.common.IosDropdown
import io.zer0.muse.ui.common.IosTopBar
import io.zer0.muse.ui.common.MuseBottomSheet
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.common.MuseToast
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.tiny
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 调试日志页 — 参考 rikkahub LogPage / kelivo log_viewer_page 设计。
 *
 * 功能:
 *  - 顶部 IosTopBar 标题"调试日志" + 返回按钮 + 操作区(暂停/继续、导出、清空)
 *  - 过滤器行:等级下拉(ALL/ERROR/WARN/INFO/DEBUG) + Tag 搜索 + 关键字搜索
 *  - 主体 LazyColumn 展示日志,每条可展开查看完整消息 + stack trace
 *  - 自动滚动到底部(最新日志);新日志到达时若用户在底部则继续跟随,
 *    否则停在原位置;暂停按钮可冻结滚动
 *
 * 数据源:[DebugLogStore.getAll] 每 500ms 轮询一次。日志由 [Logger.sink] 推送。
 *
 * @param onBack 返回回调(由 NavHost 注入)
 */
@Composable
fun DebugScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    // ── 过滤器状态 ───────────────────────────────────────────────────────
    // 等级过滤:ALL 表示不过滤;其余值与 DebugLogStore.LEVELS 对齐
    var levelFilter by remember { mutableStateOf("ALL") }
    var tagQuery by remember { mutableStateOf("") }
    var keywordQuery by remember { mutableStateOf("") }

    // ── 滚动状态 ───────────────────────────────────────────────────────
    // paused:用户主动暂停跟随;atBottom:用户当前是否在底部(用于判断是否自动跟随)
    var paused by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            info.index >= listState.layoutInfo.totalItemsCount - 1
        }
    }

    // ── 日志数据(轮询 DebugLogStore) ──────────────────────────────────
    var allLogs by remember { mutableStateOf<List<DebugLogEntry>>(emptyList()) }
    LaunchedEffect(Unit) {
        // 首次立即拉一次,避免空白闪烁
        allLogs = DebugLogStore.getAll()
        // 之后每 500ms 轮询;Logger 是高频路径,500ms 在体验和性能间取平衡
        while (true) {
            delay(500)
            allLogs = DebugLogStore.getAll()
        }
    }

    // 应用过滤器(在 Composable 侧做,避免 DebugLogStore 维护过滤状态)
    val filteredLogs by remember(levelFilter, tagQuery, keywordQuery, allLogs) {
        derivedStateOf {
            val tagTrim = tagQuery.trim()
            val kwTrim = keywordQuery.trim()
            allLogs.asSequence()
                .filter { levelFilter == "ALL" || it.level == levelFilter }
                .filter { tagTrim.isEmpty() || it.tag.contains(tagTrim, ignoreCase = true) }
                .filter {
                    kwTrim.isEmpty() ||
                        it.message.contains(kwTrim, ignoreCase = true) ||
                        (it.throwable?.contains(kwTrim, ignoreCase = true) == true)
                }
                .toList()
        }
    }

    // ── 自动滚动逻辑 ───────────────────────────────────────────────────
    // filteredLogs 变化时:若未暂停且用户在底部,滚动到最后一条
    LaunchedEffect(filteredLogs.size) {
        if (!paused && atBottom && filteredLogs.isNotEmpty()) {
            // 用 scrollToItem 而非 animateScrollToItem,新日志高频到达时动画会卡顿
            listState.scrollToItem(filteredLogs.lastIndex)
        }
    }
    // 首次进入:滚动到底部展示最新日志
    LaunchedEffect(Unit) {
        if (allLogs.isNotEmpty()) {
            listState.scrollToItem(allLogs.lastIndex)
        }
    }
    // 监听 atBottom 变化:用户手动滚到底部时自动恢复跟随(等同取消暂停)
    LaunchedEffect(Unit) {
        snapshotFlow { atBottom }.collect { bottom ->
            if (bottom && paused) paused = false
        }
    }

    // ── 清空确认弹窗 ───────────────────────────────────────────────────
    var showClearDialog by remember { mutableStateOf(false) }
    if (showClearDialog) {
        MuseDialog(
            onDismissRequest = { showClearDialog = false },
            title = stringResource(R.string.debug_clear_logs_title),
            content = { Text(stringResource(R.string.debug_clear_logs_confirm, DebugLogStore.size())) },
            confirmText = stringResource(R.string.debug_action_clear),
            onConfirm = {
                DebugLogStore.clear()
                allLogs = emptyList()
                showClearDialog = false
                MuseToast.show(context.getString(R.string.debug_cleared_toast))
            },
            onDismiss = { showClearDialog = false },
        )
    }

    // ── 展开状态:记录当前展开的 entry(用 timestamp+tag+message 作 key,简单近似唯一) ──
    var expandedKey by remember { mutableStateOf<String?>(null) }

    // ── 崩溃日志面板(P1-4:把 MuseCrashHandler 已有但未 UI 化的崩溃日志列表/导出能力透出)
    //  - 入口:IosTopBar 上的 History 图标
    //  - 面板内列出 listCrashLogs() 全部崩溃日志文件,可展开查看内容、单条分享、一键打包 ZIP 分享
    var showCrashSheet by remember { mutableStateOf(false) }
    if (showCrashSheet) {
        CrashLogSheet(onDismiss = { showCrashSheet = false })
    }

    // ── 本地数据分析面板(P3-2:把 LocalAnalyticsTracker 已采集但未 UI 化的指标透出)
    //  - 入口:IosTopBar 上的 Analytics 图标
    //  - 面板内展示 DAU/MAU/总会话/总消息/启动次数/崩溃率 + D1/D7/D30 留存 + 功能使用 Top 10
    var showAnalyticsSheet by remember { mutableStateOf(false) }
    if (showAnalyticsSheet) {
        AnalyticsSheet(onDismiss = { showAnalyticsSheet = false })
    }

    // ── 数据库完整性面板(P3-3:把 IntegrityChecker 已有但未 UI 化的完整性检查结果透出)
    //  - 入口:IosTopBar 上的 HealthAndSafety 图标
    //  - 面板内展示最近一次完整性检查结果(状态 / DB 大小 / 时间)+ "立即检查"按钮
    var showDbIntegritySheet by remember { mutableStateOf(false) }
    if (showDbIntegritySheet) {
        DbIntegritySheet(onDismiss = { showDbIntegritySheet = false })
    }

    ScaffoldLayout(
        levelFilter = levelFilter,
        onLevelChange = { levelFilter = it },
        tagQuery = tagQuery,
        onTagChange = { tagQuery = it },
        keywordQuery = keywordQuery,
        onKeywordChange = { keywordQuery = it },
        paused = paused,
        onTogglePause = { paused = !paused },
        onClear = { showClearDialog = true },
        onShowCrashLogs = { showCrashSheet = true },
        onShowAnalytics = { showAnalyticsSheet = true },
        onShowDbIntegrity = { showDbIntegritySheet = true },
        // v1.0.4 (P3-7): 一键复制当前过滤后的日志到剪贴板(纯文本),便于内测用户粘贴反馈
        onCopy = {
            if (filteredLogs.isEmpty()) {
                MuseToast.show(context.getString(R.string.debug_no_logs_to_copy))
            } else {
                val text = buildString {
                    filteredLogs.forEach { entry ->
                        append(entry.formatLine())
                        append('\n')
                    }
                }
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("Muse Debug Log", text)
                )
                MuseToast.show(context.getString(R.string.debug_logs_copied_toast, filteredLogs.size))
            }
        },
        onExport = {
            val file = DebugLogStore.exportToFile()
            if (file == null) {
                MuseToast.show(context.getString(R.string.debug_export_failed_no_logs))
            } else {
                val uri = runCatching {
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file,
                    )
                }.getOrNull()
                if (uri == null) {
                    MuseToast.show(context.getString(R.string.debug_export_failed_no_uri))
                } else {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(
                        Intent.createChooser(intent, context.getString(R.string.debug_share_log_chooser))
                    )
                }
            }
        },
        logs = filteredLogs,
        listState = listState,
        expandedKey = expandedKey,
        onToggleExpand = { key -> expandedKey = if (expandedKey == key) null else key },
        onBack = onBack,
    )
}

/**
 * Scaffold 包装 — 把状态从主 Composable 抽离,使布局可预览/可测试。
 *
 * 单独 private 是因为外部无需直接调用。
 */
@Composable
private fun ScaffoldLayout(
    levelFilter: String,
    onLevelChange: (String) -> Unit,
    tagQuery: String,
    onTagChange: (String) -> Unit,
    keywordQuery: String,
    onKeywordChange: (String) -> Unit,
    paused: Boolean,
    onTogglePause: () -> Unit,
    onClear: () -> Unit,
    onShowCrashLogs: () -> Unit,
    onShowAnalytics: () -> Unit,
    onShowDbIntegrity: () -> Unit,
    onCopy: () -> Unit,
    onExport: () -> Unit,
    logs: List<DebugLogEntry>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    expandedKey: String?,
    onToggleExpand: (String) -> Unit,
    onBack: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            IosTopBar(
                title = stringResource(R.string.debug_screen_title),
                onBack = onBack,
                actions = {
                    // 暂停/继续跟随按钮
                    IconButton(onClick = onTogglePause) {
                        Icon(
                            imageVector = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = if (paused) stringResource(R.string.debug_cd_resume_follow) else stringResource(R.string.debug_cd_pause_follow),
                            tint = if (paused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    // 崩溃日志入口(P1-4):展示 MuseCrashHandler 已落盘的崩溃日志列表 + 一键打包 ZIP 分享
                    IconButton(onClick = onShowCrashLogs) {
                        Icon(
                            imageVector = Icons.Outlined.History,
                            contentDescription = stringResource(R.string.debug_cd_crash_logs),
                        )
                    }
                    // 本地数据分析入口(P3-2):展示 LocalAnalyticsTracker 已采集的 DAU/MAU/留存/功能使用
                    IconButton(onClick = onShowAnalytics) {
                        Icon(
                            imageVector = Icons.Outlined.Analytics,
                            contentDescription = stringResource(R.string.debug_cd_analytics),
                        )
                    }
                    // 数据库完整性入口(P3-3):展示 IntegrityChecker 最近一次 PRAGMA integrity_check 结果
                    IconButton(onClick = onShowDbIntegrity) {
                        Icon(
                            imageVector = Icons.Outlined.HealthAndSafety,
                            contentDescription = stringResource(R.string.debug_cd_db_integrity),
                        )
                    }
                    // v1.0.4 (P3-7): 复制按钮 — 把当前过滤后的日志复制到剪贴板(纯文本)
                    IconButton(onClick = onCopy) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = stringResource(R.string.debug_cd_copy),
                        )
                    }
                    // 导出按钮
                    IconButton(onClick = onExport) {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = stringResource(R.string.debug_cd_export),
                        )
                    }
                    // 清空按钮
                    IconButton(onClick = onClear) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = stringResource(R.string.debug_cd_clear),
                        )
                    }
                },
            )

            // 顶部栏与过滤器行之间的间距
            Spacer(Modifier.height(MusePaddings.contentGap))

            // ── 过滤器行 ───────────────────────────────────────────────
            FilterRow(
                levelFilter = levelFilter,
                onLevelChange = onLevelChange,
                tagQuery = tagQuery,
                onTagChange = onTagChange,
                keywordQuery = keywordQuery,
                onKeywordChange = onKeywordChange,
            )

            // 过滤器行与日志计数之间的间距
            Spacer(Modifier.height(MusePaddings.contentGap))

            // ── 日志计数 ───────────────────────────────────────────────
            Text(
                text = stringResource(R.string.debug_log_count, logs.size) +
                    if (paused) stringResource(R.string.debug_log_count_paused_suffix) else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = MusePaddings.screen),
            )

            // 计数与日志列表之间的间距(至少 8.dp,避免与 LazyColumn 贴合过紧)
            Spacer(Modifier.height(MusePaddings.contentGap))

            // ── 日志列表 ───────────────────────────────────────────────
            if (logs.isEmpty()) {
                EmptyLogs()
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = MusePaddings.screen,
                        vertical = MusePaddings.contentGap,
                    ),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                ) {
                    items(
                        items = logs,
                        // 用 index+timestamp+tag 组合 key,避免相同内容条目被复用导致展开状态错乱
                        key = { entry -> "${entry.timestamp}_${entry.tag}_${entry.message.hashCode()}" },
                    ) { entry ->
                        val entryKey = "${entry.timestamp}_${entry.tag}_${entry.message.hashCode()}"
                        LogEntryItem(
                            entry = entry,
                            expanded = expandedKey == entryKey,
                            onClick = { onToggleExpand(entryKey) },
                            // v1.0.4 (P3-7): 关键字高亮 — 用户在搜索框输入的关键字在消息中高亮显示
                            highlight = keywordQuery.trim(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 过滤器行:等级下拉 + Tag 搜索 + 关键字搜索(横向滚动避免窄屏挤压)。
 */
@Composable
private fun FilterRow(
    levelFilter: String,
    onLevelChange: (String) -> Unit,
    tagQuery: String,
    onTagChange: (String) -> Unit,
    keywordQuery: String,
    onKeywordChange: (String) -> Unit,
) {
    // ALL + 全部等级;IosDropdown 期望 List<Pair<value, displayText>>
    val levelOptions = buildList {
        add("ALL" to "ALL")
        DebugLogStore.LEVELS.forEach { add(it to it) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 横向滚动起始留白(与屏幕水平边距对齐)
        Spacer(Modifier.width(MusePaddings.screen))
        // 等级下拉(固定宽度,避免横向滚动时占位过多)
        IosDropdown(
            value = levelFilter,
            onValueChange = onLevelChange,
            label = stringResource(R.string.debug_level_label),
            options = levelOptions,
            modifier = Modifier.width(120.dp),
        )
        Spacer(Modifier.width(MusePaddings.contentGap))
        OutlinedTextField(
            value = tagQuery,
            onValueChange = onTagChange,
            label = { Text("Tag") },
            singleLine = true,
            shape = MuseShapes.medium,
            modifier = Modifier.width(140.dp),
        )
        Spacer(Modifier.width(MusePaddings.contentGap))
        OutlinedTextField(
            value = keywordQuery,
            onValueChange = onKeywordChange,
            label = { Text(stringResource(R.string.debug_keyword_label)) },
            singleLine = true,
            shape = MuseShapes.medium,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            modifier = Modifier.width(180.dp),
        )
        // 横向滚动末尾留白,避免最后一个控件贴右边缘
        Spacer(Modifier.width(MusePaddings.screen))
    }
}

/**
 * 空状态:无日志或无匹配项时展示。
 */
@Composable
private fun EmptyLogs() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Outlined.BugReport,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(MusePaddings.contentGap))
            Text(
                text = stringResource(R.string.debug_empty_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(MusePaddings.tightGap))
            Text(
                text = stringResource(R.string.debug_empty_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/**
 * 单条日志条目。点击切换展开/折叠。
 *
 * 展开后显示完整消息(无 maxLines)和 throwable stack trace(若有)。
 * 折叠时消息最多 2 行,末尾省略号。
 */
@Composable
private fun LogEntryItem(
    entry: DebugLogEntry,
    expanded: Boolean,
    onClick: () -> Unit,
    // v1.0.4 (P3-7): 关键字高亮 — 非空时在 message 中以黄色背景高亮所有匹配子串
    highlight: String = "",
) {
    val timeStr = remember(entry.timestamp) {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(entry.timestamp))
    }
    val levelColor = remember(entry.level) { levelColor(entry.level) }
    // v1.0.4 (P3-7): 把 message 构建为带高亮的 AnnotatedString
    val messageAnnotated = remember(entry.message, highlight) {
        buildAnnotatedString {
            if (highlight.isEmpty()) {
                append(entry.message)
                return@buildAnnotatedString
            }
            // 大小写不敏感查找:遍历所有匹配位置,用 yellow background + black text 高亮
            val text = entry.message
            var idx = 0
            val lowerText = text.lowercase(Locale.US)
            val lowerHl = highlight.lowercase(Locale.US)
            while (idx < text.length) {
                val hit = lowerText.indexOf(lowerHl, idx)
                if (hit < 0) {
                    append(text.substring(idx))
                    break
                }
                if (hit > idx) append(text.substring(idx, hit))
                pushStyle(
                    androidx.compose.ui.text.SpanStyle(
                        background = Color(0xFFFFEB3B),
                        color = Color.Black,
                    )
                )
                append(text.substring(hit, hit + highlight.length))
                pop()
                idx = hit + highlight.length
            }
        }
    }

    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.cardInner),
        ) {
            // 第一行:时间 + 等级标签 + Tag
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = timeStr,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = MuseMonoFontFamily,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.width(MusePaddings.contentGap))
                LevelChip(level = entry.level, color = levelColor)
                Spacer(Modifier.width(MusePaddings.contentGap))
                Text(
                    text = entry.tag,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(MusePaddings.tightGap))
            // 第二行:消息(折叠时 2 行省略,展开时全量)
            // v1.0.4 (P3-7): 用 messageAnnotated 渲染,支持关键字高亮
            Text(
                text = messageAnnotated,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = if (expanded) TextOverflow.Visible else TextOverflow.Ellipsis,
            )
            // 展开后:throwable stack trace(若有)
            AnimatedVisibility(
                visible = expanded && !entry.throwable.isNullOrEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column {
                    Spacer(Modifier.height(MusePaddings.contentGap))
                    Surface(
                        shape = MuseShapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = entry.throwable.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(MusePaddings.contentGap)
                                .horizontalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 等级标签胶囊(彩色背景 + 等级首字母)。
 *
 * 颜色约定:ERROR 红 / WARN 橙 / INFO 蓝 / DEBUG 灰。
 */
@Composable
private fun LevelChip(level: String, color: Color) {
    Surface(
        shape = MuseShapes.tiny,
        color = color.copy(alpha = 0.18f),
    ) {
        Text(
            text = level.take(1),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(MusePaddings.tightGap),
        )
    }
}

/**
 * 把等级字符串映射为展示颜色。onSurface 兜底未知等级。
 */
private fun levelColor(level: String): Color {
    // 不能在非 Composable 函数访问 MaterialTheme.colorScheme,这里返回 ARGB 常量
    return when (level) {
        DebugLogStore.LEVEL_ERROR -> Color(0xFFD32F2F)
        DebugLogStore.LEVEL_WARN -> Color(0xFFEF6C00)
        DebugLogStore.LEVEL_INFO -> Color(0xFF1976D2)
        DebugLogStore.LEVEL_DEBUG -> Color(0xFF616161)
        else -> Color(0xFF616161)
    }
}

// ════════════════════════════════════════════════════════════════════════════
// P1-4:崩溃日志面板 — 把 MuseCrashHandler 已有但仅 SafeMode 使用的崩溃日志
//       列表 / ZIP 打包 / 单条分享能力,在正常模式下也透出给用户。
//
//  数据源:MuseCrashHandler.listCrashLogs(context) → List<File>
//  打包  :MuseCrashHandler.packageCrashLogsToZip(context) → File?(cacheDir/zip)
//  分享  :FileProvider + ACTION_SEND(走 file_paths.xml 中已声明的 files-path / cache-path)
// ════════════════════════════════════════════════════════════════════════════

/**
 * 崩溃日志底部面板。
 *
 * 行为:
 *  - 打开时异步拉取 [MuseCrashHandler.listCrashLogs],按 mtime 降序展示
 *  - 每条崩溃日志可点击展开,内联预览前 [CRASH_PREVIEW_CHARS] 字符
 *  - 顶部"导出 ZIP 并分享"按钮调用 [MuseCrashHandler.packageCrashLogsToZip]
 *    打包全部崩溃日志 + 设备信息,通过 ACTION_SEND 分享
 *  - 每条日志右侧"分享"按钮单独分享该 .txt 文件
 *
 * 设计参考:SafeModeScreen 已有的 shareCrashLog,这里把同一能力在正常模式下复用。
 */
@Composable
private fun CrashLogSheet(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    // ── 数据状态 ─────────────────────────────────────────────────────────
    var logs by remember { mutableStateOf<List<File>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    // 当前展开的崩溃日志文件名(null = 全部折叠)
    var expandedFile by remember { mutableStateOf<String?>(null) }
    // 展开后懒加载的文件内容(避免一次性把所有日志读入内存)
    var expandedContent by remember { mutableStateOf<String?>(null) }

    // 打开时拉一次崩溃日志列表(读 filesDir 是 IO,放 Dispatchers.IO)
    LaunchedEffect(Unit) {
        logs = withContext(Dispatchers.IO) { MuseCrashHandler.listCrashLogs(context) }
        loading = false
    }

    // expandedFile 变化时,异步读取对应文件内容(截断到预览长度,避免大文件 OOM)
    LaunchedEffect(expandedFile) {
        val name = expandedFile
        if (name == null) {
            expandedContent = null
        } else {
            val file = logs.firstOrNull { it.name == name }
            expandedContent = if (file != null) {
                withContext(Dispatchers.IO) {
                    runCatching { file.readText().take(CRASH_PREVIEW_CHARS) }.getOrNull()
                }
            } else {
                null
            }
        }
    }

    MuseBottomSheet(onDismissRequest = onDismiss) {
        // ── 标题行 + ZIP 导出按钮 ────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.debug_crash_log_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            // ZIP 打包分享:即使只有 1 条崩溃日志也走 zip 路径,统一带 device_info
            Button(onClick = { shareCrashZip(context) }) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.debug_export_zip_and_share))
            }
        }

        Spacer(Modifier.height(MusePaddings.contentGap))

        // ── 计数 + 状态分支 ──────────────────────────────────────────────
        when {
            loading -> {
                Text(
                    text = stringResource(R.string.debug_loading_crash_logs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = MusePaddings.contentGap),
                )
            }
            logs.isEmpty() -> {
                Text(
                    text = stringResource(R.string.debug_no_crash_logs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = MusePaddings.contentGap),
                )
            }
            else -> {
                Text(
                    text = stringResource(R.string.debug_crash_log_count, logs.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(MusePaddings.tightGap))
                // 限制最大高度,避免列表过长撑爆 BottomSheet
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                ) {
                    items(
                        items = logs,
                        key = { it.name },
                    ) { file ->
                        CrashLogItem(
                            file = file,
                            expanded = expandedFile == file.name,
                            expandedContent = if (expandedFile == file.name) expandedContent else null,
                            onToggleExpand = {
                                expandedFile = if (expandedFile == file.name) null else file.name
                            },
                            onShare = { shareCrashFile(context, file) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单条崩溃日志条目。
 *
 * 折叠时:文件名 + 大小 + 修改时间 + 分享按钮
 * 展开时:在折叠信息下方追加内联预览(等宽字体,可垂直滚动)
 */
@Composable
private fun CrashLogItem(
    file: File,
    expanded: Boolean,
    expandedContent: String?,
    onToggleExpand: () -> Unit,
    onShare: () -> Unit,
) {
    // 文件名形如 crash-20260721-153012.txt;直接展示原文件名,保持与磁盘一致便于排查
    val sizeStr = remember(file.length()) { formatFileSize(file.length()) }
    val timeStr = remember(file.lastModified()) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(file.lastModified()))
    }

    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpand),
    ) {
        Column(modifier = Modifier.padding(MusePaddings.cardInnerAux)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = MuseMonoFontFamily,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "$timeStr · $sizeStr",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onShare) {
                    Icon(
                        imageVector = Icons.Outlined.Share,
                        contentDescription = stringResource(R.string.debug_cd_share_crash_log),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 展开后:内联预览(等宽字体 + 垂直滚动)
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    val preview = expandedContent
                    Surface(
                        shape = MuseShapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = preview ?: stringResource(R.string.debug_loading),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(MusePaddings.contentGap)
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                    if (preview != null && preview.length >= CRASH_PREVIEW_CHARS) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.debug_crash_preview_truncated, CRASH_PREVIEW_CHARS),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// P3-2:本地数据分析面板 — 把 LocalAnalyticsTracker 已采集但未 UI 化的指标透出
//
//  数据源:LocalAnalyticsTracker.getSnapshot() → AnalyticsSnapshot
//         LocalAnalyticsTracker.getFeatureUsage() → List<Pair<String, Int>>(已按 count 降序)
//  隐私:所有数据仅本地 DataStore,无任何上报
// ════════════════════════════════════════════════════════════════════════════

/**
 * 本地数据分析底部面板。
 *
 * 展示内容:
 *  - 核心指标卡片:DAU 今日 / MAU 本月 / 总会话 / 总消息 / 启动次数 / 崩溃次数(含崩溃率)
 *  - 留存卡片:D1 / D7 / D30 留存标记
 *  - 最近活跃日期
 *  - 功能使用 Top 10(按 count 降序,空时提示)
 */
@Composable
private fun AnalyticsSheet(onDismiss: () -> Unit) {
    val tracker: LocalAnalyticsTracker = koinInject()

    var snapshot by remember { mutableStateOf<AnalyticsSnapshot?>(null) }
    var featureUsage by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        snapshot = tracker.getSnapshot()
        featureUsage = tracker.getFeatureUsage()
        loading = false
    }

    MuseBottomSheet(onDismissRequest = onDismiss) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.debug_analytics_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(MusePaddings.contentGap))

        when {
            loading -> {
                Text(
                    text = stringResource(R.string.debug_loading_analytics),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = MusePaddings.contentGap),
                )
            }
            snapshot == null -> {
                Text(
                    text = stringResource(R.string.debug_analytics_load_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = MusePaddings.contentGap),
                )
            }
            else -> {
                val s = snapshot!!
                // ── 核心指标:2 列网格 ─────────────────────────────────────────
                MetricGrid(
                    metrics = listOf(
                        stringResource(R.string.debug_metric_dau_today) to s.dauToday.toString(),
                        stringResource(R.string.debug_metric_mau_month) to s.mauMonth.toString(),
                        stringResource(R.string.debug_metric_total_sessions) to s.totalSessions.toString(),
                        stringResource(R.string.debug_metric_total_messages) to s.totalMessages.toString(),
                        stringResource(R.string.debug_metric_launch_count) to s.launchCount.toString(),
                        stringResource(R.string.debug_metric_crash_count) to
                            stringResource(R.string.debug_metric_crash_count_value, s.crashCount, formatPercent(s.crashRate)),
                    ),
                )

                Spacer(Modifier.height(MusePaddings.contentGap))

                // ── 留存卡片 ─────────────────────────────────────────────────
                RetentionCard(
                    d1 = s.d1Retention,
                    d7 = s.d7Retention,
                    d30 = s.d30Retention,
                )

                Spacer(Modifier.height(MusePaddings.contentGap))

                // ── 其他状态:首次对话 / 记忆系统 / 最近活跃 ──────────────────
                StatusRow(
                    label = stringResource(R.string.debug_status_first_chat),
                    value = if (s.firstChatCompleted) stringResource(R.string.debug_value_yes) else stringResource(R.string.debug_value_no),
                )
                StatusRow(
                    label = stringResource(R.string.debug_status_memory_adopted),
                    value = if (s.memoryAdopted) stringResource(R.string.debug_value_yes) else stringResource(R.string.debug_value_no),
                )
                if (s.lastActiveDate.isNotBlank()) {
                    StatusRow(label = stringResource(R.string.debug_status_last_active), value = s.lastActiveDate)
                }

                Spacer(Modifier.height(MusePaddings.contentGap))

                // ── 功能使用 Top 10 ──────────────────────────────────────────
                FeatureUsageSection(usage = featureUsage)
            }
        }
    }
}

/**
 * 指标网格 — 2 列展示键值对,简洁卡片样式。
 */
@Composable
private fun MetricGrid(metrics: List<Pair<String, String>>) {
    // 用 columnCount=2 的简化网格:每两个一组渲染一行
    val rows = metrics.chunked(2)
    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap)) {
        rows.forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                rowItems.forEach { (label, value) ->
                    Surface(
                        shape = MuseShapes.medium,
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(modifier = Modifier.padding(MusePaddings.itemGap)) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = value,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                // 若最后一行只有 1 个,补一个占位以保持网格对齐
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * 留存卡片:D1 / D7 / D30 三段进度。
 */
@Composable
private fun RetentionCard(d1: Int, d7: Int, d30: Int) {
    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(MusePaddings.itemGap)) {
            Text(
                text = stringResource(R.string.debug_retention_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                RetentionItem(label = stringResource(R.string.debug_retention_d1), retained = d1)
                RetentionItem(label = stringResource(R.string.debug_retention_d7), retained = d7)
                RetentionItem(label = stringResource(R.string.debug_retention_d30), retained = d30)
            }
        }
    }
}

@Composable
private fun RetentionItem(label: String, retained: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = androidx.compose.foundation.shape.CircleShape,
            color = if (retained > 0) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.size(32.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (retained > 0) {
                        Icons.Filled.Check
                    } else {
                        Icons.Filled.Close
                    },
                    contentDescription = null,
                    tint = if (retained > 0) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 键值对行(标签 + 值)。 */
@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 功能使用 Top 10 列表。
 *
 * 空列表显示提示;非空时显示条形图风格的进度条(相对最大值的比例)。
 */
@Composable
private fun FeatureUsageSection(usage: List<Pair<String, Int>>) {
    Text(
        text = stringResource(R.string.debug_feature_usage_top),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    if (usage.isEmpty()) {
        Text(
            text = stringResource(R.string.debug_no_feature_usage),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = MusePaddings.contentGap),
        )
    } else {
        val maxCount = usage.maxOf { it.second }.coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            usage.take(10).forEach { (name, count) ->
                FeatureUsageRow(
                    name = name,
                    count = count,
                    fraction = count.toFloat() / maxCount,
                )
            }
        }
    }
}

@Composable
private fun FeatureUsageRow(name: String, count: Int, fraction: Float) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(2.dp))
        // 简化条形图:用 Surface 高度表示比例
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(fraction),
                color = MaterialTheme.colorScheme.primary,
                content = {},
            )
        }
    }
}

/** 把 0..1 的浮点数格式化为百分比字符串(保留 1 位小数)。 */
private fun formatPercent(value: Float): String = "%.1f%%".format(value * 100)

// ════════════════════════════════════════════════════════════════════════════
// P3-3:数据库完整性面板 — 把 IntegrityChecker 已有但未 UI 化的检查结果透出
//
//  数据源:IntegrityChecker.getLatestStatus() → DbIntegrityLogEntity?
//  触发检查:IntegrityChecker.checkAndLog() → Boolean(同步等待结果)
//  落盘:每次检查结果写入 db_integrity_log 表,自动保留最近 30 条
// ════════════════════════════════════════════════════════════════════════════

/**
 * 数据库完整性底部面板。
 *
 * 展示内容:
 *  - 最近一次检查结果:状态(ok / error)+ DB 大小 + 检查时间 + 详情
 *  - "立即检查"按钮:触发 [IntegrityChecker.checkAndLog],显示 loading + 结果
 *  - 异常状态显示备份提示(引导用户从自动备份恢复)
 */
@Composable
private fun DbIntegritySheet(onDismiss: () -> Unit) {
    val checker: IntegrityChecker = koinInject()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var latest by remember { mutableStateOf<DbIntegrityLogEntity?>(null) }
    var loading by remember { mutableStateOf(true) }
    var checking by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    // 打开时拉一次最近状态
    LaunchedEffect(Unit) {
        latest = checker.getLatestStatus()
        loading = false
    }

    MuseBottomSheet(onDismissRequest = onDismiss) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.debug_db_integrity_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(MusePaddings.contentGap))

        when {
            loading -> {
                Text(
                    text = stringResource(R.string.debug_loading_db_integrity),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = MusePaddings.contentGap),
                )
            }
            else -> {
                latest?.let { entity ->
                    val isOk = entity.status.equals("ok", ignoreCase = true)
                    // ── 状态卡片:圆形图标 + 状态文字 + DB 大小 + 检查时间 ────────
                    Surface(
                        shape = MuseShapes.large,
                        color = if (isOk) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        } else {
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(MusePaddings.screen),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                shape = androidx.compose.foundation.shape.CircleShape,
                                color = if (isOk) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                                modifier = Modifier.size(40.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (isOk) {
                                            Icons.Filled.Check
                                        } else {
                                            Icons.Filled.Close
                                        },
                                        contentDescription = null,
                                        tint = if (isOk) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            MaterialTheme.colorScheme.onError
                                        },
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isOk) stringResource(R.string.debug_db_status_ok) else stringResource(R.string.debug_db_status_error),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (isOk) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.debug_db_size_label, formatFileSize(entity.dbSizeBytes)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = stringResource(R.string.debug_db_check_time_label, dateFormat.format(Date(entity.checkedAt))),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    // ── 异常时显示详情 + 备份提示 ─────────────────────────────
                    if (!isOk) {
                        Spacer(Modifier.height(MusePaddings.contentGap))
                        Surface(
                            shape = MuseShapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = entity.details.takeIf { it.isNotBlank() }
                                    ?: stringResource(R.string.debug_no_details),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .padding(MusePaddings.contentGap)
                                    .heightIn(max = 160.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                        }
                        Spacer(Modifier.height(MusePaddings.contentGap))
                        Text(
                            text = stringResource(R.string.debug_db_error_advice),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    Spacer(Modifier.height(MusePaddings.contentGap))

                    // ── 立即检查按钮 ─────────────────────────────────────────
                    Button(
                        onClick = {
                            if (checking) return@Button
                            scope.launch {
                                checking = true
                                val ok = checker.checkAndLog()
                                latest = checker.getLatestStatus()
                                checking = false
                                MuseToast.show(
                                    context.getString(
                                        if (ok) R.string.debug_integrity_check_passed
                                        else R.string.debug_integrity_check_failed
                                    )
                                )
                            }
                        },
                        enabled = !checking,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MuseShapes.medium,
                    ) {
                        if (checking) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.debug_checking))
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.HealthAndSafety,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.debug_check_now))
                        }
                    }
                } ?: run {
                    // 无任何检查记录(首次使用 / 表为空)
                    Text(
                        text = stringResource(R.string.debug_no_check_record),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = MusePaddings.contentGap),
                    )
                    Button(
                        onClick = {
                            if (checking) return@Button
                            scope.launch {
                                checking = true
                                checker.checkAndLog()
                                latest = checker.getLatestStatus()
                                checking = false
                            }
                        },
                        enabled = !checking,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MuseShapes.medium,
                    ) {
                        if (checking) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.debug_checking))
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.HealthAndSafety,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.debug_check_now))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 打包全部崩溃日志为 ZIP 并通过 ACTION_SEND 分享。
 *
 * ZIP 内容由 [MuseCrashHandler.packageCrashLogsToZip] 决定:device_info.txt + 各 crash-*.txt。
 * 文件位于 cacheDir(已在 file_paths.xml 中通过 cache-path 暴露给 FileProvider)。
 */
private fun shareCrashZip(context: android.content.Context) {
    val zipFile = MuseCrashHandler.packageCrashLogsToZip(context)
    if (zipFile == null) {
        MuseToast.show(context.getString(R.string.debug_export_failed_no_crash_logs))
        return
    }
    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            zipFile,
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, "muse crash logs — ${zipFile.name}")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            android.content.Intent.createChooser(intent, context.getString(R.string.debug_share_crash_zip_chooser))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        MuseToast.show(context.getString(R.string.debug_share_failed_no_uri))
    }
}

/**
 * 分享单条崩溃日志文件(.txt)。
 *
 * 文件位于 filesDir/crash/(已在 file_paths.xml 中通过 files-path 暴露给 FileProvider)。
 */
private fun shareCrashFile(context: android.content.Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, "muse crash log — ${file.name}")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            android.content.Intent.createChooser(intent, context.getString(R.string.debug_share_crash_log_chooser))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        MuseToast.show(context.getString(R.string.debug_share_failed_no_uri))
    }
}

/** 把字节数格式化为人类可读的文件大小(B / KB / MB)。 */
private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    return "%.1f MB".format(mb)
}

/** 崩溃日志内联预览的最大字符数,避免一次性把超大堆栈读入 Compose 状态。 */
private const val CRASH_PREVIEW_CHARS = 2000
