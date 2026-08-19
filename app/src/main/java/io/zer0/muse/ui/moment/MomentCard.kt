package io.zer0.muse.ui.moment

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.moment.MomentCommentEntity
import io.zer0.muse.data.moment.MomentEntity
import io.zer0.muse.data.moment.images
import io.zer0.muse.ui.common.form.MuseTextField

/**
 * v1.0.74: 朋友圈动态卡片 — 微信朋友圈 1:1 布局。
 *
 * 微信结构: 头像左上 → 右侧竖排(名字 → 正文 → 图片 → 时间) → 底部右侧"赞/评论"文字按钮。
 * 评论: 点击"评论"才展开评论区 + 输入框(不再每卡片常驻)。
 * 保留: 9 宫格大图 / 长文本折叠 / 长按删除 / 头像进主页。
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
    // 前端修复 (持久化-7): 卡片内状态改 rememberSaveable;
    // 注意 LazyColumn item 内 saveable 依赖 item 稳定 key(调用处 items(key = { it.id }) 已满足)
    var commentInput by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var commentsExpanded by rememberSaveable { mutableStateOf(false) }
    var showActions by rememberSaveable { mutableStateOf(false) }
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
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .animateContentSize(),
        ) {
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
                            Modifier.clip(CircleShape).clickable(onClick = onAvatarClick)
                        } else {
                            Modifier
                        },
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = moment.senderName,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        val contentText = moment.content
                        val collapsed = contentText.length > 200 && !expanded
                        Text(
                            text = if (collapsed) contentText.take(200) + "…" else contentText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (contentText.length > 200) {
                            Text(
                                text = if (expanded) {
                                    stringResource(R.string.action_collapse)
                                } else {
                                    stringResource(R.string.moment_expand_full)
                                },
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                ),
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .clickable { expanded = !expanded },
                            )
                        }
                        if (images.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            MomentImageGrid(
                                images = images,
                                onImageClick = { idx -> viewerIndex = idx },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = momentTimeText(moment.createdAt, context),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    if (moment.likes > 0 || comments.isNotEmpty() || isFavorite) {
                        Row(
                            modifier = Modifier.padding(top = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (moment.likes > 0) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
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
                                        modifier = Modifier.size(14.dp),
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
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.Chat,
                                        contentDescription = stringResource(R.string.moment_comment_cd),
                                        tint = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.size(14.dp),
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
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Star,
                                        contentDescription = stringResource(R.string.moment_favorite),
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.size(14.dp),
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
                Column(horizontalAlignment = Alignment.End) {
                    AnimatedVisibility(
                        visible = showActions,
                        enter = fadeIn() + expandHorizontally(expandFrom = Alignment.End),
                        exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.End),
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.inverseSurface,
                            shape = RoundedCornerShape(8.dp),
                            shadowElevation = 6.dp,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                MomentAction(
                                    icon = if (moment.likedByUser) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                    label = stringResource(R.string.moment_like_cd),
                                    onClick = {
                                        showActions = false
                                        onToggleLike()
                                    },
                                )
                                MomentAction(
                                    icon = Icons.AutoMirrored.Outlined.Chat,
                                    label = stringResource(R.string.moment_comment_cd),
                                    onClick = {
                                        showActions = false
                                        commentsExpanded = true
                                    },
                                )
                                MomentAction(
                                    icon = if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                                    label = stringResource(R.string.moment_favorite),
                                    onClick = {
                                        showActions = false
                                        onToggleFavorite()
                                    },
                                )
                                MomentAction(
                                    icon = Icons.Filled.Share,
                                    label = stringResource(R.string.action_share),
                                    onClick = {
                                        showActions = false
                                        onShare()
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        onClick = { showActions = !showActions },
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreHoriz,
                            contentDescription = stringResource(R.string.moment_more_actions),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        )
                    }
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = commentsExpanded,
                enter = androidx.compose.animation.fadeIn() +
                    androidx.compose.animation.expandVertically(
                        animationSpec = androidx.compose.animation.core.tween(
                            durationMillis = 220,
                            easing = androidx.compose.animation.core.FastOutSlowInEasing,
                        ),
                    ),
                exit = androidx.compose.animation.fadeOut() +
                    androidx.compose.animation.shrinkVertically(
                        animationSpec = androidx.compose.animation.core.tween(durationMillis = 150),
                    ),
            ) {
                if (comments.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
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
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
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
                    Spacer(Modifier.width(8.dp))
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
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(enabled = commentInput.isNotBlank()) {
                                onAddComment(commentInput.trim())
                                commentInput = ""
                            }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                    )
                }
            }
        }
        HorizontalDivider(
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
        io.zer0.muse.ui.common.feedback.MuseDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = stringResource(R.string.moment_delete_title),
            content = {
                Text(
                    text = stringResource(R.string.moment_delete_confirm),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.action_delete),
            onConfirm = {
                showDeleteConfirm = false
                onDelete?.invoke()
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

/** 微信朋友圈式深色操作菜单中的单个动作。 */
@Composable
private fun MomentAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val contentColor = MaterialTheme.colorScheme.inverseOnSurface
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
        )
    }
}

/**
 * v1.0.74: 9 宫格图片布局。
 * - 1 张:自适应宽度,高 200dp
 * - 2 张:2 列居中
 * - 4 张:2x2
 * - 其他:3x3(最多 9 张)
 */
@Composable
fun MomentImageGrid(
    images: List<String>,
    modifier: Modifier = Modifier,
    // v1.0.74: 点击图片回调(全屏查看器)
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
                    .height(200.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .clickable { onImageClick(0) },
            )
        }
        else -> {
            // 2 图单独走 2 列居中(微信 2 图并排)
            val columns = when (count) {
                2 -> 2
                4 -> 2
                else -> 3
            }
            val rows = (count + columns - 1) / columns
            Column(
                modifier = modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(rows) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(columns) { col ->
                            val idx = row * columns + col
                            if (idx < count) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                        .clickable { onImageClick(idx) },
                                ) {
                                    io.zer0.muse.ui.SmartImage(
                                        model = images[idx],
                                        contentDescription = stringResource(R.string.moment_image_cd_indexed, idx + 1),
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    // 第 9 张溢出提示
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
