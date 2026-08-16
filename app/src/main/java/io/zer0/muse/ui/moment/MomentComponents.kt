package io.zer0.muse.ui.moment

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.moment.MomentCommentEntity
import io.zer0.muse.data.moment.MomentEntity
import io.zer0.muse.data.moment.MomentMessage
import io.zer0.muse.ui.theme.MusePaddings

// ═══════════════ 通用头像 ═══════════════

/** v1.0.73: 圆形头像 — 图片优先(用户资料/助手头像),无图时渐变底 + 首字。 */
@Composable
fun MomentAvatar(
    senderType: String,
    name: String,
    size: Int,
    avatarUrl: String? = null,
    modifier: Modifier = Modifier,
) {
    val gradient = Brush.linearGradient(
        listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.tertiary,
        ),
    )
    Box(
        modifier = modifier
            .size(size.dp)
            // v1.0.74 fix: 内部 SmartImage 超出圆的部分要 clip,否则图片显示成方块
            .clip(CircleShape)
            .background(gradient, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (!avatarUrl.isNullOrBlank()) {
            io.zer0.muse.ui.SmartImage(
                model = avatarUrl,
                contentDescription = stringResource(R.string.moment_avatar_cd),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = name.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                ),
            )
        }
    }
}

// ═══════════════ 封面区 ═══════════════

