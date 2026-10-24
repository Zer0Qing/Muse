@file:Suppress("FunctionNaming", "CyclomaticComplexMethod")

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseFormDialog
import io.zer0.muse.ui.common.form.MuseIconContainer
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.museAnimateItem
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.state.MuseErrorStateBox
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.MuseSurface
import io.zer0.muse.ui.markdown.MarkdownPreviewTruncation
import io.zer0.muse.ui.markdown.MarkdownText
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseShapes
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 8.3: 跨会话收藏夹页。
 *
 * - 数据源: [ChatViewModel.state.favoriteMessages](observeAllFavorites 聚合的全部收藏消息)
 * - 列表项: 角色 + 模型 + 时间戳 + 内容预览(结构感知截断)+ 取消收藏按钮
 * - 内容渲染: 直接用 [MarkdownText](支持代码块/链接点击/列表等),保证与聊天页一致
 * - 取消收藏: 调 [ChatViewModel.toggleFavorite],乐观更新 + DB 同步
 *
 * 设计: warm-paper 卡片样式(圆角 12dp,surfaceVariant 背景),与 SettingsScreen 卡片一致。
 *
 * §11 收敛后只有**一套**分组:用户自定义 tag(favoriteTag 列)。
 * 预设分类对话框已移除(预设 chip 行在 U-20 被砍后,预设值只剩内部 token 裸奔在 UI)。
 * 存量 `__group_*__` 值读取时映射成本地化文案(见 [favoriteTagLabel]),不单独建迁移。
 */
@Composable
fun FavoritesScreen(onBack: () -> Unit, viewModel: ChatViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val staleFavoriteIds by viewModel.staleFavoriteIds.collectAsStateWithLifecycle()
    val dateFormat = remember { SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL, Locale.getDefault()) }

    // v1.104 U7: 长按/点分组按钮时弹"设置分组"对话框的目标消息
    var tagEditTarget by remember { mutableStateOf<UIMessage?>(null) }

    // v2.6.2（用户反馈）: 点击卡片 → 全屏原文预览（此前 onClick 被分组对话框占用，
    // 用户“没法点开看完整原文”）。
    var fullTextTarget by remember { mutableStateOf<UIMessage?>(null) }

    // 当前 filter 下显示的收藏列表(本地过滤,不影响 state.favoriteMessages 全量)
    val visibleFavorites = remember(state.favoriteMessages, state.favoriteTagFilter) {
        when (state.favoriteTagFilter) {
            null -> state.favoriteMessages
            ChatViewModel.FAVORITE_TAG_UNGROUPED -> state.favoriteMessages.filter { it.favoriteTag == null }
            else -> state.favoriteMessages.filter { it.favoriteTag == state.favoriteTagFilter }
        }
    }

    io.zer0.muse.ui.common.surface.MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.favorites_title, state.favoriteMessages.size),
                onBack = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // U-20: 只保留自定义 tag 行分级筛选。
            // §11-1: 预设分组对话框已移除 —— 它写的就是 `__group_*__` 内部 token,
            // 而预设 chip 行不存在,导致 token 直接裸在自定义 tag 行里。现在分组只有一条入口:
            // 卡片上的"设置分组"按钮 → 用户自己输入的分组名。
            if (state.favoriteMessages.isNotEmpty()) {
                val tagCounts = remember(state.favoriteMessages) {
                    state.favoriteMessages.groupBy { it.favoriteTag }.mapValues { it.value.size }
                }
                FavoriteTagFilterRow(
                    allCount = state.favoriteMessages.size,
                    ungroupedCount = tagCounts[null] ?: 0,
                    tags = state.favoriteTags,
                    tagCounts = tagCounts,
                    currentFilter = state.favoriteTagFilter,
                    onFilterChange = { viewModel.setFavoriteTagFilter(it) },
                )
            }
            // v1.77: 首次加载时显示 loading,避免闪空状态
            if (state.isFavoritesLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    MuseSpinner()
                }
            } else if (state.favoritesError != null) {
                // ST-01: 收藏列表加载失败错误态 + 重试
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    MuseErrorStateBox(
                        message = state.favoritesError.orEmpty(),
                        onRetry = { viewModel.retryLoadFavorites() },
                    )
                }
            } else if (state.favoriteMessages.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    MuseEmptyState(
                        icon = MuseIcons.star,
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
                    MuseEmptyState(
                        icon = MuseIcons.star,
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
                        Box(modifier = museAnimateItem()) {
                            FavoriteCard(
                                message = msg,
                                dateFormat = dateFormat,
                                isStaleVariant = msg.id in staleFavoriteIds,
                                onUnfavorite = { viewModel.toggleFavorite(msg.id) },
                                onLongClick = { tagEditTarget = msg },
                                // v2.6.2: 点击看完整原文；分组入口是卡片内按钮
                                onClick = { fullTextTarget = msg },
                                onGroupClick = { tagEditTarget = msg },
                            )
                        }
                    }
                }
            }
        }
    }

    // v1.104 U7: 设置分组对话框（§11-1 收敛后唯一的写入口：用户自定义 tag）
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

    // v2.6.2（用户反馈）: 全屏原文预览 —— 点击收藏卡片打开，可滚动看完整内容。
    fullTextTarget?.let { msg ->
        FavoriteFullTextDialog(message = msg, dateFormat = dateFormat, onDismiss = { fullTextTarget = null })
    }
}

