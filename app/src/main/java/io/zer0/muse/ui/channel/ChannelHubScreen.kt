package io.zer0.muse.ui.channel

import android.content.Context
import android.os.PowerManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.channel.ChannelConfig
import io.zer0.muse.channel.ChannelConnectionState
import io.zer0.muse.channel.ChannelContactMetaStore
import io.zer0.muse.channel.ChannelConversationStore
import io.zer0.muse.channel.ChannelInbox
import io.zer0.muse.channel.ChannelManager
import io.zer0.muse.channel.ChannelPlatform
import io.zer0.muse.channel.ChannelRuntimeRegistry
import io.zer0.muse.channel.ChannelRuntimeStatus
import io.zer0.muse.channel.DingtalkReceiver
import io.zer0.muse.channel.FeishuReceiver
import io.zer0.muse.channel.QqReceiver
import io.zer0.muse.channel.TelegramReceiver
import io.zer0.muse.channel.WeClawReceiver
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseAnchoredMenu
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseCapsuleTab
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.surface.MuseListItem
import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.ui.settings.KeepAliveGuideDialog
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseBorderWidth
import io.zer0.muse.ui.theme.MuseButtonSize
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.statusColors
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private sealed interface UnifiedInboxFilter {
    data object All : UnifiedInboxFilter
    data object Issues : UnifiedInboxFilter
    data class PlatformFilter(val platform: ChannelPlatform) : UnifiedInboxFilter
}

private data class UnifiedConversationItem(
    val channelId: String,
    val from: String,
    val platform: ChannelPlatform,
    val displayName: String,
    val preview: String,
    val updatedAt: Long,
    val isGroup: Boolean,
    val aiPaused: Boolean,
    val blockedDispatchId: String?,
    val blockedError: String,
    val lastInboundState: ChannelInbox.DispatchState?,
)

/**
 * 精装版消息渠道中心（Channels Hub）：
 *  - 顶部态势头卡（在线数 / 最近活动 / 后台常驻保护引导 / 一键接入）
 *  - 双 Tab 切换：
 *      0 · 渠道控制台（精装状态卡片 + 异常一键重试 / 修复 + 空态 5 平台快速接入舞台）
 *      1 · 统一消息（跨平台会话流，显示备注名/AI状态/接管状态，直接进入对话接管台）
 */
