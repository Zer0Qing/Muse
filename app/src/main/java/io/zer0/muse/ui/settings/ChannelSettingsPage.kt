package io.zer0.muse.ui.settings

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.channel.ChannelConfig
import io.zer0.muse.channel.ChannelInbox
import io.zer0.muse.channel.ChannelManager
import io.zer0.muse.channel.ChannelPlatform
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.channel.WeClawClient
import io.zer0.muse.channel.WeClawReceiver
import io.zer0.muse.channel.TelegramReceiver
import io.zer0.muse.channel.DingtalkReceiver
import io.zer0.muse.channel.QqReceiver
import io.zer0.muse.channel.FeishuReceiver
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.MuseDropdown
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.qrcode.QrCodeGenerator
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * v1.0.92: 消息渠道配置页 — 外部 IM 桥接(飞书 / QQ 等)。
 *
 * 职责:
 *  - 渠道列表(启停开关 / 编辑 / 删除);
 *  - 添加/编辑对话框(AppID/AppSecret/目标 ID,凭据本地加密保存);
 *  - 简要说明(推送能力 + 凭据安全边界 + 去哪注册)。
 */
@Composable
fun ChannelSettingsScreen(
    onBack: () -> Unit,
    onOpenConversations: (String) -> Unit = {},
) {
    val manager: ChannelManager = koinInject()
    val weClawReceiver: WeClawReceiver = koinInject()
    val telegramReceiver: TelegramReceiver = koinInject()
    val dingtalkReceiver: DingtalkReceiver = koinInject()
    val qqReceiver: QqReceiver = koinInject()
    val feishuReceiver: FeishuReceiver = koinInject()
    // 配置变更后重启全部接收器(长轮询/长连接按最新渠道配置重建)
    val restartAllReceivers = {
        weClawReceiver.restart()
        telegramReceiver.restart()
        dingtalkReceiver.restart()
        qqReceiver.restart()
        feishuReceiver.restart()
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val channels by manager.channels.collectAsStateWithLifecycle(initialValue = emptyList())
    val inbox by ChannelInbox.messages.collectAsStateWithLifecycle(initialValue = emptyList())
    var showAdd by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<ChannelConfig?>(null) }
    var deleteTarget by remember { mutableStateOf<ChannelConfig?>(null) }

    LaunchedEffect(Unit) {
        manager.refresh()
        ChannelInbox.attach(context)
    }

    SettingsSubPageScaffold(
        title = stringResource(R.string.channel_page_title),
        onBack = onBack,
    ) {
        // ── 说明卡 ──
        item(key = "intro") {
            CardGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
                item {
                    Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
                        Text(
                            text = stringResource(R.string.channel_intro_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.channel_intro),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        // ── 渠道列表 ──
        channels.forEach { cfg ->
            item(key = cfg.id) {
                CardGroup(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    item {
                        ChannelRow(
                            config = cfg,
                            onOpenChat = { onOpenConversations(cfg.id) },
                            onToggle = { enabled ->
                                scope.launch {
                                    manager.upsert(cfg.copy(enabled = enabled))
                                    restartAllReceivers()
                                }
                            },
                            onEdit = { editTarget = cfg },
                            onDelete = { deleteTarget = cfg },
                        )
                    }
                }
            }
        }

        if (channels.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.channel_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
        }

        // ── 添加按钮（v2.0.1: 上移至「最近收到」之前） ──
        item(key = "add") {
            Surface(
                shape = MuseShapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clickable { showAdd = true },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = MuseIcons.plus,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    )
                    Text(
                        text = stringResource(R.string.channel_add),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // ── 最近入站消息(webhook 接收) ──
        if (inbox.isNotEmpty()) {
            item(key = "inbox_title") {
                Text(
                    text = stringResource(R.string.channel_inbox_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            inbox.take(5).forEach { msg ->
                item(key = "inbox_${msg.timestamp}_${msg.platform}") {
                    CardGroup(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        item {
                            // v2.0.1: 平台名本地化（历史数据存的是枚举名，如 WECLAW）。
                            val inboxPlatform = remember(msg.platform) {
                                runCatching { ChannelPlatform.valueOf(msg.platform) }.getOrNull()
                            }
                            val platformLabel = if (inboxPlatform != null) {
                                stringResource(channelPlatformNameRes(inboxPlatform))
                            } else {
                                msg.platform
                            }
                            Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
                                Text(
                                    text = "$platformLabel · ${msg.from.ifBlank { "-" }}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                                Text(
                                    text = msg.summary.ifBlank { "-" },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }

    }

    // ── 添加 / 编辑对话框 ──
    if (showAdd || editTarget != null) {
        ChannelEditDialog(
            initial = editTarget,
            onDismiss = {
                showAdd = false
                editTarget = null
            },
            onSave = { cfg ->
                scope.launch {
                    manager.upsert(cfg)
                    restartAllReceivers()
                    showAdd = false
                    editTarget = null
                }
            },
        )
    }

    // ── 删除确认 ──
    deleteTarget?.let { cfg ->
        MuseDialog(
            onDismissRequest = { deleteTarget = null },
            title = stringResource(R.string.channel_delete_confirm),
            content = {
                val label = stringResource(channelPlatformNameRes(cfg.platform))
                Text(cfg.name.ifBlank { label })
            },
            confirmText = stringResource(R.string.skill_delete),
            onConfirm = {
                scope.launch {
                    manager.remove(cfg.id)
                    restartAllReceivers()
                }
                deleteTarget = null
            },
            dismissText = stringResource(R.string.settings_common_cancel),
            onDismiss = { deleteTarget = null },
        )
    }
}

/** 单条渠道行:名称/平台目标 + 对话入口 + 启停开关 + 编辑/删除。 */
@Composable
private fun ChannelRow(
    config: ChannelConfig,
    onOpenChat: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val platformLabel = stringResource(channelPlatformNameRes(config.platform))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = config.name.ifBlank { platformLabel },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "$platformLabel · ${config.targetId.ifBlank { "-" }}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Text(
            text = stringResource(R.string.channel_open_chat),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable(onClick = onOpenChat)
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )
        Text(
            text = stringResource(R.string.channel_edit),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable(onClick = onEdit)
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )
        Text(
            text = stringResource(R.string.skill_delete),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .clickable(onClick = onDelete)
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )
        MuseSwitch(
            checked = config.enabled,
            onCheckedChange = onToggle,
        )
    }
}


/** 添加/编辑渠道对话框（v2.0.1: 按平台定制接入字段，对齐 Hana Bridge 模型）。 */
@Composable
private fun ChannelEditDialog(
    initial: ChannelConfig?,
    onDismiss: () -> Unit,
    onSave: (ChannelConfig) -> Unit,
) {
    var platform by remember { mutableStateOf(initial?.platform ?: ChannelPlatform.FEISHU) }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var appId by remember { mutableStateOf(initial?.appId.orEmpty()) }
    var secretInput by remember { mutableStateOf("") }
    var targetId by remember { mutableStateOf(initial?.targetId.orEmpty()) }
    var qqType by remember { mutableStateOf(initial?.targetType ?: "group") }
    var robotCode by remember { mutableStateOf(initial?.robotCode.orEmpty()) }
    var corpId by remember { mutableStateOf(initial?.corpId.orEmpty()) }
    var apiBaseUrl by remember { mutableStateOf(initial?.apiBaseUrl.orEmpty()) }
    var region by remember { mutableStateOf(initial?.region ?: "cn") }
    var autoReply by remember { mutableStateOf(initial?.autoReply ?: false) }
    var assistantId by remember { mutableStateOf(initial?.assistantId.orEmpty()) }
    var showBind by remember { mutableStateOf(false) }
    var showTutorial by remember { mutableStateOf(false) }
    var showSecret by remember { mutableStateOf(false) }
    var validating by remember { mutableStateOf(false) }
    var validationResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    // null = 未改动；空字符串 = 明确解除绑定；非空 = 新扫码得到的 token。
    var weclawTokenOverride by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val manager: ChannelManager = koinInject()
    val assistantRepository: AssistantRepository = koinInject()
    val assistants by assistantRepository.observeAll.collectAsStateWithLifecycle(initialValue = emptyList())
    val inbox by ChannelInbox.messages.collectAsStateWithLifecycle(initialValue = emptyList())
    val storedSecret = initial?.appSecret.orEmpty()
    val storedSecretForPlatform = if (platform == initial?.platform) storedSecret else ""
    val effectiveSecret = if (platform == ChannelPlatform.WECLAW) {
        weclawTokenOverride ?: storedSecretForPlatform
    } else {
        secretInput.trim().ifBlank { storedSecretForPlatform }
    }
    val accountOptions = remember(inbox, platform) {
        inbox.filter { it.platform == platform.name && it.from.isNotBlank() }
            .map { it.from }
            .distinct()
            .take(6)
    }
    val validationFailedText = stringResource(R.string.channel_test_failed)
    val canSave = when (platform) {
        ChannelPlatform.WECLAW -> effectiveSecret.isNotBlank()
        ChannelPlatform.TELEGRAM -> effectiveSecret.isNotBlank()
        else -> appId.isNotBlank() && effectiveSecret.isNotBlank()
    }
    val validate = {
        scope.launch {
            validating = true
            validationResult = null
            val probe = ChannelConfig(
                id = initial?.id ?: "validate",
                platform = platform,
                appId = appId.trim(),
                appSecret = effectiveSecret,
                targetId = targetId.trim(),
                targetType = qqType,
                robotCode = robotCode.trim(),
                corpId = corpId.trim(),
                apiBaseUrl = apiBaseUrl.trim(),
                region = region,
            )
            val result = manager.validate(probe)
            validationResult = result.fold(
                onSuccess = { true to it },
                onFailure = { false to (it.message ?: validationFailedText) },
            )
            validating = false
        }
        Unit
    }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(if (initial == null) R.string.channel_add else R.string.channel_edit_title),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.channel_how_to),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.End)
                        .clickable { showTutorial = true }
                        .padding(vertical = 2.dp),
                )
                val platformOptions = mutableListOf<Pair<String, String>>()
                for (p in ChannelPlatform.entries) {
                    platformOptions += p.name to stringResource(channelPlatformNameRes(p))
                }
                MuseDropdown(
                    value = platform.name,
                    onValueChange = { value ->
                        ChannelPlatform.entries.firstOrNull { it.name == value }?.let {
                            platform = it
                            validationResult = null
                        }
                    },
                    label = stringResource(R.string.channel_field_platform),
                    options = platformOptions,
                )
                MuseTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.channel_field_name)) },
                    singleLine = true,
                )

                when (platform) {
                    ChannelPlatform.WECLAW -> {
                        val bound = effectiveSecret.isNotBlank()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(
                                    if (bound) R.string.channel_weclaw_logged_in
                                    else R.string.channel_weclaw_need_scan,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (bound) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (bound) {
                                Text(
                                    text = stringResource(R.string.channel_weclaw_unbind),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier
                                        .clickable {
                                            weclawTokenOverride = ""
                                            targetId = ""
                                        }
                                        .padding(4.dp),
                                )
                            }
                        }
                        Surface(
                            shape = MuseShapes.medium,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showBind = true },
                        ) {
                            Text(
                                text = stringResource(
                                    if (bound) R.string.channel_weclaw_rescan
                                    else R.string.channel_weclaw_scan,
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            )
                        }
                        Text(
                            text = stringResource(R.string.channel_weclaw_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.channel_weclaw_exclusive),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Surface(
                            shape = MuseShapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(R.string.channel_weclaw_reply_window),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                    ChannelPlatform.FEISHU -> {
                        val regionOptions = listOf(
                            "cn" to stringResource(R.string.channel_region_feishu_cn),
                            "intl" to stringResource(R.string.channel_region_lark_global),
                        )
                        MuseDropdown(
                            value = region,
                            onValueChange = { region = it },
                            label = stringResource(R.string.channel_field_region),
                            options = regionOptions,
                        )
                        MuseTextField(
                            value = appId,
                            onValueChange = { appId = it },
                            label = { Text(stringResource(R.string.channel_field_app_id)) },
                            singleLine = true,
                        )
                        SecretInputField(
                            value = secretInput,
                            onValueChange = { secretInput = it },
                            label = stringResource(R.string.channel_field_app_secret),
                            storedSecret = storedSecretForPlatform,
                            visible = showSecret,
                            onToggleVisible = { showSecret = !showSecret },
                        )
                        PlatformCredentialFooter(
                            hint = stringResource(R.string.channel_feishu_hint),
                            validating = validating,
                            result = validationResult,
                            onValidate = validate,
                        )
                    }
                    ChannelPlatform.TELEGRAM -> {
                        SecretInputField(
                            value = secretInput,
                            onValueChange = { secretInput = it },
                            label = stringResource(R.string.channel_field_telegram_token),
                            storedSecret = storedSecretForPlatform,
                            visible = showSecret,
                            onToggleVisible = { showSecret = !showSecret },
                        )
                        PlatformCredentialFooter(
                            hint = stringResource(R.string.channel_telegram_hint),
                            validating = validating,
                            result = validationResult,
                            onValidate = validate,
                        )
                    }
                    ChannelPlatform.DINGTALK -> {
                        MuseTextField(
                            value = corpId,
                            onValueChange = { corpId = it },
                            label = { Text(stringResource(R.string.channel_field_corp_id)) },
                            singleLine = true,
                        )
                        MuseTextField(
                            value = appId,
                            onValueChange = { appId = it },
                            label = { Text(stringResource(R.string.channel_field_client_id)) },
                            singleLine = true,
                        )
                        SecretInputField(
                            value = secretInput,
                            onValueChange = { secretInput = it },
                            label = stringResource(R.string.channel_field_client_secret),
                            storedSecret = storedSecretForPlatform,
                            visible = showSecret,
                            onToggleVisible = { showSecret = !showSecret },
                        )
                        MuseTextField(
                            value = robotCode,
                            onValueChange = { robotCode = it },
                            label = { Text(stringResource(R.string.channel_field_robot_code)) },
                            supportingText = { Text(stringResource(R.string.channel_robot_code_hint)) },
                            singleLine = true,
                        )
                        MuseTextField(
                            value = apiBaseUrl,
                            onValueChange = { apiBaseUrl = it },
                            label = { Text(stringResource(R.string.channel_field_api_base)) },
                            placeholder = { Text(stringResource(R.string.channel_dingtalk_api_default)) },
                            singleLine = true,
                        )
                        PlatformCredentialFooter(
                            hint = stringResource(R.string.channel_dingtalk_hint),
                            validating = validating,
                            result = validationResult,
                            onValidate = validate,
                        )
                    }
                    ChannelPlatform.QQ -> {
                        MuseTextField(
                            value = appId,
                            onValueChange = { appId = it },
                            label = { Text(stringResource(R.string.channel_field_app_id)) },
                            singleLine = true,
                        )
                        SecretInputField(
                            value = secretInput,
                            onValueChange = { secretInput = it },
                            label = stringResource(R.string.channel_field_app_secret),
                            storedSecret = storedSecretForPlatform,
                            visible = showSecret,
                            onToggleVisible = { showSecret = !showSecret },
                        )
                        MuseDropdown(
                            value = qqType,
                            onValueChange = { qqType = it },
                            label = stringResource(R.string.channel_field_qq_type),
                            options = listOf(
                                "group" to stringResource(R.string.channel_type_group),
                                "c2c" to stringResource(R.string.channel_type_c2c),
                            ),
                        )
                        PlatformCredentialFooter(
                            hint = stringResource(R.string.channel_qq_hint),
                            validating = validating,
                            result = validationResult,
                            onValidate = validate,
                        )
                    }
                }

                if (platform != ChannelPlatform.WECLAW) {
                    Text(
                        text = stringResource(R.string.channel_owner_select),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (accountOptions.isNotEmpty()) {
                        val accountPairs = mutableListOf<Pair<String, String>>()
                        for (account in accountOptions) {
                            accountPairs += account to prettifyAccount(account)
                        }
                        MuseDropdown(
                            value = targetId,
                            onValueChange = { targetId = it },
                            label = stringResource(R.string.channel_owner_select),
                            options = accountPairs,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.channel_owner_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    Text(
                        text = stringResource(R.string.channel_owner_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    MuseTextField(
                        value = targetId,
                        onValueChange = { targetId = it },
                        label = { Text(stringResource(R.string.channel_field_target)) },
                        singleLine = true,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.channel_auto_reply),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.channel_auto_reply_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    MuseSwitch(checked = autoReply, onCheckedChange = { autoReply = it })
                }
                // v2.0.1: 自动回复使用的助手 — 继承其人设、模型与参数。
                val assistantOptions = mutableListOf<Pair<String, String>>()
                assistantOptions += "" to stringResource(R.string.channel_assistant_default)
                for (a in assistants) {
                    assistantOptions += a.id to a.name
                }
                MuseDropdown(
                    value = assistantId,
                    onValueChange = { assistantId = it },
                    label = stringResource(R.string.channel_field_assistant),
                    options = assistantOptions,
                )
                Text(
                    text = stringResource(R.string.channel_assistant_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        },
        confirmText = stringResource(R.string.channel_save),
        confirmEnabled = canSave,
        onConfirm = {
            onSave(
                ChannelConfig(
                    id = initial?.id ?: ("ch_" + System.currentTimeMillis()),
                    platform = platform,
                    name = name.trim(),
                    enabled = initial?.enabled ?: true,
                    appId = appId.trim(),
                    appSecret = effectiveSecret.trim(),
                    targetId = targetId.trim(),
                    targetType = qqType,
                    robotCode = robotCode.trim(),
                    corpId = corpId.trim(),
                    apiBaseUrl = apiBaseUrl.trim(),
                    region = region,
                    autoReply = autoReply,
                    assistantId = assistantId,
                    createdAt = initial?.createdAt ?: System.currentTimeMillis(),
                ),
            )
        },
        dismissText = stringResource(R.string.settings_common_cancel),
        onDismiss = onDismiss,
    )

    if (showBind) {
        WeClawBindDialog(
            onDismiss = { showBind = false },
            onBound = { status ->
                weclawTokenOverride = status.botToken
                appId = status.botId
                if (status.userId.isNotBlank()) targetId = status.userId
                // v2.0.1: 扫码绑定是明确意图 — 默认打开自动回复,避免"绑了却没反应"。
                autoReply = true
                showBind = false
            },
        )
    }
    if (showTutorial) {
        ChannelTutorialDialog(
            platform = platform,
            onDismiss = { showTutorial = false },
        )
    }
}

/** v2.0: ClawBot 扫码绑定对话框(拉取二维码 → 轮询状态 → 回填凭据)。 */
@Composable
private fun WeClawBindDialog(
    onDismiss: () -> Unit,
    onBound: (WeClawClient.QrStatus) -> Unit,
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf("loading") }
    var errorMsg by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val qr = WeClawClient.getQrCode().getOrElse { e ->
            status = "error"
            errorMsg = e.message.orEmpty()
            return@LaunchedEffect
        }
        bitmap = QrCodeGenerator.generateQrBitmap(qr.imgContent, 600)
        if (bitmap == null) {
            status = "error"
            return@LaunchedEffect
        }
        status = "wait"
        while (isActive) {
            delay(2_000)
            val st = WeClawClient.getQrStatus(qr.qrcode).getOrNull() ?: continue
            when (st.status) {
                "confirmed" -> {
                    onBound(st)
                    return@LaunchedEffect
                }
                "expired" -> {
                    status = "expired"
                    return@LaunchedEffect
                }
                else -> status = st.status
            }
        }
    }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.channel_weclaw_bind_title),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when {
                    status == "error" -> Text(
                        text = stringResource(R.string.channel_weclaw_bind_failed) +
                            errorMsg.take(120),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    bitmap != null -> {
                        Image(
                            bitmap = bitmap!!.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.size(220.dp),
                        )
                        Text(
                            text = when (status) {
                                "scaned" -> stringResource(R.string.channel_weclaw_status_scaned)
                                "expired" -> stringResource(R.string.channel_weclaw_status_expired)
                                else -> stringResource(R.string.channel_weclaw_bind_hint)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> Text(
                        text = stringResource(R.string.channel_weclaw_bind_loading),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        onConfirm = null,
        dismissText = stringResource(R.string.settings_common_cancel),
        onDismiss = onDismiss,
    )
}

/** 密钥输入框：已有密钥不回显，只允许输入新值覆盖。 */
@Composable
private fun SecretInputField(
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
            Text(
                text = stringResource(
                    if (visible) R.string.channel_secret_hide else R.string.channel_secret_show,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClick = onToggleVisible)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        },
        singleLine = true,
        visualTransformation = if (visible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
    )
}

/** 凭证说明 + 测试连接 + 结果。 */
@Composable
private fun PlatformCredentialFooter(
    hint: String,
    validating: Boolean,
    result: Pair<Boolean, String>?,
    onValidate: () -> Unit,
) {
    Text(
        text = hint,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.channel_test_connection),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable(enabled = !validating, onClick = onValidate)
                .padding(vertical = 4.dp),
        )
        if (validating) {
            MuseSpinner(size = MuseIconSizes.iconSmall)
        }
        if (result != null) {
            val (ok, message) = result
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 平台接入教程：沿用 Hana Bridge 的官方配置步骤。 */
@Composable
private fun ChannelTutorialDialog(
    platform: ChannelPlatform,
    onDismiss: () -> Unit,
) {
    val stepsRes = when (platform) {
        ChannelPlatform.WECLAW -> R.array.channel_tutorial_weclaw_steps
        ChannelPlatform.TELEGRAM -> R.array.channel_tutorial_telegram_steps
        ChannelPlatform.FEISHU -> R.array.channel_tutorial_feishu_steps
        ChannelPlatform.DINGTALK -> R.array.channel_tutorial_dingtalk_steps
        ChannelPlatform.QQ -> R.array.channel_tutorial_qq_steps
    }
    val steps = stringArrayResource(stepsRes)
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.channel_tutorial_title),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (index in steps.indices) {
                    Text(
                        text = "${index + 1}. ${steps[index]}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        confirmText = stringResource(R.string.channel_tutorial_close),
        onConfirm = onDismiss,
        dismissText = null,
        onDismiss = onDismiss,
    )
}

/** v2.0.1: 账号 id 展示美化 — 过长时保留首尾。 */
private fun prettifyAccount(raw: String): String =
    if (raw.length <= 18) raw else raw.take(8) + "…" + raw.takeLast(4)

/**
 * v2.0.1: 平台本地化显示名 — UI 不直接显示枚举常量名（FEISHU / WECLAW 等英式写法）。
 *
 * value 匹配仍以 [ChannelPlatform.name] 为稳定键；仅展示层随语言变化。
 */
@androidx.annotation.StringRes
private fun channelPlatformNameRes(platform: ChannelPlatform): Int = when (platform) {
    ChannelPlatform.FEISHU -> R.string.channel_platform_feishu
    ChannelPlatform.QQ -> R.string.channel_platform_qq
    ChannelPlatform.WECLAW -> R.string.channel_platform_weclaw
    ChannelPlatform.TELEGRAM -> R.string.channel_platform_telegram
    ChannelPlatform.DINGTALK -> R.string.channel_platform_dingtalk
}
