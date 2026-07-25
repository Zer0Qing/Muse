package io.zer0.muse.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import io.zer0.muse.ui.common.IosChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import io.zer0.muse.ui.common.IosTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.R
import io.zer0.muse.ui.common.EmptyState
import io.zer0.muse.ui.markdown.MarkdownText
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseIconSizes
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 8.3: 跨会话收藏夹页。
 *
 * - 数据源: [ChatViewModel.state.favoriteMessages](observeAllFavorites 聚合的全部收藏消息)
 * - 列表项: 角色 + 模型 + 时间戳 + 内容预览(前 280 字,超出截断)+ 取消收藏按钮
 * - 内容渲染: 直接用 [MarkdownText](支持代码块/链接点击/列表等),保证与聊天页一致
 * - 取消收藏: 调 [ChatViewModel.toggleFavorite],乐观更新 + DB 同步
 *
 * 设计: warm-paper 卡片样式(圆角 12dp,surfaceVariant 背景),与 SettingsScreen 卡片一致。
 *
 * v1.104 U7: 顶部增加 FilterChip 分组筛选行(全部 / 未分组 / 各 tag),
 * 长按卡片弹"设置分组"对话框,可自定义分组名(留空 = 移到未分组)。
 */
@Composable
fun FavoritesScreen(
    onBack: () -> Unit,
    viewModel: ChatViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dateFormat = remember { SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL, Locale.getDefault()) }

    // v1.104 U7: 长按卡片时弹"设置分组"对话框的目标消息
    var tagEditTarget by remember { mutableStateOf<UIMessage?>(null) }

    // v2.0: 收藏时弹出分组选择对话框的目标消息
    var favoriteGroupTarget by remember { mutableStateOf<UIMessage?>(null) }

    // v2.0: 当前 filter 下显示的收藏列表(本地过滤,不影响 state.favoriteMessages 全量)
    val visibleFavorites = remember(state.favoriteMessages, state.favoriteTagFilter, state.favoriteGroup) {
        val groupFiltered = if (state.favoriteGroup != null) {
            state.favoriteMessages.filter { it.favoriteTag == state.favoriteGroup }
        } else {
            state.favoriteMessages
        }
        when (state.favoriteTagFilter) {
            null -> groupFiltered
            ChatViewModel.FAVORITE_TAG_UNGROUPED -> groupFiltered.filter { it.favoriteTag == null }
            else -> groupFiltered.filter { it.favoriteTag == state.favoriteTagFilter }
        }
    }

    Scaffold(
        topBar = {
            IosTopBar(
                title = stringResource(R.string.favorites_title, state.favoriteMessages.size),
                onBack = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // v2.0: 预设分类 FilterChip 行(仅有收藏时显示)
            if (state.favoriteMessages.isNotEmpty()) {
                FavoriteGroupFilterRow(
                    currentGroup = state.favoriteGroup,
                    onGroupChange = { viewModel.setFavoriteGroup(it) },
                )
                val tagCounts = remember(state.favoriteMessages) {
                    state.favoriteMessages.groupBy { it.favoriteTag }.mapValues { it.value.size }
                }
                FavoriteTagFilterRow(
                    allCount = state.favoriteMessages.size,
                    ungroupedCount = tagCounts[null] ?: 0,
                    tags = state.favoriteTags,
                    tagCounts = tagCounts,
                    currentFilter = if (state.favoriteGroup != null) null else state.favoriteTagFilter,
                    onFilterChange = { viewModel.setFavoriteTagFilter(it) },
                )
            }
            // v1.77: 首次加载时显示 loading,避免闪空状态
            if (state.isFavoritesLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (state.favoriteMessages.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        icon = Icons.Outlined.Star,
                        title = stringResource(R.string.favorites_empty_title),
                        subtitle = stringResource(R.string.favorites_empty_subtitle),
                    )
                }
            } else if (visibleFavorites.isEmpty()) {
                // v1.104 U7: 该分组下无收藏(区别于"完全没有收藏")
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        icon = Icons.Outlined.Star,
                        title = stringResource(R.string.favorites_empty_in_filter),
                        subtitle = null,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        horizontal = 16.dp,
                        vertical = 12.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(visibleFavorites, key = { it.id }) { msg ->
                        FavoriteCard(
                            message = msg,
                            dateFormat = dateFormat,
                            onUnfavorite = { viewModel.toggleFavorite(msg.id) },
                            onLongClick = { tagEditTarget = msg },
                            onClick = { favoriteGroupTarget = msg },
                        )
                    }
                }
            }
        }
    }

    // v2.0: 收藏时分组选择对话框
    favoriteGroupTarget?.let { msg ->
        FavoriteGroupSelectDialog(
            currentTag = msg.favoriteTag,
            onSelect = { group ->
                viewModel.setMessageFavoriteTag(msg.id, group)
                favoriteGroupTarget = null
            },
            onDismiss = { favoriteGroupTarget = null },
        )
    }

    // v1.104 U7: 设置分组对话框
    tagEditTarget?.let { msg ->
        FavoriteTagEditDialog(
            currentTag = msg.favoriteTag,
            onConfirm = { newTag ->
                viewModel.setMessageFavoriteTag(msg.id, newTag)
                tagEditTarget = null
            },
            onDismiss = { tagEditTarget = null },
        )
    }
}

