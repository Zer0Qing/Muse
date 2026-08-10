package io.zer0.muse.ui.moment

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.data.moment.MomentCommentEntity
import io.zer0.muse.data.moment.MomentEntity
import io.zer0.muse.ui.theme.MusePaddings

/** v1.0.72: 朋友圈主界面(小手机壳内)。 */
@Composable
fun MomentsScreen(
    moments: List<MomentEntity>,
    commentsByMoment: Map<String, List<MomentCommentEntity>>,
    onToggleLike: (MomentEntity) -> Unit,
    onAddComment: (MomentEntity, String) -> Unit,
    onDelete: (MomentEntity) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        // 壳内顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MusePaddings.screen, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "朋友圈",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "Muse 的动态",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        if (moments.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "还没有动态",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Muse 会在一天里记录一些生活碎片",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = MusePaddings.screen,
                    vertical = MusePaddings.contentGap,
                ),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
            ) {
                items(moments, key = { it.id }) { moment ->
                    MomentCard(
                        moment = moment,
                        comments = commentsByMoment[moment.id] ?: emptyList(),
                        onToggleLike = { onToggleLike(moment) },
                        onAddComment = { text -> onAddComment(moment, text) },
                        onDelete = { onDelete(moment) },
                    )
                }
            }
        }
    }
}

/** v1.0.72: 单条动态卡片。 */
@Composable
fun MomentCard(
    moment: MomentEntity,
    comments: List<MomentCommentEntity>,
    onToggleLike: () -> Unit,
    onAddComment: (String) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var commentInput by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
            .padding(14.dp),
    ) {
        // 头部:头像 + 名字 + 时间 + 更多
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "M",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Muse",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = momentTimeText(moment.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(
                    imageVector = Icons.Outlined.MoreHoriz,
                    contentDescription = "删除动态",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // 正文
        Spacer(Modifier.height(8.dp))
        Text(
            text = moment.content,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        // 点赞 + 评论
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.clickable(onClick = onToggleLike),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (moment.likedByUser) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = "点赞",
                    tint = if (moment.likedByUser) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    modifier = Modifier.size(16.dp),
                )
                if (moment.likes > 0) {
                    Spacer(Modifier.width(4.dp))
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
            Spacer(Modifier.weight(1f))
            // 类型标签
            Text(
                text = typeLabel(moment.type),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            )
        }

        // 评论列表
        if (comments.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                comments.forEach { comment ->
                    Row {
                        Text(
                            text = if (comment.sender == "assistant") "Muse: " else "你: ",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                        )
                        Text(
                            text = comment.content,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // 评论输入
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.OutlinedTextField(
                value = commentInput,
                onValueChange = { commentInput = it },
                placeholder = { Text("评论...", style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(22.dp),
                textStyle = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "发送",
                style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .clickable {
                        if (commentInput.isNotBlank()) {
                            onAddComment(commentInput.trim())
                            commentInput = ""
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }

    // 删除确认
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
                onDelete()
            },
            dismissText = "取消",
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

private fun momentTimeText(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
        diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
        else -> {
            val date = java.util.Date(timestamp)
            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(date)
        }
    }
}

private fun typeLabel(type: String): String = when (type) {
    "mood_diary" -> "日记"
    "event" -> "记录"
    "seasonal" -> "随笔"
    else -> "分享"
}
