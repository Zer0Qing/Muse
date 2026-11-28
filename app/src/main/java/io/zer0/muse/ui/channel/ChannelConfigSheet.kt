package io.zer0.muse.ui.channel

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import io.zer0.muse.channel.ChannelRuntimeRegistry
import io.zer0.muse.channel.buildCredentials
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseCapsuleTab
import io.zer0.muse.ui.common.form.MuseDropdown
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseAvatarSize
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

/**
 * 已有渠道的三页签配置面板：
 *  - 0: 回复规则（名称、绑定助手、自动回复开关、群聊触发条件）
 *  - 1: 访问名单（公开/白名单/黑名单 + 最近联系人可视化勾选芯片）
 *  - 2: 连接凭据（精简必选凭据 + 测试连接 + 重连 + 折叠高级项 / 微信扫码重绑）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChannelConfigSheet(
    initial: ChannelConfig,
    initialTab: Int = 0,
    onDismiss: () -> Unit,
    onSave: (ChannelConfig) -> Unit,
    onReconnectNow: (ChannelPlatform) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager: ChannelManager = koinInject()
    val assistantRepository: AssistantRepository = koinInject()
    val assistants by assistantRepository.observeAll.collectAsStateWithLifecycle(initialValue = emptyList())
    val conversations by ChannelConversationStore.conversations.collectAsStateWithLifecycle()
    val inbox by ChannelInbox.messages.collectAsStateWithLifecycle()
    val metas by ChannelContactMetaStore.metas.collectAsStateWithLifecycle()
    val statuses by ChannelRuntimeRegistry.statuses.collectAsStateWithLifecycle()

    var selectedTab by rememberSaveable(initial.id, initialTab) {
        mutableIntStateOf(initialTab.coerceIn(0, 2))
    }
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var appId by remember(initial.id) { mutableStateOf(initial.appId) }
    var secretInput by remember(initial.id) { mutableStateOf("") }
    var weclawTokenOverride by remember(initial.id) { mutableStateOf<String?>(null) }
    var webhookEnabled by remember(initial.id) { mutableStateOf(initial.webhookEnabled) }
    var webhookTokenInput by remember(initial.id) { mutableStateOf("") }
    var targetId by remember(initial.id) { mutableStateOf(initial.targetId) }
    var targetType by remember(initial.id) { mutableStateOf(initial.targetType) }
    var robotCode by remember(initial.id) { mutableStateOf(initial.robotCode) }
    var corpId by remember(initial.id) { mutableStateOf(initial.corpId) }
    var apiBaseUrl by remember(initial.id) { mutableStateOf(initial.apiBaseUrl) }
    var region by remember(initial.id) { mutableStateOf(initial.region) }
    var autoReply by remember(initial.id) { mutableStateOf(initial.autoReply) }
    var assistantId by remember(initial.id) { mutableStateOf(initial.assistantId) }
    var groupTriggerMode by remember(initial.id) { mutableStateOf(initial.groupTriggerMode) }
    var keywordsRaw by remember(initial.id) { mutableStateOf(initial.triggerKeywords.joinToString(", ")) }
    var policyMode by remember(initial.id) { mutableStateOf(initial.policyMode) }
    var policyTargetsRaw by remember(initial.id) { mutableStateOf(initial.policyTargets.joinToString("\n")) }

    var showSecret by remember { mutableStateOf(false) }
    var showWebhookToken by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var showWeClawScanDialog by remember { mutableStateOf(false) }
    var confirmWeClawUnbind by remember { mutableStateOf(false) }
    var validating by remember { mutableStateOf(false) }
    var validationOutcome by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    val effectiveSecret = if (initial.platform == ChannelPlatform.WECLAW) {
        weclawTokenOverride ?: initial.appSecret
    } else {
        secretInput.trim().ifBlank { initial.appSecret }
    }
    val effectiveWebhookToken = webhookTokenInput.trim().ifBlank { initial.webhookVerificationToken }

    fun buildDraft(): ChannelConfig = initial.copy(
        name = name.trim(),
        appId = appId.trim(),
        appSecret = effectiveSecret.trim(),
        webhookEnabled = webhookEnabled && (initial.platform == ChannelPlatform.FEISHU || initial.platform == ChannelPlatform.QQ),
        webhookVerificationToken = if (initial.platform == ChannelPlatform.FEISHU || initial.platform == ChannelPlatform.QQ) {
            effectiveWebhookToken
        } else {
            ""
        },
        targetId = targetId.trim(),
        targetType = targetType,
        robotCode = robotCode.trim(),
        corpId = corpId.trim(),
        apiBaseUrl = apiBaseUrl.trim(),
        region = region,
        autoReply = autoReply,
        assistantId = assistantId,
        groupTriggerMode = groupTriggerMode,
        triggerKeywords = ChannelPolicy.normalizeTargets(keywordsRaw),
        policyMode = policyMode,
        policyTargets = ChannelPolicy.normalizeTargets(policyTargetsRaw),
    )

    val baseline = remember(initial) { initial }
    val hasChanges = buildDraft() != baseline
    val canSave = initial.platform.buildCredentials(
        appId = appId,
        secret = effectiveSecret,
        robotCode = robotCode,
        apiBaseUrl = apiBaseUrl,
        region = region,
    ).isComplete() && (!webhookEnabled || effectiveWebhookToken.isNotBlank())

    val requestDismiss: () -> Unit = {
        if (hasChanges) showDiscardDialog = true else onDismiss()
    }

    val recentContacts = remember(conversations, inbox, metas, initial.id) {
        val fromConv = conversations.keys
            .filter { it.startsWith("${initial.id}|") }
            .map { it.substringAfter("|") }
        val fromInbox = inbox
            .filter { it.sourceChannelId == initial.id && it.from.isNotBlank() }
            .map { it.from }
        (fromConv + fromInbox).distinct().take(12)
    }

    val groupLabel = stringResource(R.string.channel_contact_group_label)
    val tabs = listOf(
        stringResource(R.string.channel_config_tab_reply),
        stringResource(R.string.channel_config_tab_policy),
        stringResource(R.string.channel_config_tab_creds),
    )

    MuseBottomSheet(
        onDismissRequest = requestDismiss,
        maxHeightFraction = 0.92f,
        canDismiss = { !hasChanges },
        headerContent = {
            Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    PlatformBrandBadge(platform = initial.platform, badgeSize = MuseAvatarSize.inline)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = initial.name.ifBlank { stringResource(channelPlatformNameRes(initial.platform)) },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        LiveStatusPill(enabled = initial.enabled, status = statuses[initial.id])
                    }
                    MuseTactileButton(
                        icon = MuseIcons.x,
                        contentDescription = stringResource(R.string.common_close),
                        onClick = requestDismiss,
                        compact = true,
                    )
                }
                MuseCapsuleTab(
                    tabs = tabs,
                    selectedIndex = selectedTab,
                    onSelect = { selectedTab = it.coerceIn(0, 2) },
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = MuseAlpha.divider),
                )
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
        ) {
            when (selectedTab) {
                0 -> {
                    MuseTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.common_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val assistantOptions = mutableListOf(
                        "" to stringResource(R.string.channel_assistant_default),
                    )
                    assistants.forEach { assistantOptions += it.id to it.name }
                    MuseDropdown(
                        value = assistantId,
                        onValueChange = { assistantId = it },
                        label = stringResource(R.string.channel_field_assistant),
                        options = assistantOptions,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.channel_auto_reply),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = stringResource(R.string.channel_auto_reply_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        MuseSwitch(checked = autoReply, onCheckedChange = { autoReply = it })
                    }
                    val triggerOptions = listOf(
                        ChannelConfig.GROUP_TRIGGER_MENTION to stringResource(R.string.channel_group_trigger_mention),
                        ChannelConfig.GROUP_TRIGGER_ALL to stringResource(R.string.channel_group_trigger_all),
                        ChannelConfig.GROUP_TRIGGER_KEYWORD to stringResource(R.string.channel_group_trigger_keyword),
                    )
                    MuseDropdown(
                        value = groupTriggerMode,
                        onValueChange = { groupTriggerMode = it },
                        label = stringResource(R.string.channel_group_trigger_label),
                        options = triggerOptions,
                    )
                    Text(
                        text = stringResource(R.string.channel_group_trigger_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (groupTriggerMode == ChannelConfig.GROUP_TRIGGER_KEYWORD) {
                        MuseTextField(
                            value = keywordsRaw,
                            onValueChange = { keywordsRaw = it },
                            label = { Text(stringResource(R.string.channel_group_keywords_label)) },
                            placeholder = { Text(stringResource(R.string.channel_group_keywords_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                1 -> {
                    val policyOptions = listOf(
                        ChannelPolicy.MODE_OPEN to stringResource(R.string.channel_policy_open),
                        ChannelPolicy.MODE_ALLOWLIST to stringResource(R.string.channel_policy_allowlist),
                        ChannelPolicy.MODE_DENYLIST to stringResource(R.string.channel_policy_denylist),
                    )
                    MuseDropdown(
                        value = policyMode,
                        onValueChange = { policyMode = it },
                        label = stringResource(R.string.channel_policy_label),
                        options = policyOptions,
                    )
                    Text(
                        text = stringResource(R.string.channel_policy_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (policyMode != ChannelPolicy.MODE_OPEN) {
                        Text(
                            text = stringResource(R.string.channel_policy_pick_recent),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (recentContacts.isEmpty()) {
                            Text(
                                text = stringResource(R.string.channel_policy_no_recent),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            val selectedSet = ChannelPolicy.normalizeTargets(policyTargetsRaw).toSet()
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                                verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                            ) {
                                recentContacts.forEach { contactId ->
                                    val selected = contactId in selectedSet
                                    val displayName = ChannelContactMetaStore.resolvedName(
                                        channelId = initial.id,
                                        contactId = contactId,
                                        groupPrefix = groupLabel,
                                    )
                                    Surface(
                                        modifier = Modifier.clickable {
                                            val next = if (selected) {
                                                selectedSet - contactId
                                            } else {
                                                selectedSet + contactId
                                            }
                                            policyTargetsRaw = next.joinToString("\n")
                                        },
                                        shape = MuseShapes.pill,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                        border = BorderStroke(
                                            MuseBorderWidth.hairline,
                                            if (selected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.outlineVariant
                                            },
                                        ),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(MusePaddings.chipInnerLoose),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                                        ) {
                                            if (selected) {
                                                Icon(
                                                    imageVector = MuseIcons.check,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(MuseIconSizes.iconTiny),
                                                )
                                            }
                                            Text(
                                                text = displayName,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (selected) {
                                                    MaterialTheme.colorScheme.onPrimaryContainer
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                },
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        MuseTextField(
                            value = policyTargetsRaw,
                            onValueChange = { policyTargetsRaw = it },
                            label = { Text(stringResource(R.string.channel_policy_targets_label)) },
                            supportingText = { Text(stringResource(R.string.channel_policy_targets_hint)) },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                else -> {
                    when (initial.platform) {
                        ChannelPlatform.WECLAW -> {
                            val bound = effectiveSecret.isNotBlank()
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                            ) {
                                Text(
                                    text = stringResource(
                                        if (bound) R.string.channel_weclaw_logged_in else R.string.channel_weclaw_need_scan,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (bound) MaterialTheme.statusColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (bound) {
                                    MuseCapsuleButton(
                                        text = stringResource(R.string.channel_weclaw_unbind),
                                        onClick = { confirmWeClawUnbind = true },
                                        destructive = true,
                                        fillWidth = false,
                                        size = MuseButtonSize.Compact,
                                    )
                                }
                            }
                            MuseCapsuleButton(
                                text = stringResource(if (bound) R.string.channel_weclaw_rescan else R.string.channel_weclaw_scan),
                                onClick = { showWeClawScanDialog = true },
                                variant = IosCapsuleButtonVariant.Secondary,
                            )
                        }
                        ChannelPlatform.TELEGRAM -> {
                            ChannelSecretField(
                                value = secretInput,
                                onValueChange = { secretInput = it },
                                label = stringResource(R.string.channel_field_telegram_token),
                                storedSecret = initial.appSecret,
                                visible = showSecret,
                                onToggleVisible = { showSecret = !showSecret },
                            )
                        }
                        ChannelPlatform.FEISHU -> {
                            MuseTextField(
                                value = appId,
                                onValueChange = { appId = it },
                                label = { Text(stringResource(R.string.channel_field_app_id)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            ChannelSecretField(
                                value = secretInput,
                                onValueChange = { secretInput = it },
                                label = stringResource(R.string.channel_field_app_secret),
                                storedSecret = initial.appSecret,
                                visible = showSecret,
                                onToggleVisible = { showSecret = !showSecret },
                            )
                        }
                        ChannelPlatform.DINGTALK -> {
                            MuseTextField(
                                value = appId,
                                onValueChange = { appId = it },
                                label = { Text(stringResource(R.string.channel_field_client_id)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            ChannelSecretField(
                                value = secretInput,
                                onValueChange = { secretInput = it },
                                label = stringResource(R.string.channel_field_client_secret),
                                storedSecret = initial.appSecret,
                                visible = showSecret,
                                onToggleVisible = { showSecret = !showSecret },
                            )
                        }
                        ChannelPlatform.QQ -> {
                            MuseTextField(
                                value = appId,
                                onValueChange = { appId = it },
                                label = { Text(stringResource(R.string.channel_field_app_id)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            ChannelSecretField(
                                value = secretInput,
                                onValueChange = { secretInput = it },
                                label = stringResource(R.string.channel_field_app_secret),
                                storedSecret = initial.appSecret,
                                visible = showSecret,
                                onToggleVisible = { showSecret = !showSecret },
                            )
                        }
                    }

                    if (initial.platform != ChannelPlatform.WECLAW) {
                        MuseTextField(
                            value = targetId,
                            onValueChange = { targetId = it },
                            label = { Text(stringResource(R.string.channel_field_target)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                        ) {
                            MuseCapsuleButton(
                                text = stringResource(R.string.channel_test_connection),
                                onClick = {
                                    scope.launch {
                                        validating = true
                                        validationOutcome = null
                                        val res = manager.validateStructured(buildDraft())
                                        validationOutcome = if (res.ok) {
                                            true to res.summary.ifBlank { res.botName }
                                        } else {
                                            false to res.errorDetail
                                        }
                                        validating = false
                                    }
                                },
                                loading = validating,
                                variant = IosCapsuleButtonVariant.Secondary,
                                fillWidth = false,
                                size = MuseButtonSize.Compact,
                            )
                            MuseCapsuleButton(
                                text = stringResource(R.string.channel_card_reconnect),
                                onClick = {
                                    onReconnectNow(initial.platform)
                                    MuseToast.show(context.getString(R.string.channel_card_reconnecting_toast, name.ifBlank { initial.id }))
                                },
                                variant = IosCapsuleButtonVariant.Text,
                                fillWidth = false,
                                size = MuseButtonSize.Compact,
                            )
                        }
                        validationOutcome?.let { (ok, message) ->
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (ok) MaterialTheme.statusColors.success else MaterialTheme.statusColors.error,
                            )
                        }

                        // 折叠高级选项（Webhook / 飞书区域 / 钉钉高级参数 / QQ 目标类型）
                        if (initial.platform in setOf(ChannelPlatform.FEISHU, ChannelPlatform.QQ, ChannelPlatform.DINGTALK)) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showAdvanced = !showAdvanced },
                                shape = MuseShapes.medium,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = MuseAlpha.mutedSurface),
                            ) {
                                Row(
                                    modifier = Modifier.padding(MusePaddings.cardInner),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                                ) {
                                    Text(
                                        text = stringResource(R.string.channel_wizard_advanced_toggle),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Icon(
                                        imageVector = if (showAdvanced) MuseIcons.chevronUp else MuseIcons.chevronDown,
                                        contentDescription = null,
                                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                                    )
                                }
                            }
                            if (showAdvanced) {
                                ChannelPlatformAdvancedFields(
                                    platform = initial.platform,
                                    region = region,
                                    onRegionChange = { region = it },
                                    corpId = corpId,
                                    onCorpIdChange = { corpId = it },
                                    robotCode = robotCode,
                                    onRobotCodeChange = { robotCode = it },
                                    apiBaseUrl = apiBaseUrl,
                                    onApiBaseUrlChange = { apiBaseUrl = it },
                                    targetType = targetType,
                                    onTargetTypeChange = { targetType = it },
                                    webhookEnabled = webhookEnabled,
                                    onWebhookEnabledChange = { webhookEnabled = it },
                                    webhookTokenInput = webhookTokenInput,
                                    onWebhookTokenChange = { webhookTokenInput = it },
                                    storedWebhookToken = initial.webhookVerificationToken,
                                    showWebhookToken = showWebhookToken,
                                    onToggleWebhookTokenVisible = { showWebhookToken = !showWebhookToken },
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap, Alignment.End),
            ) {
                MuseCapsuleButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = requestDismiss,
                    variant = IosCapsuleButtonVariant.Text,
                    fillWidth = false,
                )
                MuseCapsuleButton(
                    text = stringResource(R.string.common_save),
                    onClick = { onSave(buildDraft()) },
                    enabled = canSave,
                    variant = IosCapsuleButtonVariant.Primary,
                    fillWidth = false,
                )
            }
        }
    }

    if (showDiscardDialog) {
        MuseDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = stringResource(R.string.settings_unsaved_changes_title),
            content = { Text(stringResource(R.string.settings_unsaved_changes_content)) },
            confirmText = stringResource(R.string.settings_unsaved_discard),
            onConfirm = {
                showDiscardDialog = false
                onDismiss()
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { showDiscardDialog = false },
            destructive = true,
        )
    }

    if (confirmWeClawUnbind) {
        MuseDialog(
            onDismissRequest = { confirmWeClawUnbind = false },
            title = stringResource(R.string.channel_weclaw_unbind),
            content = { Text(stringResource(R.string.channel_weclaw_unbind_confirm)) },
            confirmText = stringResource(R.string.channel_weclaw_unbind),
            onConfirm = {
                confirmWeClawUnbind = false
                weclawTokenOverride = ""
                targetId = ""
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { confirmWeClawUnbind = false },
            destructive = true,
        )
    }

    if (showWeClawScanDialog) {
        MuseDialog(
            onDismissRequest = { showWeClawScanDialog = false },
            title = stringResource(R.string.channel_weclaw_bind_title),
            content = {
                WeClawQrBindContent(
                    onBound = { st ->
                        weclawTokenOverride = st.botToken
                        appId = st.botId
                        if (st.userId.isNotBlank()) targetId = st.userId
                        autoReply = true
                        showWeClawScanDialog = false
                        MuseToast.show(context.getString(R.string.channel_weclaw_logged_in))
                    },
                )
            },
            onConfirm = null,
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { showWeClawScanDialog = false },
        )
    }
}

@Composable
internal fun ChannelSecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    storedSecret: String,
    visible: Boolean,
    onToggleVisible: () -> Unit,
) {
    MuseTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = if (storedSecret.isNotBlank() && value.isBlank()) {
            { Text(stringResource(R.string.channel_secret_stored_placeholder)) }
        } else {
            null
        },
        trailingIcon = {
            MuseTactileButton(
                icon = if (visible) MuseIcons.eyeOff else MuseIcons.eye,
                contentDescription = stringResource(
                    if (visible) R.string.channel_secret_hide else R.string.channel_secret_show,
                ),
                onClick = onToggleVisible,
                compact = true,
            )
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun ChannelPlatformAdvancedFields(
    platform: ChannelPlatform,
    region: String,
    onRegionChange: (String) -> Unit,
    corpId: String,
    onCorpIdChange: (String) -> Unit,
    robotCode: String,
    onRobotCodeChange: (String) -> Unit,
    apiBaseUrl: String,
    onApiBaseUrlChange: (String) -> Unit,
    targetType: String,
    onTargetTypeChange: (String) -> Unit,
    webhookEnabled: Boolean,
    onWebhookEnabledChange: (Boolean) -> Unit,
    webhookTokenInput: String,
    onWebhookTokenChange: (String) -> Unit,
    storedWebhookToken: String,
    showWebhookToken: Boolean,
    onToggleWebhookTokenVisible: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        if (platform == ChannelPlatform.FEISHU) {
            MuseDropdown(
                value = region,
                onValueChange = onRegionChange,
                label = stringResource(R.string.channel_field_region),
                options = listOf(
                    "cn" to stringResource(R.string.channel_region_feishu_cn),
                    "intl" to stringResource(R.string.channel_region_lark_global),
                ),
            )
        }
        if (platform == ChannelPlatform.DINGTALK) {
            MuseTextField(
                value = corpId,
                onValueChange = onCorpIdChange,
                label = { Text(stringResource(R.string.channel_field_corp_id)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            MuseTextField(
                value = robotCode,
                onValueChange = onRobotCodeChange,
                label = { Text(stringResource(R.string.channel_field_robot_code)) },
                supportingText = { Text(stringResource(R.string.channel_robot_code_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            MuseTextField(
                value = apiBaseUrl,
                onValueChange = onApiBaseUrlChange,
                label = { Text(stringResource(R.string.channel_field_api_base)) },
                placeholder = { Text(stringResource(R.string.channel_dingtalk_api_default)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (platform == ChannelPlatform.QQ) {
            MuseDropdown(
                value = targetType,
                onValueChange = onTargetTypeChange,
                label = stringResource(R.string.channel_field_qq_type),
                options = listOf(
                    "group" to stringResource(R.string.channel_type_group),
                    "c2c" to stringResource(R.string.channel_type_c2c),
                ),
            )
        }
        if (platform == ChannelPlatform.FEISHU || platform == ChannelPlatform.QQ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.channel_webhook_receive),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.channel_webhook_receive_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                MuseSwitch(checked = webhookEnabled, onCheckedChange = onWebhookEnabledChange)
            }
            if (webhookEnabled) {
                ChannelSecretField(
                    value = webhookTokenInput,
                    onValueChange = onWebhookTokenChange,
                    label = stringResource(R.string.channel_webhook_verification_token),
                    storedSecret = storedWebhookToken,
                    visible = showWebhookToken,
                    onToggleVisible = onToggleWebhookTokenVisible,
                )
                Text(
                    text = stringResource(
                        if (platform == ChannelPlatform.FEISHU) {
                            R.string.channel_webhook_feishu_hint
                        } else {
                            R.string.channel_webhook_qq_hint
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
