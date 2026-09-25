package io.zer0.muse.ui.moment

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.data.moment.MomentCommentEntity
import io.zer0.muse.data.moment.MomentEntity
import io.zer0.muse.data.moment.MomentMessage
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.common.state.MuseErrorStateBox
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.feedback.MuseToast
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
    /** v1.xxx: 立即生成一条 AI Moment(反馈后自动刷新动态流)。 */
    onGenerateMoment: () -> Unit = {},
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

    // U-24: 复用 ChatNavGraph 注入的同一 ViewModel,驱动"立即生成"按钮 loading 态与结果通知
    // (MomentsScreen 与 ChatNavGraph 在同一 ViewModelStoreOwner 作用域,koinViewModel 返回相同实例)
    val momentViewModel: MomentViewModel = org.koin.androidx.compose.koinViewModel()
    val momentState by momentViewModel.state.collectAsStateWithLifecycle()
    val isGeneratingNow = momentState.isGeneratingNow
    val generateNotice = momentState.generateNotice

    fun shareMoment(moment: MomentEntity) {
        val text = buildString {
            append(moment.senderName)
            appendLine("：")
            appendLine(moment.content)
            if (moment.mood?.isNotBlank() == true) {
                appendLine(context.getString(R.string.moment_mood_prefix, moment.mood))
            }
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

    // U-24: 手动"立即生成"结果反馈(成功 / 无素材 / LLM 未产出),展示后清空
    LaunchedEffect(generateNotice) {
        if (generateNotice != null) {
            val msg = context.getString(
                when (generateNotice) {
                    MomentViewModel.MomentGenerateNotice.SUCCESS -> R.string.moment_generate_success
                    MomentViewModel.MomentGenerateNotice.NO_MATERIAL -> R.string.moment_generate_no_material
                    MomentViewModel.MomentGenerateNotice.LLM_FAILED -> R.string.moment_generate_failed
                },
            )
            snackbarHostState.showSnackbar(msg)
            momentViewModel.consumeGenerateNotice()
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
    // v1.0.90: 发布选择菜单（拍摄 / 从手机相册选择 / 取消）。
    // 原来只有「短按图文、长按纯文字」，用户基本发现不了长按；现在点相机就弹菜单。
    var showPublishSheet by remember { mutableStateOf(false) }
    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview(),
    ) { bitmap ->
        if (bitmap != null) {
            val dataUri = runCatching {
                val baos = java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, baos)
                "data:image/jpeg;base64," +
                    android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
            }.getOrNull()
            if (dataUri != null) {
                pendingPublishImages = listOf(dataUri)
                page = "publish"
            } else {
                MuseToast.show(context.getString(R.string.moment_image_prepare_failed))
            }
        }
    }
    // 发布选图 launcher(多选)
    val publishImagesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(9),
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                // v2.0 复核修正:单次准备,避免对同一 uri 调两次 onPrepareImage;
                // 失败计数与成功列表同源,全部失败时才提示。
                val prepared = uris.map { it to onPrepareImage(it) }
                val images = prepared.mapNotNull { (_, dataUri) -> dataUri?.takeIf { it.isNotBlank() } }
                val failed = prepared.count { (_, dataUri) -> dataUri.isNullOrBlank() }
                if (failed > 0 && images.isEmpty()) {
                    MuseToast.show(context.getString(R.string.moment_image_prepare_failed))
                }
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
                        snackbarHostState.showSnackbar(context.getString(R.string.moment_published))
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
                      onPublishMenu = { showPublishSheet = true },
                    onOpenSelfProfile = {
                        profileSenderId = null
                        profileSenderType = "user"
                        profileSenderName = userName.ifBlank { context.getString(R.string.moment_sender_me) }
                        page = "profile"
                    },
                )
                if (showPublishSheet) {
                    MuseBottomSheet(
                        onDismissRequest = { showPublishSheet = false },
                        bottomContentSpacing = MusePaddings.contentGap,
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            PublishChoiceRow(stringResource(R.string.moment_publish_take_photo)) {
                                showPublishSheet = false
                                takePictureLauncher.launch(null)
                            }
                            PublishChoiceRow(stringResource(R.string.moment_publish_from_album)) {
                                showPublishSheet = false
                                pendingPublishImages = emptyList()
                                page = "publish"
                            }
                            PublishChoiceRow(stringResource(R.string.action_cancel)) {
                                showPublishSheet = false
                            }
                        }
                    }
                }
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
                                Text(stringResource(R.string.moment_search_hint))
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = MuseIcons.search,
                                    contentDescription = null,
                                )
                            },
                            trailingIcon = {
                                if (searchQuery.isNotBlank()) {
                                    MuseTactileButton(
                                        icon = MuseIcons.x,
                                        onClick = { searchQuery = "" },
                                        contentDescription = stringResource(R.string.moment_search_clear_cd),
                                    )
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
                                imageVector = if (favoritesOnly) MuseIcons.star else MuseIcons.star,
                                contentDescription = stringResource(R.string.moment_favorites_only_cd),
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
                // ── v1.xxx: 立即生成一条 AI Moment(手动触发入口)──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    MuseCapsuleButton(
                        text = if (isGeneratingNow) {
                            stringResource(R.string.moment_generating)
                        } else {
                            stringResource(R.string.moment_generate_now)
                        },
                        onClick = onGenerateMoment,
                        enabled = !isGeneratingNow,
                        loading = isGeneratingNow,
                        variant = IosCapsuleButtonVariant.Text,
                        fillWidth = false,
                    )
                }
                // ── 动态流(下拉刷新) ──
                // MEM-03: 加载失败显示错误态 + 重试(此前静默卡 loading)
                if (momentState.error != null) {
                    MuseErrorStateBox(
                        message = momentState.error.orEmpty(),
                        onRetry = { momentViewModel.load() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MusePaddings.screen),
                    )
                } else {
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
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.TopCenter))
    }
}

/** 发布选择菜单的一行（微信那种整行居中文字）。 */
@Composable
private fun PublishChoiceRow(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
