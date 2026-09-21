package io.zer0.muse.ui.chat

import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.theme.MuseMotion
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.tools.ToolApprovalPolicy
import io.zer0.muse.ui.PendingToolApproval
import io.zer0.muse.ui.SmartImage
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.surface.MuseSurface
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tool approval card (既有实现 GenerationHandler.kt port).
 *
 * Shown when a tool call requires user approval before execution.
 * Displays tool name, argument preview, and approve/deny buttons.
 *
 * v1.0.20: 新增"始终允许"/"始终拒绝"按钮 — 点击后通过 [onPersistPolicy]
 * 持久化到 [io.zer0.muse.tools.ToolConfigStore],后续该工具调用直接走对应策略,
 * 不再弹审批卡片。
 *
 * v1.x: 新增"本会话允许"按钮 — 介于"批准本次"(单次)与"始终允许"(持久)之间的中间地带。
 * 点击后通过 [onAllowThisSession] 把工具加入 [io.zer0.muse.tools.SessionPermissionStore]
 * 的会话级临时允许缓存(纯内存,不持久化),本会话内该工具不再弹审批卡片,
 * 切换会话/冷启动后自动失效。同时触发 [onApprove] 处理本次调用。
 *
 * v1.x: 对 generate_image 等支持参考图的工具,在卡片中渲染"选择参考图"按钮 —
 * 用户从相册选择本地图片后转 data URI,通过 [onReferenceImageChange] 写入
 * [io.zer0.muse.ui.PendingToolApproval.referenceImageOverride];批准时由
 * ChatViewModel 注入 [io.zer0.muse.tools.ToolApprovalState.Approved.argOverrides],
 * ToolOrchestrator 合并进工具执行参数。LLM 自身无法访问用户本地相册,
 * 故图生图的参考图主要从此入口提供。
 *
 * v1.0.48: UI 重构 — 紧凑单行主操作(批准/拒绝/更多)+ 折叠次级操作,
 * 替代原三行按钮 + 双复选框的臃肿布局;配色由 tertiaryContainer 改为
 * surface + primary 强调色,视觉更清爽。
 */
