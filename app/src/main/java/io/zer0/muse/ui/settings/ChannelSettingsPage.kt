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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.channel.ChannelConfig
import io.zer0.muse.channel.ChannelInbox
import io.zer0.muse.channel.ChannelManager
import io.zer0.muse.channel.ChannelPlatform
import io.zer0.muse.channel.WeClawClient
import io.zer0.muse.channel.WeClawReceiver
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.MuseDropdown
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.form.MuseTextField
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
) {
    val manager: ChannelManager = koinInject()
    val weClawReceiver: WeClawReceiver = koinInject()
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
                            onToggle = { enabled ->
                                scope.launch {
                                    manager.upsert(cfg.copy(enabled = enabled))
                                    weClawReceiver.restart()
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
            inbox.take(10).forEach { msg ->
                item(key = "inbox_${msg.timestamp}_${msg.platform}") {
                    CardGroup(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        item {
                            Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
                                Text(
                                    text = "${msg.platform} · ${msg.from.ifBlank { "-" }}",
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

        // ── 添加按钮 ──
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
                        imageVector = Icons.Outlined.Add,
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
                    weClawReceiver.restart()
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
            content = { Text(cfg.name.ifBlank { cfg.platform.name }) },
            confirmText = stringResource(R.string.skill_delete),
            onConfirm = {
                scope.launch {
                    manager.remove(cfg.id)
                    weClawReceiver.restart()
                }
                deleteTarget = null
            },
            dismissText = stringResource(R.string.settings_common_cancel),
            onDismiss = { deleteTarget = null },
        )
    }
}

/** 单条渠道行:名称/平台目标 + 启停开关 + 编辑/删除。 */
@Composable
private fun ChannelRow(
    config: ChannelConfig,
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
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = config.name.ifBlank { config.platform.name },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "${config.platform.name} · ${config.targetId.ifBlank { "-" }}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
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

/** 添加/编辑渠道对话框。 */
@Composable
private fun ChannelEditDialog(
    initial: ChannelConfig?,
    onDismiss: () -> Unit,
    onSave: (ChannelConfig) -> Unit,
) {
    var platform by remember { mutableStateOf(initial?.platform ?: ChannelPlatform.FEISHU) }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var appId by remember { mutableStateOf(initial?.appId.orEmpty()) }
    var appSecret by remember { mutableStateOf(initial?.appSecret.orEmpty()) }
    var targetId by remember { mutableStateOf(initial?.targetId.orEmpty()) }
    var qqType by remember { mutableStateOf(initial?.targetType ?: "group") }
    var autoReply by remember { mutableStateOf(initial?.autoReply ?: false) }
    var showBind by remember { mutableStateOf(false) }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(if (initial == null) R.string.channel_add else R.string.channel_edit_title),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MuseDropdown(
                    value = platform.name,
                    onValueChange = { v ->
                        ChannelPlatform.entries.firstOrNull { it.name == v }?.let { platform = it }
                    },
                    label = stringResource(R.string.channel_field_platform),
                    options = ChannelPlatform.entries.map { it.name to it.name },
                )
                MuseTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.channel_field_name)) },
                )
                MuseTextField(
                    value = appId,
                    onValueChange = { appId = it },
                    label = { Text(stringResource(R.string.channel_field_app_id)) },
                )
                MuseTextField(
                    value = appSecret,
                    onValueChange = { appSecret = it },
                    label = { Text(stringResource(R.string.channel_field_app_secret)) },
                    visualTransformation = PasswordVisualTransformation(),
                )
                MuseTextField(
                    value = targetId,
                    onValueChange = { targetId = it },
                    label = { Text(stringResource(R.string.channel_field_target)) },
                )
                if (platform == ChannelPlatform.QQ) {
                    MuseDropdown(
                        value = qqType,
                        onValueChange = { qqType = it },
                        label = stringResource(R.string.channel_field_qq_type),
                        options = listOf(
                            "group" to stringResource(R.string.channel_type_group),
                            "c2c" to stringResource(R.string.channel_type_c2c),
                        ),
                    )
                }
                if (platform == ChannelPlatform.WECLAW) {
                    // v2.0: ClawBot 扫码绑定
                    if (appSecret.isBlank()) {
                        Text(
                            text = stringResource(R.string.channel_weclaw_unbound_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Surface(
                        shape = MuseShapes.medium,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showBind = true },
                    ) {
                        Text(
                            text = stringResource(R.string.channel_weclaw_bind),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                }
                if (platform == ChannelPlatform.TELEGRAM) {
                    Text(
                        text = stringResource(R.string.channel_telegram_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (platform == ChannelPlatform.DINGTALK) {
                    Text(
                        text = stringResource(R.string.channel_dingtalk_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.channel_auto_reply),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    MuseSwitch(checked = autoReply, onCheckedChange = { autoReply = it })
                }
            }
        },
        confirmText = stringResource(R.string.channel_save),
        onConfirm = {
            onSave(
                ChannelConfig(
                    id = initial?.id ?: ("ch_" + System.currentTimeMillis()),
                    platform = platform,
                    name = name.trim(),
                    enabled = initial?.enabled ?: true,
                    appId = appId.trim(),
                    appSecret = appSecret.trim(),
                    targetId = targetId.trim(),
                    targetType = qqType,
                    autoReply = autoReply,
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
                appId = status.botId
                appSecret = status.botToken
                if (status.userId.isNotBlank()) targetId = status.userId
                if (name.isBlank()) name = "ClawBot"
                showBind = false
            },
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