/** v1.0.74: 朋友圈封面(点击换图)+ 返回 + 消息铃铛 + 发布(短按图文/长按纯文字)。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MomentsFeedHeader(
    coverImage: String?,
    userName: String,
    userAvatarUri: String?,
    unreadMessagesCount: Int,
    onBack: () -> Unit,
    onOpenMessages: () -> Unit,
    onShortPublish: () -> Unit,
    onLongPublish: () -> Unit,
    onPickCover: () -> Unit,
    // v1.0.74: 自己的头像点击进主页
    onOpenSelfProfile: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            // v1.0.74 fix: 背景图覆盖到状态栏后面(edge-to-edge 沉浸),不再 statusBarsPadding 下沉;
            // 内部按钮各自用 statusBarsPadding 避让,背景图全屏铺满顶部。
            .height(210.dp)
            .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f),
                        MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ),
            )
            .combinedClickable(onClick = onPickCover, onLongClick = onPickCover),
    ) {
        // 封面背景图(用户自选)
        if (!coverImage.isNullOrBlank()) {
            io.zer0.muse.ui.SmartImage(
                model = coverImage,
                contentDescription = stringResource(R.string.moment_cover_cd),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 返回
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .statusBarsPadding()
                .padding(MusePaddings.screen)
                .background(Color.Black.copy(alpha = 0.25f), CircleShape),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
                tint = Color.White,
            )
        }
        // 右上: 消息铃铛 + 发布(v1.0.74 fix: 合并为 Row,去掉 64dp 硬编码避让导致的两钮相切)
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(MusePaddings.screen),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box {
                IconButton(
                    onClick = onOpenMessages,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.25f), CircleShape),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Notifications,
                        contentDescription = stringResource(R.string.moment_messages_cd),
                        tint = Color.White,
                    )
                }
                if (unreadMessagesCount > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(if (unreadMessagesCount > 9) 20.dp else 16.dp)
                            .background(Color(0xFFFF3B30), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (unreadMessagesCount > 9) "9+" else "$unreadMessagesCount",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                    }
                }
            }
            // 发布:短按图文,长按纯文字
            IconButton(
                onClick = onShortPublish,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.25f), CircleShape),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.moment_publish_cd),
                    tint = Color.White,
                )
            }
        }
        // 右下: 换封面胶囊 + 名字 + 头像
        // v1.0.74 fix: 封面整块可点换图误触率高,改为显式"换封面"胶囊
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // v1.0.74: 换封面按钮不常驻 — 点击封面即可换图(封面 combinedClickable 已处理),此处只留名字+头像
            Text(
                text = userName,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                ),
            )
            Spacer(Modifier.width(10.dp))
            MomentAvatar(
                senderType = "user",
                name = userName,
                avatarUrl = userAvatarUri,
                size = 52,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.35f), CircleShape)
                    .padding(3.dp)
                    // v1.0.74: 自己的头像也可点击进自己的主页
                    .clickable(onClick = onOpenSelfProfile),
            )
        }
    }
}

// ═══════════════ 动态流(下拉刷新) ═══════════════

/** v1.0.74: 朋友圈动态流 — 下拉刷新 + 头像点击进个人主页。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentFeedList(
    moments: List<MomentEntity>,
    commentsByMoment: Map<String, List<MomentCommentEntity>>,
    userAvatarUri: String?,
    assistants: Map<String, io.zer0.muse.data.assistant.AssistantEntity> = emptyMap(),
    onToggleLike: (MomentEntity) -> Unit,
    onAddComment: (MomentEntity, String) -> Unit,
    onRefresh: () -> Unit,
    onOpenProfile: (MomentEntity) -> Unit,
    // v1.0.74: 加载中(避免首次进入空态闪现)
    isLoading: Boolean = false,
    // v1.0.74: 删除动态(仅用户自己的动态可删)
    onDelete: (MomentEntity) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var refreshing by remember { mutableStateOf(false) }

    // v1.0.74 fix: 加载中显示转圈,不闪"还没有动态"
    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator()
        }
        return
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            onRefresh()
            refreshing = false
        },
        modifier = modifier.fillMaxSize(),
    ) {
        if (moments.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.moment_empty_feed),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.moment_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
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
                        onAvatarClick = { onOpenProfile(moment) },
                        // v1.0.74 fix: 助手头像此前全部缺失(渐变块认不出谁发的),
                        // 按 senderId 从 assistants map 取图片头像;
                        // 旧数据 senderId 为空时按 senderName 匹配兜底
                        avatarUrl = if (moment.senderType == "assistant") {
                            val byId = moment.senderId?.let { assistants[it]?.avatarImageUrl?.takeIf { url -> url.isNotBlank() } }
                            byId ?: assistants.values.firstOrNull { it.name == moment.senderName }
                                ?.avatarImageUrl?.takeIf { url -> url.isNotBlank() }
                        } else {
                            userAvatarUri
                        },
                        // v1.0.74: 仅用户自己的动态可长按删除
                        onDelete = if (moment.senderType == "user") {
                            { onDelete(moment) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

// ═══════════════ 消息中心 ═══════════════

/** v1.0.74: 消息中心 — 用户动态收到的赞/评,按时间倒序。 */
@Composable
fun MomentMessagesPage(
    messages: List<MomentMessage>,
    userName: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // v1.0.74 fix: 顶栏避让状态栏
                .statusBarsPadding()
                .padding(horizontal = MusePaddings.screen, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.moment_messages_cd),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (messages.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.moment_messages_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = PaddingValues(horizontal = MusePaddings.screen, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages, key = { it.id }) { msg ->
                    io.zer0.muse.ui.common.surface.MuseIsland(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundAlpha = 1f,
                    ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MomentAvatar(
                            senderType = "assistant",
                            name = msg.actorName,
                            size = 40,
                            avatarUrl = msg.actorAvatar,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (msg.type == "like") {
                                    Icon(
                                        imageVector = Icons.Filled.Favorite,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text(
                                    text = when (msg.type) {
                                        "like" -> stringResource(R.string.moment_notification_liked, msg.actorName)
                                        else -> stringResource(R.string.moment_notification_commented, msg.actorName)
                                    },
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            if (msg.type == "comment" && msg.content.isNotBlank()) {
                                Text(
                                    text = msg.content,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "\"${msg.momentContent.take(30)}${if (msg.momentContent.length > 30) "..." else ""}\"",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                            )
                        }
                        Text(
                            text = momentTimeText(msg.createdAt, context),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    } // MuseIsland
                }
            }
        }
    }
}

// ═══════════════ 个人主页 ═══════════════

/** v1.0.74: 个人主页 — 发布者(用户/助手)的时间轴。 */
@Composable
fun MomentProfilePage(
    moments: List<MomentEntity>,
    commentsByMoment: Map<String, List<MomentCommentEntity>>,
    senderType: String,
    senderName: String,
    avatarUrl: String?,
    onBack: () -> Unit,
    onToggleLike: (MomentEntity) -> Unit,
    onAddComment: (MomentEntity, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // v1.0.74 fix: 顶栏避让状态栏
                .statusBarsPadding()
                .padding(horizontal = MusePaddings.screen, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.moment_profile_title, senderName),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        // 头部:头像 + 名字 + 动态数
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MusePaddings.screen, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MomentAvatar(
                senderType = senderType,
                name = senderName,
                avatarUrl = avatarUrl,
                size = 56,
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = senderName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.moment_moment_count, moments.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        // 时间轴
        if (moments.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.moment_empty_feed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = PaddingValues(horizontal = MusePaddings.screen, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
            ) {
                items(moments, key = { it.id }) { moment ->
                    MomentCard(
                        moment = moment,
                        comments = commentsByMoment[moment.id] ?: emptyList(),
                        onToggleLike = { onToggleLike(moment) },
                        onAddComment = { text -> onAddComment(moment, text) },
                        avatarUrl = avatarUrl,
                    )
                }
            }
        }
    }
}

// ═══════════════ 发布弹窗 ═══════════════

/** v1.0.74: 发布弹窗 — 图文(短按)/纯文字(长按)。 */
@Composable
fun PublishDialog(
    visible: Boolean,
    textOnly: Boolean,
    initialImages: List<String>,
    onPrepareImage: suspend (android.net.Uri) -> String?,
    onPickImages: () -> Unit,
    onPublish: (String, List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    var draft by remember { mutableStateOf("") }
    // v1.0.74 fix: 选图后不显示 — remember 无 key,initialImages 更新不触发重建。
    // 用 remember(initialImages) 跟随父状态,图片立刻出现在弹窗预览。
    var pickedImages by remember(initialImages) { mutableStateOf(initialImages) }

    io.zer0.muse.ui.common.feedback.MuseDialog(
        onDismissRequest = onDismiss,
        title = if (textOnly) {
            stringResource(R.string.moment_publish_text_only)
        } else {
            stringResource(R.string.moment_publish_feed)
        },
        content = {
            Column {
                io.zer0.muse.ui.common.form.MuseTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text(stringResource(R.string.moment_publish_placeholder)) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!textOnly) {
                    Spacer(Modifier.height(10.dp))
                    // 已选图片(最多 9 张)
                    if (pickedImages.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            pickedImages.take(9).forEach { img ->
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                ) {
                                    io.zer0.muse.ui.SmartImage(
                                        model = img,
                                        contentDescription = stringResource(R.string.moment_pending_image_cd),
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onPickImages) {
                            Icon(
                                imageVector = Icons.Filled.PhotoLibrary,
                                contentDescription = stringResource(R.string.moment_add_images_hint, 9),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            // C-19: 硬编码中文改资源
                            text = if (pickedImages.isNotEmpty()) {
                                stringResource(R.string.moment_images_selected, pickedImages.size)
                            } else {
                                stringResource(R.string.moment_add_images_hint, 9)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        },
        confirmText = stringResource(R.string.moment_publish_confirm),
        // v1.0.74 fix: 空内容可点发布却没反应;改为空时禁用主按钮
        onConfirm = if (draft.isNotBlank() || (!textOnly && pickedImages.isNotEmpty())) {
            {
                onPublish(draft.trim(), if (textOnly) emptyList() else pickedImages)
            }
        } else {
            null
        },
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
    )
}

/** 时间显示:刚刚/X 分钟前/X 小时前/昨天/X 天前/日期。
 *  v1.0.74 fix: "昨天"按自然日判断(此前 47 小时前的动态也显示"昨天")。 */
internal fun momentTimeText(timestamp: Long, context: Context): String {
    val diff = System.currentTimeMillis() - timestamp
    if (diff < 86_400_000L) {
        return when {
            diff < 60_000L -> context.getString(R.string.chat_list_time_just_now)
            diff < 3_600_000L -> context.getString(R.string.chat_list_time_minutes_ago, diff / 60_000L)
            else -> context.getString(R.string.chat_list_time_hours_ago, diff / 3_600_000L)
        }
    }
    val target = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
    val now = java.util.Calendar.getInstance()
    val isYesterday = target.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) &&
        target.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR) - 1
    return when {
        isYesterday -> context.getString(R.string.chat_list_time_yesterday)
        diff < 7 * 86_400_000L -> context.getString(R.string.chat_list_time_days_ago, diff / 86_400_000L)
        else -> {
            val date = java.util.Date(timestamp)
            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(date)
        }
    }
}
