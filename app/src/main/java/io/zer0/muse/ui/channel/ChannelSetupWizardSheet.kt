package io.zer0.muse.ui.channel

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.channel.ChannelConfig
import io.zer0.muse.channel.ChannelConnectionState
import io.zer0.muse.channel.ChannelContactMetaStore
import io.zer0.muse.channel.ChannelInbox
import io.zer0.muse.channel.ChannelManager
import io.zer0.muse.channel.ChannelPlatform
import io.zer0.muse.channel.ChannelPolicy
import io.zer0.muse.channel.ChannelRuntimeRegistry
import io.zer0.muse.channel.ChannelRuntimeStatus
import io.zer0.muse.channel.WeClawClient
import io.zer0.muse.channel.buildCredentials
import io.zer0.muse.channel.capabilities
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.state.MuseProgressBar
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.qrcode.QrCodeGenerator
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseAvatarSize
import io.zer0.muse.ui.theme.MuseBorderWidth
import io.zer0.muse.ui.theme.MuseButtonSize
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.ui.theme.statusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File
import java.io.FileOutputStream

private const val MAX_QR_REFRESH_COUNT = 3

private data class WizardDiagnosticsState(
    val runtime: ChannelRuntimeStatus?,
    val connected: Boolean,
    val authChecking: Boolean,
    val authOk: Boolean?,
    val authSummary: String,
    val claimedConfirmed: Boolean,
    val claiming: Boolean,
    val firstClaimInbound: ChannelInbox.Inbound?,
)

private data class WizardCredentialsState(
    val platform: ChannelPlatform,
    val name: String,
    val appId: String,
    val secret: String,
    val showSecret: Boolean,
    val showGuide: Boolean,
    val showAdvanced: Boolean,
    val canProceed: Boolean,
)

private data class WizardCredentialsActions(
    val onNameChange: (String) -> Unit,
    val onAppIdChange: (String) -> Unit,
    val onSecretChange: (String) -> Unit,
    val onToggleSecret: () -> Unit,
    val onToggleGuide: () -> Unit,
    val onToggleAdvanced: () -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
)

private data class WizardCredentialValues(
    val appId: String,
    val secret: String,
    val showSecret: Boolean,
)

private data class WizardStepState(
    val currentStep: Int,
    val platform: ChannelPlatform,
    val credentials: WizardCredentialsState,
    val diagnostics: WizardDiagnosticsState,
    val canClaim: Boolean,
)

private data class WizardStepActions(
    val onPickPlatform: (ChannelPlatform) -> Unit,
    val credentials: WizardCredentialsActions,
    val onBound: (WeClawClient.QrStatus) -> Unit,
    val advancedContent: @Composable () -> Unit,
    val onBack: () -> Unit,
    val onClaimConfirm: () -> Unit,
    val onFinish: () -> Unit,
)

