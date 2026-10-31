package io.zer0.muse.miniphone.ui.chat

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.zer0.muse.miniphone.ui.icons.MiniPhoneIcons
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 聊天页入口:取会话自己的 ViewModel,把返回值挂到系统返回键上,并负责拉起系统图片选择器。
 *
 * 会话 id 是本页唯一的构造参数,VM 以它为键存在 ViewModelStore 里 —— 退出再进同一个会话
 * 会复用同一份状态,不会重新从库里拉一遍。
 */
@Composable
internal fun MiniPhoneChatRoute(threadId: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    // 以会话 id 作 ViewModel 键:不同会话各一份状态,同一个会话退出再进复用
    val viewModel: MiniPhoneChatViewModel = koinViewModel(key = threadId) { parametersOf(threadId) }
    val state by viewModel.uiState.collectAsState()
    // 系统图片选择器:只认图片,一次最多 MAX_PICKED_IMAGES 张;选中的交给 VM 逐张落库
    val pickImages = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(MAX_PICKED_IMAGES),
    ) { uris -> viewModel.sendImages(uris) }
    // 页面可见期间持续清未读:进页清一次,之后每来一条新消息再清一次。
    // 只在可见时挂着 —— 在别处也清的话,后台来的消息会永远看不到未读提示
    DisposableEffect(viewModel) {
        viewModel.setVisible(true)
        onDispose { viewModel.setVisible(false) }
    }
    BackHandler(onBack = onBack)
    MiniPhoneChatScreen(
        state = state,
        onBack = onBack,
        onLoadOlder = viewModel::loadOlder,
        input = ChatInputActions(
            onSend = viewModel::send,
            onPickImages = {
                pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onTranscribeVoice = viewModel::transcribeVoice,
        ),
        messageActions = ChatMessageActions(
            onResend = viewModel::resend,
            onDelete = viewModel::deleteMessage,
        ),
        modifier = modifier,
        groupActions = GroupAdminActions(
            onRename = viewModel.groupAdmin::renameGroup,
            onAddMembers = viewModel.groupAdmin::addMembers,
            onRemoveMember = viewModel.groupAdmin::removeMember,
            onDisband = viewModel.groupAdmin::disbandGroup,
        ),
    )
}

/**
 * 聊天页:顶栏 + 消息列表 + 输入栏,以及覆盖在最上面的看图层。
 *
 * 无状态:状态与回调都从外面来,换一份 [ChatUiState] 就能在任何地方预览或断言。
 * 当前放大的图片只活在本页([rememberSaveable]),关页就没了,不进 `ChatUiState`。
 *
 * 新消息提示也活在本页:人离开底部后又有消息到达时,底部浮出一枚「N 条新消息」,
 * 点一下平滑回到最新一条;人本来就停在最新一条上时不显示。判定逻辑在 [NewMessagesEffect]。
 *
 * @param input 输入栏的三个回调(发送 / 选图 / 录音转写)
 * @param messageActions 单条消息上的重发与删除
 * @param groupActions 群成员页的管理动作(改名 / 加人 / 移出 / 解散);私信里用不到
 */