@Composable
fun ToolApprovalCard(
    toolName: String,
    argumentsPreview: String,
    onApprove: () -> Unit,
    onDeny: (reason: String) -> Unit,
    /**
     * v1.0.20: 持久化单工具策略回调。
     *
     * "始终允许"按钮点击时传入 [ToolApprovalPolicy.ALWAYS_ALLOW],
     * 调用方应在此回调内:
     *  1. 调用 [io.zer0.muse.tools.ToolConfigStore.setPolicy] 持久化策略
     *  2. 同步触发 onApprove / onDeny 处理本次调用
     */
    onPersistPolicy: (ToolApprovalPolicy) -> Unit = {},
    /**
     * v1.x: "本会话允许"按钮回调 — 把工具加入会话级临时允许缓存。
     *
     * 调用方应在此回调内调用 [io.zer0.muse.tools.SessionPermissionStore.allowToolForSession]
     * 把工具名加入当前会话的内存缓存;同时由按钮内部触发 [onApprove] 处理本次调用。
     * 本会话内该工具不再弹审批卡片,切换会话/冷启动后自动失效。
     */
    onAllowThisSession: () -> Unit = {},
    /**
     * v1.x: 用户在审批卡片中选择的参考图(data URI,如 "data:image/jpeg;base64,...")。
     * 非空时显示缩略图预览;null 表示未选择。
     */
    referenceImageOverride: String? = null,
    /**
     * v1.x: 参考图选择变化回调。dataUri 非空表示用户选了新图;null 表示清除。
     */
    onReferenceImageChange: (String?) -> Unit = {},
    /**
     * Approval deadline shown as a countdown. Must match the VM-side approval timeout so the
     * card cannot silently expire while the user still believes it is still waiting.
     */
    countdownSeconds: Int = DEFAULT_APPROVAL_COUNTDOWN_SECONDS,
    /**
     * CHAT-08: 倒计时是否暂停(折叠为「N 项待审批」阅读期间为 true)。
     * 暂停时 UI 倒计时冻结,且由调用方同步暂停 VM 侧超时,避免 UI/VM 失步。
     */
    countdownPaused: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var showDenyReason by remember { mutableStateOf(false) }
    var denyReason by remember { mutableStateOf("") }
    // 参考图读取中标志(避免大图阻塞主线程时按钮无响应)
    var isLoadingRefImage by remember { mutableStateOf(false) }
    // v1.x: 次级操作折叠状态(本会话允许 / 始终允许)
    var showMoreOptions by remember { mutableStateOf(false) }
    // 审批超时倒计时:与 VM 的 30s 超时对齐,归零后卡片会被 VM 侧拒绝。
    // CHAT-08: countdownPaused 时倒计时冻结(折叠阅读期间),恢复后继续。
    var remainingSeconds by remember(toolName, countdownSeconds) {
        mutableStateOf(countdownSeconds.coerceAtLeast(0))
    }
    LaunchedEffect(toolName, countdownSeconds, countdownPaused) {
        while (remainingSeconds > 0) {
            if (countdownPaused) {
                kotlinx.coroutines.delay(500)
            } else {
                kotlinx.coroutines.delay(1_000)
                remainingSeconds -= 1
            }
        }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // v1.x: 相册选择器,仅对支持参考图的工具启用
    val supportsReferenceImage = toolName in REFERENCE_IMAGE_TOOL_NAMES
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        isLoadingRefImage = true
        scope.launch {
            // 大图片读取 + Base64 编码移到 IO 线程,避免阻塞 UI
            val dataUri = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = context.contentResolver
                    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    require(bytes != null && bytes.size <= MAX_REF_IMAGE_BYTES) {
                        context.getString(R.string.chat_image_too_large)
                    }
                    // 推断 mime;PickVisualMedia 返回的 content URI 一般有 type
                    val mime = resolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: "image/png"
                    val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    "data:$mime;base64,$base64"
                }
            }
            isLoadingRefImage = false
            dataUri.onSuccess { onReferenceImageChange(it) }
                .onFailure { e ->
                    MuseToast.show(context.getString(R.string.chat_ref_image_load_failed, e.message ?: ""))
                }
        }
    }

    // Phase 2: 统一走项目容器基元 [MuseSurface],保留原 Card 的圆角(medium)、
    // surface 底色、0dp 阴影与 1dp primary 边框,不改变可见尺寸。
    MuseSurface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surface,
        // CHAT-09: 消息内卡片统一规范 — 底色 + 1dp 描边 + 统一圆角(此前 primary 45% 边框)
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Header: 工具图标徽标(使用语义图标) + 标题/工具名
            val visualIcon = remember(toolName) { ToolCallVisuals.iconFor(toolName) }
            // I18N-01: 标签走资源
            val displayName = remember(toolName) { ToolCallVisuals.labelFor(toolName, context.resources) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = visualIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    )
                }
                Column {
                    Text(
                        text = stringResource(R.string.tool_approval_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.weight(1f))
                // 倒计时与风险提示:审批不会永远等待(30s 后自动拒绝)。
                // CHAT-08: 暂停期间显示「已暂停」,不再倒计时。
                if (countdownPaused) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Pause,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.tool_approval_countdown_paused),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.tool_approval_countdown, remainingSeconds),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (remainingSeconds <= 10) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            // Arguments preview (truncated) — 放入圆角代码框,视觉更清爽
            val preview = if (argumentsPreview.length > 200) {
                argumentsPreview.take(200) + "..."
            } else {
                argumentsPreview
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MuseShapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                )
            }

            // v1.x: 参考图选择区(仅 generate_image 等支持参考图的工具显示)
            // LLM 无法访问用户本地相册,故图生图参考图由此入口提供
            if (supportsReferenceImage) {
                ReferenceImageSection(
                    referenceImageOverride = referenceImageOverride,
                    isLoading = isLoadingRefImage,
                    onPick = {
                        runCatching {
                            imagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        }.onFailure {
                            MuseToast.show(context.getString(R.string.chat_ref_image_load_failed, it.message ?: ""))
                        }
                    },
                    onClear = { onReferenceImageChange(null) },
                )
            }

            // 主操作行:批准(强调) + 拒绝 + 更多(折叠次级操作)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 批准 — 主按钮,最高优先级,使用 filled 强调
                MuseCapsuleButton(
                    text = stringResource(R.string.tool_approval_approve),
                    onClick = onApprove,
                    leadingIcon = Icons.Default.Check,
                    fillWidth = false,
                )

                // 拒绝 — 次级,OutlinedButton
                MuseCapsuleButton(
                    text = if (showDenyReason) stringResource(R.string.tool_approval_confirm_deny)
                    else stringResource(R.string.tool_approval_deny),
                    onClick = {
                        if (showDenyReason) {
                            onDeny(denyReason)
                        } else {
                            showDenyReason = true
                        }
                    },
                    variant = IosCapsuleButtonVariant.Secondary,
                    leadingIcon = Icons.Default.Close,
                    fillWidth = false,
                )

                Spacer(Modifier.width(2.dp))

                // 更多 — 折叠/展开次级操作
                MuseCapsuleButton(
                    text = stringResource(R.string.action_more),
                    onClick = { showMoreOptions = !showMoreOptions },
                    variant = IosCapsuleButtonVariant.Text,
                    fillWidth = false,
                )
            }

            // 拒绝理由输入框(点击拒绝后展开)
            if (showDenyReason) {
                MuseTextField(
                    value = denyReason,
                    onValueChange = { denyReason = it },
                    label = { Text(stringResource(R.string.tool_approval_deny_reason)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // 次级操作(折叠区):本会话允许 / 始终允许
            AnimatedVisibility(
                visible = showMoreOptions,
                enter = MuseMotion.expandFadeEnter(),
                exit = MuseMotion.expandFadeExit(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MuseCapsuleButton(
                            text = stringResource(R.string.tool_approval_allow_this_session),
                            onClick = {
                                // 把工具加入会话级临时允许缓存,并触发本次批准
                                onAllowThisSession()
                                onApprove()
                            },
                            variant = IosCapsuleButtonVariant.Secondary,
                            leadingIcon = Icons.Default.Bookmark,
                            fillWidth = false,
                        )

                        MuseCapsuleButton(
                            text = stringResource(R.string.tool_approval_always_approve),
                            onClick = {
                                // 持久化 ALWAYS_ALLOW 策略,并触发本次批准
                                onPersistPolicy(ToolApprovalPolicy.ALWAYS_ALLOW)
                                onApprove()
                            },
                            variant = IosCapsuleButtonVariant.Secondary,
                            leadingIcon = Icons.Default.VerifiedUser,
                            fillWidth = false,
                        )
                    }
                }
            }
        }
    }
}

