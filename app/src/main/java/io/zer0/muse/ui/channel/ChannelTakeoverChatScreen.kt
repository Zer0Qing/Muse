package io.zer0.muse.ui.channel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.channel.ChannelConfig
import io.zer0.muse.channel.ChannelContactMetaStore
import io.zer0.muse.channel.ChannelConversationStore
import io.zer0.muse.channel.ChannelInbox
import io.zer0.muse.channel.ChannelManager
import io.zer0.muse.channel.ChannelPlatform
import io.zer0.muse.channel.ChannelPolicy
import io.zer0.muse.channel.capabilities
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseDropdown
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.ui.markdown.MarkdownText
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseBorderWidth
import io.zer0.muse.ui.theme.MuseButtonSize
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.ui.theme.statusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File
import java.io.FileOutputStream
import java.util.Base64

private data class TakeoverMessageListState(
    val listState: LazyListState,
    val turns: List<ChannelConversationStore.Turn>,
    val blockedItems: List<ChannelInbox.Inbound>,
)

private data class TakeoverContactSheetState(
    val channelId: String,
    val from: String,
    val config: ChannelConfig?,
    val initialAlias: String,
    val initialAssistantOverride: String,
    val summary: String,
    val assistants: List<Pair<String, String>>,
)
import java.util.Date

private const val BUBBLE_WIDTH_FRACTION = 0.84f
private const val TIME_GAP_MS = 10 * 60 * 1000L

/**
 * 渠道会话与人工接管台：
 *  - 顶栏联系人头像 + 备注名 + 「AI 托管 ↔ 人工接管」切换胶囊 + 联系人策略抽屉；
 *  - Markdown 助手气泡、图片缩略图/原图预览、失败入站一键重试；
 *  - 底部悬浮接管输入岛，支持发文本与选图并同步入对话上下文。
 */
@Composable
fun ChannelTakeoverChatScreen(channelId: String, from: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager: ChannelManager = koinInject()
    val assistantRepository: AssistantRepository = koinInject()

    LaunchedEffect(Unit) {
        manager.refresh()
        ChannelInbox.attach(context)
        ChannelConversationStore.attach(context)
        ChannelContactMetaStore.attach(context)
    }

    val channels by manager.channels.collectAsStateWithLifecycle(initialValue = emptyList())
    val conversations by ChannelConversationStore.conversations.collectAsStateWithLifecycle()
    val inbox by ChannelInbox.messages.collectAsStateWithLifecycle()
    val metas by ChannelContactMetaStore.metas.collectAsStateWithLifecycle()
    val assistants by assistantRepository.observeAll.collectAsStateWithLifecycle(initialValue = emptyList())

    val config = channels.firstOrNull { it.id == channelId }
    val platform = config?.platform ?: ChannelPlatform.FEISHU
    val key = ChannelContactMetaStore.key(channelId, from)
    val conversation = conversations[key]
    val turns = conversation?.turns.orEmpty()
    val meta = metas[key]
    val groupLabel = stringResource(R.string.channel_contact_group_label)
    val contactName = remember(metas, channelId, from, groupLabel) {
        ChannelContactMetaStore.resolvedName(channelId, from, groupLabel)
    }
    val isGroup = meta?.isGroup == true || from.startsWith("group:")
    val aiPaused = meta?.aiPaused == true
    val blockedItems = remember(inbox, channelId, from) {
        inbox.filter {
            it.sourceChannelId == channelId &&
                it.from == from &&
                it.dispatchState == ChannelInbox.DispatchState.BLOCKED
        }
    }
    val hasRecentInbound = remember(inbox, channelId, from) {
        inbox.any { it.sourceChannelId == channelId && it.from == from }
    }

    var draftText by remember { mutableStateOf("") }
    var pendingImagePath by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var showContactSheet by remember { mutableStateOf(false) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val copied = copyPickedImageToCache(context, uri)
            if (!copied.isNullOrBlank()) {
                pendingImagePath = copied
            }
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(turns.size, blockedItems.size) {
        val total = turns.size + blockedItems.size
        if (total > 0) {
            listState.animateScrollToItem(total - 1)
        }
    }

    MusePageScaffold(
        topBar = {
            TakeoverChatTopBar(
                displayName = contactName,
                platform = platform,
                isGroup = isGroup,
                aiPaused = aiPaused,
                onBack = onBack,
                onToggleAiPaused = { ChannelContactMetaStore.setAiPaused(channelId, from, !aiPaused) },
                onOpenSettings = { showContactSheet = true },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            TakeoverMessageList(
                context = context,
                state = TakeoverMessageListState(
                    listState = listState,
                    turns = turns,
                    blockedItems = blockedItems,
                ),
                onPreviewImage = { bmp -> previewBitmap = bmp },
            )

            TakeoverInputIsland(
                aiPaused = aiPaused,
                requiresInboundHint = platform.capabilities.requiresRecentInbound && !hasRecentInbound,
                draftText = draftText,
                onDraftChange = { draftText = it },
                pendingImagePath = pendingImagePath,
                onClearPendingImage = { pendingImagePath = "" },
                onPickImage = { imagePicker.launch("image/*") },
                sending = sending,
                onSend = {
                    if (sending) return@TakeoverInputIsland
                    val textToSend = draftText
                    val imageToSend = pendingImagePath
                    if (textToSend.isBlank() && imageToSend.isBlank()) return@TakeoverInputIsland
                    scope.launch {
                        sending = true
                        val result = manager.sendManualReply(
                            channelId = channelId,
                            targetId = from,
                            text = textToSend,
                            localImagePath = imageToSend,
                        )
                        sending = false
                        if (result.ok) {
                            draftText = ""
                            pendingImagePath = ""
                        } else {
                            MuseToast.show(
                                context.getString(R.string.channel_card_test_failed, result.detail),
                            )
                        }
                    }
                },
            )
        }
    }

    if (showContactSheet) {
        TakeoverContactSheet(
            context = context,
            scope = scope,
            manager = manager,
            state = TakeoverContactSheetState(
                channelId = channelId,
                from = from,
                config = config,
                initialAlias = meta?.alias.orEmpty(),
                initialAssistantOverride = meta?.assistantIdOverride.orEmpty(),
                summary = conversation?.summary.orEmpty(),
                assistants = assistants.map { it.id to it.name },
            ),
            onDismiss = { showContactSheet = false },
        )
    }

    previewBitmap?.let { bmp ->
        MuseDialog(
            onDismissRequest = { previewBitmap = null },
            title = stringResource(R.string.channel_chat_preview_image_cd),
            content = {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = stringResource(R.string.channel_chat_preview_image_cd),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = MusePaddings.dialogContentMaxHeight),
                )
            },
            confirmText = stringResource(R.string.common_close),
            onConfirm = { previewBitmap = null },
            dismissText = null,
            onDismiss = { previewBitmap = null },
        )
    }
}