@Composable
internal fun MiniPhoneChatScreen(
    state: ChatUiState,
    onBack: () -> Unit,
    onLoadOlder: () -> Unit,
    input: ChatInputActions = ChatInputActions(),
    messageActions: ChatMessageActions = ChatMessageActions(),
    modifier: Modifier = Modifier,
    groupActions: GroupAdminActions = GroupAdminActions(),
) {
    val colors = MiniPhoneTheme.colors
    var viewingUri by rememberSaveable { mutableStateOf<String?>(null) }
    // 群成员页盖在聊天页上:只读一层,关掉就回到对话
    var membersOpen by rememberSaveable { mutableStateOf(false) }
    // 列表状态提到本页:新消息提示要读它推导「人在不在底部」
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var newMessageCount by remember { mutableIntStateOf(0) }
    NewMessagesEffect(
        listState = listState,
        messageTotal = state.messages.size,
        onCountChanged = { count -> newMessageCount = count },
    )
    // 看图层盖在聊天页上时,返回键先关它
    BackHandler(enabled = viewingUri != null) { viewingUri = null }
    BackHandler(enabled = membersOpen) { membersOpen = false }
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background),
        ) {
            ChatTopBar(
                name = state.title,
                avatarUri = if (state.isGroup) null else state.peerAvatarUri,
                onBack = onBack,
                // 群里顶栏就人多一行,私信里既没有人也不知道这个入口
                memberCount = state.memberCount.takeIf { state.isGroup },
                onOpenMembers = if (state.isGroup) {
                    { membersOpen = true }
                } else {
                    null
                },
            )
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                ChatMessageList(
                    state = state,
                    listState = listState,
                    maxBubbleWidth = maxWidth * BUBBLE_MAX_WIDTH_RATIO,
                    onLoadOlder = onLoadOlder,
                    onOpenImage = { uri -> viewingUri = uri },
                    messageActions = messageActions,
                    modifier = Modifier.fillMaxSize(),
                )
                if (newMessageCount > 0) {
                    ChatNewMessagesPill(
                        count = newMessageCount,
                        onClick = {
                            // 先收起胶囊再滚:滚动过程中人已经在回底部的路上,不必等动画结束
                            newMessageCount = 0
                            scope.launch { listState.animateScrollToItem(0) }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = PillBottomGap),
                    )
                }
            }
            ChatInputBar(
                onSend = input.onSend,
                onPickImages = input.onPickImages,
                onTranscribeVoice = input.onTranscribeVoice,
                voiceInputEnabled = state.voiceInputAvailable,
            )
        }
        val viewing = viewingUri
        if (viewing != null) {
            MiniPhoneImageViewer(uri = viewing, onDismiss = { viewingUri = null })
        }
        if (membersOpen && state.isGroup) {
            GroupMembersPage(
                members = state.members,
                title = state.title,
                characters = state.characters,
                onBack = { membersOpen = false },
                actions = GroupAdminActions(
                    onRename = groupActions.onRename,
                    onAddMembers = groupActions.onAddMembers,
                    onRemoveMember = groupActions.onRemoveMember,
                    onDisband = {
                        // 解散之后这个会话已经不在了:关掉成员页,再退回消息列表
                        groupActions.onDisband()
                        membersOpen = false
                        onBack()
                    },
                ),
            )
        }
    }
}

/**
 * 顶栏:返回、头像、名字;群聊多一个人数,点一下进成员页。
 *
 * 右侧留一块与返回键等宽的空白,名字不会被挤偏。人数紧跟名字而不是靠右——
 * 「阿远、小满、我（3）」是一个整体,分开写会读成两个不相干的信息。
 */
@Composable
private fun ChatTopBar(
    name: String,
    avatarUri: String?,
    onBack: () -> Unit,
    memberCount: Int? = null,
    onOpenMembers: (() -> Unit)? = null,
) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(dimens.pageHeaderHeight)
                .padding(horizontal = 8.dp)
                .testTag(MiniPhoneChatTags.GROUP_TOP_BAR)
                .then(if (onOpenMembers != null) Modifier.clickable(onClick = onOpenMembers) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(TopBarButtonSize)
                    .clip(CircleShape)
                    .clickable(onClick = onBack)
                    .testTag(MiniPhoneChatTags.BACK_BUTTON),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MiniPhoneIcons.Back,
                    contentDescription = BACK_DESCRIPTION,
                    tint = colors.textPrimary,
                    modifier = Modifier.size(TopBarIconSize),
                )
            }
            MiniPhoneChatAvatar(
                name = name,
                avatarUri = avatarUri,
                size = TopBarAvatarSize,
                modifier = Modifier.testTag(MiniPhoneChatTags.TOP_AVATAR),
            )
            Spacer(Modifier.width(8.dp))
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = name,
                    style = typography.title.copy(color = colors.textPrimary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .testTag(MiniPhoneChatTags.PEER_NAME),
                )
                if (memberCount != null) {
                    Text(
                        text = memberCountLabelOf(memberCount),
                        style = typography.timestamp.copy(color = colors.textSecondary),
                        maxLines = 1,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .testTag(MiniPhoneChatTags.GROUP_MEMBER_COUNT),
                    )
                }
            }
            Spacer(Modifier.width(TopBarButtonSize))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(dimens.hairline)
                .background(colors.divider),
        )
    }
}

/** 群人数写法:全角括号紧跟名字。 */
internal fun memberCountLabelOf(memberCount: Int): String = "（$memberCount）"

