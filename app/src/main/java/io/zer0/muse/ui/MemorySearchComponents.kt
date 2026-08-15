@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "CyclomaticComplexMethod", "TooManyFunctions", "ReturnCount", "TooGenericExceptionCaught", "SwallowedException", "MaxLineLength")
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)


package io.zer0.muse.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import io.zer0.muse.ui.common.form.MuseChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.markdown.MarkdownText
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.ui.common.surface.MuseSurface
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * iOS 风格搜索栏 — Surface + BasicTextField,surfaceVariant 背景,圆角。
 */
@Composable
internal fun MemorySearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MuseShapes.semiLarge,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MusePaddings.screen, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = stringResource(R.string.memory_screen_search_cd),
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(R.string.memory_screen_search_fact_hint),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }
    }
}

/**
 * v9: 根据分类筛选记忆条目(null=全部)。
 */
internal fun filterMemoryItemsByCategory(
    items: List<MemoryItem>,
    category: String?,
): List<MemoryItem> {
    return if (category == null) items else items.filter {
        it.category.equals(category, ignoreCase = true)
    }
}

internal fun isToday(createdAt: String?): Boolean {
    if (createdAt == null) return false
    return try {
        val date = java.time.Instant.parse(createdAt)
        val today = java.time.LocalDate.now()
        val itemDate = date.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        itemDate == today
    } catch (e: Exception) { false }
}

internal fun isThisWeek(createdAt: String?): Boolean {
    if (createdAt == null) return false
    return try {
        val date = java.time.Instant.parse(createdAt)
        val today = java.time.LocalDate.now()
        val itemDate = date.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val weekStart = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        !itemDate.isBefore(weekStart) && !itemDate.isAfter(today)
    } catch (e: Exception) { false }
}

internal fun isThisMonth(createdAt: String?): Boolean {
    if (createdAt == null) return false
    return try {
        val date = java.time.Instant.parse(createdAt)
        val today = java.time.LocalDate.now()
        val itemDate = date.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        itemDate.year == today.year && itemDate.month == today.month
    } catch (e: Exception) { false }
}

/**
 * v9: 分类筛选胶囊 — 全部 / 核心事实 / 偏好 / 经历 / 关系 / 目标 / 医疗。
 *
 * 参考图风格:横向滚动,选中项用 inverseSurface 深色药丸,未选中用浅灰。
 */
@Composable
internal fun CategoryFilterChipRow(
    selectedCategory: String?,
    onSelect: (String?) -> Unit,
) {
    val scrollState = rememberScrollState()
    val categories = listOf(
        null to stringResource(R.string.memory_category_all),
        "identity" to stringResource(R.string.memory_category_identity),
        "preference" to stringResource(R.string.memory_category_preference),
        "event" to stringResource(R.string.memory_category_event),
        "relationship" to stringResource(R.string.memory_category_relationship),
        "goal" to stringResource(R.string.memory_category_goal),
        "medical" to stringResource(R.string.memory_category_medical),
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = MusePaddings.screen, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        categories.forEach { (category, label) ->
            val selected = selectedCategory == category
            CategoryChip(
                label = label,
                selected = selected,
                onClick = { onSelect(category) },
            )
        }
    }
}

@Composable
internal fun CategoryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = MuseShapes.pill,
        color = if (selected) MaterialTheme.colorScheme.inverseSurface
            else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.inverseOnSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/**
 * v8: 作用域筛选行 — 横向滚动 MuseChip。
 */
@Composable
internal fun ScopeFilterChipRow(
    options: List<ScopeOption>,
    selectedScope: String?,
    onSelect: (String?) -> Unit,
) {
    val scrollState = rememberScrollState()

    Text(
        text = stringResource(R.string.memory_scope_label),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen, vertical = 2.dp),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = MusePaddings.screen, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { option ->
            val isSelected = selectedScope == option.id
            MuseChip(
                selected = isSelected,
                onClick = { onSelect(option.id) },
                label = option.displayName,
            )
        }
    }
}

/**
 * v8: 单条 Fact 项的 scope 徽章。
 */
