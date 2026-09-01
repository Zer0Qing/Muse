package io.zer0.muse.ui.moment

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.zer0.muse.data.moment.MomentCommentEntity
import io.zer0.muse.data.moment.MomentEntity
import io.zer0.muse.data.moment.MomentMessage
import io.zer0.muse.R
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.launch

/**
 * v1.0.74: AI 朋友圈主界面。
 *
 * - 封面:点击换背景图;右上角消息铃铛(未读红点)+ 发布(短按图文/长按纯文字)
 * - 动态流:下拉刷新;点击头像进个人主页;9 宫格多图;长文本折叠
 * - 消息中心:赞/评列表
 * - 横幅通知:有人赞/评时顶部横幅
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MomentsScreen(
    moments: List<MomentEntity>,
    commentsByMoment: Map<String, List<MomentCommentEntity>>,
    favoriteMomentIds: Set<String> = emptySet(),
    messages: List<MomentMessage>,
    unreadMessagesCount: Int,
    isLoading: Boolean = false,
    userAvatarUri: String?,
    userName: String,
    coverImage: String?,
    assistants: Map<String, io.zer0.muse.data.assistant.AssistantEntity> = emptyMap(),
    banner: String? = null,
    onToggleLike: (MomentEntity) -> Unit,
    onToggleFavorite: (String) -> Unit = {},
    onAddComment: (MomentEntity, String) -> Unit,
    onDeleteMoment: (MomentEntity) -> Unit = {},
    onPublish: (String, List<String>) -> Unit,
    onSetCover: (String) -> Unit,
    onPrepareImage: suspend (android.net.Uri) -> String?,
    onMarkMessagesRead: () -> Unit,
    onConsumeBanner: () -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    /** v1.0.74: 初始页(feed=动态流 / messages=消息中心;小手机"消息"图标直达消息页)。 */
    initialPage: String = "feed",
    modifier: Modifier = Modifier,
) {
    // 页内导航(feed / messages / profile / publish / publish_text)
    // 前端修复 (持久化-4): 页导航/主页参数均为 String/Boolean 标量,改 rememberSaveable
    var page by rememberSaveable { mutableStateOf(initialPage) }
    var profileSenderId by rememberSaveable { mutableStateOf<String?>(null) }
    var profileSenderType by rememberSaveable { mutableStateOf("assistant") }
    var profileSenderName by rememberSaveable { mutableStateOf("") }
    var targetMomentId by rememberSaveable { mutableStateOf<String?>(null) }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    // v1.0.74 fix: 发布选图状态 — 此前用顶层普通 var 不触发重组,选图后图片丢失。
    // 改为 remember 的 State,选图回调更新后 PublishDialog 能拿到最新图。
    // 前端修复 (持久化-4): List<String> 泛型列表无法直接 saveable,保持 remember(临时发布草稿,重建后丢失可接受)
    var pendingPublishImages by remember { mutableStateOf<List<String>>(emptyList()) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun shareMoment(moment: MomentEntity) {
        val text = buildString {
            append(moment.senderName)
            appendLine("：")
            appendLine(moment.content)
            if (moment.mood?.isNotBlank() == true) appendLine("心情：${moment.mood}")
        }.trim()
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, text)
        }
        // RuntimeLocaleProvider 可能提供 ContextWrapper,不保证是 Activity。
        // Android 15 从非 Activity Context 启动 chooser 必须带 NEW_TASK,
        // 否则点击朋友圈“分享”会直接抛 AndroidRuntimeException。
        context.startActivity(
            android.content.Intent.createChooser(intent, context.getString(R.string.action_share)).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    // v1.0.74 fix: 消息页/个人主页按系统返回键应回 feed,而不是直接退出整个朋友圈(跳两级)
    androidx.activity.compose.BackHandler(enabled = page != "feed") {
        page = "feed"
    }

    // 横幅通知
    LaunchedEffect(banner) {
        if (!banner.isNullOrBlank()) {
            snackbarHostState.showSnackbar(banner)
            onConsumeBanner()
        }
    }

    // 封面换图 launcher
    val coverLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        uri?.let { picked ->
            scope.launch {
                val dataUri = onPrepareImage(picked)
                if (dataUri != null) onSetCover(dataUri)
            }
        }
    }
    // 发布选图 launcher(多选)
    val publishImagesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(9),
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val images = uris.mapNotNull { onPrepareImage(it) }.filter { it.isNotBlank() }
                if (images.isNotEmpty()) pendingPublishImages = (pendingPublishImages + images).take(9)
            }
        }
    }

    when (page) {
        "messages" -> {
            MomentMessagesPage(
                messages = messages,
                userName = userName,
                onBack = { page = "feed" },
                onOpenMoment = { momentId ->
                    searchQuery = ""
                    favoritesOnly = false
                    searchVisible = false
                    targetMomentId = momentId
                    page = "feed"
                },
            )
        }
        "profile" -> {
            MomentProfilePage(
                moments = moments.filter {
                    if (profileSenderType == "user") {
                        it.senderType == "user" || it.source == "user"
                    }
                    else it.senderId == profileSenderId
                },
                commentsByMoment = commentsByMoment,
                favoriteMomentIds = favoriteMomentIds,
                senderType = profileSenderType,
                senderName = profileSenderName,
                avatarUrl = if (profileSenderType == "user") {
                    userAvatarUri
                } else {
                    profileSenderId?.let { assistants[it]?.avatarImageUrl?.takeIf { u -> u.isNotBlank() } }
                },
                onBack = { page = "feed" },
                onToggleLike = onToggleLike,
                onToggleFavorite = { moment -> onToggleFavorite(moment.id) },
                onAddComment = onAddComment,
                onShare = ::shareMoment,
                onDelete = onDeleteMoment,
            )
        }
        "publish", "publish_text" -> {
            PublishDialog(
                visible = true,
                textOnly = page == "publish_text",
                initialImages = pendingPublishImages,
                onPrepareImage = onPrepareImage,
                onPickImages = {
                    publishImagesLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onPublish = { text, images ->
                    onPublish(text, images)
                    pendingPublishImages = emptyList()
                    page = "feed"
                    // v1.0.74: 发布成功反馈
                    scope.launch {
                        snackbarHostState.showSnackbar("动态已发布")
                    }
                },
                onDismiss = {
                    pendingPublishImages = emptyList()
                    page = "feed"
                },
            )
        }
        else -> {
            Column(modifier = modifier.fillMaxSize()) {
                // ── 封面区(v1.0.74: 背景图覆盖状态栏,不再下移)──
                MomentsFeedHeader(
                    coverImage = coverImage,
                    userName = userName,
                    userAvatarUri = userAvatarUri,
                    unreadMessagesCount = unreadMessagesCount,
                    onBack = onBack,
                    onOpenMessages = {
                        onMarkMessagesRead()
                        page = "messages"
                    },
                    onToggleSearch = { searchVisible = !searchVisible },
                    onShortPublish = {
                        pendingPublishImages = emptyList()
                        page = "publish"
                    },
                    onLongPublish = {
                        pendingPublishImages = emptyList()
                        page = "publish_text"
                    },
                    onPickCover = {
                        coverLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onOpenSelfProfile = {
                        profileSenderId = null
                        profileSenderType = "user"
                        profileSenderName = userName.ifBlank { "我" }
                        page = "profile"
                    },
                )
                if (searchVisible) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = MusePaddings.screen,
                                vertical = MusePaddings.tightGap,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        io.zer0.muse.ui.common.form.MuseTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = {
                                Text("搜索动态、发布者或类型")
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Search,
                                    contentDescription = null,
                                )
                            },
                            trailingIcon = {
                                if (searchQuery.isNotBlank()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(
                                            imageVector = Icons.Default.Clear,
                                            contentDescription = "清空搜索",
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Surface(
                            onClick = { favoritesOnly = !favoritesOnly },
                            shape = CircleShape,
                            color = if (favoritesOnly) {
                                MaterialTheme.colorScheme.tertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            modifier = Modifier.padding(start = MusePaddings.tightGap),
                        ) {
                            Icon(
                                imageVector = if (favoritesOnly) Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = "只看收藏",
                                tint = if (favoritesOnly) {
                                    MaterialTheme.colorScheme.onTertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }
                }
                // ── 动态流(下拉刷新) ──
                MomentFeedList(
                    moments = moments.filter { moment ->
                        val query = searchQuery.trim()
                        (!favoritesOnly || moment.id in favoriteMomentIds) &&
                            (query.isBlank() ||
                                moment.content.contains(query, ignoreCase = true) ||
                                moment.senderName.contains(query, ignoreCase = true) ||
                                moment.type.contains(query, ignoreCase = true))
                    },
                    commentsByMoment = commentsByMoment,
                    favoriteMomentIds = favoriteMomentIds,
                    userAvatarUri = userAvatarUri,
                    assistants = assistants,
                    isLoading = isLoading,
                    onToggleLike = onToggleLike,
                    onToggleFavorite = { moment -> onToggleFavorite(moment.id) },
                    onShare = ::shareMoment,
                    onAddComment = onAddComment,
                    onRefresh = onRefresh,
                    targetMomentId = targetMomentId,
                    onTargetMomentConsumed = { targetMomentId = null },
                    onOpenProfile = { moment ->
                        profileSenderId = moment.senderId
                        profileSenderType = moment.senderType
                        profileSenderName = moment.senderName
                        page = "profile"
                    },
                    onDelete = { moment -> onDeleteMoment(moment) },
                )
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.TopCenter))
    }
}