/**
 * v2.0: 预设分类 FilterChip 行 — 横向滚动,显示"全部 / 灵感 / 代码 / 学习 / 自定义"。
 */
@Composable
private fun FavoriteGroupFilterRow(
    currentGroup: String?,
    onGroupChange: (String?) -> Unit,
) {
    val groups = listOf(
        null to stringResource(R.string.favorites_group_all),
        ChatViewModel.FAVORITE_GROUP_INSPIRATION to stringResource(R.string.favorites_group_inspiration),
        ChatViewModel.FAVORITE_GROUP_CODE to stringResource(R.string.favorites_group_code),
        ChatViewModel.FAVORITE_GROUP_LEARNING to stringResource(R.string.favorites_group_learning),
        ChatViewModel.FAVORITE_GROUP_CUSTOM to stringResource(R.string.favorites_group_custom),
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(groups, key = { it.first ?: "all" }) { (group, label) ->
            IosChip(
                selected = currentGroup == group,
                onClick = { onGroupChange(group) },
                label = label,
            )
        }
    }
}

/**
 * v2.0: 收藏时分组选择对话框 — 显示预设分类供用户选择。
 */
@Composable
private fun FavoriteGroupSelectDialog(
    currentTag: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val groups = listOf(
        null to stringResource(R.string.favorites_group_all),
        ChatViewModel.FAVORITE_GROUP_INSPIRATION to stringResource(R.string.favorites_group_inspiration),
        ChatViewModel.FAVORITE_GROUP_CODE to stringResource(R.string.favorites_group_code),
        ChatViewModel.FAVORITE_GROUP_LEARNING to stringResource(R.string.favorites_group_learning),
        ChatViewModel.FAVORITE_GROUP_CUSTOM to stringResource(R.string.favorites_group_custom),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.favorites_group_select_title)) },
        text = {
            Column {
                groups.forEach { (group, label) ->
                    TextButton(
                        onClick = { onSelect(group) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = label,
                            fontWeight = if (currentTag == group) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                        if (currentTag == group) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                if (currentTag != null && currentTag !in listOf(
                        ChatViewModel.FAVORITE_GROUP_INSPIRATION,
                        ChatViewModel.FAVORITE_GROUP_CODE,
                        ChatViewModel.FAVORITE_GROUP_LEARNING,
                        ChatViewModel.FAVORITE_GROUP_CUSTOM,
                    )
                ) {
                    HorizontalDivider()
                    TextButton(
                        onClick = { onSelect(currentTag) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = currentTag,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/**
 * v1.104 U7: 顶部 FilterChip 行 — 横向滚动,显示"全部 / 未分组 / 各 tag"。
 *
 * - "全部"永远显示,选中时 state.favoriteTagFilter = null
 * - "未分组"仅当存在 favoriteTag=null 的收藏时显示,选中时 filter = FAVORITE_TAG_UNGROUPED
 * - 各 tag 由 state.favoriteTags 提供,DAO 已去 NULL / 去重 / 升序
 */
@Composable
private fun FavoriteTagFilterRow(
    allCount: Int,
    ungroupedCount: Int,
    tags: List<String>,
    tagCounts: Map<String?, Int>,
    currentFilter: String?,
    onFilterChange: (String?) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "all") {
            IosChip(
                selected = currentFilter == null,
                onClick = { onFilterChange(null) },
                label = stringResource(R.string.favorites_filter_all, allCount),
            )
        }
        if (ungroupedCount > 0) {
            item(key = "ungrouped") {
                IosChip(
                    selected = currentFilter == ChatViewModel.FAVORITE_TAG_UNGROUPED,
                    onClick = { onFilterChange(ChatViewModel.FAVORITE_TAG_UNGROUPED) },
                    label = stringResource(R.string.favorites_filter_ungrouped, ungroupedCount),
                )
            }
        }
        items(tags, key = { it }) { tag ->
            IosChip(
                selected = currentFilter == tag,
                onClick = { onFilterChange(tag) },
                label = stringResource(R.string.favorites_tag_chip, tag, tagCounts[tag] ?: 0),
            )
        }
    }
}

/**
 * v1.104 U7: 设置分组对话框。
 *
 * - 输入框初始值 = currentTag(可空)
 * - 确定按钮:把输入框值传给 onConfirm(trim 后空字符串视为 null = 移到未分组)
 * - 清除分组按钮(仅当 currentTag 非空时显示):直接传 null 给 onConfirm
 * - 取消按钮:关闭对话框,不改任何东西
 */
@Composable
private fun FavoriteTagEditDialog(
    currentTag: String?,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(currentTag ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.favorites_tag_edit_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.favorites_tag_edit_hint)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text(stringResource(R.string.favorites_tag_edit_confirm))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!currentTag.isNullOrBlank()) {
                    TextButton(onClick = { onConfirm(null) }) {
                        Text(stringResource(R.string.favorites_tag_edit_clear))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.favorites_tag_edit_cancel))
                }
            }
        },
    )
}

/**
 * 单条收藏消息卡片。
 *
 * v1.104 U7: 加 onLongClick 回调,长按弹"设置分组"对话框;
 * 若 message.favoriteTag 非空,卡片底部显示分组标签。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteCard(
    message: UIMessage,
    dateFormat: SimpleDateFormat,
    onUnfavorite: () -> Unit,
    onLongClick: () -> Unit,
    onClick: () -> Unit = {},
) {
    val roleLabel = when (message.role) {
        MessageRole.USER -> stringResource(R.string.favorites_role_user)
        MessageRole.ASSISTANT -> "AI"
        MessageRole.SYSTEM -> stringResource(R.string.favorites_role_system)
        MessageRole.TOOL -> stringResource(R.string.favorites_role_tool)
    }
    val roleColor = when (message.role) {
        MessageRole.USER -> MaterialTheme.colorScheme.primary
        MessageRole.ASSISTANT -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.outline
    }
    Card(
        shape = MuseShapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 顶部行: 角色标签 + 模型 + 时间 + 取消收藏按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = roleLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = roleColor,
                        modifier = Modifier
                            .border(
                                width = 1.dp,
                                color = roleColor,
                                shape = MuseShapes.extraSmall,
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    message.modelId?.takeIf { it.isNotBlank() }?.let { model ->
                        Text(
                            text = model,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = dateFormat.format(Date(message.createdAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    IconButton(
                        onClick = onUnfavorite,
                        modifier = Modifier.size(MuseIconSizes.touchTarget),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = stringResource(R.string.favorites_unfavorite_cd),
                            tint = MaterialTheme.colorScheme.secondary,
                            // L-FS1: 18dp 硬编码改用 MuseIconSizes.iconSmall 令牌
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // 内容预览: 前 280 字用 MarkdownText 渲染(支持代码块/链接),超出截断
            val preview = if (message.content.length > 280) {
                message.content.take(280) + "…"
            } else {
                message.content
            }
            MarkdownText(
                text = preview,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                // Phase 8.4: UrlCitation — 收藏夹预览也支持 [N] 引用点击
                citationUrls = message.citationUrls,
            )
            // v1.104 U7: 卡片底部显示分组标签(若有)
            message.favoriteTag?.takeIf { it.isNotBlank() }?.let { tag ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.favorites_tag_label, tag),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}