@Composable
fun ChannelHubScreen(onBack: () -> Unit, onOpenConversations: (String) -> Unit, onOpenChat: (String, String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager: ChannelManager = koinInject()
    val assistantRepository: AssistantRepository = koinInject()
    val weClawReceiver: WeClawReceiver = koinInject()
    val telegramReceiver: TelegramReceiver = koinInject()
    val dingtalkReceiver: DingtalkReceiver = koinInject()
    val qqReceiver: QqReceiver = koinInject()
    val feishuReceiver: FeishuReceiver = koinInject()

    val restartReceiverFor: (ChannelPlatform) -> Unit = { platform ->
        when (platform) {
            ChannelPlatform.WECLAW -> weClawReceiver.restart()
            ChannelPlatform.TELEGRAM -> telegramReceiver.restart()
            ChannelPlatform.DINGTALK -> dingtalkReceiver.restart()
            ChannelPlatform.QQ -> qqReceiver.restart()
            ChannelPlatform.FEISHU -> feishuReceiver.restart()
        }
    }

    val channels by manager.channels.collectAsStateWithLifecycle(initialValue = emptyList())
    val inbox by ChannelInbox.messages.collectAsStateWithLifecycle(initialValue = emptyList())
    val conversations by ChannelConversationStore.conversations.collectAsStateWithLifecycle()
    val metas by ChannelContactMetaStore.metas.collectAsStateWithLifecycle()
    val statuses by ChannelRuntimeRegistry.statuses.collectAsStateWithLifecycle()
    val assistants by assistantRepository.observeAll.collectAsStateWithLifecycle(initialValue = emptyList())

    LaunchedEffect(Unit) {
        manager.refresh()
        ChannelInbox.attach(context)
        ChannelConversationStore.attach(context)
        ChannelContactMetaStore.attach(context)
    }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var wizardPlatform by remember { mutableStateOf<ChannelPlatform?>(null) }
    var showWizard by remember { mutableStateOf(false) }
    var editingTarget by remember { mutableStateOf<Pair<ChannelConfig, Int>?>(null) }
    var deleteTarget by remember { mutableStateOf<ChannelConfig?>(null) }
    var showKeepAliveDialog by remember { mutableStateOf(false) }
    var inboxFilter by remember { mutableStateOf<UnifiedInboxFilter>(UnifiedInboxFilter.All) }

    val assistantNames = remember(assistants) { assistants.associate { it.id to it.name } }
    val blockedCount = remember(inbox) { inbox.count { it.dispatchState == ChannelInbox.DispatchState.BLOCKED } }
    val groupLabel = stringResource(R.string.channel_contact_group_label)

    val unifiedItems = remember(channels, conversations, inbox, metas, groupLabel) {
        buildUnifiedItems(channels, conversations, inbox, metas, groupLabel)
    }

    val filteredItems = remember(unifiedItems, inboxFilter) {
        when (inboxFilter) {
            UnifiedInboxFilter.All -> unifiedItems
            UnifiedInboxFilter.Issues -> unifiedItems.filter { it.blockedDispatchId != null }
            is UnifiedInboxFilter.PlatformFilter -> {
                val target = (inboxFilter as UnifiedInboxFilter.PlatformFilter).platform
                unifiedItems.filter { it.platform == target }
            }
        }
    }

    MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.channel_page_title),
                onBack = onBack,
                actions = {
                    MuseTactileButton(
                        icon = MuseIcons.plus,
                        contentDescription = stringResource(R.string.channel_hub_add_action),
                        onClick = {
                            wizardPlatform = null
                            showWizard = true
                        },
                    )
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = MusePaddings.pageMargin,
                end = MusePaddings.pageMargin,
                top = MusePaddings.contentGap,
                bottom = MusePaddings.listBottomClearance,
            ),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
        ) {
            item(key = "hero_card") {
                ChannelHeroStatusCard(
                    channels = channels,
                    statuses = statuses,
                    inbox = inbox,
                    onAddClick = {
                        wizardPlatform = null
                        showWizard = true
                    },
                    onKeepAliveClick = { showKeepAliveDialog = true },
                )
            }

            item(key = "hub_tabs") {
                val msgTabLabel = if (blockedCount > 0) {
                    "${stringResource(R.string.channel_hub_tab_messages)} ($blockedCount)"
                } else {
                    stringResource(R.string.channel_hub_tab_messages)
                }
                MuseCapsuleTab(
                    tabs = listOf(
                        stringResource(R.string.channel_hub_tab_channels),
                        msgTabLabel,
                    ),
                    selectedIndex = selectedTab,
                    onSelect = { selectedTab = it.coerceIn(0, 1) },
                )
            }

            if (selectedTab == 0) {
                if (channels.isEmpty()) {
                    item(key = "empty_stage") {
                        EmptyChannelStarterStage(
                            onPickPlatform = { platform ->
                                wizardPlatform = platform
                                showWizard = true
                            },
                        )
                    }
                } else {
                    items(channels, key = { it.id }) { cfg ->
                        val channelChatCount = remember(conversations, cfg.id) {
                            conversations.keys.count { it.startsWith("${cfg.id}|") }
                        }
                        val blockedForChannel = remember(inbox, cfg.id) {
                            inbox.filter {
                                it.sourceChannelId == cfg.id &&
                                    it.dispatchState == ChannelInbox.DispatchState.BLOCKED
                            }
                        }
                        val boundAssistant = cfg.assistantId.takeIf { it.isNotBlank() }?.let { assistantNames[it] }
                            ?: stringResource(R.string.channel_assistant_default)

                        ChannelHubCard(
                            config = cfg,
                            runtime = statuses[cfg.id],
                            assistantLabel = boundAssistant,
                            chatCount = channelChatCount,
                            blockedItems = blockedForChannel,
                            onToggle = { enabled ->
                                scope.launch {
                                    manager.upsert(cfg.copy(enabled = enabled))
                                    restartReceiverFor(cfg.platform)
                                }
                            },
                            onOpenChats = { onOpenConversations(cfg.id) },
                            onEdit = { tab -> editingTarget = cfg to tab },
                            onSendTest = {
                                scope.launch {
                                    val result = manager.sendText(
                                        cfg.id,
                                        context.getString(R.string.channel_wizard_welcome_reply),
                                    )
                                    MuseToast.show(
                                        if (result.ok) {
                                            context.getString(R.string.channel_card_test_sent)
                                        } else {
                                            context.getString(R.string.channel_card_test_failed, result.detail)
                                        },
                                    )
                                }
                            },
                            onReconnect = {
                                restartReceiverFor(cfg.platform)
                                MuseToast.show(
                                    context.getString(
                                        R.string.channel_card_reconnecting_toast,
                                        cfg.name.ifBlank { cfg.id },
                                    ),
                                )
                            },
                            onDelete = { deleteTarget = cfg },
                            onRetryBlocked = { dispatchId ->
                                if (ChannelInbox.retryBlocked(dispatchId)) {
                                    MuseToast.show(context.getString(R.string.channel_retry_enqueued))
                                }
                            },
                        )
                    }
                }
            } else {
                item(key = "inbox_filters") {
                    UnifiedInboxFilterRow(
                        channels = channels,
                        current = inboxFilter,
                        onSelect = { inboxFilter = it },
                    )
                }
                if (filteredItems.isEmpty()) {
                    item(key = "inbox_empty") {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MuseShapes.large,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(
                                MuseBorderWidth.hairline,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = MuseAlpha.divider),
                            ),
                        ) {
                            Column(
                                modifier = Modifier.padding(MusePaddings.largeGap),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                            ) {
                                Icon(
                                    imageVector = MuseIcons.inbox,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = MuseAlpha.disabledContent),
                                    modifier = Modifier.size(MuseIconSizes.iconLarge),
                                )
                                Text(
                                    text = stringResource(R.string.channel_inbox_unified_empty),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = stringResource(R.string.channel_inbox_unified_empty_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else {
                    items(filteredItems, key = { "${it.channelId}|${it.from}" }) { item ->
                        UnifiedInboxConversationRow(
                            item = item,
                            onClick = { onOpenChat(item.channelId, item.from) },
                            onRetryBlocked = { dispatchId ->
                                if (ChannelInbox.retryBlocked(dispatchId)) {
                                    MuseToast.show(context.getString(R.string.channel_retry_enqueued))
                                }
                            },
                            onDismissBlocked = { dispatchId ->
                                if (ChannelInbox.dismissBlocked(dispatchId)) {
                                    MuseToast.show(context.getString(R.string.channel_dismiss_done))
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (showWizard) {
        ChannelSetupWizardSheet(
            initialPlatform = wizardPlatform,
            onDismiss = { showWizard = false },
            onCompleted = { showWizard = false },
            onRestartReceiver = restartReceiverFor,
        )
    }

    editingTarget?.let { (cfg, tab) ->
        ChannelConfigSheet(
            initial = cfg,
            initialTab = tab,
            onDismiss = { editingTarget = null },
            onSave = { updated ->
                scope.launch {
                    manager.upsert(updated)
                    restartReceiverFor(updated.platform)
                    editingTarget = null
                }
            },
            onReconnectNow = restartReceiverFor,
        )
    }

    deleteTarget?.let { cfg ->
        MuseDialog(
            onDismissRequest = { deleteTarget = null },
            title = stringResource(R.string.channel_delete_confirm),
            content = {
                Text(cfg.name.ifBlank { stringResource(channelPlatformNameRes(cfg.platform)) })
            },
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                scope.launch {
                    manager.remove(cfg.id)
                    restartReceiverFor(cfg.platform)
                }
                deleteTarget = null
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { deleteTarget = null },
            destructive = true,
        )
    }

    if (showKeepAliveDialog) {
        KeepAliveGuideDialog(onDismiss = { showKeepAliveDialog = false })
    }
}

@Composable
private fun ChannelHeroStatusCard(
    channels: List<ChannelConfig>,
    statuses: Map<String, ChannelRuntimeStatus>,
    inbox: List<ChannelInbox.Inbound>,
    onAddClick: () -> Unit,
    onKeepAliveClick: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val colors = MaterialTheme.statusColors
    val onlineCount = remember(channels, statuses) { ChannelRuntimeRegistry.onlineCount(channels) }
    val issueCount = remember(channels, statuses) { ChannelRuntimeRegistry.issueCount(channels) }
    val lastInboundAt = remember(inbox) { inbox.maxOfOrNull { it.timestamp } ?: 0L }

    val ignoringBattery = remember(channels.size) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    }

    val (dotColor, titleText) = when {
        channels.isEmpty() -> colors.neutral to stringResource(R.string.channel_hub_hero_empty)
        issueCount > 0 -> colors.error to stringResource(R.string.channel_hub_hero_issue, issueCount)
        onlineCount > 0 -> colors.success to stringResource(R.string.channel_hub_hero_online, onlineCount)
        else -> colors.warning to stringResource(R.string.channel_hub_hero_idle)
    }

    val subText = when {
        channels.isEmpty() -> stringResource(R.string.channel_hub_hero_sub_empty)
        lastInboundAt > 0L -> stringResource(
            R.string.channel_hub_hero_sub_active,
            formatChannelRelativeTime(lastInboundAt, resources),
        )
        else -> stringResource(R.string.channel_hub_hero_sub_ready, channels.size)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            MuseBorderWidth.hairline,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = MuseAlpha.divider),
        ),
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.cardInner),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(MuseSpacing.sm)
                                .clip(CircleShape)
                                .background(dotColor),
                        )
                        Text(
                            text = titleText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = subText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MuseCapsuleButton(
                    text = stringResource(R.string.channel_hub_add_action),
                    leadingIcon = MuseIcons.plus,
                    onClick = onAddClick,
                    fillWidth = false,
                    size = MuseButtonSize.Compact,
                )
            }

            if (channels.any { it.enabled } && !ignoringBattery) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onKeepAliveClick),
                    shape = MuseShapes.medium,
                    color = colors.warningContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(MusePaddings.chipInnerLoose),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                    ) {
                        Icon(
                            imageVector = MuseIcons.battery,
                            contentDescription = null,
                            tint = colors.warning,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                        Text(
                            text = stringResource(R.string.channel_hub_keep_alive_banner),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.warning,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = MuseIcons.chevronRight,
                            contentDescription = null,
                            tint = colors.warning,
                            modifier = Modifier.size(MuseIconSizes.iconTiny),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyChannelStarterStage(onPickPlatform: (ChannelPlatform) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MusePaddings.contentGap),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MusePaddings.contentGap),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
        ) {
            Text(
                text = stringResource(R.string.channel_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.channel_empty_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        WizardPlatformList(selected = null, onPick = onPickPlatform)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChannelHubCard(
    config: ChannelConfig,
    runtime: ChannelRuntimeStatus?,
    assistantLabel: String,
    chatCount: Int,
    blockedItems: List<ChannelInbox.Inbound>,
    onToggle: (Boolean) -> Unit,
    onOpenChats: () -> Unit,
    onEdit: (Int) -> Unit,
    onSendTest: () -> Unit,
    onReconnect: () -> Unit,
    onDelete: () -> Unit,
    onRetryBlocked: (String) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val colors = MaterialTheme.statusColors
    val triggerLabel = when (config.groupTriggerMode) {
        ChannelConfig.GROUP_TRIGGER_MENTION -> stringResource(R.string.channel_group_trigger_mention)
        ChannelConfig.GROUP_TRIGGER_KEYWORD -> stringResource(R.string.channel_group_trigger_keyword)
        else -> stringResource(R.string.channel_group_trigger_all)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenChats),
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            MuseBorderWidth.hairline,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = MuseAlpha.divider),
        ),
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.cardInner),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                PlatformBrandBadge(platform = config.platform)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                    ) {
                        Text(
                            text = config.name.ifBlank { stringResource(channelPlatformNameRes(config.platform)) },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        LiveStatusPill(enabled = config.enabled, status = runtime)
                    }
                    Text(
                        text = "$assistantLabel · $triggerLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                MuseSwitch(checked = config.enabled, onCheckedChange = onToggle)
            }

            if (runtime != null && config.enabled &&
                (runtime.state == ChannelConnectionState.AUTH_ERROR || runtime.state == ChannelConnectionState.ERROR)
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MuseShapes.medium,
                    color = colors.errorContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(MusePaddings.chipInnerLoose),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                    ) {
                        Text(
                            text = runtime.lastError.ifBlank { stringResource(R.string.channel_status_error) },
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        MuseCapsuleButton(
                            text = stringResource(R.string.channel_card_fix_config),
                            onClick = { onEdit(2) },
                            variant = IosCapsuleButtonVariant.Text,
                            fillWidth = false,
                            size = MuseButtonSize.Compact,
                        )
                    }
                }
            }

            if (blockedItems.isNotEmpty()) {
                val latestBlocked = blockedItems.first()
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MuseShapes.medium,
                    color = colors.errorContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(MusePaddings.chipInnerLoose),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                    ) {
                        Text(
                            text = stringResource(R.string.channel_card_blocked_alert, blockedItems.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        MuseCapsuleButton(
                            text = stringResource(R.string.channel_card_retry_now),
                            onClick = { onRetryBlocked(latestBlocked.dispatchId) },
                            variant = IosCapsuleButtonVariant.Text,
                            fillWidth = false,
                            size = MuseButtonSize.Compact,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
            ) {
                ChannelTagPill(
                    text = stringResource(R.string.channel_card_chats_count, chatCount),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChannelTagPill(
                    text = stringResource(
                        if (config.autoReply) R.string.channel_badge_ai_auto else R.string.channel_badge_manual_paused,
                    ),
                    containerColor = if (config.autoReply) colors.infoContainer else colors.neutralContainer,
                    contentColor = if (config.autoReply) colors.info else colors.neutral,
                )
                Box(modifier = Modifier.weight(1f))
                MuseCapsuleButton(
                    text = stringResource(R.string.channel_card_open_chats),
                    onClick = onOpenChats,
                    variant = IosCapsuleButtonVariant.Secondary,
                    fillWidth = false,
                    size = MuseButtonSize.Compact,
                )
                Box {
                    MuseTactileButton(
                        icon = MuseIcons.moreHorizontal,
                        contentDescription = stringResource(R.string.channel_card_more_cd),
                        onClick = { menuExpanded = true },
                        compact = true,
                    )
                    MuseAnchoredMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        alignEnd = true,
                    ) {
                        MuseListItem(
                            onClick = {
                                menuExpanded = false
                                onEdit(0)
                            },
                            headlineContent = { Text(stringResource(R.string.channel_card_edit_config)) },
                        )
                        MuseListItem(
                            onClick = {
                                menuExpanded = false
                                onSendTest()
                            },
                            headlineContent = { Text(stringResource(R.string.channel_card_send_test)) },
                        )
                        MuseListItem(
                            onClick = {
                                menuExpanded = false
                                onReconnect()
                            },
                            headlineContent = { Text(stringResource(R.string.channel_card_reconnect)) },
                        )
                        MuseListItem(
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                            headlineContent = {
                                Text(
                                    text = stringResource(R.string.common_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UnifiedInboxFilterRow(channels: List<ChannelConfig>, current: UnifiedInboxFilter, onSelect: (UnifiedInboxFilter) -> Unit) {
    val platforms = remember(channels) { channels.map { it.platform }.distinct() }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
    ) {
        item(key = "all") {
            FilterChipPill(
                label = stringResource(R.string.channel_inbox_filter_all),
                selected = current == UnifiedInboxFilter.All,
                onClick = { onSelect(UnifiedInboxFilter.All) },
            )
        }
        item(key = "issues") {
            FilterChipPill(
                label = stringResource(R.string.channel_inbox_filter_issues),
                selected = current == UnifiedInboxFilter.Issues,
                onClick = { onSelect(UnifiedInboxFilter.Issues) },
            )
        }
        items(platforms, key = { it.name }) { p ->
            FilterChipPill(
                label = stringResource(channelPlatformNameRes(p)),
                selected = current == UnifiedInboxFilter.PlatformFilter(p),
                onClick = { onSelect(UnifiedInboxFilter.PlatformFilter(p)) },
            )
        }
    }
}

@Composable
private fun FilterChipPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = MuseShapes.pill,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(MusePaddings.chipInnerLoose),
        )
    }
}

@Composable
private fun UnifiedInboxConversationRow(
    item: UnifiedConversationItem,
    onClick: () -> Unit,
    onRetryBlocked: (String) -> Unit,
    onDismissBlocked: (String) -> Unit,
) {
    val resources = LocalResources.current
    val colors = MaterialTheme.statusColors

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            MuseBorderWidth.hairline,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = MuseAlpha.divider),
        ),
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.cardInner),
            verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                ContactAvatarWithPlatform(
                    displayName = item.displayName,
                    platform = item.platform,
                    isGroup = item.isGroup,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                    ) {
                        Text(
                            text = item.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = formatChannelRelativeTime(item.updatedAt, resources),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                    ) {
                        Text(
                            text = item.preview.ifBlank { stringResource(R.string.channel_conversation_attachment_placeholder) },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        val (tagRes, tagBg, tagFg) = when {
                            item.blockedDispatchId != null -> Triple(R.string.channel_badge_blocked, colors.errorContainer, colors.error)
                            item.lastInboundState == ChannelInbox.DispatchState.PENDING ||
                                item.lastInboundState == ChannelInbox.DispatchState.PROCESSING ->
                                Triple(R.string.channel_badge_pending, colors.infoContainer, colors.info)
                            item.aiPaused -> Triple(R.string.channel_badge_manual_paused, colors.warningContainer, colors.warning)
                            else -> Triple(R.string.channel_badge_ai_auto, colors.successContainer, colors.success)
                        }
                        ChannelTagPill(
                            text = stringResource(tagRes),
                            containerColor = tagBg,
                            contentColor = tagFg,
                        )
                    }
                }
            }

            if (item.blockedDispatchId != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MuseShapes.medium,
                    color = colors.errorContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(MusePaddings.chipInnerLoose),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                    ) {
                        Text(
                            text = item.blockedError.ifBlank { stringResource(R.string.channel_badge_blocked) },
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        MuseCapsuleButton(
                            text = stringResource(R.string.channel_card_retry_now),
                            onClick = { onRetryBlocked(item.blockedDispatchId) },
                            variant = IosCapsuleButtonVariant.Text,
                            fillWidth = false,
                            size = MuseButtonSize.Compact,
                        )
                        MuseCapsuleButton(
                            text = stringResource(R.string.channel_card_ignore),
                            onClick = { onDismissBlocked(item.blockedDispatchId) },
                            variant = IosCapsuleButtonVariant.Text,
                            fillWidth = false,
                            size = MuseButtonSize.Compact,
                        )
                    }
                }
            }
        }
    }
}

private fun buildUnifiedItems(
    channels: List<ChannelConfig>,
    conversations: Map<String, ChannelConversationStore.Conversation>,
    inbox: List<ChannelInbox.Inbound>,
    metas: Map<String, ChannelContactMetaStore.ContactMeta>,
    groupLabel: String,
): List<UnifiedConversationItem> {
    val channelMap = channels.associateBy { it.id }
    val keys = linkedSetOf<String>()
    keys.addAll(conversations.keys)
    inbox.forEach { msg ->
        if (msg.sourceChannelId.isNotBlank() && msg.from.isNotBlank()) {
            keys.add("${msg.sourceChannelId}|${msg.from}")
        }
    }
    return keys.mapNotNull { key ->
        val channelId = key.substringBefore("|")
        val from = key.substringAfter("|")
        val cfg = channelMap[channelId] ?: return@mapNotNull null
        val conv = conversations[key]
        val relatedInbox = inbox.filter { it.sourceChannelId == channelId && it.from == from }
        val latestInbox = relatedInbox.maxByOrNull { it.timestamp }
        val blocked = relatedInbox.firstOrNull { it.dispatchState == ChannelInbox.DispatchState.BLOCKED }
        val meta = metas[key]
        val updatedAt = maxOf(conv?.updatedAt ?: 0L, latestInbox?.timestamp ?: 0L)
        val preview = conv?.turns?.lastOrNull()?.text?.takeIf { it.isNotBlank() }
            ?: latestInbox?.summary.orEmpty()
        UnifiedConversationItem(
            channelId = channelId,
            from = from,
            platform = cfg.platform,
            displayName = ChannelContactMetaStore.resolvedName(channelId, from, groupLabel),
            preview = preview,
            updatedAt = updatedAt,
            isGroup = meta?.isGroup == true || from.startsWith("group:") || latestInbox?.isGroup == true,
            aiPaused = meta?.aiPaused == true,
            blockedDispatchId = blocked?.dispatchId,
            blockedError = blocked?.dispatchErrorCode.orEmpty(),
            lastInboundState = latestInbox?.dispatchState,
        )
    }.sortedByDescending { it.updatedAt }
}