/**
 * 消息列表。
 *
 * `reverseLayout = true` 配一份「新 → 旧」的数据:下标 0 落在底部,进页就等于停在最新一条,
 * 而更早的消息在上方,滚到顶就是翻页方向。生成中的提示也插在下标 0。
 */
@Composable
private fun ChatMessageList(
    state: ChatUiState,
    listState: LazyListState,
    maxBubbleWidth: Dp,
    onLoadOlder: () -> Unit,
    onOpenImage: (String) -> Unit,
    messageActions: ChatMessageActions,
    modifier: Modifier = Modifier,
) {
    val displayed = remember(state.items) { state.items.asReversed() }
    // 两侧的身份只随状态变,不必每行都重新算一次
    val self = remember(state.selfName, state.selfAvatarUri) { ChatPersona(state.selfName, state.selfAvatarUri) }
    val peer = remember(state.peerName, state.peerAvatarUri) { ChatPersona(state.peerName, state.peerAvatarUri) }
    val activity = state.activity
    LazyColumn(
        state = listState,
        reverseLayout = true,
        contentPadding = PaddingValues(vertical = 8.dp),
        modifier = modifier.testTag(MiniPhoneChatTags.MESSAGE_LIST),
    ) {
        if (activity != null) {
            // 去做事的提示占掉「正在输入」那一格:知道对方在忙什么比三个点更具体
            item(key = ACTIVITY_ITEM_KEY, contentType = ACTIVITY_ITEM_KEY) {
                ChatActivityRow(kind = activity)
            }
        } else if (state.isGenerating) {
            item(key = TYPING_ITEM_KEY, contentType = TYPING_ITEM_KEY) {
                ChatTypingRow(
                    peerName = state.typingName ?: state.peerName,
                    peerAvatarUri = if (state.typingName != null) state.typingAvatarUri else state.peerAvatarUri,
                    // 群里两个角色轮流说话:那一行要写清现在是谁在打
                    showName = state.isGroup,
                )
            }
        }
        items(items = displayed, key = { it.key }, contentType = ::contentTypeOf) { item ->
            when (item) {
                is ChatItem.Time -> ChatTimeDividerRow(at = item.at)
                is ChatItem.Message -> ChatMessageRow(
                    message = item.message,
                    self = self,
                    peer = peer,
                    senderLabel = if (state.isGroup) state.memberNames[item.message.characterId] else null,
                    maxBubbleWidth = maxBubbleWidth,
                    onOpenImage = onOpenImage,
                    messageActions = messageActions,
                )
            }
        }
    }
    LoadOlderEffect(listState = listState, hasMore = state.hasMore, onLoadOlder = onLoadOlder)
}

/** 滚到列表的「顶」(倒序布局里就是最后一个下标)附近时,要上一页。 */
@Composable
private fun LoadOlderEffect(listState: LazyListState, hasMore: Boolean, onLoadOlder: () -> Unit) {
    val loadOlder by rememberUpdatedState(onLoadOlder)
    LaunchedEffect(listState, hasMore) {
        if (!hasMore) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= info.totalItemsCount - LOAD_OLDER_THRESHOLD
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { loadOlder() }
    }
}

private fun contentTypeOf(item: ChatItem): String = when (item) {
    is ChatItem.Time -> "time"
    is ChatItem.Message -> "message"
}

/** 一次最多选九张:再多也不便于一屏看完,而且每张都要单独落一条消息。 */
private const val MAX_PICKED_IMAGES: Int = 9

/** 气泡最宽占屏宽的比例:再宽就顶到对侧,左右分栏看不出来了。 */
private const val BUBBLE_MAX_WIDTH_RATIO: Float = 0.72f

/** 离列表末端还剩几条时开始要上一页。 */
private const val LOAD_OLDER_THRESHOLD: Int = 2

private const val TYPING_ITEM_KEY: String = "mp-chat-typing-item"
private const val ACTIVITY_ITEM_KEY: String = "mp-chat-activity-item"
private const val BACK_DESCRIPTION: String = "返回"

private val TopBarButtonSize = 40.dp
private val TopBarIconSize = 20.dp
private val TopBarAvatarSize = 28.dp

/** 胶囊离输入栏的间距:贴着消息区下沿浮一点,不压住最后一条气泡。 */
private val PillBottomGap = 12.dp