@Composable
private fun TakeoverMessageList(
    context: Context,
    state: TakeoverMessageListState,
    onPreviewImage: (Bitmap) -> Unit,
) {
    LazyColumn(
        state = state.listState,
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
        contentPadding = PaddingValues(
            horizontal = MusePaddings.pageMargin,
            vertical = MusePaddings.contentGap,
        ),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
    ) {
        if (state.turns.isEmpty() && state.blockedItems.isEmpty()) {
            item(key = "empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MusePaddings.emptyStateGap),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.channel_chat_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        itemsIndexed(
            items = state.turns,
            key = { index, _ -> "turn_$index" },
        ) { index, turn ->
            val previous = state.turns.getOrNull(index - 1)
            if (previous == null || turn.at - previous.at > TIME_GAP_MS) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = MuseDateFormats.dateTimeShort().format(Date(turn.at)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TakeoverChatBubble(
                turn = turn,
                onPreviewImage = onPreviewImage,
                onCopy = { text ->
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText(text, text))
                    MuseToast.show(context.getString(R.string.channel_chat_copy_msg))
                },
            )
        }
        state.blockedItems.forEach { blocked ->
            item(key = "blocked_${blocked.dispatchId}") {
                BlockedDispatchNotice(
                    errorCode = blocked.dispatchErrorCode,
                    onRetry = {
                        if (ChannelInbox.retryBlocked(blocked.dispatchId)) {
                            MuseToast.show(context.getString(R.string.channel_retry_enqueued))
                        }
                    },
                    onDismiss = {
                        if (ChannelInbox.dismissBlocked(blocked.dispatchId)) {
                            MuseToast.show(context.getString(R.string.channel_dismiss_done))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun TakeoverContactSheet(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    manager: ChannelManager,
    state: TakeoverContactSheetState,
    onDismiss: () -> Unit,
) {
    ContactSettingsSheet(
        channelId = state.channelId,
        from = state.from,
        config = state.config,
        initialAlias = state.initialAlias,
        initialAssistantOverride = state.initialAssistantOverride,
        summary = state.summary,
        assistants = state.assistants,
        onDismiss = onDismiss,
        onSaveAlias = { alias ->
            ChannelContactMetaStore.setAlias(state.channelId, state.from, alias)
            MuseToast.show(context.getString(R.string.channel_chat_alias_saved))
        },
        onSetAssistantOverride = { assistantId ->
            ChannelContactMetaStore.setAssistantOverride(state.channelId, state.from, assistantId)
        },
        onAllowContact = {
            val config = state.config ?: return@ContactSettingsSheet
            scope.launch {
                val nextTargets = (config.policyTargets + state.from).distinct()
                val nextMode = if (config.policyMode == ChannelPolicy.MODE_DENYLIST) {
                    ChannelPolicy.MODE_OPEN
                } else {
                    config.policyMode
                }
                val cleanedTargets = if (config.policyMode == ChannelPolicy.MODE_DENYLIST) {
                    config.policyTargets.filterNot { it == state.from }
                } else {
                    nextTargets
                }
                manager.upsert(config.copy(policyMode = nextMode, policyTargets = cleanedTargets))
                MuseToast.show(context.getString(R.string.channel_chat_policy_updated))
            }
        },
        onMuteContact = {
            val config = state.config ?: return@ContactSettingsSheet
            scope.launch {
                val updated = if (config.policyMode == ChannelPolicy.MODE_ALLOWLIST) {
                    config.copy(policyTargets = config.policyTargets.filterNot { it == state.from })
                } else {
                    config.copy(
                        policyMode = ChannelPolicy.MODE_DENYLIST,
                        policyTargets = (config.policyTargets + state.from).distinct(),
                    )
                }
                manager.upsert(updated)
                MuseToast.show(context.getString(R.string.channel_chat_policy_updated))
            }
        },
        onResetContext = {
            ChannelConversationStore.clear(state.channelId, state.from)
            onDismiss()
        },
    )
}

@Composable
private fun TakeoverChatTopBar(
    displayName: String,
    platform: ChannelPlatform,
    isGroup: Boolean,
    aiPaused: Boolean,
    onBack: () -> Unit,
    onToggleAiPaused: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = MaterialTheme.statusColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.chipInnerTight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
        ) {
            MuseTactileButton(
                icon = MuseIcons.arrowLeft,
                contentDescription = stringResource(R.string.action_back),
                onClick = onBack,
            )
            ContactAvatarWithPlatform(
                displayName = displayName,
                platform = platform,
                isGroup = isGroup,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = MuseSpacing.xs),
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(channelPlatformNameRes(platform)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Surface(
                modifier = Modifier
                    .clip(MuseShapes.pill)
                    .clickable(onClick = onToggleAiPaused),
                shape = MuseShapes.pill,
                color = if (aiPaused) colors.warningContainer else colors.successContainer,
            ) {
                Row(
                    modifier = Modifier.padding(MusePaddings.chipInnerLoose),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                ) {
                    Icon(
                        imageVector = if (aiPaused) MuseIcons.pause else MuseIcons.play,
                        contentDescription = null,
                        tint = if (aiPaused) colors.warning else colors.success,
                        modifier = Modifier.size(MuseIconSizes.iconTiny),
                    )
                    Text(
                        text = stringResource(
                            if (aiPaused) R.string.channel_chat_takeover_paused else R.string.channel_chat_takeover_auto,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = if (aiPaused) colors.warning else colors.success,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            MuseTactileButton(
                icon = MuseIcons.moreHorizontal,
                contentDescription = stringResource(R.string.channel_chat_contact_settings_cd),
                onClick = onOpenSettings,
            )
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = MuseAlpha.divider),
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TakeoverChatBubble(turn: ChannelConversationStore.Turn, onPreviewImage: (Bitmap) -> Unit, onCopy: (String) -> Unit) {
    val isUser = turn.role == "user"
    val body = remember(turn) {
        when {
            turn.text.isNotBlank() -> turn.text
            turn.mediaDescription.isNotBlank() -> turn.mediaDescription
            else -> ""
        }
    }

    val decodedBitmap by produceState<Bitmap?>(initialValue = null, turn.mediaBase64, turn.mediaPath) {
        value = withContext(Dispatchers.IO) {
            when {
                turn.mediaBase64.isNotBlank() -> runCatching {
                    val bytes = Base64.getDecoder().decode(turn.mediaBase64)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()
                turn.mediaPath.isNotBlank() && turn.mediaKind == "image" -> runCatching {
                    BitmapFactory.decodeFile(turn.mediaPath)
                }.getOrNull()
                else -> null
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.Start else Arrangement.End,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(BUBBLE_WIDTH_FRACTION),
            horizontalAlignment = if (isUser) Alignment.Start else Alignment.End,
            verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
        ) {
            if (!isUser) {
                val sourceRes = when (turn.source) {
                    "manual" -> R.string.channel_chat_source_manual
                    "command" -> R.string.channel_chat_source_command
                    else -> R.string.channel_chat_source_auto
                }
                Text(
                    text = stringResource(sourceRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                shape = MuseShapes.large,
                color = if (isUser) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
                modifier = Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = { if (body.isNotBlank()) onCopy(body) },
                ),
            ) {
                Column(
                    modifier = Modifier.padding(MusePaddings.bubbleInner),
                    verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                ) {
                    decodedBitmap?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = stringResource(R.string.channel_chat_preview_image_cd),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = MusePaddings.dialogContentMaxHeight)
                                .clip(MuseShapes.medium)
                                .clickable { onPreviewImage(bmp) },
                        )
                    }
                    if (decodedBitmap == null && turn.mediaPath.isNotBlank()) {
                        ChannelTagPill(
                            text = File(turn.mediaPath).name,
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    val textColor = if (isUser) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    }
                    if (body.isNotBlank()) {
                        if (isUser) {
                            Text(
                                text = body,
                                style = MaterialTheme.typography.bodyMedium,
                                color = textColor,
                            )
                        } else {
                            MarkdownText(
                                text = body,
                                style = MaterialTheme.typography.bodyMedium,
                                color = textColor,
                                onLongPressOutside = { onCopy(body) },
                            )
                        }
                    } else if (decodedBitmap == null && turn.mediaPath.isBlank()) {
                        Text(
                            text = stringResource(R.string.channel_conversation_empty_placeholder),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockedDispatchNotice(errorCode: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.statusColors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MuseShapes.medium,
        color = colors.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
        ) {
            Icon(
                imageVector = MuseIcons.alertCircle,
                contentDescription = null,
                tint = colors.error,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
            )
            Text(
                text = errorCode.ifBlank { stringResource(R.string.channel_badge_blocked) },
                style = MaterialTheme.typography.labelSmall,
                color = colors.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            MuseCapsuleButton(
                text = stringResource(R.string.channel_card_retry_now),
                onClick = onRetry,
                variant = IosCapsuleButtonVariant.Text,
                fillWidth = false,
                size = MuseButtonSize.Compact,
            )
            MuseCapsuleButton(
                text = stringResource(R.string.channel_card_ignore),
                onClick = onDismiss,
                variant = IosCapsuleButtonVariant.Text,
                fillWidth = false,
                size = MuseButtonSize.Compact,
            )
        }
    }
}

@Composable
private fun TakeoverInputIsland(
    aiPaused: Boolean,
    requiresInboundHint: Boolean,
    draftText: String,
    onDraftChange: (String) -> Unit,
    pendingImagePath: String,
    onClearPendingImage: () -> Unit,
    onPickImage: () -> Unit,
    sending: Boolean,
    onSend: () -> Unit,
) {
    val colors = MaterialTheme.statusColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(
                horizontal = MusePaddings.inputIslandHorizontal,
                vertical = MusePaddings.contentGap,
            ),
        verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
    ) {
        if (aiPaused) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MuseShapes.medium,
                color = colors.warningContainer,
            ) {
                Row(
                    modifier = Modifier.padding(MusePaddings.chipInnerLoose),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                ) {
                    Text(
                        text = stringResource(R.string.channel_chat_takeover_banner),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.warning,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (requiresInboundHint) {
            Text(
                text = stringResource(R.string.channel_chat_weclaw_inbound_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (pendingImagePath.isNotBlank()) {
            val previewBmp by produceState<Bitmap?>(initialValue = null, pendingImagePath) {
                value = withContext(Dispatchers.IO) {
                    runCatching { BitmapFactory.decodeFile(pendingImagePath) }.getOrNull()
                }
            }
            previewBmp?.let { bmp ->
                Box(modifier = Modifier.size(MusePaddings.previewThumb)) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = stringResource(R.string.channel_chat_preview_image_cd),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .matchParentSize()
                            .clip(MuseShapes.medium),
                    )
                    MuseTactileButton(
                        icon = MuseIcons.x,
                        contentDescription = stringResource(R.string.common_delete),
                        onClick = onClearPendingImage,
                        compact = true,
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MuseShapes.semiLarge,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = MuseAlpha.mutedSurface),
            border = BorderStroke(
                MuseBorderWidth.hairline,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = MuseAlpha.divider),
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MusePaddings.inputIslandInnerHorizontal),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
            ) {
                MuseTactileButton(
                    icon = MuseIcons.image,
                    contentDescription = stringResource(R.string.channel_chat_pick_image_cd),
                    onClick = onPickImage,
                )
                MuseTextField(
                    value = draftText,
                    onValueChange = onDraftChange,
                    placeholder = { Text(stringResource(R.string.channel_chat_input_placeholder)) },
                    containerColor = Color.Transparent,
                    maxLines = 4,
                    modifier = Modifier.weight(1f),
                )
                if (sending) {
                    Box(
                        modifier = Modifier.size(MuseIconSizes.touchTarget),
                        contentAlignment = Alignment.Center,
                    ) {
                        MuseSpinner(size = MuseIconSizes.iconSmall)
                    }
                } else {
                    MuseTactileButton(
                        icon = MuseIcons.send,
                        contentDescription = stringResource(R.string.channel_chat_send_cd),
                        enabled = draftText.isNotBlank() || pendingImagePath.isNotBlank(),
                        onClick = onSend,
                        tint = if (draftText.isNotBlank() || pendingImagePath.isNotBlank()) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ContactSettingsSheet(
    channelId: String,
    from: String,
    config: ChannelConfig?,
    initialAlias: String,
    initialAssistantOverride: String,
    summary: String,
    assistants: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onSaveAlias: (String) -> Unit,
    onSetAssistantOverride: (String) -> Unit,
    onAllowContact: () -> Unit,
    onMuteContact: () -> Unit,
    onResetContext: () -> Unit,
) {
    var aliasInput by remember(initialAlias) { mutableStateOf(initialAlias) }
    var assistantOverride by remember(initialAssistantOverride) { mutableStateOf(initialAssistantOverride) }
    var confirmReset by remember { mutableStateOf(false) }

    MuseBottomSheet(
        onDismissRequest = onDismiss,
        maxHeightFraction = 0.85f,
        headerContent = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                Text(
                    text = stringResource(R.string.channel_chat_contact_sheet_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                MuseTactileButton(
                    icon = MuseIcons.x,
                    contentDescription = stringResource(R.string.common_close),
                    onClick = onDismiss,
                    compact = true,
                )
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                MuseTextField(
                    value = aliasInput,
                    onValueChange = { aliasInput = it },
                    label = { Text(stringResource(R.string.channel_chat_alias_label)) },
                    placeholder = { Text(stringResource(R.string.channel_chat_alias_hint)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                MuseCapsuleButton(
                    text = stringResource(R.string.channel_chat_save_alias),
                    onClick = { onSaveAlias(aliasInput) },
                    variant = IosCapsuleButtonVariant.Secondary,
                    fillWidth = false,
                    size = MuseButtonSize.Compact,
                )
            }

            val assistantOptions = mutableListOf(
                "" to stringResource(R.string.channel_chat_assistant_follow_channel),
            ).apply { addAll(assistants) }
            MuseDropdown(
                value = assistantOverride,
                onValueChange = { next ->
                    assistantOverride = next
                    onSetAssistantOverride(next)
                },
                label = stringResource(R.string.channel_chat_assistant_override),
                options = assistantOptions,
            )

            if (config != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    MuseCapsuleButton(
                        text = stringResource(R.string.channel_chat_allow_contact),
                        onClick = onAllowContact,
                        variant = IosCapsuleButtonVariant.Secondary,
                        modifier = Modifier.weight(1f),
                        size = MuseButtonSize.Compact,
                    )
                    MuseCapsuleButton(
                        text = stringResource(R.string.channel_chat_mute_contact),
                        onClick = onMuteContact,
                        variant = IosCapsuleButtonVariant.Text,
                        modifier = Modifier.weight(1f),
                        size = MuseButtonSize.Compact,
                    )
                }
            }

            if (summary.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MuseShapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = MuseAlpha.mutedSurface),
                ) {
                    Column(
                        modifier = Modifier.padding(MusePaddings.cardInner),
                        verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                    ) {
                        Text(
                            text = stringResource(R.string.channel_chat_summary_section),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            MuseCapsuleButton(
                text = stringResource(R.string.channel_conversation_reset),
                onClick = { confirmReset = true },
                destructive = true,
            )

            Text(
                text = "$channelId · $from",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmReset) {
        MuseDialog(
            onDismissRequest = { confirmReset = false },
            title = stringResource(R.string.channel_conversation_reset_confirm_title),
            content = { Text(stringResource(R.string.channel_conversation_reset_confirm_text)) },
            confirmText = stringResource(R.string.channel_conversation_reset),
            onConfirm = {
                confirmReset = false
                onResetContext()
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { confirmReset = false },
            destructive = true,
        )
    }
}

private suspend fun copyPickedImageToCache(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    runCatching {
        val dir = File(context.cacheDir, "channel_manual_media").apply { mkdirs() }
        val target = File(dir, "img_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
            }
        } ?: return@withContext null
        target.absolutePath
    }.getOrNull()
}