/**
 * 收藏星标的选中态视觉（§11-8）。
 *
 * MuseIcons 里收藏只有一枚实心 `star`，没有 `starOutline`，而本项目不允许为了这个
 * 状态新增图标资源。所以选中/未选中靠三档既有能力区分，不靠换图标：
 *  1. 图标 tint：已收藏 = primary（主题强调色），未收藏 = outline（中性色）
 *  2. 按钮容器：有底色按钮用 [MuseIconContainer.Tonal] 表达"开着"（实心圆底）
 *  3. 直接画 Icon 的地方（朋友圈筛选）额外降 alpha 到 [FAVORITE_STAR_IDLE_ALPHA]
 * 三档叠加后深浅主题下都能一眼看出开/关，且不用新画一枚描边星。
 *
 * 原先四处写的是 `if (x) MuseIcons.star else MuseIcons.star`，两个分支取同一枚图标，
 * 等于状态位没参与渲染，只靠文案和容器色碰巧区分。
 */
internal const val FAVORITE_STAR_IDLE_ALPHA = 0.45f

/** 收藏星标颜色：选中 = 主题强调色，未选中 = 中性 outline。 */
@Composable
internal fun favoriteStarTint(selected: Boolean): Color = if (selected) {
    MaterialTheme.colorScheme.primary
} else {
    MaterialTheme.colorScheme.outline
}

/** 收藏星标透明度：只给直接画 Icon 的场景用（MuseTactileButton 不透传图标 alpha）。 */
internal fun favoriteStarAlpha(selected: Boolean): Float = if (selected) 1f else FAVORITE_STAR_IDLE_ALPHA

/**
 * 收藏分组的展示文案。
 *
 * §11-1: 历史数据里存过 `__group_inspiration__` 这类内部 token(v2.0 预设分类落库的)。
 * 预设 chip 行在 U-20 被移除后这些串就直接显示在筛选行与卡片上。这里在**读取时**
 * 映射成字符串资源,不做数据迁移:
 *  - 存量值仍能作为筛选键命中(筛选用的是原始值,不是展示文案)
 *  - 用户重新命名一次,新的就是普通自定义 tag
 *  - 不在 migrate 里写中文(迁移跑在非 UI 层,拿不到 locale)
 */
