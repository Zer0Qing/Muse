package io.zer0.muse.ui.moment

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.data.moment.MomentCommentEntity
import io.zer0.muse.data.moment.MomentEntity
import io.zer0.muse.data.moment.images
import io.zer0.muse.ui.common.form.MuseTextField

/**
 * v1.0.74: 朋友圈动态卡片 — 微信朋友圈 1:1 布局。
 *
 * 微信结构: 头像左上 → 右侧竖排(名字 → 正文 → 图片 → 时间) → 底部右侧"赞/评论"文字按钮。
 * 评论: 点击"评论"才展开评论区 + 输入框(不再每卡片常驻)。
 * 保留: 实色岛底色统一 / 9 宫格大图 / 长文本折叠 / 长按删除 / 头像进主页。
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
    // v1.0.74: 删除动态(仅用户动态长按触发;助手动态不提供删除)
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var commentInput by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var commentsExpanded by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var viewerIndex by remember { mutableStateOf(-1) }
    val images = moment.images()

    io.zer0.muse.ui.common.surface.MuseIsland(
        modifier = modifier.fillMaxWidth(),
        // 实色岛 — 与 v1.0.72 输入栏大岛同方案,统一底色不透背景
        backgroundAlpha = 1f,
    ) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp)
            .combinedClickable(
                enabled = onDelete != null,
                onClick = {},
                onLongClick = { showDeleteConfirm = true },
            ),
    ) {
        // ── 头部: 头像(左) + 名字/正文/时间(右竖排,微信布局) ──
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
                // 名字(微信: 粗体深色)
                Text(
                    text = moment.senderName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )

                // 正文(长文本折叠)
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
                        text = if (expanded) "收起" else "全文",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        ),
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clickable { expanded = !expanded },
                    )
                }

                // 媒体: 9 宫格(点击进全屏查看器)
                if (images.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    MomentImageGrid(
                        images = images,
                        onImageClick = { idx -> viewerIndex = idx },
                    )
                }

                // 时间(微信: 正文/图片下方,灰小字)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = momentTimeText(moment.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )

                // ── 底部操作栏: 右侧"赞" "评论" 文字按钮(微信样式) ──
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 赞
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(onClick = onToggleLike)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // v1.0.74: 汉字换图标(心形)
                        Icon(
                            imageVector = if (moment.likedByUser) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = "赞",
                            tint = if (moment.likedByUser) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            modifier = Modifier.size(17.dp),
                        )
                        if (moment.likes > 0) {
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = "${moment.likes}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (moment.likedByUser) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                            )
                        }
                    }
                    // 评论
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { commentsExpanded = !commentsExpanded }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // v1.0.74: 汉字换图标(评论气泡)
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Chat,
                            contentDescription = "评论",
                            tint = if (commentsExpanded) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            modifier = Modifier.size(16.dp),
                        )
                        if (comments.isNotEmpty()) {
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = "${comments.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }

                // ── 评论区(点击"评论"展开,微信交互) ──
                // v1.0.74: 展开动画(垂直展开 + 淡入),此前直接 if 切换生硬
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
                        // v1.0.74: 微信评论区样式 — 淡灰圆角块、昵称深色粗体(非蓝)、紧凑
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
                                    Text(
                                        text = (comment.senderName?.takeIf { it.isNotBlank() } ?: if (comment.sender == "assistant") "Muse" else "我") + ": ",
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

                    // 评论输入(展开时显示)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MuseTextField(
                            value = commentInput,
                            onValueChange = { commentInput = it },
                            placeholder = { Text("发表评论：", style = MaterialTheme.typography.bodySmall) },
                            singleLine = true,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "发送",
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
        }
    }
    } // MuseIsland

    // 全屏图片查看器
    if (viewerIndex >= 0 && viewerIndex < images.size) {
        io.zer0.muse.ui.common.media.FullScreenMediaViewer(
            images = images,
            initialIndex = viewerIndex,
            onDismiss = { viewerIndex = -1 },
        )
    }

    // 删除确认(仅用户自己的动态,长按触发)
    if (showDeleteConfirm) {
        io.zer0.muse.ui.common.feedback.MuseDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = "删除动态",
            content = {
                Text(
                    text = "确定删除这条动态?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = "删除",
            onConfirm = {
                showDeleteConfirm = false
                onDelete?.invoke()
            },
            dismissText = "取消",
            onDismiss = { showDeleteConfirm = false },
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
                contentDescription = "动态配图",
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
                                        contentDescription = "动态配图 ${idx + 1}",
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