@Composable
internal fun ScopeBadge(scope: String?) {
    if (scope.isNullOrBlank() || scope == "main") return
    val label = stringResource(R.string.memory_scope_assistant) + " · " + scope.take(6)
    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * 搜索结果列表 — CardGroup 容器。
 */
@Composable
internal fun SearchResultsList(
    results: List<MemoryItem>,
    isSearching: Boolean,
    onDelete: (String) -> Unit,
    headerContent: @Composable () -> Unit = {},
) {
    if (isSearching) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(MusePaddings.emptyStateGap),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
            )
        }
        return
    }
    if (results.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(MusePaddings.emptyStateGap),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.memory_screen_no_match),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = MusePaddings.screen,
            vertical = MusePaddings.contentGap,
        ),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
    ) {
        item { headerContent() }
        item {
            Text(
                text = stringResource(R.string.memory_screen_search_results, results.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 前端修复 (性能-2): 搜索结果由 CardGroup 全量 Column 渲染改为
        // LazyColumn 顶层 items(key=id) 平铺,懒加载渲染;每行独立圆角卡片
        // (布局对齐原 CardGroup 项的 headline/supporting/trailing 结构)。
        itemsIndexed(results, key = { _, item -> item.id }) { _, item ->
            MuseSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = MuseShapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                elevation = MuseElevation.card,
                tonalElevation = MuseElevation.none,
                border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        MemoryRowHeadline(item)
                        MemoryRowSupporting(item)
                    }
                    Spacer(Modifier.width(MusePaddings.contentGap))
                    MemoryRowTrailing(
                        item = item,
                        onDelete = { onDelete(item.id) },
                        onEdit = null,
                        onSetImportance = null,
                    )
                }
            }
        }
    }
}

/**
 * v9: 按分类分组展示记忆条目(参考图风格)。
 *
 * 当未选择具体分类时,按 category 分组,每个分组一个小标题 + 独立卡片组。
 * 当选中某个分类时,该分类下条目直接展示,不再重复分组标题。
 */
internal fun LazyListScope.categoryGroupedMemoryListItems(
    items: List<MemoryItem>,
    selectedCategory: String?,
    onDelete: (String) -> Unit,
    onEdit: (MemoryItem) -> Unit,
    onSetImportance: (MemoryItem) -> Unit,
    onTogglePin: (MemoryItem) -> Unit,
) {
    if (items.isEmpty()) {
        item {
            EmptyHint(text = stringResource(R.string.memory_screen_no_fact))
        }
        return
    }

    // 分类顺序(按参考图):identity / preference / event / relationship / goal / medical / other
    val categoryOrder = listOf("identity", "preference", "event", "relationship", "goal", "medical", "other")

    // 命中 selectedCategory 时,直接平铺列表
    if (selectedCategory != null) {
        // 前端修复 (性能-2): 由 item { MemoryCardGroup } 全量渲染改为顶层平铺
        memoryCardGroupItems(
            items = items,
            onDelete = onDelete,
            onEdit = onEdit,
            onSetImportance = onSetImportance,
            onTogglePin = onTogglePin,
        )
        return
    }

    // 未选中分类:按 category 分组
    val grouped = items.groupBy {
        it.category?.lowercase()?.takeIf { c -> categoryOrder.contains(c) } ?: "other"
    }

    categoryOrder.forEach { category ->
        val groupItems = grouped[category] ?: emptyList()
        if (groupItems.isEmpty()) return@forEach

        item {
            CategorySectionHeader(
                title = categoryDisplayName(category),
                count = groupItems.size,
            )
        }
        // 前端修复 (性能-2): 组内由 item { MemoryCardGroup } 全量渲染改为顶层平铺
        memoryCardGroupItems(
            items = groupItems,
            onDelete = onDelete,
            onEdit = onEdit,
            onSetImportance = onSetImportance,
            onTogglePin = onTogglePin,
        )
    }
}

@Composable
internal fun categoryDisplayName(category: String): String {
    return when (category) {
        "identity" -> stringResource(R.string.memory_category_identity)
        "preference" -> stringResource(R.string.memory_category_preference)
        "event" -> stringResource(R.string.memory_category_event)
        "relationship" -> stringResource(R.string.memory_category_relationship)
        "goal" -> stringResource(R.string.memory_category_goal)
        "medical" -> stringResource(R.string.memory_category_medical)
        else -> stringResource(R.string.memory_category_other)
    }
}

/**
 * 分类分组标题 — iOS 风格居中/左对齐小字。
 */
