package io.zer0.muse.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Api
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import io.zer0.muse.ui.common.IosChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.zer0.common.AppJson
import io.zer0.muse.R
import io.zer0.muse.data.audit.AuditLogEntity
import io.zer0.muse.data.audit.AuditLogger
import io.zer0.muse.ui.common.IosTopBar
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.common.MuseToast
import io.zer0.muse.ui.common.WindowWidthClass
import io.zer0.muse.ui.common.rememberWindowWidthClass
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.compose.koinInject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P2-4: 审计日志页 — 展示应用内关键操作日志,并支持类别筛选 / 关键词搜索 / JSON 导出。
 *
 * 结构:
 *  - 顶部 IosTopBar(返回 + 标题 + 导出 JSON + 清空按钮)
 *  - 筛选区:类别 FlowRow Chips(全部 / API 调用 / 用户操作 / 认证 / 系统)+ 关键词搜索框
 *  - 中间 LazyColumn 显示日志条目(分页加载,每页 100 条,客户端筛选)
 *  - 每条目:时间戳(yyyy-MM-dd HH:mm:ss)+ 分类图标 + action + target + success/fail 标记
 *  - 长按弹 MuseDialog 显示完整 detail JSON
 *  - 完全无日志显示"暂无日志记录";有日志但筛选无结果显示"无匹配日志"
 *
 * 数据源:[AuditLogger.query] 分页查询,首次进入加载第 1 页,
 * 滚动到底部自动加载下一页。类别与关键词筛选在客户端完成,
 * 因此筛选后可见结果受已加载量限制,用户可滚动到底部继续加载更多原始数据。
 *
 * @param onBack 返回回调(由 NavHost 注入)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AuditLogPage(
    onBack: () -> Unit,
) {
    val auditLogger: AuditLogger = koinInject()
    val scope = rememberCoroutineScope()
    val widthClass = rememberWindowWidthClass()
    val context = LocalContext.current

    var logs by remember { mutableStateOf<List<AuditLogEntity>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    var page by remember { mutableStateOf(0) }
    // 详情弹窗:长按日志条目时显示完整 detail JSON
    var detailDialog by remember { mutableStateOf<AuditLogEntity?>(null) }
    // 清空确认弹窗
    var showClearDialog by remember { mutableStateOf(false) }
    // P2-4 增强:类别筛选 + 关键词搜索
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    val pageSize = 100
    val dateFormat = remember { SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL_SEC, Locale.getDefault()) }

    // 首次进入加载第 1 页
    LaunchedEffect(Unit) {
        isLoading = true
        val first = auditLogger.query(limit = pageSize, offset = 0)
        logs = first
        hasMore = first.size == pageSize
        page = 1
        isLoading = false
    }

    fun loadMore() {
        if (isLoading || !hasMore) return
        scope.launch {
            isLoading = true
            val next = auditLogger.query(limit = pageSize, offset = page * pageSize)
            logs = logs + next
            hasMore = next.size == pageSize
            if (hasMore) page += 1
            isLoading = false
        }
    }

    // 客户端筛选:类别 + 关键词(忽略大小写,匹配 action / target / detail)
    val filteredLogs = remember(logs, selectedCategory, searchQuery) {
        val cat = selectedCategory
        val q = searchQuery.trim().lowercase(Locale.getDefault())
        if (cat == null && q.isEmpty()) {
            logs
        } else {
            logs.filter { log ->
                (cat == null || log.category == cat) &&
                    (q.isEmpty() ||
                        log.action.lowercase(Locale.getDefault()).contains(q) ||
                        log.target.lowercase(Locale.getDefault()).contains(q) ||
                        log.detail.lowercase(Locale.getDefault()).contains(q))
            }
        }
    }

    Scaffold(
        topBar = {
            IosTopBar(
                title = stringResource(R.string.settings_audit_log),
                onBack = onBack,
                largeTitle = true,
                actions = {
                    // 导出 JSON(把当前筛选后的日志序列化为 JSON 文件并通过 ACTION_SEND 分享)
                    IconButton(onClick = { shareAuditLogsAsJson(context, filteredLogs) }) {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = stringResource(R.string.audit_log_export),
                        )
                    }
                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = stringResource(R.string.audit_log_clear),
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (logs.isEmpty() && !isLoading) {
                // 完全无日志:全屏空状态(不展示筛选区)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = innerPadding.calculateTopPadding()),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.audit_log_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (widthClass == WindowWidthClass.Expanded) {
                                Modifier.widthIn(max = 720.dp)
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = MusePaddings.screen),
                    contentPadding = PaddingValues(
                        top = innerPadding.calculateTopPadding(),
                        bottom = innerPadding.calculateBottomPadding() + MusePaddings.screen,
                    ),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                ) {
                    // 筛选区:类别 Chips + 关键词搜索框
                    item(key = "filter_header") {
                        FilterHeader(
                            selectedCategory = selectedCategory,
                            onCategorySelected = { selectedCategory = it },
                            searchQuery = searchQuery,
                            onSearchQueryChanged = { searchQuery = it },
                        )
                    }
                    // 结果计数:仅当有筛选条件时显示"显示 X 条 / 共 Y 条"
                    if (selectedCategory != null || searchQuery.isNotBlank()) {
                        item(key = "result_count") {
                            Text(
                                text = stringResource(
                                    R.string.audit_log_result_count,
                                    filteredLogs.size,
                                    logs.size,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = MusePaddings.tightGap),
                            )
                        }
                    }
                    if (filteredLogs.isEmpty()) {
                        // 有原始日志但筛选无匹配
                        item(key = "empty_filtered") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = MusePaddings.contentGap * 3),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.audit_log_no_match),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        items(filteredLogs, key = { it.id }) { log ->
                            AuditLogRow(
                                log = log,
                                dateFormat = dateFormat,
                                onLongClick = { detailDialog = log },
                            )
                        }
                    }
                    // 分页加载器:只要还有更多原始数据就显示,用户滚动到底部触发加载下一页
                    if (hasMore) {
                        item(key = "footer_loader") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = MusePaddings.contentGap),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                                    strokeWidth = 2.dp,
                                )
                            }
                            LaunchedEffect(Unit) { loadMore() }
                        }
                    }
                }
            }
        }
    }

    // 详情弹窗:展示完整 detail JSON
    detailDialog?.let { log ->
        MuseDialog(
            onDismissRequest = { detailDialog = null },
            title = formatDialogTitle(log, dateFormat),
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.Start,
                ) {
                    InfoLine(label = "Action", value = log.action)
                    if (log.target.isNotBlank()) {
                        InfoLine(label = "Target", value = log.target)
                    }
                    InfoLine(label = "Success", value = if (log.success) "true" else "false")
                    if (log.detail.isNotBlank() && log.detail != "{}") {
                        Spacer(Modifier.height(MusePaddings.contentGap))
                        Text(
                            text = "Detail",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(MusePaddings.tightGap))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MuseShapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        ) {
                            Text(
                                text = prettyJson(log.detail),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(MusePaddings.contentGap),
                            )
                        }
                    }
                }
            },
            confirmText = stringResource(R.string.common_confirm),
            onConfirm = { detailDialog = null },
            dismissText = null,
        )
    }

    // 清空确认弹窗
    if (showClearDialog) {
        MuseDialog(
            onDismissRequest = { showClearDialog = false },
            title = stringResource(R.string.audit_log_clear),
            content = {
                Text(
                    text = stringResource(R.string.audit_log_clear_confirm),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            },
            confirmText = stringResource(R.string.audit_log_clear),
            onConfirm = {
                scope.launch {
                    auditLogger.clearAll()
                    logs = emptyList()
                    hasMore = false
                    page = 0
                    selectedCategory = null
                    searchQuery = ""
                }
                showClearDialog = false
            },
            destructive = true,
        )
    }
}

