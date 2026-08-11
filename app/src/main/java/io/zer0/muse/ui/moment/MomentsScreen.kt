package io.zer0.muse.ui.moment

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.data.moment.MomentCommentEntity
import io.zer0.muse.data.moment.MomentEntity
import io.zer0.muse.data.moment.MomentMessage
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
    messages: List<MomentMessage>,
    unreadMessagesCount: Int,
    isLoading: Boolean = false,
    userAvatarUri: String?,
    userName: String,
    coverImage: String?,
    assistants: Map<String, io.zer0.muse.data.assistant.AssistantEntity> = emptyMap(),
    banner: String? = null,
    onToggleLike: (MomentEntity) -> Unit,
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
    var page by remember { mutableStateOf(initialPage) }
    var profileSenderId by remember { mutableStateOf<String?>(null) }
    var profileSenderType by remember { mutableStateOf("assistant") }
    var profileSenderName by remember { mutableStateOf("") }
    // v1.0.74 fix: 发布选图状态 — 此前用顶层普通 var 不触发重组,选图后图片丢失。
    // 改为 remember 的 State,选图回调更新后 PublishDialog 能拿到最新图。
    var pendingPublishImages by remember { mutableStateOf<List<String>>(emptyList()) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

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
            )
        }
        "profile" -> {
            MomentProfilePage(
                moments = moments.filter {
                    if (profileSenderType == "user") it.senderType == "user"
                    else it.senderId == profileSenderId
                },
                commentsByMoment = commentsByMoment,
                senderType = profileSenderType,
                senderName = profileSenderName,
                avatarUrl = if (profileSenderType == "user") {
                    userAvatarUri
                } else {
                    profileSenderId?.let { assistants[it]?.avatarImageUrl?.takeIf { u -> u.isNotBlank() } }
                },
                onBack = { page = "feed" },
                onToggleLike = onToggleLike,
                onAddComment = onAddComment,
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
                // ── 动态流(下拉刷新) ──
                MomentFeedList(
                    moments = moments,
                    commentsByMoment = commentsByMoment,
                    userAvatarUri = userAvatarUri,
                    assistants = assistants,
                    isLoading = isLoading,
                    onToggleLike = onToggleLike,
                    onAddComment = onAddComment,
                    onRefresh = onRefresh,
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