/**
 * 新建渠道的三步引导向导：
 *  - Step 1: 选平台卡片矩阵（直接点平台可直达 Step 2）
 *  - Step 2: 极简核心凭据 + 可展开图文指引（带平台直达链接与复制芯片）+ 微信保存/分享二维码自动保存
 *  - Step 3: 实时诊断（凭据校验 → 长连接建立）+ 发消息互动认领账号（告别“鸡生蛋”）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChannelSetupWizardSheet(
    initialPlatform: ChannelPlatform? = null,
    onDismiss: () -> Unit,
    onCompleted: (ChannelPlatform) -> Unit,
    onRestartReceiver: (ChannelPlatform) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager: ChannelManager = koinInject()
    val inbox by ChannelInbox.messages.collectAsStateWithLifecycle()
    val statuses by ChannelRuntimeRegistry.statuses.collectAsStateWithLifecycle()

    var step by remember { mutableIntStateOf(if (initialPlatform == null) 1 else 2) }
    var platform by remember { mutableStateOf(initialPlatform ?: ChannelPlatform.WECLAW) }
    val draftId = remember { "ch_${System.currentTimeMillis()}" }
    val wizardStartedAt = remember { System.currentTimeMillis() }

    var name by remember { mutableStateOf("") }
    var appId by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var webhookEnabled by remember { mutableStateOf(false) }
    var webhookToken by remember { mutableStateOf("") }
    var targetType by remember { mutableStateOf("group") }
    var robotCode by remember { mutableStateOf("") }
    var corpId by remember { mutableStateOf("") }
    var apiBaseUrl by remember { mutableStateOf("") }
    var region by remember { mutableStateOf("cn") }

    var showSecret by remember { mutableStateOf(false) }
    var showWebhookToken by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }

    // Step 3 诊断与认领状态
    var savedChannel by remember { mutableStateOf<ChannelConfig?>(null) }
    var authChecking by remember { mutableStateOf(false) }
    var authOk by remember { mutableStateOf<Boolean?>(null) }
    var authSummary by remember { mutableStateOf("") }
    var claimedConfirmed by remember { mutableStateOf(false) }
    var claiming by remember { mutableStateOf(false) }
    var step3StartedAt by remember { mutableLongStateOf(0L) }

    val canProceedStep2 = platform.buildCredentials(
        appId = appId,
        secret = secret,
        robotCode = robotCode,
        apiBaseUrl = apiBaseUrl,
        region = region,
    ).isComplete() && (!webhookEnabled || webhookToken.isNotBlank())

    suspend fun persistAndEnterStep3(targetPlatform: ChannelPlatform, overrideTargetId: String = "") {
        val cfg = ChannelConfig(
            id = draftId,
            platform = targetPlatform,
            name = name.trim().ifBlank { context.getString(channelPlatformNameRes(targetPlatform)) },
            enabled = true,
            appId = appId.trim(),
            appSecret = secret.trim(),
            webhookEnabled = webhookEnabled && (targetPlatform == ChannelPlatform.FEISHU || targetPlatform == ChannelPlatform.QQ),
            webhookVerificationToken = webhookToken.trim(),
            targetId = overrideTargetId.trim(),
            targetType = targetType,
            robotCode = robotCode.trim(),
            corpId = corpId.trim(),
            apiBaseUrl = apiBaseUrl.trim(),
            region = region,
            autoReply = true,
            groupTriggerMode = ChannelConfig.GROUP_TRIGGER_MENTION,
        )
        manager.upsert(cfg)
        onRestartReceiver(targetPlatform)
        savedChannel = cfg
        step3StartedAt = System.currentTimeMillis()
        step = 3
        authChecking = true
        val res = manager.validateStructured(cfg)
        authOk = res.ok
        authSummary = if (res.ok) res.summary.ifBlank { res.botName } else res.errorDetail
        if (res.ok && res.botName.isNotBlank()) {
            val withBotName = cfg.copy(botDisplayName = res.botName)
            manager.upsert(withBotName)
            savedChannel = withBotName
        }
        authChecking = false
    }

    val firstClaimInbound = remember(inbox, savedChannel, step3StartedAt) {
        val cfg = savedChannel ?: return@remember null
        inbox.firstOrNull {
            it.sourceChannelId == cfg.id &&
                it.timestamp >= wizardStartedAt &&
                it.from.isNotBlank()
        }
    }
    val welcomeText = stringResource(R.string.channel_wizard_welcome_reply)

    MuseBottomSheet(
        onDismissRequest = onDismiss,
        maxHeightFraction = 0.94f,
        headerContent = { ChannelWizardHeader(step = step, onDismiss = onDismiss) },
    ) {
        WizardStepContent(
            state = WizardStepState(
                currentStep = step,
                platform = platform,
                credentials = WizardCredentialsState(
                    platform = platform,
                    name = name,
                    appId = appId,
                    secret = secret,
                    showSecret = showSecret,
                    showGuide = showGuide,
                    showAdvanced = showAdvanced,
                    canProceed = canProceedStep2,
                ),
                diagnostics = WizardDiagnosticsState(
                    runtime = savedChannel?.id?.let { statuses[it] },
                    connected = savedChannel?.id?.let { statuses[it] }?.state == ChannelConnectionState.CONNECTED ||
                        platform == ChannelPlatform.WECLAW && authOk == true,
                    authChecking = authChecking,
                    authOk = authOk,
                    authSummary = authSummary,
                    claimedConfirmed = claimedConfirmed,
                    claiming = claiming,
                    firstClaimInbound = firstClaimInbound,
                ),
                canClaim = savedChannel != null,
            ),
            actions = WizardStepActions(
                onPickPlatform = { chosen ->
                    platform = chosen
                    webhookEnabled = false
                    webhookToken = ""
                    step = 2
                },
                credentials = WizardCredentialsActions(
                    onNameChange = { name = it },
                    onAppIdChange = { appId = it },
                    onSecretChange = { secret = it },
                    onToggleSecret = { showSecret = !showSecret },
                    onToggleGuide = { showGuide = !showGuide },
                    onToggleAdvanced = { showAdvanced = !showAdvanced },
                    onPrevious = { step = 1 },
                    onNext = { scope.launch { persistAndEnterStep3(platform) } },
                ),
                onBound = { status ->
                    secret = status.botToken
                    appId = status.botId
                    scope.launch {
                        persistAndEnterStep3(
                            targetPlatform = ChannelPlatform.WECLAW,
                            overrideTargetId = status.userId,
                        )
                    }
                },
                advancedContent = {
                    ChannelPlatformAdvancedFields(
                        platform = platform,
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
                        webhookTokenInput = webhookToken,
                        onWebhookTokenChange = { webhookToken = it },
                        storedWebhookToken = "",
                        showWebhookToken = showWebhookToken,
                        onToggleWebhookTokenVisible = { showWebhookToken = !showWebhookToken },
                    )
                },
                onBack = { step = 2 },
                onClaimConfirm = {
                    val cfg = savedChannel
                    val inbound = firstClaimInbound
                    if (cfg != null && inbound != null) {
                        scope.launch {
                            claiming = true
                            val updated = cfg.copy(
                                targetId = inbound.from,
                                policyMode = ChannelPolicy.MODE_ALLOWLIST,
                                policyTargets = listOf(inbound.from),
                            )
                            manager.upsert(updated)
                            savedChannel = updated
                            manager.sendManualReply(
                                channelId = updated.id,
                                targetId = inbound.from,
                                text = welcomeText,
                            )
                            claimedConfirmed = true
                            claiming = false
                        }
                    }
                },
                onFinish = { onCompleted(platform) },
            ),
        )
    }
}

@Composable
private fun WizardStepContent(
    state: WizardStepState,
    actions: WizardStepActions,
) {
    AnimatedContent(targetState = state.currentStep, label = "channel_wizard_step") { currentStep ->
        when (currentStep) {
            1 -> Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                Text(
                    text = stringResource(R.string.channel_wizard_step1_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                WizardPlatformList(
                    selected = state.platform,
                    onPick = actions.onPickPlatform,
                )
            }
            2 -> WizardCredentialsStep(
                state = state.credentials,
                actions = actions.credentials,
                onBound = actions.onBound,
                advancedContent = actions.advancedContent,
            )
            else -> WizardDiagnosticsStep(
                platform = state.platform,
                state = state.diagnostics,
                canClaim = state.canClaim,
                onBack = actions.onBack,
                onClaimConfirm = actions.onClaimConfirm,
                onFinish = actions.onFinish,
            )
        }
    }
}

@Composable
private fun WizardCredentialsStep(
    state: WizardCredentialsState,
    actions: WizardCredentialsActions,
    onBound: (WeClawClient.QrStatus) -> Unit,
    advancedContent: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            PlatformBrandBadge(platform = state.platform, badgeSize = MuseAvatarSize.inline)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(channelPlatformNameRes(state.platform)),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.channel_wizard_step2_title),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        WizardGuideAccordion(
            platform = state.platform,
            expanded = state.showGuide,
            onToggle = actions.onToggleGuide,
        )
        MuseTextField(
            value = state.name,
            onValueChange = actions.onNameChange,
            label = { Text(stringResource(R.string.common_name)) },
            placeholder = { Text(stringResource(channelPlatformNameRes(state.platform))) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        WizardPlatformCredentials(
            platform = state.platform,
            values = WizardCredentialValues(
                appId = state.appId,
                secret = state.secret,
                showSecret = state.showSecret,
            ),
            onAppIdChange = actions.onAppIdChange,
            onSecretChange = actions.onSecretChange,
            onBound = onBound,
            onToggleSecret = actions.onToggleSecret,
        )
        if (state.platform in setOf(ChannelPlatform.FEISHU, ChannelPlatform.QQ, ChannelPlatform.DINGTALK)) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = actions.onToggleAdvanced),
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = if (state.showAdvanced) MuseIcons.chevronUp else MuseIcons.chevronDown,
                        contentDescription = null,
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    )
                }
            }
            if (state.showAdvanced) {
                advancedContent()
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MuseCapsuleButton(
                text = stringResource(R.string.channel_wizard_prev),
                onClick = actions.onPrevious,
                variant = IosCapsuleButtonVariant.Text,
                fillWidth = false,
            )
            if (state.platform != ChannelPlatform.WECLAW) {
                MuseCapsuleButton(
                    text = stringResource(R.string.channel_wizard_next),
                    onClick = actions.onNext,
                    enabled = state.canProceed,
                    variant = IosCapsuleButtonVariant.Primary,
                    fillWidth = false,
                )
            }
        }
    }
}

@Composable
private fun ChannelWizardHeader(step: Int, onDismiss: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.channel_wizard_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.channel_wizard_step_label, step),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MuseTactileButton(
                icon = MuseIcons.x,
                contentDescription = stringResource(R.string.common_close),
                onClick = onDismiss,
                compact = true,
            )
        }
        MuseProgressBar(progress = step / 3f)
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = MuseAlpha.divider),
        )
    }
}

@Composable
private fun WizardDiagnosticsStep(
    platform: ChannelPlatform,
    state: WizardDiagnosticsState,
    canClaim: Boolean,
    onBack: () -> Unit,
    onClaimConfirm: () -> Unit,
    onFinish: () -> Unit,
) {
    val runtime = state.runtime
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
    ) {
        Text(
            text = stringResource(R.string.channel_wizard_step3_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        WizardDiagnosticsChecks(state = state, runtime = runtime)
        val inbound = state.firstClaimInbound
        if (inbound != null && !state.claimedConfirmed && canClaim) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MuseShapes.large,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = MuseAlpha.statusTint),
                border = BorderStroke(
                    MuseBorderWidth.hairline,
                    MaterialTheme.colorScheme.primary.copy(alpha = MuseAlpha.divider),
                ),
            ) {
                Column(
                    modifier = Modifier.padding(MusePaddings.cardInner),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    val senderName = inbound.senderDisplayName.ifBlank { inbound.from }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                    ) {
                        ContactAvatarWithPlatform(
                            displayName = senderName,
                            platform = platform,
                            isGroup = inbound.isGroup,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = senderName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = inbound.summary.ifBlank { inbound.from },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    MuseCapsuleButton(
                        text = stringResource(R.string.channel_wizard_claim_confirm),
                        loading = state.claiming,
                        onClick = onClaimConfirm,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.authOk == false) {
                MuseCapsuleButton(
                    text = stringResource(R.string.channel_wizard_prev),
                    onClick = onBack,
                    variant = IosCapsuleButtonVariant.Text,
                    fillWidth = false,
                )
            } else {
                Box {}
            }
            MuseCapsuleButton(
                text = stringResource(
                    if (state.claimedConfirmed) R.string.channel_wizard_finish else R.string.channel_wizard_skip_claim,
                ),
                onClick = onFinish,
                variant = if (state.claimedConfirmed) IosCapsuleButtonVariant.Primary else IosCapsuleButtonVariant.Secondary,
                fillWidth = false,
            )
        }
    }
}

@Composable
private fun WizardDiagnosticsChecks(
    state: WizardDiagnosticsState,
    runtime: ChannelRuntimeStatus?,
) {
    WizardCheckRow(
        title = stringResource(R.string.channel_wizard_check_auth),
        detail = when {
            state.authChecking -> stringResource(R.string.channel_wizard_check_auth_running)
            state.authOk == true -> state.authSummary
            state.authOk == false -> state.authSummary
            else -> stringResource(R.string.channel_wizard_check_auth_running)
        },
        state = when {
            state.authChecking -> WizardCheckState.RUNNING
            state.authOk == true -> WizardCheckState.DONE
            state.authOk == false -> WizardCheckState.ERROR
            else -> WizardCheckState.RUNNING
        },
    )
    WizardCheckRow(
        title = stringResource(R.string.channel_wizard_check_connect),
        detail = when {
            runtime?.state == ChannelConnectionState.AUTH_ERROR ||
                runtime?.state == ChannelConnectionState.ERROR ->
                runtime.lastError.ifBlank { stringResource(R.string.channel_status_error) }
            state.connected -> stringResource(R.string.channel_wizard_check_connect_ok)
            else -> stringResource(R.string.channel_wizard_check_connect_waiting)
        },
        state = when {
            runtime?.state == ChannelConnectionState.AUTH_ERROR ||
                runtime?.state == ChannelConnectionState.ERROR -> WizardCheckState.ERROR
            state.connected -> WizardCheckState.DONE
            else -> WizardCheckState.RUNNING
        },
    )
    WizardCheckRow(
        title = stringResource(R.string.channel_wizard_check_claim),
        detail = when {
            state.claimedConfirmed -> stringResource(R.string.channel_wizard_claim_done)
            state.firstClaimInbound != null -> {
                val inbound = state.firstClaimInbound
                val senderLabel = ChannelContactMetaStore.resolvedName(
                    channelId = inbound.sourceChannelId,
                    contactId = inbound.from,
                    groupPrefix = stringResource(R.string.channel_contact_group_label),
                )
                stringResource(R.string.channel_wizard_claim_detected, senderLabel)
            }
            else -> stringResource(R.string.channel_wizard_claim_waiting)
        },
        state = when {
            state.claimedConfirmed -> WizardCheckState.DONE
            state.firstClaimInbound != null -> WizardCheckState.READY
            else -> WizardCheckState.WAITING
        },
    )
}

@Composable
private fun WizardPlatformCredentials(
    platform: ChannelPlatform,
    values: WizardCredentialValues,
    onAppIdChange: (String) -> Unit,
    onSecretChange: (String) -> Unit,
    onBound: (WeClawClient.QrStatus) -> Unit,
    onToggleSecret: () -> Unit,
) {
    when (platform) {
        ChannelPlatform.WECLAW -> {
            WeClawQrBindContent(onBound = onBound)
            Text(
                text = stringResource(R.string.channel_wizard_qr_auto_saved),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ChannelPlatform.TELEGRAM -> {
            ChannelSecretField(
                value = values.secret,
                onValueChange = onSecretChange,
                label = stringResource(R.string.channel_field_telegram_token),
                storedSecret = "",
                visible = values.showSecret,
                onToggleVisible = onToggleSecret,
            )
        }
        ChannelPlatform.FEISHU, ChannelPlatform.QQ -> {
            MuseTextField(
                value = values.appId,
                onValueChange = onAppIdChange,
                label = { Text(stringResource(R.string.channel_field_app_id)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ChannelSecretField(
                value = values.secret,
                onValueChange = onSecretChange,
                label = stringResource(R.string.channel_field_app_secret),
                storedSecret = "",
                visible = values.showSecret,
                onToggleVisible = onToggleSecret,
            )
        }
        ChannelPlatform.DINGTALK -> {
            MuseTextField(
                value = values.appId,
                onValueChange = onAppIdChange,
                label = { Text(stringResource(R.string.channel_field_client_id)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ChannelSecretField(
                value = values.secret,
                onValueChange = onSecretChange,
                label = stringResource(R.string.channel_field_client_secret),
                storedSecret = "",
                visible = values.showSecret,
                onToggleVisible = onToggleSecret,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WizardPlatformList(selected: ChannelPlatform?, onPick: (ChannelPlatform) -> Unit) {
    val ordered = listOf(
        ChannelPlatform.WECLAW,
        ChannelPlatform.TELEGRAM,
        ChannelPlatform.FEISHU,
        ChannelPlatform.DINGTALK,
        ChannelPlatform.QQ,
    )
    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
        ordered.forEach { platform ->
            val brand = platform.brandColors()
            val caps = platform.capabilities
            val isSelected = selected == platform
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(platform) },
                shape = MuseShapes.large,
                color = if (isSelected) brand.container.copy(alpha = MuseAlpha.mutedSurface) else MaterialTheme.colorScheme.surface,
                border = BorderStroke(
                    width = if (isSelected) MuseBorderWidth.medium else MuseBorderWidth.hairline,
                    color = if (isSelected) brand.accent else MaterialTheme.colorScheme.outlineVariant,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(MusePaddings.cardInner),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    PlatformBrandBadge(platform = platform, badgeSize = MuseAvatarSize.detail)
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                        ) {
                            Text(
                                text = stringResource(channelPlatformNameRes(platform)),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val highlightBadge = when (platform) {
                                ChannelPlatform.WECLAW -> R.string.channel_badge_recommended
                                ChannelPlatform.TELEGRAM -> R.string.channel_badge_quick
                                else -> R.string.channel_badge_no_public_ip
                            }
                            ChannelTagPill(
                                text = stringResource(highlightBadge),
                                containerColor = brand.container,
                                contentColor = brand.accent,
                            )
                        }
                        Text(
                            text = stringResource(channelPlatformDescRes(platform)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                            verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                        ) {
                            if (caps.proactive) {
                                ChannelTagPill(
                                    text = stringResource(R.string.channel_badge_proactive),
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (caps.media) {
                                ChannelTagPill(
                                    text = stringResource(R.string.channel_badge_media),
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Icon(
                        imageVector = MuseIcons.chevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WizardGuideAccordion(platform: ChannelPlatform, expanded: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    val stepsRes = when (platform) {
        ChannelPlatform.WECLAW -> R.array.channel_tutorial_weclaw_steps
        ChannelPlatform.TELEGRAM -> R.array.channel_tutorial_telegram_steps
        ChannelPlatform.FEISHU -> R.array.channel_tutorial_feishu_steps
        ChannelPlatform.DINGTALK -> R.array.channel_tutorial_dingtalk_steps
        ChannelPlatform.QQ -> R.array.channel_tutorial_qq_steps
    }
    val steps = stringArrayResource(stepsRes)
    val portalUrl = channelPlatformPortalUrl(platform)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = MuseAlpha.mutedSurface),
    ) {
        Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                Icon(
                    imageVector = MuseIcons.book,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
                Text(
                    text = stringResource(R.string.channel_wizard_guide_toggle),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(top = MusePaddings.contentGap),
                    verticalArrangement = Arrangement.spacedBy(MuseSpacing.smTight),
                ) {
                    steps.forEachIndexed { index, text ->
                        Text(
                            text = "${index + 1}. $text",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (platform == ChannelPlatform.FEISHU) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                            verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                        ) {
                            CopyableTokenChip(value = "im:message")
                            CopyableTokenChip(value = "im.message.receive_v1")
                        }
                    }
                    if (portalUrl.isNotBlank()) {
                        MuseCapsuleButton(
                            text = stringResource(R.string.channel_wizard_open_portal),
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(portalUrl))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }.onFailure { e -> Logger.w("ChannelWizard", "打开开放平台失败: ${e.message}") }
                            },
                            leadingIcon = MuseIcons.externalLink,
                            variant = IosCapsuleButtonVariant.Secondary,
                            fillWidth = false,
                            size = MuseButtonSize.Compact,
                        )
                    }
                }
            }
        }
    }
}

private enum class WizardCheckState { RUNNING, WAITING, READY, DONE, ERROR }

@Composable
private fun WizardCheckRow(title: String, detail: String, state: WizardCheckState) {
    val colors = MaterialTheme.statusColors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        Box(
            modifier = Modifier.size(MuseAvatarSize.inline),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                WizardCheckState.RUNNING -> MuseSpinner(size = MuseIconSizes.iconSmall, color = colors.info)
                WizardCheckState.DONE -> Icon(
                    imageVector = MuseIcons.circleCheck,
                    contentDescription = null,
                    tint = colors.success,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
                WizardCheckState.READY -> Icon(
                    imageVector = MuseIcons.circleCheck,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
                WizardCheckState.ERROR -> Icon(
                    imageVector = MuseIcons.alertCircle,
                    contentDescription = null,
                    tint = colors.error,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
                WizardCheckState.WAITING -> Icon(
                    imageVector = MuseIcons.circle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (state == WizardCheckState.ERROR) colors.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 升级版微信二维码绑定卡片：支持纯白高对比框、保存到相册（单台手机长按识别）及系统分享。 */