/**
 * 筛选区:类别 FlowRow Chips + 关键词搜索框。
 *
 * Chips:全部 / API 调用 / 用户操作 / 认证 / 系统,单选互斥,再次点击同一项取消选择。
 * 搜索框:实时按 action / target / detail 模糊匹配(忽略大小写)。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterHeader(
    selectedCategory: String?,
    onCategorySelected: (String?) -> Unit,
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
) {
    val categories = remember {
        listOf(
            null to R.string.audit_log_category_all,
            "api_call" to R.string.audit_log_category_api,
            "user_action" to R.string.audit_log_category_user,
            "auth" to R.string.audit_log_category_auth,
            "system" to R.string.audit_log_category_system,
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MusePaddings.tightGap),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        ) {
            categories.forEach { (cat, labelRes) ->
                IosChip(
                    selected = selectedCategory == cat,
                    onClick = {
                        onCategorySelected(if (selectedCategory == cat) null else cat)
                    },
                    label = stringResource(labelRes),
                )
            }
        }
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChanged,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    text = stringResource(R.string.audit_log_search_hint),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
            },
            trailingIcon = if (searchQuery.isNotEmpty()) {
                {
                    IconButton(onClick = { onSearchQueryChanged("") }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.audit_log_clear_search),
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                    }
                }
            } else null,
            singleLine = true,
            shape = MuseShapes.large,
            textStyle = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * 单条审计日志的行视图。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AuditLogRow(
    log: AuditLogEntity,
    dateFormat: SimpleDateFormat,
    onLongClick: () -> Unit,
) {
    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = onLongClick,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 分类图标(圆形背景 + 不同图标)
            CategoryIcon(category = log.category)
            Spacer(Modifier.size(MusePaddings.contentGap))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = log.action,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.size(MusePaddings.contentGap))
                    // success / fail 标记(用 Material Icons 表达,遵循项目"禁止 emoji"约定)
                    val statusColor = if (log.success) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                    val statusIcon = if (log.success) {
                        Icons.Filled.Check
                    } else {
                        Icons.Filled.Close
                    }
                    Surface(
                        shape = CircleShape,
                        color = statusColor.copy(alpha = 0.15f),
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = statusIcon,
                                contentDescription = null,
                                tint = statusColor,
                                modifier = Modifier.size(MuseIconSizes.iconTiny),
                            )
                        }
                    }
                }
                Spacer(Modifier.size(MusePaddings.tightGap))
                // 时间戳
                Text(
                    text = dateFormat.format(Date(log.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // target(若有)
                if (log.target.isNotBlank()) {
                    Text(
                        text = "→ ${log.target}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 分类图标 — 按 [category] 显示不同图标与背景色。
 *
 *  - api_call: Api 图标 + primary
 *  - user_action: Person 图标 + secondary
 *  - auth: Lock 图标 + tertiary
 *  - system: Settings 图标 + surfaceVariant
 *  - 其他: 默认 Settings 图标
 */
