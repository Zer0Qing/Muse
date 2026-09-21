package io.zer0.muse.ui.moment

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseAnchoredMenu
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.surface.MuseListItem
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.data.moment.MomentCommentEntity
import io.zer0.muse.data.moment.MomentEntity
import io.zer0.muse.data.moment.images
import io.zer0.muse.ui.common.form.MuseTextField

/**
 * v1.0.74: 朋友圈动态卡片 — 微信朋友圈布局。
 *
 * 微信结构: 头像左上 → 右侧竖排(名字 → 正文 → 图片 → 时间) → 底部右侧"赞/评论"文字按钮。
 * 评论: 点击"评论"才展开评论区 + 输入框(不再每卡片常驻)。
 * 保留: 9 宫格多图 / 长文本折叠 / 长按删除 / 头像进主页。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MomentCard(
    moment: MomentEntity,
    comments: List<MomentCommentEntity>,
    onToggleLike: () -> Unit,
    onAddComment: (String) -> Unit,
    avatarUrl: String? = null,
    onAvatarClick: (() -> Unit)? = null,
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    onShare: () -> Unit = {},
    // v1.0.74: 删除动态(仅用户动态长按触发;助手动态不提供删除)
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var commentInput by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var commentsExpanded by rememberSaveable { mutableStateOf(false) }
    var showCommentInput by rememberSaveable { mutableStateOf(false) }
    var showActionsMenu by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var viewerIndex by rememberSaveable { mutableStateOf(-1) }
    val images = moment.images()
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap)
                .animateContentSize(
                    animationSpec = MuseMotion.tween(
                        durationMillis = MuseAnimation.NORMAL_MS,
                        easing = MuseAnimation.EaseOutCubic,
                    ),
                ),
        ) {
            // Header: 头像 + 名字 + 正文 + 图片
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        enabled = onDelete != null || moment.content.length > 200,
                        onClick = {
                            if (moment.content.length > 200) expanded = !expanded
                        },
                        onLongClick = {
                            if (onDelete != null) showDeleteConfirm = true
                        },
                    ),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    MomentAvatar(
                        senderType = moment.senderType,
                        name = moment.senderName,
                        size = 40,
                        avatarUrl = avatarUrl,
                        modifier = if (onAvatarClick != null) {
                            Modifier
                                .minimumInteractiveComponentSize()
                                .clip(CircleShape)
                                .clickable(onClick = onAvatarClick)
                        } else {
                            Modifier
                        },
                    )
                    Spacer(Modifier.width(MusePaddings.tinyGap))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = moment.senderName,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            ),
                        )
                        Spacer(Modifier.height(MusePaddings.tinyGap))
                        val contentText = moment.content
                        val collapsed = contentText.length > 200 && !expanded
                        Text(
                            text = if (collapsed) contentText.take(200) + "…" else contentText,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                lineHeight = 22.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (contentText.length > 200) {
                            Text(
                                text = if (expanded) {
                                    stringResource(R.string.action_collapse)
                                } else {
                                    stringResource(R.string.moment_expand_full)
                                },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                ),
                                modifier = Modifier
                                    .padding(top = MusePaddings.tinyGap)
                                    .clickable { expanded = !expanded },
                            )
                        }
                        if (images.isNotEmpty()) {
                            Spacer(Modifier.height(MusePaddings.contentGap))
                            MomentImageGrid(
                                images = images,
                                onImageClick = { idx -> viewerIndex = idx },
                            )
                        }
                    }
                    // MEM-09: 删除入口可见化
                    if (onDelete != null) {
                        var showMoreMenu by remember { mutableStateOf(false) }
                        Box {
                            MuseTactileButton(
                                icon = Icons.Filled.MoreHoriz,
                                onClick = { showMoreMenu = true },
                                contentDescription = stringResource(R.string.action_more),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                size = MuseIconSizes.touchTarget,
                                modifier = Modifier
                                    
                                    .padding(MusePaddings.tinyGap),
                            )
                            MuseAnchoredMenu(
                                expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false },
                            ) {

                                MuseListItem(
                                    onClick = {
                                        showMoreMenu = false
                                        showDeleteConfirm = true
                                    },
                                    headlineContent = {
                                        Text(
                                            text = stringResource(R.string.action_delete),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                )
                            
                            }
                        }
                    }
                }
            }

            // Footer: 时间 + 赞/评 + 操作
            Spacer(Modifier.height(MusePaddings.contentGap))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = momentTimeText(moment.createdAt, context),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    if (moment.likes > 0 || comments.isNotEmpty() || isFavorite) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MusePaddings.auxGap),
                        ) {
                            if (moment.likes > 0) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap),
                                ) {
                                    Icon(
                                        imageVector = if (moment.likedByUser) {
                                            Icons.Filled.Favorite
                                        } else {
                                            Icons.Filled.FavoriteBorder
                                        },
                                        contentDescription = stringResource(R.string.moment_like_cd),
                                        tint = if (moment.likedByUser) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outline
                                        },
                                        modifier = Modifier.size(MuseIconSizes.iconTiny),
                                    )
                                    Text(
                                        text = moment.likes.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                            if (comments.isNotEmpty()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap),
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.Chat,
                                        contentDescription = stringResource(R.string.moment_comment_cd),
                                        tint = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.size(MuseIconSizes.iconTiny),
                                    )
                                    Text(
                                        text = comments.size.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                            if (isFavorite) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Star,
                                        contentDescription = stringResource(R.string.moment_favorite),
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.size(MuseIconSizes.iconTiny),
                                    )
                                    Text(
                                        text = stringResource(R.string.moment_favorite),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                        }
                    }
                }
                // 右下角 ··· 按钮
                Box(
                    modifier = Modifier
                        .size(MuseIconSizes.touchTarget)
                        .clip(CircleShape)
                        .clickable { showActionsMenu = !showActionsMenu },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.moment_more_actions),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(MuseIconSizes.iconSmallTiny),
                    )
                }
                // 微信风格操作条:「赞 | 评论」并排
                if (showActionsMenu) {
                    Surface(
                        shape = RoundedCornerShape(MusePaddings.contentGap),
                        color = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                        shadowElevation = 2.dp,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap),
                        ) {
                            Text(
                                text = if (moment.likedByUser) {
                                    stringResource(R.string.moment_like_cancel)
                                } else {
                                    stringResource(R.string.moment_like_cd)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(MusePaddings.tinyGap))
                                    .clickable {
                                        showActionsMenu = false
                                        onToggleLike()
                                    }
                                    .padding(horizontal = MusePaddings.auxGap, vertical = MusePaddings.tightGap),
                            )
                            Box(
                                modifier = Modifier
                                    .width(0.5.dp)
                                    .height(16.dp)
                                    .background(MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.3f)),
                            )
                            Text(
                                text = stringResource(R.string.moment_comment_cd),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(MusePaddings.tinyGap))
                                    .clickable {
                                        showActionsMenu = false
                                        commentsExpanded = true
                                        showCommentInput = true
                                    }
                                    .padding(horizontal = MusePaddings.auxGap, vertical = MusePaddings.tightGap),
                            )
                        }
                    }
                }
            }

            // 评论区
            AnimatedVisibility(
                visible = commentsExpanded,
                enter = MuseMotion.expandFadeEnter(
                    durationMillis = MuseAnimation.NORMAL_MS,
                    fadeDurationMillis = MuseAnimation.TACTILE_MS,
                ),
                exit = MuseMotion.expandFadeExit(
                    durationMillis = MuseAnimation.NORMAL_MS,
                    fadeDurationMillis = MuseAnimation.TACTILE_MS,
                ),
            ) {
                if (comments.isNotEmpty()) {
                    Spacer(Modifier.height(MusePaddings.labelVerticalGap))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(MusePaddings.contentGap))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = MusePaddings.auxGap, vertical = MusePaddings.contentGap),
                        verticalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap),
                    ) {
                        comments.forEach { comment ->
                            Row {
                                val senderLabel = if (comment.sender == "assistant") {
                                    stringResource(R.string.moment_sender_muse)
                                } else {
                                    stringResource(R.string.moment_sender_me)
                                }
                                Text(
                                    text = (comment.senderName?.takeIf { it.isNotBlank() } ?: senderLabel) + ": ",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = comment.content,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                if (showCommentInput) {
                    Spacer(Modifier.height(MusePaddings.labelVerticalGap))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MuseTextField(
                            value = commentInput,
                            onValueChange = { commentInput = it },
                            placeholder = {
                                Text(
                                    stringResource(R.string.moment_comment_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            singleLine = true,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(MusePaddings.contentGap))
                        Text(
                            text = stringResource(R.string.action_send),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = if (commentInput.isNotBlank()) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                                },
                                fontWeight = FontWeight.Medium,
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(MusePaddings.contentGap))
                                .clickable(enabled = commentInput.isNotBlank()) {
                                    onAddComment(commentInput.trim())
                                    commentInput = ""
                                    showCommentInput = false
                                }
                                .padding(horizontal = MusePaddings.auxGap, vertical = MusePaddings.tightGap),
                        )
                    }
                }
            }
        }
        androidx.compose.material3.HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        )
    }

    if (viewerIndex >= 0 && viewerIndex < images.size) {
        io.zer0.muse.ui.common.media.FullScreenMediaViewer(
            images = images,
            initialIndex = viewerIndex,
            onDismiss = { viewerIndex = -1 },
        )
    }

    if (showDeleteConfirm) {
        io.zer0.muse.ui.common.settings.ConfirmDeleteDialog(
            title = stringResource(R.string.moment_delete_title),
            itemName = stringResource(R.string.moment_delete_item_name, moment.senderName),
            consequence = stringResource(R.string.moment_delete_consequence),
            onConfirm = {
                showDeleteConfirm = false
                onDelete?.invoke()
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

/**
 * 朋友圈图片网格。
 * - 1 张: 自适应宽度,保持宽高比(微信风格)
 * - 2 张: 2 列居中
 * - 4 张: 2x2
 * - 其他: 3x3(最多 9 张)
 */