/**
 * CHAT-08: 多张审批卡折叠为「N 项待审批」摘要条,避免多卡堆叠顶掉输入栏。
 *
 * 折叠态只占一行;展开后逐张渲染 [ToolApprovalCard]。折叠(阅读)期间各卡
 * 倒计时暂停([countdownPaused]),配合 VM 侧暂停,避免审批在用户阅读时被 30s 自动拒绝。
 * 展开状态由调用方持有(与 VM 暂停联动)。
 */
@Composable
fun PendingApprovalsSummary(
    approvals: List<PendingToolApproval>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onApprove: (PendingToolApproval) -> Unit,
    onDeny: (PendingToolApproval, String) -> Unit,
    onPersistPolicy: (PendingToolApproval, ToolApprovalPolicy) -> Unit,
    onAllowThisSession: (PendingToolApproval) -> Unit,
    onReferenceImageChange: (PendingToolApproval, String?) -> Unit,
    countdownPaused: Boolean,
    modifier: Modifier = Modifier,
) {
    // I18N-01: 标签走资源
    val resources = LocalContext.current.resources
    // lambda 内不可直接引用 composable 上下文,标签列表在 composable 作用域预计算
    val pendingLabels = approvals.joinToString(", ") { ToolCallVisuals.labelFor(it.toolName, resources) }
    Column(modifier = modifier.fillMaxWidth()) {
        MuseSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            shape = MuseShapes.medium,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpanded() }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.VerifiedUser,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.tool_approval_pending_n, approvals.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = pendingLabels,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (countdownPaused) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Pause,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.tool_approval_countdown_paused),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(
                        if (expanded) R.string.action_collapse else R.string.action_expand,
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
            }
        }
        if (expanded) {
            approvals.forEach { approval ->
                ToolApprovalCard(
                    toolName = approval.toolName,
                    argumentsPreview = approval.argumentsPreview,
                    onApprove = { onApprove(approval) },
                    onDeny = { reason -> onDeny(approval, reason) },
                    onPersistPolicy = { policy -> onPersistPolicy(approval, policy) },
                    onAllowThisSession = { onAllowThisSession(approval) },
                    referenceImageOverride = approval.referenceImageOverride,
                    onReferenceImageChange = { dataUri -> onReferenceImageChange(approval, dataUri) },
                    countdownPaused = countdownPaused,
                )
            }
        }
    }
}