@Composable
internal fun WeClawQrBindContent(onBound: (WeClawClient.QrStatus) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf("loading") }
    var errorMsg by remember { mutableStateOf("") }
    var pollFailures by remember { mutableIntStateOf(0) }
    var refreshCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        var qr = WeClawClient.getQrCode().getOrElse { e ->
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
            val st = WeClawClient.getQrStatus(qr.qrcode).getOrNull()
            if (st == null) {
                pollFailures++
                if (pollFailures >= 3) status = "unstable"
                delay(1_500)
                continue
            }
            pollFailures = 0
            when (st.status.lowercase()) {
                "confirmed" -> {
                    onBound(st)
                    return@LaunchedEffect
                }
                "expired" -> {
                    if (refreshCount >= MAX_QR_REFRESH_COUNT) {
                        status = "expired"
                        return@LaunchedEffect
                    }
                    refreshCount++
                    val fresh = WeClawClient.getQrCode().getOrNull()
                    if (fresh == null) {
                        status = "expired"
                        return@LaunchedEffect
                    }
                    qr = fresh
                    bitmap = QrCodeGenerator.generateQrBitmap(fresh.imgContent, 600)
                    status = "refreshed"
                }
                "scaned", "scanned" -> status = "scaned"
                "wait" -> if (status == "unstable") status = "wait"
                else -> Unit
            }
            delay(1_000)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        when {
            status == "error" -> Text(
                text = stringResource(R.string.channel_weclaw_bind_failed) + errorMsg.take(120),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.statusColors.error,
            )
            bitmap != null -> {
                val qrBmp = bitmap
                Surface(
                    shape = MuseShapes.large,
                    color = Color.White,
                    border = BorderStroke(MuseBorderWidth.hairline, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    if (qrBmp != null) {
                        Image(
                            bitmap = qrBmp.asImageBitmap(),
                            contentDescription = stringResource(R.string.channel_wizard_qr_cd),
                            modifier = Modifier
                                .fillMaxWidth(0.68f)
                                .aspectRatio(1f)
                                .padding(MusePaddings.contentGap),
                        )
                    }
                }
                Text(
                    text = when (status) {
                        "scaned" -> stringResource(R.string.channel_weclaw_status_scaned)
                        "expired" -> stringResource(R.string.channel_weclaw_status_expired)
                        "unstable" -> stringResource(R.string.channel_weclaw_status_unstable)
                        "refreshed" -> stringResource(R.string.channel_weclaw_status_refreshed)
                        else -> stringResource(R.string.channel_weclaw_bind_hint)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MuseCapsuleButton(
                        text = stringResource(R.string.channel_wizard_qr_save),
                        leadingIcon = MuseIcons.download,
                        variant = IosCapsuleButtonVariant.Secondary,
                        fillWidth = false,
                        size = MuseButtonSize.Compact,
                        onClick = {
                            val bmp = bitmap ?: return@MuseCapsuleButton
                            scope.launch {
                                val res = saveBitmapToGallery(context, bmp)
                                if (res.isSuccess) {
                                    MuseToast.show(context.getString(R.string.channel_wizard_qr_saved))
                                } else {
                                    MuseToast.show(
                                        context.getString(
                                            R.string.channel_wizard_qr_save_failed,
                                            res.exceptionOrNull()?.message.orEmpty(),
                                        ),
                                    )
                                }
                            }
                        },
                    )
                    MuseCapsuleButton(
                        text = stringResource(R.string.channel_wizard_qr_share),
                        leadingIcon = MuseIcons.share,
                        variant = IosCapsuleButtonVariant.Secondary,
                        fillWidth = false,
                        size = MuseButtonSize.Compact,
                        onClick = {
                            val bmp = bitmap ?: return@MuseCapsuleButton
                            scope.launch {
                                shareQrBitmap(context, bmp)
                            }
                        },
                    )
                }
            }
            else -> {
                MuseSpinner(size = MuseIconSizes.iconMedium)
                Text(
                    text = stringResource(R.string.channel_weclaw_bind_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private suspend fun saveBitmapToGallery(context: Context, bitmap: Bitmap): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "muse_weclaw_qr_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Muse")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert returned null")
        resolver.openOutputStream(uri)?.use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG encode failed" }
        } ?: error("OutputStream unavailable")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        Unit
    }
}

private suspend fun shareQrBitmap(context: Context, bitmap: Bitmap) {
    val uriResult = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "shared_qr").apply { mkdirs() }
            val file = File(dir, "weclaw_bind_qr.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
    }
    uriResult.onSuccess { uri ->
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, context.getString(R.string.channel_wizard_qr_share_title))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }
            .onFailure { e -> Logger.w("ChannelWizard", "分享二维码失败: ${e.message}") }
    }.onFailure { e ->
        MuseToast.show(context.getString(R.string.channel_wizard_qr_save_failed, e.message.orEmpty()))
    }
}
