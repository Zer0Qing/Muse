@file:OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package io.zer0.muse.ui.groupchat

import android.content.Intent
import io.zer0.muse.util.ShareIntentHelper
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Build
import androidx.core.content.ContextCompat
import io.zer0.muse.ui.ModelSwitchSheet
import io.zer0.muse.ui.ChatSelectionBar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.theme.MuseIconSizes
import kotlinx.coroutines.delay
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.ui.graphics.vector.ImageVector
import compose.icons.TablerIcons
import compose.icons.tablericons.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.MusePopover
import io.zer0.muse.ui.common.MuseFloatingActionItem
import io.zer0.muse.ui.common.MuseFloatingActionMenu
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.groupchat.GroupChatMessageEntity
import io.zer0.muse.doc.DocumentParser
import io.zer0.muse.ui.common.media.FullScreenMediaViewer
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.theme.MuseHaptics
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.assistantBubble
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel

/**
 * 群聊详情页 — 消息流 + 输入栏 + Agent 轮转回复。
 *
 * 设计(warm-paper 风格):
 *  - Scaffold:TopAppBar(群聊名 + 返回 + 成员列表按钮)+ 底部 InputBar
 *  - 中间:LazyColumn 消息流,区分 user / assistant 消息
 *  - assistant 消息:左侧 + 头像 + senderName
 *  - user 消息:右侧
 *  - Agent 回复期间显示"正在思考..."等待状态
 *  - 消息流自动滚动到底部
 *
 * @param chatId 群聊 id
 * @param onBack 返回回调
 * @param viewModel 群聊 ViewModel
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GroupChatDetailScreen(
    chatId: String,
    onBack: () -> Unit,
    /** HTML/SVG 代码块全屏预览回调(参数为完整 HTML 源码)。 */
    onHtmlPreview: (String) -> Unit = {},
    /** B0-07: 打开提示词模板管理页。 */
    onOpenPromptTemplateManager: () -> Unit = {},
    /** v1.0.72: 编辑助手供应商(跳模型与服务设置页)。 */
    onEditAssistantProvider: (String) -> Unit = {},
    viewModel: GroupChatViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // v1.45: 用 ViewModel 中缓存的滚动位置初始化 LazyListState,切页/后台后恢复位置
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = state.listFirstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = state.listFirstVisibleItemScrollOffset,
    )
    // v1.45: 滚动位置变化时同步缓存到 ViewModel
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                viewModel.onListScrollPositionChanged(index, offset)
            }
    }
    // v1.0.74 fix (前端审计 1.4): 消费 jumpTargetId — 搜索结果点击后滚动到目标消息并短暂高亮。
    // 此前 jumpTargetId 仅在 ViewModel 定义,UI 无任何监听,搜索跳转功能静默失效。
    var highlightedJumpMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(viewModel.jumpTargetId) {
        val targetId = viewModel.jumpTargetId.value ?: return@LaunchedEffect
        // 消息区起始偏移: 顶部 load_more_indicator 存在时占 index 0
        val msgStart = if (state.isLoadingMore) 1 else 0
        val idx = state.currentMessages.indexOfFirst { it.id == targetId }
        if (idx >= 0) {
            listState.animateScrollToItem(msgStart + idx)
            highlightedJumpMessage = targetId
            // 高亮 2.5s 后清除
            delay(2500)
            if (highlightedJumpMessage == targetId) highlightedJumpMessage = null
        }
    }
    var showMembersDialog by rememberSaveable { mutableStateOf(false) }
    var showToolSheet by rememberSaveable { mutableStateOf(false) }
    // v1.97: 群聊编辑对话框
    var showEditDialog by rememberSaveable { mutableStateOf(false) }
    // v1.0.72: 三点菜单功能
    var showSearchDialog by rememberSaveable { mutableStateOf(false) }
    var showProviderDialog by rememberSaveable { mutableStateOf(false) }
    var providerTargetAssistantId by remember { mutableStateOf<String?>(null) }
    // v1.77: 消息长按菜单 + 删除确认
    var messageMenuTarget by remember { mutableStateOf<GroupChatMessageEntity?>(null) }
    var messageMenuBounds by remember { mutableStateOf(Rect.Zero) }
    var messageMenuPointInWindow by remember { mutableStateOf<Offset?>(null) }
    var deleteMessageTarget by remember { mutableStateOf<GroupChatMessageEntity?>(null) }
    // v1.x: 多选批量删除确认
    var deleteSelectedTarget by remember { mutableStateOf(false) }
    // v2.x: 引用回复 / 悄悄话 / 表决 / 总结 对话框状态
    var replyToMessage by remember { mutableStateOf<GroupChatMessageEntity?>(null) }
    var whisperTarget by remember { mutableStateOf<GroupChatMessageEntity?>(null) }
    var showVoteDialog by remember { mutableStateOf(false) }
    var showSummaryDialog by remember { mutableStateOf(false) }
    // v2.x: 群聊上下文管理 sheet(群共享文档 + AI 专属上下文)
    var showContextSheet by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    // 图片选择器:选中的图片加入待发送列表
    val imageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            try {
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val dataUri = withContext(Dispatchers.IO) {
                    val bytes = readImageBytes(context, uri)
                    val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
                    if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
                        throw IllegalStateException("无法解析图片尺寸")
                    }
                    val maxDim = maxOf(boundsOptions.outWidth, boundsOptions.outHeight)
                    var sampleSize = 1
                    while (maxDim / sampleSize > 1024) sampleSize *= 2
                    val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                        ?: throw IllegalStateException("无法解码图片")
                    try { encodeBitmapToBase64(bitmap) } finally { bitmap.recycle() }
                }
                viewModel.addPendingImage("data:image/jpeg;base64,$dataUri")
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Logger.w("GroupChatDetail", "图片读取失败: ${t.message}", t)
            }
        }
    }

    // v1.0.30: 文件附件选择器 — 保存文件为 base64 附件
    val documentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            try {
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val (bytes, mimeType, fileName) = withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openInputStream(uri)
                        ?: context.contentResolver.openFileDescriptor(uri, "r")?.let { java.io.FileInputStream(it.fileDescriptor) }
                        ?: throw IllegalStateException("无法读取文件")
                    val data = stream.use { it.readBytes() }
                    Triple(data, context.contentResolver.getType(uri) ?: "application/octet-stream", uri.lastPathSegment ?: "文件")
                }
                if (bytes.size > 10 * 1024 * 1024) return@launch
                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                viewModel.addPendingFileAttachment(FileAttachment(name = fileName, mimeType = mimeType, base64 = base64))
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Logger.w("GroupChatDetail", "文件读取失败: ${t.message}", t)
            }
        }
    }

    // v1.112 (C2): Prompt 模板选择 sheet 状态
    var showPromptTemplateSheet by remember { mutableStateOf(false) }
    val settings: io.zer0.muse.data.SettingsRepository = org.koin.compose.koinInject()
    val promptTemplates by settings.promptTemplatesFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    // v1.0.72: 加号菜单媒体区 — 相册/相机权限 + 拍照 + 相册缩略图点击
    val galleryPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        android.Manifest.permission.READ_MEDIA_IMAGES
    } else {
        android.Manifest.permission.READ_EXTERNAL_STORAGE
    }
    var hasGalleryPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, galleryPermission) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
    val galleryPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasGalleryPermission = granted }

    // 拍照:系统相机(TakePicture 不需要 CAMERA 权限,FileProvider 保存)
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    // 相册缩略图/拍照结果 → 压缩 base64 → 加入待发送
    fun loadUriToPending(uri: Uri) {
        scope.launch {
            try {
                val dataUri = withContext(Dispatchers.IO) {
                    val bytes = readImageBytes(context, uri)
                    val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
                    if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
                        throw IllegalStateException("无法解析图片尺寸")
                    }
                    val maxDim = maxOf(boundsOptions.outWidth, boundsOptions.outHeight)
                    var sampleSize = 1
                    while (maxDim / sampleSize > 1024) sampleSize *= 2
                    val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                        ?: throw IllegalStateException("无法解码图片")
                    try { encodeBitmapToBase64(bitmap) } finally { bitmap.recycle() }
                }
                viewModel.addPendingImage("data:image/jpeg;base64,$dataUri")
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Logger.w("GroupChatDetail", "图片读取失败: ${t.message}", t)
            }
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = pendingCameraUri
        if (success && uri != null) {
            loadUriToPending(uri)
        }
        pendingCameraUri = null
    }

    // 拍照入口:创建 FileProvider uri 并调系统相机
    fun startCameraCapture() {
        val file = java.io.File.createTempFile("muse_group_capture_", ".jpg", context.cacheDir)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        pendingCameraUri = uri
        takePictureLauncher.launch(uri)
    }

    // 进入页面时选中该群聊(加载消息流)
    LaunchedEffect(chatId) {
        viewModel.selectChat(chatId)
    }

    // M7: 群聊被删除时自动返回列表页
    LaunchedEffect(state.chatDeleted) {
        if (state.chatDeleted) onBack()
    }

    // v1.126: 错误提示自动清除(显示 3 秒后重置)
    LaunchedEffect(state.errorMessage) {
        if (state.errorMessage != null) {
            kotlinx.coroutines.delay(3000)
            viewModel.clearError()
        }
    }

    // 消息列表变化时自动滚动到底部
    // H-GC1 修复: 原先无条件 animateScrollToItem 到底部,用户查看历史消息时会被强制拉回。
    // 改为:仅当 listState 已在底部附近(最后 2 个 item 内)时才自动滚动,
    // 用户主动上滑查看历史时不打断。
    // v1.53-GC: 列表头部多了一个"加载更多"指示器 item,自动滚到底部仍以消息 lastIndex 为准
    // (思考指示器在消息之后,不影响"已到底部"判断)。
    LaunchedEffect(state.currentMessages.size, state.isAgentResponding, state.streamingContent?.length) {
        if (state.currentMessages.isEmpty()) return@LaunchedEffect
        val lastIndex = state.currentMessages.lastIndex
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val isNearBottom = lastVisible >= lastIndex - 2 || lastVisible < 0
        if (isNearBottom) {
            // v1.0.74 fix (前端审计 1.6): streamingContent 长度加入 key 后,
            // 流式输出逐 token 增长时也会触发滚动,用户能看到生成中的内容。
            listState.animateScrollToItem(lastIndex)
        }
    }

    // v1.53-GC: 上滑加载更多历史消息 — 到达列表顶部(firstVisibleItemIndex==0)且满足条件时触发。
    // 触发后 ViewModel 设置 lastHistoryLoadCount,下面的 LaunchedEffect 据此调整滚动位置,
    // 跳过新插入的条数,使 firstVisibleItemIndex 变为 lastHistoryLoadCount(>0),
    // 从而避免在顶部重复触发(用户需再次主动上滑才继续加载)。
    var savedScrollOffset by remember { mutableStateOf(0) }
    val loadMoreTrigger by remember {
        derivedStateOf {
            state.hasMoreHistory &&
                !state.isLoadingMore &&
                !state.isAgentResponding &&
                state.currentMessages.isNotEmpty() &&
                listState.firstVisibleItemIndex == 0
        }
    }
    LaunchedEffect(loadMoreTrigger) {
        if (loadMoreTrigger) {
            // 记录当前 offset,加载完成后跳到新位置时保持视觉位置
            savedScrollOffset = listState.firstVisibleItemScrollOffset
            viewModel.loadMoreHistory()
        }
    }
    // v1.53-GC: 加载完成后调整滚动位置,保持视觉位置不跳动
    // (原来在顶部的消息现在在 lastHistoryLoadCount 位置)
    LaunchedEffect(state.lastHistoryLoadCount) {
        if (state.lastHistoryLoadCount > 0) {
            listState.scrollToItem(state.lastHistoryLoadCount, savedScrollOffset)
            viewModel.clearHistoryLoadCount()
            savedScrollOffset = 0
        }
    }

    val chatName = state.currentChat?.name ?: stringResource(R.string.groupchat_default_name)

    // v1.0.74: 群聊自定义背景(与聊天背景共用设置)
    val chatBackground by org.koin.compose.koinInject<io.zer0.muse.data.SettingsRepository>()
        .chatBackgroundFlow
        .collectAsState(initial = null)

    Box(modifier = Modifier.fillMaxSize()) {
        if (!chatBackground.isNullOrBlank()) {
            io.zer0.muse.ui.SmartImage(
                model = chatBackground,
                contentDescription = stringResource(R.string.groupchat_bg_cd), // 前端修复 (i18n-1)
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    Scaffold(
        // v1.0.52: 让 Scaffold 统一处理 IME 内边距,避免 bottomBar 内部 GroupChatInputBar
        // 再应用一次 imePadding 导致双重 padding(发送按钮被推到不可点击位置)
        contentWindowInsets = WindowInsets(0),
        topBar = {
            // v1.0.72: 三岛顶栏(返回 / 群聊名 / 三点菜单),与单聊 Telegram 风格统一
            // v1.0.72 fix: 去掉全宽背景遮罩 — 三岛悬浮在消息列表上(与 Telegram 一致)
            // 三点菜单: 搜索 / 编辑群聊 / 编辑助手供应商
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = MusePaddings.screen, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                    // 左岛:返回
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        // v1.0.74 fix (前端审计 7): 42dp → 48dp 触摸目标
                        modifier = Modifier.size(48.dp),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize().clickable(onClick = onBack),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = TablerIcons.ArrowLeft,
                                contentDescription = stringResource(R.string.groupchat_back),
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(MuseIconSizes.iconMedium),
                            )
                        }
                    }
                    // 中岛:群聊名
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.weight(1f).height(42.dp),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = chatName,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    // 右岛:三点菜单
                    var showTopMenu by rememberSaveable { mutableStateOf(false) }
                    Box(
                        // v1.0.74 fix (前端审计 7): 42dp → 48dp 触摸目标
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize().clickable { showTopMenu = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.MoreVert,
                                    contentDescription = stringResource(R.string.chat_top_menu_cd),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                                )
                            }
                        }
                        if (showTopMenu) {
                            MuseFloatingActionMenu(
                                items = listOf(
                                    MuseFloatingActionItem(
                                        key = "search",
                                        icon = Icons.Outlined.Search,
                                        label = stringResource(R.string.groupchat_search),
                                        onClick = {
                                            showTopMenu = false
                                            showSearchDialog = true
                                        },
                                    ),
                                    MuseFloatingActionItem(
                                        key = "edit",
                                        icon = TablerIcons.Edit,
                                        label = stringResource(R.string.groupchat_edit_cd),
                                        onClick = {
                                            showTopMenu = false
                                            showEditDialog = true
                                        },
                                    ),
                                    MuseFloatingActionItem(
                                        key = "provider",
                                        icon = Icons.Outlined.AutoAwesome,
                                        label = stringResource(R.string.groupchat_edit_provider),
                                        onClick = {
                                            showTopMenu = false
                                            showProviderDialog = true
                                        },
                                    ),
                                ),
                                onDismiss = { showTopMenu = false },
                            )
                        }
                    }
            }
        },
        containerColor = if (chatBackground.isNullOrBlank()) {
            MaterialTheme.colorScheme.background
        } else {
            androidx.compose.ui.graphics.Color.Transparent
        },
        bottomBar = {
            // v1.0.52: imePadding 提到 Column 层,统一处理键盘内边距,
            // 避免 GroupChatInputBar 内部 imePadding 导致双重 padding
            Column(
                modifier = Modifier.imePadding(),
            ) {
                // A4: 多选批量操作栏 — 与单聊共用 ChatSelectionBar 统一组件
                if (state.selectedMessageIds.isNotEmpty()) {
                    val selectedText = state.currentMessages
                        .filter { it.id in state.selectedMessageIds }
                        .joinToString("\n\n") { "${it.senderName}: ${it.body}" }
                    ChatSelectionBar(
                        count = state.selectedMessageIds.size,
                        onSelectAll = { viewModel.selectAllVisible() },
                        onCopy = {
                            if (selectedText.isNotBlank()) {
                                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                                cm?.setPrimaryClip(
                                    android.content.ClipData.newPlainText("muse-selected", selectedText),
                                )
                            }
                        },
                        onDelete = { deleteSelectedTarget = true },
                        onExport = {
                            if (selectedText.isNotBlank()) {
                                val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_TEXT, selectedText)
                                }
                                ShareIntentHelper.startChooserSafely(context, sendIntent)
                            }
                        },
                        onExit = { viewModel.clearSelection() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MusePaddings.screen, vertical = 6.dp),
                    )
                }
                // ActivityHub: 输入框上方的紧凑活动状态栏,展示当前轮转中各 agent 的状态 chip。
                // IDLE 状态被 AgentActivityBar 内部过滤不显示;无活动时整个栏不占空间。
                AgentActivityBar(activities = state.activities)
                // 待发送图片预览行
                if (state.pendingImages.isNotEmpty()) {
                    PendingImagesRow(
                        images = state.pendingImages,
                        onRemove = { viewModel.removePendingImage(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = MusePaddings.screen, vertical = 8.dp),
                    )
                }
                // 解析群聊成员(供 @mention 自动补全过滤)
                val chat = state.currentChat
                val memberIds = remember(chat) {
                    chat?.let { viewModel.parseMemberIds(it) } ?: emptyList()
                }
                val members = remember(memberIds, state.assistants) {
                    memberIds.mapNotNull { id -> state.assistants.find { it.id == id } }
                }
                GroupChatInputBar(
                    text = state.inputText,
                    onTextChange = { viewModel.updateInput(it) },
                    onSend = {
                        viewModel.sendMessage(state.inputText)
                        keyboard?.hide()
                    },
                    onOpenToolSheet = {
                        MuseHaptics.light(haptic)
                        showToolSheet = true
                    },
                    enabled = !state.isAgentResponding,
                    canSend = state.inputText.isNotBlank() || state.pendingImages.isNotEmpty() || state.pendingFileAttachments.isNotEmpty(),
                    members = members,
                )
            }
        },
    ) { innerPadding ->
        // v1.126: 错误提示横幅(覆盖在内容上方)
        val errorMsg = state.errorMessage
        if (errorMsg != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(innerPadding)
                    .padding(MusePaddings.cardInnerSpaced),
                contentAlignment = Alignment.TopCenter,
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = errorMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
        // v1.77: 空消息引导状态(新群聊进入后不显示空白)
        if (state.currentMessages.isEmpty() && !state.isAgentResponding) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(io.zer0.muse.R.drawable.ic_muse_logo),
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.groupchat_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    // v1.0.72: 不 pad top — 消息列表延伸到顶部悬浮岛后面(Telegram 效果)
                    .padding(bottom = innerPadding.calculateBottomPadding()),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = MusePaddings.screen,
                    end = MusePaddings.screen,
                    // v1.0.72: 顶部让位给悬浮三岛
                    top = innerPadding.calculateTopPadding(),
                    bottom = MusePaddings.itemGap,
                ),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
            ) {
                // v1.53-GC: 上滑加载更多历史时的顶部加载指示器(参考单聊 HistoryLoadMorePlaceholder)。
                // isLoadingMore=true 时插入此 item(占据 index 0),加载完成后(lastHistoryLoadCount>0)
                // 由 LaunchedEffect 调 scrollToItem 跳过新插入条数,保持视觉位置不跳。
                if (state.isLoadingMore) {
                    item(key = "load_more_indicator") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = MusePaddings.contentGap),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.chat_loading_more_history),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(
                    items = state.currentMessages,
                    key = { it.id },
                ) { message ->
                    val expandedState = state.messageExpandedStates[message.id]
                    GroupChatMessageBubble(
                        message = message,
                        assistants = state.assistants,
                        chatPrefs = state.chatPreferences,
                        // v1.0.74 fix (前端审计 1.4): 搜索跳转高亮
                        highlighted = message.id == highlightedJumpMessage,
                        isMoodExpanded = expandedState?.isMoodExpanded,
                        isReasoningExpanded = expandedState?.isReasoningExpanded,
                        onToggleMoodExpanded = { viewModel.toggleMessageMoodExpanded(message.id) },
                        onToggleReasoningExpanded = { viewModel.toggleMessageReasoningExpanded(message.id) },
                        // v1.77: 长按弹出操作菜单
                         onLongClick = { bounds, pointInWindow ->
                             MuseHaptics.medium(haptic)
                             if (state.selectedMessageIds.isNotEmpty()) {
                                 viewModel.toggleMessageSelection(message.id)
                             } else {
                                 messageMenuBounds = bounds
                                 messageMenuPointInWindow = pointInWindow
                                 messageMenuTarget = message
                             }
                         },
                        // v1.x: 多选模式
                        selectionMode = state.selectedMessageIds.isNotEmpty(),
                        selected = message.id in state.selectedMessageIds,
                        onSelectToggle = { viewModel.toggleMessageSelection(message.id) },
                        onHtmlPreview = onHtmlPreview,
                    )
                }
                // Agent 正在回复时的"正在思考..."状态
                if (state.isAgentResponding) {
                    item(key = "thinking_indicator") {
                        ThinkingIndicator(currentSpeaker = state.currentSpeaker)
                    }
                }
                // v1.x: 群聊流式输出 — 生成中的内容实时展示(落库后由正式消息替换)
                state.streamingContent?.takeIf { it.isNotBlank() }?.let { streaming ->
                    item(key = "streaming_content") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = MusePaddings.screen, vertical = 4.dp),
                        ) {
                            Text(
                                text = state.currentSpeaker?.name
                                    ?: stringResource(R.string.groupchat_streaming_title),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                            )
                            Surface(
                                shape = MuseShapes.assistantBubble,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            ) {
                                Text(
                                    text = streaming,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(MusePaddings.cardInner),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 成员列表对话框
    if (showMembersDialog) {
        val chat = state.currentChat
        val memberIds = remember(chat) {
            chat?.let { viewModel.parseMemberIds(it) } ?: emptyList()
        }
        val members = remember(memberIds, state.assistants) {
            memberIds.mapNotNull { id -> state.assistants.find { it.id == id } }
        }
        MembersDialog(
            memberNames = members.map { it.name },
            memberCount = memberIds.size,
            onDismiss = { showMembersDialog = false },
        )
    }

    // 加号菜单(工具面板)
    if (showToolSheet) {
        GroupChatToolSheet(
            onPickImage = { imageLauncher.launch("image/*") },
            onPickDocument = {
                runCatching {
                    documentLauncher.launch(arrayOf("text/*", "application/pdf"))
                }.onFailure {
                    Logger.w("GroupChatDetail", "文件选择器启动失败", it)
                }
            },
            onInsertKnowledge = {
                // v1.112: 在输入框末尾插入 @ 标记,用户手动补全文档名
                val current = state.inputText
                val prefix = if (current.isBlank() || current.endsWith(" ") || current.endsWith("\n")) "" else " "
                viewModel.updateInput("$current$prefix@")
            },
            onPickPromptTemplate = { showPromptTemplateSheet = true },
            onOpenMembers = { showMembersDialog = true },
            onLaunchVote = { showVoteDialog = true },
            onLaunchSummary = { showSummaryDialog = true },
            onOpenContext = { showContextSheet = true },
            onMentionMember = {
                val current = state.inputText
                val prefix = if (current.isBlank() || current.endsWith(" ") || current.endsWith("\n")) "" else " "
                viewModel.updateInput("$current$prefix@")
            },
            onEditGroup = { showEditDialog = true },
            // v1.0.72: 媒体区参数
            hasGalleryPermission = hasGalleryPermission,
            galleryPermission = galleryPermission,
            onRequestGalleryPermission = { galleryPermissionLauncher.launch(galleryPermission) },
            onPickGalleryImage = { uri -> loadUriToPending(uri) },
            onCaptureImage = { startCameraCapture() },
            onDismiss = { showToolSheet = false },
        )
    }

    // v1.112 (C2): Prompt 模板选择 sheet
    if (showPromptTemplateSheet) {
        MuseBottomSheet(onDismissRequest = { showPromptTemplateSheet = false }) {
            Text(
                text = stringResource(R.string.chat_prompt_templates_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // v1.114: 加 key(it.id),列表变化时 Compose 可按 key 复用 item 组合,
                // 避免新增/删除模板时整列重组(PromptTemplate.id 唯一)。
                items(items = promptTemplates, key = { it.id }) { template ->
                    Surface(
                        onClick = {
                            val current = state.inputText
                            val newText = if (current.isBlank()) template.content else "$current\n\n${template.content}"
                            viewModel.updateInput(newText)
                            showPromptTemplateSheet = false
                        },
                        color = MaterialTheme.colorScheme.surface,
                        shape = MuseShapes.semiLarge,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(MusePaddings.itemGap)) {
                            Text(
                                text = template.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (template.category.isNotBlank()) {
                                Text(
                                    text = template.category,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }
            }
        }
            TextButton(
                onClick = {
                    showPromptTemplateSheet = false
                    onOpenPromptTemplateManager()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.prompt_template_manage_entry))
            }
    }

    // v1.97: 编辑群聊对话框(改名 / 改成员)
    if (showEditDialog) {
        val chat = state.currentChat
        val initialMemberIds = remember(chat) {
            chat?.let { viewModel.parseMemberIds(it) } ?: emptyList()
        }
        EditGroupChatDialog(
            dialogKey = chatId,
            initialName = chat?.name ?: "",
            assistants = state.assistants,
            initialMemberIds = initialMemberIds,
            initialDiscussionMode = chat?.discussionMode ?: "round_robin",
            initialAutoMaxRounds = chat?.autoMaxRounds ?: 5,
            initialHostId = chat?.hostId,
            onDismiss = { showEditDialog = false },
            onConfirm = { newName, newMemberIds, newMode, newMaxRounds, newHostId ->
                viewModel.updateChat(
                    chatId = chatId,
                    name = newName,
                    memberIds = newMemberIds,
                    discussionMode = newMode,
                    autoMaxRounds = newMaxRounds,
                    hostId = newHostId,
                )
                showEditDialog = false
            },
        )
    }

    // v1.0.72: 群聊搜索对话框(三点菜单 → 搜索)
    if (showSearchDialog) {
        var query by rememberSaveable { mutableStateOf("") }
        // 防抖搜索:停止输入 300ms 后触发
        LaunchedEffect(query) {
            delay(300)
            viewModel.searchMessages(query)
        }
        MuseDialog(
            onDismissRequest = {
                showSearchDialog = false
                viewModel.searchMessages("")
            },
            title = stringResource(R.string.groupchat_search),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    MuseTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.groupchat_search_hint)) },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(MusePaddings.contentGap))
                    if (state.isSearching) {
                        Box(modifier = Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    } else if (query.isNotBlank() && state.searchResults.isEmpty()) {
                        Text(
                            text = stringResource(R.string.groupchat_search_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            state.searchResults.forEach { result ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            showSearchDialog = false
                                            viewModel.searchMessages("")
                                            // 跳转到该消息(滚动到对应位置)
                                            viewModel.jumpToMessage(result.id)
                                        }
                                        .padding(vertical = 8.dp),
                                ) {
                                    Text(
                                        text = result.senderName.ifBlank { stringResource(R.string.groupchat_sender_user) }, // 前端修复 (i18n-1)
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = result.body,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            }
                        }
                    }
                }
            },
            confirmText = stringResource(R.string.action_close),
            onConfirm = {
                showSearchDialog = false
                viewModel.searchMessages("")
            },
            dismissText = stringResource(R.string.groupchat_cancel),
            onDismiss = {
                showSearchDialog = false
                viewModel.searchMessages("")
            },
        )
    }

    // v1.0.72: 编辑助手供应商 — 两步:选成员 → ModelSwitchSheet(保存到该助手)
    if (showProviderDialog) {
        MuseDialog(
            onDismissRequest = { showProviderDialog = false },
            title = stringResource(R.string.groupchat_edit_provider_title),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    state.assistants.forEach { assistant ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showProviderDialog = false
                                    providerTargetAssistantId = assistant.id
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = TablerIcons.User,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(MusePaddings.contentGap))
                            Text(
                                text = assistant.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = Icons.Outlined.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            },
            confirmText = stringResource(R.string.action_close),
            onConfirm = { showProviderDialog = false },
            dismissText = stringResource(R.string.groupchat_cancel),
            onDismiss = { showProviderDialog = false },
        )
    }

    // 选中成员后:打开 ModelSwitchSheet 配置该成员的供应商/模型
    providerTargetAssistantId?.let { assistantId ->
        val target = state.assistants.firstOrNull { it.id == assistantId }
        if (target != null) {
            // 读取当前供应商/模型(优先用该助手的 per-assistant 配置,否则回退全局)
            val providers by settings.providersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
            val activeProviderId by settings.activeProviderIdFlow.collectAsStateWithLifecycle(initialValue = null)
            val selectedModelId by settings.selectedModelIdFlow.collectAsStateWithLifecycle(initialValue = null)
            ModelSwitchSheet(
                providers = providers,
                activeProviderId = target.providerId ?: activeProviderId,
                selectedModelId = target.modelId ?: selectedModelId,
                onPickProvider = { providerId ->
                    // 切换供应商:立即保存 providerId(模型延后)
                    viewModel.updateAssistantModel(assistantId, providerId, target.modelId)
                },
                onPickModel = { modelId ->
                    viewModel.updateAssistantModel(
                        assistantId,
                        target.providerId ?: activeProviderId,
                        modelId,
                    )
                },
                onRefreshModels = { providerId ->
                    // 刷新模型列表(群聊场景暂不实现独立刷新)
                },
                isFetchingModels = false,
                fetchModelsError = null,
                onDismiss = { providerTargetAssistantId = null },
            )
        }
    }

    // v2.x: 消息长按操作菜单 — 锚定消息气泡,不再用全屏遮罩居中弹窗。
    messageMenuTarget?.let { msg ->
        MusePopover(
            anchorBounds = messageMenuBounds,
            gapDp = 8,
            anchorPointInWindow = messageMenuPointInWindow,
            onDismiss = { messageMenuTarget = null },
        ) {
            val scheme = MaterialTheme.colorScheme
            val textColor = scheme.onSurface
            val iconBlock = scheme.surfaceVariant
            val divider = scheme.outlineVariant
            Surface(
                color = scheme.surface,
                shape = MuseShapes.extraLarge,
                shadowElevation = 8.dp,
                tonalElevation = 0.dp,
                modifier = Modifier.widthIn(min = 200.dp, max = 260.dp),
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Text(
                            text = if (msg.senderType == "user") stringResource(R.string.groupchat_my_message) else msg.senderName,
                            style = MaterialTheme.typography.labelMedium,
                            color = textColor.copy(alpha = 0.6f),
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                        )
                        GroupChatActionRow(TablerIcons.Copy, stringResource(R.string.groupchat_copy), textColor, iconBlock) {
                            messageMenuTarget = null
                            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("message", msg.body))
                            io.zer0.muse.ui.common.feedback.MuseToast.show(context.getString(R.string.groupchat_copied))
                        }
                        GroupChatActionRow(TablerIcons.MessageCircle, stringResource(R.string.groupchat_reply), textColor, iconBlock) {
                            messageMenuTarget = null
                            replyToMessage = msg
                        }
                        GroupChatActionRow(TablerIcons.Square, stringResource(R.string.groupchat_select), textColor, iconBlock) {
                            messageMenuTarget = null
                            viewModel.startSelection(msg.id)
                        }
                        // AI 消息 → 重新生成 / 悄悄话
                        if (msg.senderType == "assistant") {
                            GroupChatActionRow(TablerIcons.Refresh, stringResource(R.string.groupchat_regenerate), textColor, iconBlock) {
                                messageMenuTarget = null
                                viewModel.regenerateAgentMessage(msg.senderId)
                            }
                            GroupChatActionRow(TablerIcons.Eye, stringResource(R.string.groupchat_whisper), textColor, iconBlock) {
                                messageMenuTarget = null
                                whisperTarget = msg
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .height(0.5.dp)
                                .background(divider),
                        )
                        GroupChatActionRow(TablerIcons.Trash, stringResource(R.string.groupchat_delete), Color(0xFFFF3B30), iconBlock) {
                            messageMenuTarget = null
                            deleteMessageTarget = msg
                        }
                }
            }
        }
    }

    // v1.77: 删除消息确认
    deleteMessageTarget?.let { msg ->
        MuseDialog(
            onDismissRequest = { deleteMessageTarget = null },
            title = stringResource(R.string.groupchat_delete_message_title),
            content = {
                Text(
                    text = stringResource(R.string.groupchat_delete_message_confirm),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.groupchat_delete),
            onConfirm = {
                deleteMessageTarget = null
                viewModel.deleteMessage(msg.id)
            },
            dismissText = stringResource(R.string.groupchat_cancel),
            onDismiss = { deleteMessageTarget = null },
        )
    }

    // v1.x: 多选批量删除确认
    if (deleteSelectedTarget) {
        MuseDialog(
            onDismissRequest = { deleteSelectedTarget = false },
            title = stringResource(R.string.groupchat_delete_selected_title, state.selectedMessageIds.size),
            content = {
                Text(
                    text = stringResource(R.string.groupchat_delete_selected_confirm),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.groupchat_delete),
            onConfirm = {
                deleteSelectedTarget = false
                viewModel.deleteSelectedMessages()
            },
            dismissText = stringResource(R.string.groupchat_cancel),
            onDismiss = { deleteSelectedTarget = false },
        )
    }

    // v2.x: 引用回复对话框
    replyToMessage?.let { msg ->
        // 前端修复 (持久化-10): 引用回复草稿改 rememberSaveable,旋转不丢已输入内容
        var replyText by rememberSaveable { mutableStateOf("") }
        MuseDialog(
            onDismissRequest = { replyToMessage = null },
            title = stringResource(R.string.groupchat_reply),
            content = {
                Column {
                    Text(
                        text = stringResource(R.string.groupchat_reply_quote, msg.senderName, msg.body.take(80)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                    MuseTextField(
                        value = replyText,
                        onValueChange = { replyText = it },
                        label = { Text(stringResource(R.string.groupchat_reply_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmText = stringResource(R.string.groupchat_send),
            onConfirm = {
                if (replyText.isNotBlank()) {
                    viewModel.sendMessage(replyText.trim())
                    replyToMessage = null
                }
            },
            dismissText = stringResource(R.string.groupchat_cancel),
            onDismiss = { replyToMessage = null },
        )
    }

    // v2.x: 悄悄话对话框
    whisperTarget?.let { target ->
        var whisperText by remember { mutableStateOf("") }
        MuseDialog(
            onDismissRequest = { whisperTarget = null },
            title = stringResource(R.string.groupchat_whisper_to, target.senderName),
            content = {
                Column {
                    Text(
                        text = stringResource(R.string.groupchat_whisper_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                    MuseTextField(
                        value = whisperText,
                        onValueChange = { whisperText = it },
                        label = { Text(stringResource(R.string.groupchat_whisper_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmText = stringResource(R.string.groupchat_send),
            onConfirm = {
                if (whisperText.isNotBlank()) {
                    viewModel.sendWhisper(target.senderId, whisperText.trim())
                    whisperTarget = null
                }
            },
            dismissText = stringResource(R.string.groupchat_cancel),
            onDismiss = { whisperTarget = null },
        )
    }

    // v2.x: 发起表决对话框
    if (showVoteDialog) {
        var voteTopic by remember { mutableStateOf("") }
        MuseDialog(
            onDismissRequest = { showVoteDialog = false },
            title = stringResource(R.string.groupchat_vote_title),
            content = {
                MuseTextField(
                    value = voteTopic,
                    onValueChange = { voteTopic = it },
                    label = { Text(stringResource(R.string.groupchat_vote_topic_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmText = stringResource(R.string.groupchat_vote),
            onConfirm = {
                if (voteTopic.isNotBlank()) {
                    viewModel.launchVote(voteTopic.trim())
                    showVoteDialog = false
                }
            },
            dismissText = stringResource(R.string.groupchat_cancel),
            onDismiss = { showVoteDialog = false },
        )
    }

    // v2.x: 总结对话框(选择总结者)
    if (showSummaryDialog) {
        MuseDialog(
            onDismissRequest = { showSummaryDialog = false },
            title = stringResource(R.string.groupchat_summary_title),
            content = {
                Column {
                    Text(
                        text = stringResource(R.string.groupchat_summary_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                    state.assistants.filter { it.id in (state.currentChat?.let { c -> viewModel.parseMemberIds(c) } ?: emptyList()) }
                        .forEach { assistant ->
                            TextButton(onClick = {
                                viewModel.launchSummary(assistant.id)
                                showSummaryDialog = false
                            }) {
                                Text(assistant.name)
                            }
                        }
                    // 默认用第一个成员
                    TextButton(onClick = {
                        viewModel.launchSummary(null)
                        showSummaryDialog = false
                    }) {
                        Text(stringResource(R.string.groupchat_summary_auto))
                    }
                }
            },
            onConfirm = null,
            dismissText = stringResource(R.string.groupchat_cancel),
            onDismiss = { showSummaryDialog = false },
        )
    }

    // v2.x: 群聊上下文管理 sheet(群共享文档 + AI 专属上下文)
    if (showContextSheet) {
        val chat = state.currentChat
        val sharedDocs = remember(chat) {
            chat?.let { viewModel.parseSharedDocs(it) } ?: emptyList()
        }
        val privateContextMap = remember(chat) {
            chat?.let { viewModel.parseMemberPrivateContext(it) } ?: emptyMap()
        }
        val memberIds = remember(chat) {
            chat?.let { viewModel.parseMemberIds(it) } ?: emptyList()
        }
        val members = remember(memberIds, state.assistants) {
            memberIds.mapNotNull { id -> state.assistants.find { it.id == id } }
        }
        // 新文档输入字段
        var newDocTitle by remember { mutableStateOf("") }
        var newDocContent by remember { mutableStateOf("") }
        // 当前编辑中的成员专属上下文(assistantId -> 编辑中文本)
        val editingContexts = remember { mutableStateOf<Map<String, String>>(emptyMap()) }

        MuseBottomSheet(onDismissRequest = { showContextSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // ── 群共享文档 ──
                Text(
                    text = stringResource(R.string.groupchat_context_shared_docs),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.groupchat_context_shared_docs_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (sharedDocs.isEmpty()) {
                    Text(
                        text = stringResource(R.string.groupchat_context_no_docs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    sharedDocs.forEach { doc ->
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = MuseShapes.semiLarge,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(MusePaddings.itemGap)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = doc.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = stringResource(R.string.groupchat_context_doc_chars, doc.content.length),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                    IconButton(onClick = { viewModel.removeSharedDoc(doc.id) }) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = stringResource(R.string.groupchat_delete),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                // 添加新文档表单
                MuseTextField(
                    value = newDocTitle,
                    onValueChange = { newDocTitle = it },
                    label = { Text(stringResource(R.string.groupchat_context_doc_title)) },
                    placeholder = { Text(stringResource(R.string.groupchat_context_doc_title_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                MuseTextField(
                    value = newDocContent,
                    onValueChange = { newDocContent = it },
                    label = { Text(stringResource(R.string.groupchat_context_doc_content)) },
                    placeholder = { Text(stringResource(R.string.groupchat_context_doc_content_hint)) },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = {
                        if (newDocTitle.isNotBlank() && newDocContent.isNotBlank()) {
                            viewModel.addSharedDoc(newDocTitle, newDocContent)
                            newDocTitle = ""
                            newDocContent = ""
                        }
                    },
                    enabled = newDocTitle.isNotBlank() && newDocContent.isNotBlank(),
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(R.string.groupchat_context_add_doc))
                }

                // 分隔线
                Surface(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp),
                ) {}

                // ── AI 专属上下文 ──
                Text(
                    text = stringResource(R.string.groupchat_context_private_context),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.groupchat_context_private_context_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (members.isEmpty()) {
                    Text(
                        text = stringResource(R.string.groupchat_no_members),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    members.forEach { assistant ->
                        val savedText = privateContextMap[assistant.id] ?: ""
                        val editingText = editingContexts.value[assistant.id] ?: savedText
                        val isEditing = editingContexts.value.containsKey(assistant.id)
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = MuseShapes.semiLarge,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(MusePaddings.itemGap)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = assistant.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (!isEditing) {
                                        Text(
                                            text = stringResource(
                                                if (savedText.isNotBlank()) R.string.groupchat_context_private_context_set
                                                else R.string.groupchat_context_private_context_empty,
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                        TextButton(onClick = {
                                            editingContexts.value = editingContexts.value + (assistant.id to savedText)
                                        }) {
                                            Text(stringResource(R.string.groupchat_edit))
                                        }
                                    }
                                }
                                if (isEditing) {
                                    MuseTextField(
                                        value = editingText,
                                        onValueChange = { newText ->
                                            editingContexts.value = editingContexts.value + (assistant.id to newText)
                                        },
                                        label = { Text(stringResource(R.string.groupchat_context_private_context_hint)) },
                                        minLines = 2,
                                        maxLines = 6,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp),
                                    )
                                    Row(
                                        horizontalArrangement = Arrangement.End,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        TextButton(onClick = {
                                            // 清除该成员的专属上下文
                                            viewModel.setMemberPrivateContext(assistant.id, "")
                                            editingContexts.value = editingContexts.value - assistant.id
                                        }) {
                                            Text(stringResource(R.string.groupchat_context_clear))
                                        }
                                        TextButton(onClick = {
                                            viewModel.setMemberPrivateContext(assistant.id, editingText)
                                            editingContexts.value = editingContexts.value - assistant.id
                                        }) {
                                            Text(stringResource(R.string.groupchat_context_save))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
} // Scaffold
} // 背景 Box(v1.0.74 自定义聊天背景)

/** v1.0.72: 群聊长按菜单行(固定配色:图标底块 + 文字)。 */
@Composable
private fun GroupChatActionRow(
    icon: ImageVector,
    text: String,
    textColor: Color,
    iconBlockColor: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(9.dp),
            color = iconBlockColor,
            modifier = Modifier.size(30.dp),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = text,
                    modifier = Modifier.size(16.dp),
                    tint = textColor,
                )
            }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
        )
    }
}