/**
 * v1.x: 工具审批卡片中的参考图选择区。
 *
 * 三种状态:
 *  - 未选图:显示"+ 参考图"按钮,点击触发 PickVisualMedia
 *  - 读取中:显示进度指示器
 *  - 已选图:显示缩略图预览(带清除按钮)
 */
@Composable
private fun ReferenceImageSection(
    referenceImageOverride: String?,
    isLoading: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.chat_ref_image_cd),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            isLoading -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MuseSpinner(
                        size = MuseIconSizes.iconSmall,
                    )
                    Text(
                        text = stringResource(R.string.chat_ref_image_cd),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            referenceImageOverride.isNullOrBlank() -> {
                MuseCapsuleButton(
                    text = stringResource(R.string.chat_ref_image_add),
                    onClick = onPick,
                    variant = IosCapsuleButtonVariant.Secondary,
                    leadingIcon = Icons.Default.Photo,
                    fillWidth = false,
                )
            }
            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 120.dp)
                        .clip(MuseShapes.small),
                ) {
                    SmartImage(
                        model = referenceImageOverride,
                        contentDescription = stringResource(R.string.chat_ref_image_cd),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    MuseTactileButton(
                        icon = Icons.Default.Close,
                        onClick = onClear,
                        contentDescription = stringResource(R.string.tool_approval_deny),
                        tint = MaterialTheme.colorScheme.onSurface,
                        size = MuseIconSizes.touchTarget,
                        iconSize = MuseIconSizes.iconSmall,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            
                            .background(
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                shape = CircleShape,
                            ),
                    )
                }
            }
        }
    }
}

/**
 * v1.x: 支持在审批卡片中选取本地参考图的工具名集合(与 ChatViewModel 中保持一致)。
 * 命名上仅在本文件内使用,故 private。
 */
private val REFERENCE_IMAGE_TOOL_NAMES: Set<String> = setOf("generate_image")

/**
 * Must stay in sync with `ChatViewModel.TOOL_APPROVAL_TIMEOUT_MS`. Kept here as a UI default
 * so the card can warn before the VM silently declines the pending call.
 */
private const val DEFAULT_APPROVAL_COUNTDOWN_SECONDS = 30

/** 参考图大小上限 5MB(对齐 InputBar.ImageGenParamsPanel 中现有约束)。 */
private const val MAX_REF_IMAGE_BYTES = 5L * 1024 * 1024