@Composable
private fun CategoryIcon(category: String) {
    val (icon, tint) = when (category) {
        "api_call" -> Icons.Outlined.Api to MaterialTheme.colorScheme.primary
        "user_action" -> Icons.Outlined.Person to MaterialTheme.colorScheme.secondary
        "auth" -> Icons.Outlined.Lock to MaterialTheme.colorScheme.tertiary
        "system" -> Icons.Outlined.Settings to MaterialTheme.colorScheme.onSurfaceVariant
        else -> Icons.Outlined.Settings to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = CircleShape,
        color = tint.copy(alpha = 0.15f),
        modifier = Modifier.size(MuseIconSizes.iconLarge),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
            )
        }
    }
}

/** 详情弹窗中的键值对行。 */
@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MusePaddings.tightGap),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 详情弹窗标题(时间 + action)。 */
private fun formatDialogTitle(log: AuditLogEntity, dateFormat: SimpleDateFormat): String {
    return dateFormat.format(Date(log.timestamp))
}

/**
 * 把紧凑的 JSON 字符串格式化为带缩进的可读形式。
 *
 * 用极简状态机解析,避免引入额外 JSON 库依赖。输入非合法 JSON 时原样返回。
 */
private fun prettyJson(json: String): String {
    if (json.isBlank()) return ""
    val builder = StringBuilder(json.length * 2)
    var indent = 0
    var inString = false
    var escaped = false
    for (ch in json) {
        if (inString) {
            builder.append(ch)
            if (escaped) {
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else if (ch == '"') {
                inString = false
            }
            continue
        }
        when (ch) {
            '"' -> {
                inString = true
                builder.append(ch)
            }
            '{', '[' -> {
                builder.append(ch)
                indent++
                builder.append('\n').append("  ".repeat(indent))
            }
            '}', ']' -> {
                indent = (indent - 1).coerceAtLeast(0)
                builder.append('\n').append("  ".repeat(indent)).append(ch)
            }
            ',' -> {
                builder.append(ch)
                builder.append('\n').append("  ".repeat(indent))
            }
            ':' -> {
                builder.append(": ")
            }
            ' ', '\t', '\n', '\r' -> {
                // 跳过原有空白,用我们的格式替代
            }
            else -> {
                builder.append(ch)
            }
        }
    }
    return builder.toString()
}

/**
 * 把筛选后的日志列表导出为 JSON 文件并通过 ACTION_SEND 分享。
 *
 * JSON 结构:`[{ id, timestamp, iso_time, category, action, target, detail, success }, ...]`
 * 文件位于 cacheDir(已在 file_paths.xml 中通过 cache-path 暴露给 FileProvider)。
 */
private fun shareAuditLogsAsJson(context: android.content.Context, logs: List<AuditLogEntity>) {
    if (logs.isEmpty()) {
        MuseToast.show(context.getString(R.string.audit_log_export_empty))
        return
    }
    val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault())
    val jsonArray: JsonArray = buildJsonArray {
        for (log in logs) {
            add(buildJsonObject {
                put("id", log.id)
                put("timestamp", log.timestamp)
                put("iso_time", isoFormat.format(Date(log.timestamp)))
                put("category", log.category)
                put("action", log.action)
                put("target", log.target)
                put("detail", log.detail)
                put("success", log.success)
            })
        }
    }
    val compactJson = AppJson.encodeToString(JsonArray.serializer(), jsonArray)
    val prettyJsonString = prettyJson(compactJson)

    runCatching {
        val exportFile = File(
            context.cacheDir,
            "audit_logs_export_${System.currentTimeMillis()}.json",
        )
        exportFile.writeText(prettyJsonString)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            exportFile,
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(
                android.content.Intent.EXTRA_SUBJECT,
                context.getString(R.string.audit_log_export_subject, logs.size),
            )
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            android.content.Intent.createChooser(intent, context.getString(R.string.audit_log_export_chooser))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        MuseToast.show(context.getString(R.string.audit_log_export_failed))
    }
}