@Composable
internal fun CategorySectionHeader(
    title: String,
    count: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * 前端修复 (性能-2): 记忆行分组 — 由 @Composable Column 全量渲染改为
 * LazyListScope items(key=id) 顶层平铺,懒加载渲染。
 * 每行独立圆角卡片(替代原整组圆角卡 + 内部 divider)。
 */
internal fun LazyListScope.memoryCardGroupItems(
    items: List<MemoryItem>,
    onDelete: (String) -> Unit,
    onEdit: (MemoryItem) -> Unit,
    onSetImportance: (MemoryItem) -> Unit,
    onTogglePin: (MemoryItem) -> Unit,
) {
    itemsIndexed(items, key = { _, item -> item.id }) { _, item ->
        MuseSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = MuseShapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            elevation = MuseElevation.card,
            tonalElevation = MuseElevation.none,
            border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            MemoryFactRow(
                item = item,
                onDelete = { onDelete(item.id) },
                onEdit = onEdit,
                onSetImportance = onSetImportance,
                onTogglePin = onTogglePin,
            )
        }
    }
}

/**
 * v1.x: 单条记忆行 — 正文全宽(可换行完整显示) + meta 行 + ⋮ 操作菜单。
 * 操作(置顶/重要度/编辑/删除)收进菜单,不再挤占正文。
 */
@Composable
private fun MemoryFactRow(
    item: MemoryItem,
    onDelete: () -> Unit,
    onEdit: (MemoryItem) -> Unit,
    onSetImportance: (MemoryItem) -> Unit,
    onTogglePin: (MemoryItem) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = MusePaddings.screen, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.content,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = buildMetaText(item),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (item.importance > 0) {
                    ImportanceTag(importance = item.importance)
                }
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.memory_menu_cd),
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(if (item.pinnedAt == null) R.string.memory_menu_pin else R.string.memory_menu_unpin)) },
                    onClick = {
                        menuOpen = false
                        onTogglePin(item)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.memory_menu_importance)) },
                    onClick = {
                        menuOpen = false
                        onSetImportance(item)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.memory_menu_edit)) },
                    onClick = {
                        menuOpen = false
                        onEdit(item)
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.memory_menu_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/**
 * 记忆卡片主体内容 — 与参考图一致:正文 + 日期/作用域 meta + 重要性标签。
 */
@Composable
internal fun MemoryCardContent(item: MemoryItem) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = item.content,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = buildMetaText(item),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            if (item.importance > 0) {
                ImportanceTag(importance = item.importance)
            }
        }
    }
}

@Composable
internal fun buildMetaText(item: MemoryItem): String {
    val dateText = item.time?.take(10)
        ?: item.createdAt?.take(10)
        ?: ""
    val scopeText = if (!item.scope.isNullOrBlank() && item.scope != "main") {
        stringResource(R.string.memory_scope_assistant) + " · " + item.scope.take(6)
    } else ""
    return when {
        dateText.isNotBlank() && scopeText.isNotBlank() -> "$dateText · $scopeText"
        dateText.isNotBlank() -> dateText
        scopeText.isNotBlank() -> scopeText
        else -> ""
    }
}

/**
 * 重要性小标签(右侧绿色/红色药丸)。
 */