@Composable
fun MomentImageGrid(
    images: List<String>,
    modifier: Modifier = Modifier,
    onImageClick: (Int) -> Unit = {},
) {
    val count = images.size.coerceAtMost(9)
    when (count) {
        1 -> {
            io.zer0.muse.ui.SmartImage(
                model = images[0],
                contentDescription = stringResource(R.string.moment_image_cd),
                contentScale = ContentScale.Crop,
                modifier = modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(MusePaddings.tinyGap))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .clickable { onImageClick(0) },
            )
        }
        else -> {
            val columns = when (count) {
                2 -> 2
                4 -> 2
                else -> 3
            }
            val rows = (count + columns - 1) / columns
            Column(
                modifier = modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap),
            ) {
                repeat(rows) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap)) {
                        repeat(columns) { col ->
                            val idx = row * columns + col
                            if (idx < count) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(MusePaddings.tinyGap))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                        .clickable { onImageClick(idx) },
                                ) {
                                    io.zer0.muse.ui.SmartImage(
                                        model = images[idx],
                                        contentDescription = stringResource(R.string.moment_image_cd_indexed, idx + 1),
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    if (idx == 8 && images.size > 9) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = "+${images.size - 9}",
                                                style = MaterialTheme.typography.titleLarge.copy(
                                                    color = androidx.compose.ui.graphics.Color.White,
                                                    fontWeight = FontWeight.Bold,
                                                ),
                                            )
                                        }
                                    }
                                }
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}