@Composable
private fun favoriteTagLabel(tag: String): String = when (tag) {
    ChatViewModel.FAVORITE_GROUP_INSPIRATION -> stringResource(R.string.favorites_group_inspiration)
    ChatViewModel.FAVORITE_GROUP_CODE -> stringResource(R.string.favorites_group_code)
    ChatViewModel.FAVORITE_GROUP_LEARNING -> stringResource(R.string.favorites_group_learning)
    ChatViewModel.FAVORITE_GROUP_CUSTOM -> stringResource(R.string.favorites_group_custom)
    else -> tag
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
            MuseChip(
                selected = currentFilter == null,
                onClick = { onFilterChange(null) },
                label = stringResource(R.string.favorites_filter_all, allCount),
            )
        }
        if (ungroupedCount > 0) {
            item(key = "ungrouped") {
                MuseChip(
                    selected = currentFilter == ChatViewModel.FAVORITE_TAG_UNGROUPED,
                    onClick = { onFilterChange(ChatViewModel.FAVORITE_TAG_UNGROUPED) },
                    label = stringResource(R.string.favorites_filter_ungrouped, ungroupedCount),
                )
            }
        }
        items(tags, key = { it }) { tag ->
            MuseChip(
                selected = currentFilter == tag,
                onClick = { onFilterChange(tag) },
                label = stringResource(R.string.favorites_tag_chip, favoriteTagLabel(tag), tagCounts[tag] ?: 0),
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
private fun FavoriteTagEditDialog(currentTag: String?, onConfirm: (String?) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(currentTag ?: "") }
    MuseFormDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.favorites_tag_edit_title),
        confirmText = stringResource(R.string.favorites_tag_edit_confirm),
        confirmEnabled = text.isNotBlank(),
        onConfirm = { onConfirm(text) },
        dismissText = stringResource(R.string.favorites_tag_edit_cancel),
        onDismiss = onDismiss,
        content = {
            MuseTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.favorites_tag_edit_hint)) },
                singleLine = true,
            )
            if (!currentTag.isNullOrBlank()) {
                MuseCapsuleButton(
                    text = stringResource(R.string.favorites_tag_edit_clear),
                    onClick = { onConfirm(null) },
                    variant = IosCapsuleButtonVariant.Text,
                    fillWidth = false,
                )
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
@Suppress("LongMethod", "LongParameterList")
@Composable
private fun FavoriteCard(
    message: UIMessage,
    dateFormat: SimpleDateFormat,
    onUnfavorite: () -> Unit,
    onLongClick: () -> Unit,
    /** §11-4: 该收藏属于变体组里已被新版本取代的旧行。 */
    isStaleVariant: Boolean = false,
    onClick: () -> Unit = {},
    /** v2.6.2: 分组入口（移到卡片内按钮，把点击让给“看原文”）。 */
    onGroupClick: () -> Unit = {},
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
    MuseSurface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
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
                    // §11-4: 该收藏是变体组里已被新版本取代的旧行 —— 如实告知,
                    // 不擅自换成最新版(换行会让取消收藏落到一条未收藏的行上)。
                    if (isStaleVariant) {
                        Text(
                            text = stringResource(R.string.favorites_stale_variant_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // LAYOUT-01: 尾部元信息槽（时间 + 收藏按钮）按内容宽度排布，只限行不取宽，
                    // 否则内层 Row 会吃掉外层行宽、把左侧角色/模型标签挤成竖排。
                    Text(
                        text = dateFormat.format(Date(message.createdAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MuseTactileButton(
                        icon = MuseIcons.star,
                        onClick = onUnfavorite,
                        contentDescription = stringResource(R.string.favorites_unfavorite_cd),
                        // §11-8: 收藏页里每一张卡都是已收藏，星标走选中态视觉（主色 + 实心圆底），
                        // 不再写死 secondary 色 —— 统一取 [favoriteStarTint] 一处口径。
                        tint = favoriteStarTint(selected = true),
                        container = MuseIconContainer.Tonal,
                        size = MuseIconSizes.touchTarget,
                    )
                    // v2.6.2: 分组入口（原来占用整卡点击，现缩小为小按钮）
                    MuseTactileButton(
                        icon = MuseIcons.bookmark,
                        onClick = onGroupClick,
                        contentDescription = stringResource(R.string.favorites_group_action_cd),
                        tint = MaterialTheme.colorScheme.outline,
                        size = MuseIconSizes.touchTarget,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            // 内容预览: 结构感知截断到约 280 字(§11-9)。
            // v2.6.8: 先走展示出口再截断 —— 否则卡片消息的预览里会冒出 cardId/JSON。
            // 先用 take(280) 硬切会切断代码围栏 / 成对强调符 / [N] 引用,
            // 未闭合围栏会让省略号之后的全部内容渲染成代码块。
            val body = io.zer0.muse.transformer.InternalMarkupSanitizer.displayContent(message.content)
            val preview = remember(body) { MarkdownPreviewTruncation.preview(body) }
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
                    text = stringResource(R.string.favorites_tag_label, favoriteTagLabel(tag)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

/**
 * v2.6.2（用户反馈）: 收藏原文全屏预览。
 *
 * 点击收藏卡片打开，滚动展示完整正文（不截断），复用聊天页同款 MarkdownText 渲染。
 */
@Composable
private fun FavoriteFullTextDialog(message: UIMessage, dateFormat: SimpleDateFormat, onDismiss: () -> Unit) {
    val roleLabel = when (message.role) {
        MessageRole.USER -> stringResource(R.string.favorites_role_user)
        MessageRole.ASSISTANT -> "AI"
        MessageRole.SYSTEM -> stringResource(R.string.favorites_role_system)
        MessageRole.TOOL -> stringResource(R.string.favorites_role_tool)
    }
    MuseDialog(
        onDismissRequest = onDismiss,
        title = roleLabel,
        dismissText = stringResource(R.string.action_close),
        onDismiss = onDismiss,
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    message.modelId?.takeIf { it.isNotBlank() }?.let { model ->
                        Text(
                            text = model,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = dateFormat.format(Date(message.createdAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Spacer(Modifier.height(12.dp))
                // 完整正文，可滚动（不截断）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    MarkdownText(
                        text = io.zer0.muse.transformer.InternalMarkupSanitizer.displayContent(message.content),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                        citationUrls = message.citationUrls,
                    )
                }
            }
        },
    )
}