@Composable
internal fun ImportanceTag(importance: Int) {
    if (importance <= 0) return
    val text = if (importance >= 2) stringResource(R.string.memory_importance_critical)
        else stringResource(R.string.memory_importance_important)
    val color = if (importance >= 2) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = MuseShapes.pill,
        color = color.copy(alpha = 0.12f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * 记忆卡片操作区 — 编辑 / 删除 / 设置重要程度。
 */
@Composable
internal fun MemoryCardTrailing(
    item: MemoryItem,
    onDelete: (() -> Unit)?,
    onEdit: ((MemoryItem) -> Unit)?,
    onSetImportance: ((MemoryItem) -> Unit)?,
    onTogglePin: ((MemoryItem) -> Unit)?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (onSetImportance != null) {
            IconButton(onClick = { onSetImportance(item) }) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = stringResource(R.string.memory_sync_set_importance_cd),
                    tint = when (item.importance) {
                        2 -> MaterialTheme.colorScheme.error
                        1 -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.outline
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (onEdit != null) {
        if (onTogglePin != null) {
            IconButton(onClick = { onTogglePin(item) }) {
                Icon(
                    imageVector = Icons.Filled.PushPin,
                    contentDescription = stringResource(
                        if (item.pinnedAt == null) R.string.memory_pin_cd else R.string.memory_unpin_cd,
                    ),
                    tint = if (item.pinnedAt != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
            IconButton(onClick = { onEdit(item) }) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.memory_screen_edit_cd),
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (onDelete != null) {
            IconButton(onClick = { onDelete() }) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.memory_screen_delete_cd),
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * 记忆行标题区 — 重要性徽章 + 标题。
 */
@Composable
internal fun MemoryRowHeadline(item: MemoryItem) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (item.importance > 0) {
            ImportanceBadge(importance = item.importance)
        }
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 记忆行内容区 — markdown 内容 + 标签/时间/作用域 meta。
 */
@Composable
internal fun MemoryRowSupporting(item: MemoryItem) {
    Column {
        if (item.content.isNotBlank()) {
            Spacer(Modifier.size(4.dp))
            MarkdownText(
                text = item.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val showScopeBadge = !item.scope.isNullOrBlank() && item.scope != "main"
        if (item.tags.isNotEmpty() || item.time != null || showScopeBadge) {
            Spacer(Modifier.size(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item.tags.forEach { tag ->
                    Text(
                        text = "#$tag",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (item.time != null) {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = item.time,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                ScopeBadge(scope = item.scope)
            }
        }
        val createdAtText = formatCreatedAtText(item.createdAt)
        if ((item.source == "Fact" && item.sessionId != null) || createdAtText != null) {
            Spacer(Modifier.size(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (item.source == "Fact" && item.sessionId != null) {
                    Text(
                        text = stringResource(R.string.memory_source_session, item.sessionId.take(8)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (createdAtText != null) {
                    Text(
                        text = createdAtText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

/**
 * 记忆行操作区 — 设置重要程度 / 编辑 / 删除。
 */
@Composable
internal fun MemoryRowTrailing(
    item: MemoryItem,
    onDelete: ((MemoryItem) -> Unit)?,
    onEdit: ((MemoryItem) -> Unit)?,
    onSetImportance: ((MemoryItem) -> Unit)?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (onSetImportance != null) {
            IconButton(
                onClick = { onSetImportance(item) },
            ) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = stringResource(R.string.memory_sync_set_importance_cd),
                    tint = when (item.importance) {
                        2 -> MaterialTheme.colorScheme.error
                        1 -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.outline
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (onEdit != null) {
            IconButton(
                onClick = { onEdit(item) },
            ) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.memory_screen_edit_cd),
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (onDelete != null) {
            IconButton(
                onClick = { onDelete(item) },
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.memory_screen_delete_cd),
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * v4: 重要程度徽章。
 */
@Composable
internal fun ImportanceBadge(importance: Int) {
    if (importance <= 0) return
    val icon = if (importance >= 2) Icons.Filled.Warning else Icons.Filled.PriorityHigh
    val text = if (importance >= 2) {
        stringResource(R.string.memory_importance_critical)
    } else {
        stringResource(R.string.memory_importance_important)
    }
    val color = if (importance >= 2) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.tertiary
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * v4: 根据 createdAt 计算相对时间文案。
 */
@Composable
internal fun formatCreatedAtText(createdAt: String?): String? {
    if (createdAt == null) return null
    val days = try {
        Duration.between(Instant.parse(createdAt), Instant.now()).toDays()
    } catch (e: Exception) {
        return null
    }
    return when {
        days <= 0L -> stringResource(R.string.memory_created_today)
        days == 1L -> stringResource(R.string.memory_created_yesterday)
        else -> stringResource(R.string.memory_created_days_ago, days.toInt())
    }
}

/**
 * 错误堆栈展示框。
 */
@Composable
internal fun ErrorTraceBox(
    trace: String,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(MusePaddings.cardInnerSpaced),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.memory_screen_load_failed),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            IconButton(onClick = {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("Muse Error Trace", trace)
                )
                MuseToast.show(context.getString(R.string.memory_screen_copied_trace))
            }) {
                Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.memory_screen_copy_trace_cd))
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
            shape = MuseShapes.small,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Text(
                text = trace,
                modifier = Modifier
                    .verticalScroll(scrollState)
                    .padding(MusePaddings.itemGap),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = MuseMonoFontFamily,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Button(
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            shape = MuseShapes.large,
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.memory_screen_retry))
        }
    }
}

/**
 * 空状态提示。
 */
@Composable
internal fun EmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MusePaddings.largeGap),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

