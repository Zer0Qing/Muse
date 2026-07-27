@file:OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package io.zer0.muse.ui.groupchat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.HowToVote
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import io.zer0.muse.ui.common.IosChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import io.zer0.muse.ui.common.IosTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.groupchat.GroupChatMessageEntity
import io.zer0.muse.doc.DocumentParser
import io.zer0.muse.ui.SmartImage
import io.zer0.muse.ui.common.AssistantAvatar
import io.zer0.muse.ui.common.FullScreenMediaViewer
import io.zer0.muse.ui.common.MuseBottomSheet
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.markdown.MarkdownText
import io.zer0.muse.ui.theme.MuseHaptics
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.ui.theme.userBubble  // v1.48 (h18): 气泡形状令牌
import io.zer0.muse.ui.theme.assistantBubble  // v1.48 (h18): 气泡形状令牌
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.koin.androidx.compose.koinViewModel
import java.io.ByteArrayOutputStream

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
    var showMembersDialog by rememberSaveable { mutableStateOf(false) }
    var showToolSheet by rememberSaveable { mutableStateOf(false) }
    // v1.97: 群聊编辑对话框
    var showEditDialog by rememberSaveable { mutableStateOf(false) }
    // v1.77: 消息长按菜单 + 删除确认
    var messageMenuTarget by remember { mutableStateOf<GroupChatMessageEntity?>(null) }
    var deleteMessageTarget by remember { mutableStateOf<GroupChatMessageEntity?>(null) }
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
            // H3: 用 resultOf 替代 runCatching,避免吞 CancellationException;失败时记录日志而非静默忽略
            resultOf {
                val dataUri = withContext(Dispatchers.IO) {
                    // H5: 图片降采样 + 大小限制,防止 OOM 和 DB 膨胀
                    // 1. 先用 inJustDecodeBounds 获取图片尺寸
                    val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        BitmapFactory.decodeStream(input, null, boundsOptions)
                    } ?: throw IllegalStateException("无法读取图片")

                    if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
                        throw IllegalStateException("无法解析图片尺寸")
                    }

                    // 2. 计算 inSampleSize 使最大边长 <= 1024px
                    val maxDim = maxOf(boundsOptions.outWidth, boundsOptions.outHeight)
                    var sampleSize = 1
                    while (maxDim / sampleSize > 1024) sampleSize *= 2

                    // 3. 重新打开流并用 inSampleSize 解码 Bitmap
                    val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
                        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                        BitmapFactory.decodeStream(input, null, decodeOptions)
                    } ?: throw IllegalStateException("无法重新读取图片")

                    try {
                        // 4. 编码为 base64,超过 2MB 则递减 quality 压缩
                        encodeBitmapToBase64(bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                }
                viewModel.addPendingImage("data:image/jpeg;base64,$dataUri")
            }.onError { msg, t ->
                // H3/L11: 记录图片读取失败日志,不再静默忽略
                Logger.w("GroupChatDetail", "图片读取失败: $msg", t)
            }
        }
    }

    // v1.137: 注入 DocumentParser(与单聊一致,支持 PDF 二进制解析)
    val documentParser: DocumentParser = org.koin.compose.koinInject()

    // v1.112 (C2): 文件附件选择器 — 读取文本内容追加到输入框
    // v1.137: 改用 DocumentParser 解析(替代 bufferedReader 字符流,支持 PDF)
    val documentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            resultOf {
                withContext(Dispatchers.IO) {
                    documentParser.parse(uri, context)
                }
            }.onSuccess { text ->
                if (text.isNotBlank()) {
                    // 截断到 8000 字符(与单聊 ChatDocumentCoordinator 一致,避免输入框爆炸)
                    val DOC_MAX_CHARS = 8000
                    val truncated = if (text.length > DOC_MAX_CHARS) {
                        text.take(DOC_MAX_CHARS) + "\n…(已截断)"
                    } else {
                        text
                    }
                    val current = state.inputText
                    val merged = if (current.isBlank()) truncated else "$current\n\n---\n\n$truncated"
                    viewModel.updateInput(merged)
                } else {
                    Logger.w("GroupChatDetail", "文档内容为空或不支持的格式")
                }
            }.onError { msg, t ->
                Logger.w("GroupChatDetail", "文档解析失败: $msg", t)
            }
        }
    }

    // v1.112 (C2): Prompt 模板选择 sheet 状态
    var showPromptTemplateSheet by remember { mutableStateOf(false) }
    val settings: io.zer0.muse.data.SettingsRepository = org.koin.compose.koinInject()
    val promptTemplates by settings.promptTemplatesFlow.collectAsStateWithLifecycle(initialValue = emptyList())

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
    LaunchedEffect(state.currentMessages.size, state.isAgentResponding) {
        if (state.currentMessages.isEmpty()) return@LaunchedEffect
        val lastIndex = state.currentMessages.lastIndex
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val isNearBottom = lastVisible >= lastIndex - 2 || lastVisible < 0
        if (isNearBottom) {
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

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = chatName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.groupchat_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showMembersDialog = true }) {
                        Icon(
                            imageVector = Icons.Filled.Group,
                            contentDescription = stringResource(R.string.groupchat_members_cd),
                        )
                    }
                    IconButton(onClick = { showEditDialog = true }) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = stringResource(R.string.groupchat_edit_cd),
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column {
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
                    canSend = state.inputText.isNotBlank() || state.pendingImages.isNotEmpty(),
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
                        imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                        contentDescription = null,
                        modifier = Modifier.size(MuseIconSizes.touchTarget),
                        tint = MaterialTheme.colorScheme.outlineVariant,
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
                    .padding(innerPadding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = MusePaddings.screen,
                    vertical = MusePaddings.itemGap,
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
                        isMoodExpanded = expandedState?.isMoodExpanded,
                        isReasoningExpanded = expandedState?.isReasoningExpanded,
                        onToggleMoodExpanded = { viewModel.toggleMessageMoodExpanded(message.id) },
                        onToggleReasoningExpanded = { viewModel.toggleMessageReasoningExpanded(message.id) },
                        // v1.77: 长按弹出操作菜单
                        onLongClick = {
                            MuseHaptics.medium(haptic)
                            messageMenuTarget = message
                        },
                        onHtmlPreview = onHtmlPreview,
                    )
                }
                // Agent 正在回复时的"正在思考..."状态
                if (state.isAgentResponding) {
                    item(key = "thinking_indicator") {
                        ThinkingIndicator(currentSpeaker = state.currentSpeaker)
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
    }

    // v1.97: 编辑群聊对话框(改名 / 改成员)
    if (showEditDialog) {
        val chat = state.currentChat
        val initialMemberIds = remember(chat) {
            chat?.let { viewModel.parseMemberIds(it) } ?: emptyList()
        }
        EditGroupChatDialog(
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

    // v2.x: 消息长按操作菜单(复制 / 重新生成 / 引用回复 / 悄悄话 / 删除)
    messageMenuTarget?.let { msg ->
        MuseDialog(
            onDismissRequest = { messageMenuTarget = null },
            title = if (msg.senderType == "user") stringResource(R.string.groupchat_my_message) else msg.senderName,
            content = {
                Column {
                    TextButton(onClick = {
                        messageMenuTarget = null
                        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("message", msg.body))
                        io.zer0.muse.ui.common.MuseToast.show(context.getString(R.string.groupchat_copied))
                    }) {
                        Text(stringResource(R.string.groupchat_copy))
                    }
                    // v2.x: AI 消息 → 重新生成
                    if (msg.senderType == "assistant") {
                        TextButton(onClick = {
                            messageMenuTarget = null
                            viewModel.regenerateAgentMessage(msg.senderId)
                        }) {
                            Text(stringResource(R.string.groupchat_regenerate))
                        }
                    }
                    // v2.x: 引用回复
                    TextButton(onClick = {
                        messageMenuTarget = null
                        replyToMessage = msg
                    }) {
                        Text(stringResource(R.string.groupchat_reply))
                    }
                    // v2.x: AI 消息 → 悄悄话
                    if (msg.senderType == "assistant") {
                        TextButton(onClick = {
                            messageMenuTarget = null
                            whisperTarget = msg
                        }) {
                            Text(stringResource(R.string.groupchat_whisper))
                        }
                    }
                    TextButton(onClick = {
                        messageMenuTarget = null
                        deleteMessageTarget = msg
                    }) {
                        Text(stringResource(R.string.groupchat_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            onConfirm = null,
            dismissText = stringResource(R.string.groupchat_cancel),
            onDismiss = { messageMenuTarget = null },
        )
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

    // v2.x: 引用回复对话框
    replyToMessage?.let { msg ->
        var replyText by remember { mutableStateOf("") }
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
                    IosTextField(
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
                    IosTextField(
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
                IosTextField(
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
                IosTextField(
                    value = newDocTitle,
                    onValueChange = { newDocTitle = it },
                    label = { Text(stringResource(R.string.groupchat_context_doc_title)) },
                    placeholder = { Text(stringResource(R.string.groupchat_context_doc_title_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                IosTextField(
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
                                    IosTextField(
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
}

/**
 * 群聊消息气泡 — 区分 user(右侧)/ assistant(左侧 + 头像)。
 *
 * @param message 消息实体
 * @param assistants 全部助手列表(查找头像用)
 * @param chatPrefs 聊天偏好(控制 mood/reasoning 显示与默认展开)
 * @param isMoodExpanded mood 块外部受控展开状态(null 表示使用默认)
 * @param isReasoningExpanded reasoning 块外部受控展开状态(null 表示使用默认)
 * @param onToggleMoodExpanded mood 块展开切换回调
 * @param onToggleReasoningExpanded reasoning 块展开切换回调
 */
@Composable
private fun GroupChatMessageBubble(
    message: GroupChatMessageEntity,
    assistants: List<AssistantEntity>,
    chatPrefs: io.zer0.muse.data.ChatPreferences = io.zer0.muse.data.ChatPreferences(),
    isMoodExpanded: Boolean? = null,
    isReasoningExpanded: Boolean? = null,
    onToggleMoodExpanded: () -> Unit = {},
    onToggleReasoningExpanded: () -> Unit = {},
    // v1.77: 长按弹出操作菜单(复制 / 删除)
    onLongClick: () -> Unit = {},
    /** HTML/SVG 代码块全屏预览回调。 */
    onHtmlPreview: (String) -> Unit = {},
) {
    val isUser = message.senderType == "user"
    if (isUser) {
        // 用户消息:右侧气泡
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {},
                    onLongClick = onLongClick,
                ),
            horizontalArrangement = Arrangement.End,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 280.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Surface(
                    shape = MuseShapes.userBubble,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
                        // v2.x: 悄悄话标记
                        if (message.whisperTargetId != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(bottom = 4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(12.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.groupchat_message_whisper),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                        Text(
                            text = message.body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        // 图片附件
                        MessageImageGrid(
                            imageBase64Json = message.imageBase64Json,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    } else {
        // Agent 消息:左侧 + 头像 + senderName
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {},
                    onLongClick = onLongClick,
                ),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.Top,
        ) {
            // 头像
            val assistant = remember(message.senderId, assistants) {
                assistants.find { it.id == message.senderId }
            }
            if (assistant != null) {
                AssistantAvatar(
                    assistant = assistant,
                    avatarSize = 32.dp,
                )
            } else {
                // 兜底:首字母圆形头像
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = message.senderName.firstOrNull()?.toString() ?: "A",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            // 气泡
            Column(modifier = Modifier.widthIn(max = 280.dp)) {
                Text(
                    text = message.senderName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                )
                // v1.46: MOOD 标签胶囊(Agent 内部腹稿,可折叠)
                if (chatPrefs.showMoodBlock) {
                    message.mood?.takeIf { it.isNotBlank() }?.let { mood ->
                        val moodExpanded = isMoodExpanded ?: chatPrefs.moodExpandedByDefault
                        MoodCapsule(
                            mood = mood,
                            expanded = moodExpanded,
                            onToggle = onToggleMoodExpanded,
                        )
                    }
                }
                // v1.46: 思考过程块(可折叠)
                if (chatPrefs.showReasoning) {
                    message.reasoning?.takeIf { it.isNotBlank() }?.let { reasoning ->
                        val reasoningExpanded = isReasoningExpanded ?: chatPrefs.reasoningExpandedByDefault
                        GroupChatExpandableBlock(
                            title = stringResource(R.string.groupchat_reasoning),
                            content = reasoning,
                            expanded = reasoningExpanded,
                            onToggle = onToggleReasoningExpanded,
                            titleColor = MaterialTheme.colorScheme.outline,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        )
                    }
                }
                Surface(
                    shape = MuseShapes.assistantBubble,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                ) {
                    Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
                        MarkdownText(
                            text = message.body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            onHtmlPreview = onHtmlPreview,
                        )
                        // 图片附件
                        MessageImageGrid(
                            imageBase64Json = message.imageBase64Json,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * MOOD 标签胶囊 — 浅绿色背景,品牌色文字,可展开查看完整腹稿。
 */
@Composable
private fun MoodCapsule(
    mood: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MuseShapes.medium,
        modifier = Modifier
            .padding(bottom = 6.dp)
            .clickable { onToggle() },
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "MOOD",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) stringResource(R.string.groupchat_collapse) else stringResource(R.string.groupchat_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconTiny),
                )
            }
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = mood,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 群聊可折叠块 — 用于 MOOD / 思考过程。
 */
@Composable
private fun GroupChatExpandableBlock(
    title: String,
    content: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    titleColor: Color,
    containerColor: Color,
) {
    Surface(
        color = containerColor,
        shape = MuseShapes.medium,
        tonalElevation = 0.dp,
        modifier = Modifier
            .widthIn(max = 360.dp)
            .padding(bottom = 6.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .padding(vertical = MusePaddings.tinyGap),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = titleColor,
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) stringResource(R.string.groupchat_collapse) else stringResource(R.string.groupchat_expand),
                    tint = titleColor,
                    modifier = Modifier.size(MuseIconSizes.iconTiny),
                )
            }
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 消息图片网格 — 在气泡内展示用户/Agent 发送的图片附件。
 */
@Composable
private fun MessageImageGrid(
    imageBase64Json: String,
    modifier: Modifier = Modifier,
) {
    val images = remember(imageBase64Json) {
        // L4: 用 resultOf 替代 runCatching,getOrNull 替代 getOrDefault
        resultOf {
            AppJson.decodeFromString(ListSerializer(String.serializer()), imageBase64Json)
        }.getOrNull() ?: emptyList()
    }
    if (images.isEmpty()) return
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        images.forEach { image ->
            SmartImage(
                model = image,
                contentDescription = stringResource(R.string.groupchat_image),
                modifier = Modifier
                    .size(120.dp)
                    .clip(MuseShapes.semiLarge),
            )
        }
    }
}

/**
 * "正在思考..."等待状态 — Agent 回复期间显示。
 * v1.104: 若 currentSpeaker 非空,显示"XXX 正在思考..."并高亮其头像;
 *         为空时回退到通用"正在思考..."(向后兼容)。
 */
@Composable
private fun ThinkingIndicator(currentSpeaker: AssistantEntity? = null) {
    val displayName = currentSpeaker?.name?.takeIf { it.isNotBlank() }
    val thinkingText = if (displayName != null) {
        stringResource(R.string.groupchat_agent_thinking, displayName)
    } else {
        stringResource(R.string.groupchat_thinking)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(MuseIconSizes.iconLarge)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
            )
        }
        Spacer(Modifier.width(8.dp))
        Surface(
            // v1.48 (h18): 用 BubbleShape 令牌统一气泡圆角(原 4/18/18/18 → 6/20/20/20)
            shape = MuseShapes.assistantBubble,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        ) {
            Row(
                modifier = Modifier.padding(MusePaddings.cardInnerMedium),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,  // v1.115: 显式指定,深色模式可见性
                )
                Text(
                    text = thinkingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 群聊输入栏 — 加号菜单 + 文本输入 + 发送按钮。
 *
 * @param text 当前输入文本
 * @param onTextChange 文本变化回调
 * @param onSend 发送回调
 * @param onOpenToolSheet 打开加号菜单回调
 * @param enabled 是否可用(Agent 回复期间禁用)
 * @param canSend 是否可以发送
 * @param members 群聊成员列表(@mention 自动补全用)
 */
@Composable
private fun GroupChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onOpenToolSheet: () -> Unit,
    enabled: Boolean,
    canSend: Boolean,
    members: List<AssistantEntity> = emptyList(),
) {
    // @mention 自动补全状态:用户点击外部关闭后置 false,再次输入 @ 触发时置 true
    var showMentionDropdown by remember { mutableStateOf(false) }
    // 从文本末尾找最后一个 @,若 @ 后无空白则为有效 mention 查询(参考 openhanako channel-mentions)
    val mentionQuery: String? = remember(text) {
        val atIndex = text.lastIndexOf('@')
        if (atIndex < 0) return@remember null
        val afterAt = text.substring(atIndex + 1)
        // @ 后无空白字符 — 有效 mention 查询(空字符串表示刚输入 @)
        if (afterAt.isEmpty() || !afterAt.any { it.isWhitespace() }) afterAt else null
    }
    // 用户输入新 @ 时重新打开下拉
    LaunchedEffect(mentionQuery) {
        if (mentionQuery != null) showMentionDropdown = true
    }
    // 过滤匹配成员(按名称包含查询文本,大小写不敏感;长 alias 优先排序)
    val filteredMembers = remember(mentionQuery, members) {
        if (mentionQuery == null) {
            emptyList()
        } else if (mentionQuery.isEmpty()) {
            members.sortedByDescending { it.name.length }
        } else {
            members
                .filter { it.name.contains(mentionQuery, ignoreCase = true) }
                .sortedByDescending { it.name.length }
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Box {
            // @mention 自动补全下拉(锚定在输入框上方)
            DropdownMenu(
                expanded = showMentionDropdown && filteredMembers.isNotEmpty(),
                onDismissRequest = { showMentionDropdown = false },
            ) {
                filteredMembers.take(8).forEach { member ->
                    DropdownMenuItem(
                        text = { Text(member.name) },
                        onClick = {
                            // 替换 @query 为 @memberName(末尾加空格,便于继续输入)
                            val atIndex = text.lastIndexOf('@')
                            if (atIndex >= 0) {
                                val newText = text.substring(0, atIndex) + "@${member.name} "
                                onTextChange(newText)
                            }
                            showMentionDropdown = false
                        },
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // v1.99: 大R角/曲面屏横向安全区避让
                    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = MusePaddings.screen, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
            ) {
                // 加号菜单入口
                Surface(
                    onClick = onOpenToolSheet,
                    enabled = enabled,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.touchTarget),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.groupchat_tools),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.icon),
                        )
                    }
                }
                IosTextField(
                    value = text,
                    onValueChange = onTextChange,
                    placeholder = { Text(stringResource(R.string.groupchat_input_placeholder)) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                    singleLine = false,
                )
                Surface(
                    onClick = onSend,
                    enabled = enabled && canSend,
                    shape = CircleShape,
                    color = if (enabled && canSend) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    modifier = Modifier.size(MuseIconSizes.touchTarget),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.groupchat_send),
                            tint = if (enabled && canSend) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                            },
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 群聊活动状态栏 — 展示当前轮转中各 agent 的状态 chip。
 *
 * IDLE 状态被过滤不显示;无活动时整个栏不占空间。
 * 参考openhanako ActivityHub UI:紧凑横向 chip 行,每个 chip 含图标 + 名字 + 状态文案;
 * REPLYING 态加呼吸动画,ERROR 态用错误配色,NO_REPLY 态弱化展示。
 *
 * @param activities 当前群聊中各 agent 的活动列表
 */
@Composable
private fun AgentActivityBar(activities: List<AgentActivity>) {
    // 过滤掉 IDLE 状态(默认空闲,不展示)
    val visible = activities.filter { it.status != AgentActivityStatus.IDLE }
    if (visible.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = MusePaddings.screen, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            visible.forEach { activity ->
                AgentActivityChip(activity)
            }
        }
    }
}

/**
 * 单个 agent 活动状态 chip — 图标 + 名字 + 状态文案,配色随状态变化。
 *
 * REPLYING 态使用 [rememberInfiniteTransition] 做呼吸 alpha 动画,让用户感知"正在输出"。
 */
@Composable
private fun AgentActivityChip(activity: AgentActivity) {
    val bgColor = when (activity.status) {
        AgentActivityStatus.VIEWING -> MaterialTheme.colorScheme.secondaryContainer
        AgentActivityStatus.REPLYING -> MaterialTheme.colorScheme.primaryContainer
        AgentActivityStatus.NO_REPLY -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        AgentActivityStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
        AgentActivityStatus.IDLE -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (activity.status) {
        AgentActivityStatus.VIEWING -> MaterialTheme.colorScheme.onSecondaryContainer
        AgentActivityStatus.REPLYING -> MaterialTheme.colorScheme.onPrimaryContainer
        AgentActivityStatus.NO_REPLY -> MaterialTheme.colorScheme.onSurfaceVariant
        AgentActivityStatus.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        AgentActivityStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val icon = when (activity.status) {
        AgentActivityStatus.VIEWING -> Icons.Filled.Visibility
        AgentActivityStatus.REPLYING -> Icons.Filled.Edit
        AgentActivityStatus.NO_REPLY -> Icons.Filled.Block
        AgentActivityStatus.ERROR -> Icons.Filled.ErrorOutline
        AgentActivityStatus.IDLE -> Icons.Filled.Block
    }
    val statusLabel = when (activity.status) {
        AgentActivityStatus.VIEWING -> stringResource(R.string.groupchat_activity_viewing)
        AgentActivityStatus.REPLYING -> stringResource(R.string.groupchat_activity_replying)
        AgentActivityStatus.NO_REPLY -> stringResource(R.string.groupchat_activity_no_reply)
        AgentActivityStatus.ERROR -> stringResource(R.string.groupchat_activity_error)
        AgentActivityStatus.IDLE -> ""
    }
    // REPLYING 态呼吸动画:alpha 在 1f↔0.5f 间循环,让 chip 有"正在输出"的视觉反馈
    val infiniteTransition = rememberInfiniteTransition(label = "activity_pulse")
    val pulseAlpha = infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_alpha",
    ).value
    val chipAlpha = if (activity.status == AgentActivityStatus.REPLYING) pulseAlpha else 1f
    Surface(
        shape = MuseShapes.semiLarge,
        color = bgColor,
        contentColor = contentColor,
        modifier = Modifier.alpha(chipAlpha),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            val agentInitial = remember(activity.assistantName) {
                activity.assistantName.firstOrNull()?.toString() ?: ""
            }
            Text(
                text = "$agentInitial $statusLabel",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

/**
 * 群聊加号菜单 — MANUS 风格底部展开面板。
 *
 * v2.1: 将原顶部栏功能移入加号菜单:
 *  - 图片(相册)
 *  - 附件(文本文件,读取内容插入输入框)
 *  - 引用知识库(插入 @ 标记)
 *  - Prompt 模板(从 settings 读取模板列表,选择后插入输入框)
 *  - 成员列表 / 发起表决 / 总结讨论 / 群聊上下文 / @提及成员 / 编辑群聊
 */
@Composable
private fun GroupChatToolSheet(
    onPickImage: () -> Unit,
    onPickDocument: () -> Unit,
    onInsertKnowledge: () -> Unit,
    onPickPromptTemplate: () -> Unit,
    onOpenMembers: () -> Unit,
    onLaunchVote: () -> Unit,
    onLaunchSummary: () -> Unit,
    onOpenContext: () -> Unit,
    onMentionMember: () -> Unit,
    onEditGroup: () -> Unit,
    onDismiss: () -> Unit,
) {
    MuseBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(R.string.groupchat_pick_content),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(16.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                // 图片附件入口
                GroupChatToolRow(
                    icon = Icons.Default.Photo,
                    title = stringResource(R.string.groupchat_image),
                    subtitle = stringResource(R.string.groupchat_pick_from_gallery),
                    onClick = {
                        onPickImage()
                        onDismiss()
                    },
                )
                GroupChatToolDivider()
                // 附件入口
                GroupChatToolRow(
                    icon = Icons.Default.AttachFile,
                    title = stringResource(R.string.chat_tool_attachment),
                    onClick = {
                        onPickDocument()
                        onDismiss()
                    },
                )
                GroupChatToolDivider()
                // 引用知识库
                GroupChatToolRow(
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    title = stringResource(R.string.chat_tool_knowledge),
                    subtitle = stringResource(R.string.chat_tool_knowledge_subtitle),
                    onClick = {
                        onInsertKnowledge()
                        onDismiss()
                    },
                )
                GroupChatToolDivider()
                // Prompt 模板
                GroupChatToolRow(
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    title = stringResource(R.string.chat_prompt_templates_title),
                    onClick = {
                        onPickPromptTemplate()
                        onDismiss()
                    },
                )
                GroupChatToolDivider()
                // 成员列表(原顶部栏)
                GroupChatToolRow(
                    icon = Icons.Filled.Group,
                    title = stringResource(R.string.groupchat_tool_members),
                    onClick = {
                        onOpenMembers()
                        onDismiss()
                    },
                )
                GroupChatToolDivider()
                // 发起表决(原顶部栏)
                GroupChatToolRow(
                    icon = Icons.Filled.HowToVote,
                    title = stringResource(R.string.groupchat_tool_vote),
                    onClick = {
                        onLaunchVote()
                        onDismiss()
                    },
                )
                GroupChatToolDivider()
                // 总结讨论(原顶部栏)
                GroupChatToolRow(
                    icon = Icons.Filled.Summarize,
                    title = stringResource(R.string.groupchat_tool_summary),
                    onClick = {
                        onLaunchSummary()
                        onDismiss()
                    },
                )
                GroupChatToolDivider()
                // 群聊上下文(原顶部栏)
                GroupChatToolRow(
                    icon = Icons.Filled.Folder,
                    title = stringResource(R.string.groupchat_tool_context),
                    onClick = {
                        onOpenContext()
                        onDismiss()
                    },
                )
                GroupChatToolDivider()
                // @提及成员
                GroupChatToolRow(
                    icon = Icons.Filled.Edit,
                    title = stringResource(R.string.groupchat_tool_mention),
                    onClick = {
                        onMentionMember()
                        onDismiss()
                    },
                )
                GroupChatToolDivider()
                // 编辑群聊(原顶部栏)
                GroupChatToolRow(
                    icon = Icons.Filled.Edit,
                    title = stringResource(R.string.groupchat_edit_cd),
                    onClick = {
                        onEditGroup()
                        onDismiss()
                    },
                )
            }
        }
    }
}

/** v1.112: 群聊工具菜单行(iOS 风格左图标 + 标题/副标题 + 右箭头)。 */
@Composable
private fun GroupChatToolRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        shape = MuseShapes.semiLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(MuseIconSizes.icon),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** v1.112: 群聊工具菜单分隔线。 */
@Composable
private fun GroupChatToolDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        thickness = 0.5.dp,
    )
}

/**
 * 待发送图片预览行 — 可点击右上角删除。
 */
@Composable
private fun PendingImagesRow(
    images: List<String>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        images.forEach { image ->
            Box(modifier = Modifier.size(64.dp)) {
                io.zer0.muse.ui.SmartImage(
                    model = image,
                    contentDescription = stringResource(R.string.groupchat_pending_image),
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(MuseShapes.semiLarge),
                )
                // H-GC3 修复: 原先 IconButton size(20.dp) 触摸目标仅 20dp,不满足 48dp 红线。
                // 改为外层 Box(48dp 触摸区,半透明背景)+ 内层 Icon(14dp 视觉),
                // Box 用 align(TopEnd) 定位在右上角,触摸区可超出图片边界。
                // v1.115: 移除冗余 shape=CircleShape(.clip 已裁剪)、移除 padding 偏移(用居中对齐)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(MuseIconSizes.touchTarget)
                        .clip(CircleShape)
                        .background(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                        .clickable { onRemove(image) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.groupchat_delete),
                        modifier = Modifier.size(MuseIconSizes.iconTiny),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/**
 * 成员列表对话框。
 */
@Composable
private fun MembersDialog(
    memberNames: List<String>,
    memberCount: Int,
    onDismiss: () -> Unit,
) {
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.groupchat_members_title, memberCount),
        content = {
            if (memberNames.isEmpty()) {
                Text(
                    text = stringResource(R.string.groupchat_no_members),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    memberNames.forEach { name ->
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmText = stringResource(R.string.groupchat_close),
        onConfirm = onDismiss,
        dismissText = null,
    )
}

/**
 * v1.97: 编辑群聊对话框 — 改名 + 改成员。
 *
 * 参考 [CreateGroupChatDialog] 的样式,预填当前群聊名与已选成员,
 * 保存时调用 [GroupChatViewModel.updateChat]。
 *
 * @param initialName 当前群聊名(预填)
 * @param assistants 全部助手列表(成员候选)
 * @param initialMemberIds 当前成员 id 列表(预选)
 * @param onDismiss 关闭回调
 * @param onConfirm 确认回调(newName, newMemberIds)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditGroupChatDialog(
    initialName: String,
    assistants: List<AssistantEntity>,
    initialMemberIds: List<String>,
    initialDiscussionMode: String = "round_robin",
    initialAutoMaxRounds: Int = 5,
    initialHostId: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (
        newName: String,
        newMemberIds: List<String>,
        newDiscussionMode: String,
        newAutoMaxRounds: Int,
        newHostId: String?,
    ) -> Unit,
) {
    // v1.97: 用 rememberSaveable 持久化编辑中的状态,旋转屏不丢
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    var selectedMemberIds by rememberSaveable(initialMemberIds.joinToString(",")) {
        mutableStateOf(initialMemberIds.toSet())
    }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    // v2.x: 讨论模式状态
    var discussionMode by rememberSaveable(initialDiscussionMode) { mutableStateOf(initialDiscussionMode) }
    var autoMaxRounds by rememberSaveable(initialAutoMaxRounds) { mutableStateOf(initialAutoMaxRounds) }
    var hostId by rememberSaveable(initialHostId ?: "") { mutableStateOf(initialHostId ?: "") }

    val maxNameLength = 30
    val nameError = showErrors && name.isBlank()
    val memberError = showErrors && selectedMemberIds.isEmpty()

    // 讨论模式列表
    val modeOptions = listOf(
        "round_robin" to R.string.groupchat_mode_round_robin,
        "auto" to R.string.groupchat_mode_auto,
        "debate" to R.string.groupchat_mode_debate,
        "host" to R.string.groupchat_mode_host,
    )
    val modeDescMap = mapOf(
        "round_robin" to R.string.groupchat_mode_round_robin_desc,
        "auto" to R.string.groupchat_mode_auto_desc,
        "debate" to R.string.groupchat_mode_debate_desc,
        "host" to R.string.groupchat_mode_host_desc,
    )

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.groupchat_edit_title),
        content = {
            // 群聊名输入框
            IosTextField(
                value = name,
                onValueChange = { newName ->
                    showErrors = false
                    name = newName.take(maxNameLength)
                },
                label = { Text(stringResource(R.string.groupchat_edit_name_hint)) },
                singleLine = true,
                isError = nameError,
                supportingText = if (nameError) {
                    { Text(stringResource(R.string.groupchat_name_required), color = MaterialTheme.colorScheme.error) }
                } else {
                    { Text("${name.length}/$maxNameLength") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))

            // 成员选择
            Text(
                text = stringResource(R.string.groupchat_select_members),
                style = MaterialTheme.typography.labelMedium,
                color = if (memberError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.outline
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            if (assistants.isEmpty()) {
                Text(
                    text = stringResource(R.string.groupchat_no_assistants),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    assistants.forEach { assistant ->
                        val selected = assistant.id in selectedMemberIds
                        IosChip(
                            selected = selected,
                            onClick = {
                                showErrors = false
                                selectedMemberIds = if (selected) {
                                    selectedMemberIds - assistant.id
                                } else {
                                    selectedMemberIds + assistant.id
                                }
                            },
                            label = assistant.name,
                        )
                    }
                }
            }
            if (memberError) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.groupchat_edit_member_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.groupchat_selected_members, selectedMemberIds.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth(),
            )

            // ── v2.x: 讨论模式选择 ──
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.groupchat_mode_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                modeOptions.forEach { (mode, labelRes) ->
                    IosChip(
                        selected = discussionMode == mode,
                        onClick = { discussionMode = mode },
                        label = stringResource(labelRes),
                    )
                }
            }
            // 当前模式描述
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(modeDescMap[discussionMode] ?: R.string.groupchat_mode_round_robin_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )

            // Auto 模式:最大轮数滑块
            if (discussionMode == "auto") {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.groupchat_mode_auto_rounds),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "$autoMaxRounds",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Slider(
                    value = autoMaxRounds.toFloat(),
                    onValueChange = { autoMaxRounds = it.toInt().coerceIn(1, 20) },
                    valueRange = 1f..20f,
                    steps = 18,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // 主持人模式:选择主持人
            if (discussionMode == "host") {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.groupchat_mode_select_host),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // "不选"选项
                    IosChip(
                        selected = hostId.isBlank(),
                        onClick = { hostId = "" },
                        label = stringResource(R.string.groupchat_mode_no_host),
                    )
                    // 只能选已选成员做主持人
                    assistants.filter { it.id in selectedMemberIds }.forEach { assistant ->
                        IosChip(
                            selected = hostId == assistant.id,
                            onClick = { hostId = assistant.id },
                            label = assistant.name,
                        )
                    }
                }
            }
        },
        confirmText = stringResource(R.string.groupchat_edit_btn),
        onConfirm = {
            val trimmedName = name.trim()
            if (trimmedName.isNotBlank() && selectedMemberIds.isNotEmpty()) {
                onConfirm(
                    trimmedName,
                    selectedMemberIds.toList(),
                    discussionMode,
                    autoMaxRounds,
                    hostId.ifBlank { null },
                )
            } else {
                showErrors = true
            }
        },
        dismissText = stringResource(R.string.groupchat_cancel),
        onDismiss = onDismiss,
    )
}

/**
 * H5: 把 Bitmap 编码为 base64 字符串。
 *
 * 用 JPEG 递减 quality 压缩,直到 base64 字符串不超过 2MB。
 * 最终仍超过 2MB 则抛异常,提示用户选择较小图片。
 */
private fun encodeBitmapToBase64(bitmap: Bitmap): String {
    val maxBase64Length = 2 * 1024 * 1024 // 2MB(base64 字符串长度上限)
    val baos = ByteArrayOutputStream()
    var quality = 90
    var base64: String
    do {
        baos.reset()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        base64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.DEFAULT)
        if (base64.length > maxBase64Length) {
            quality -= 10
        } else {
            break
        }
    } while (quality >= 20)

    if (base64.length > maxBase64Length) {
        throw IllegalStateException("图片过大,请选择较小图片")
    }
    return base64
}
