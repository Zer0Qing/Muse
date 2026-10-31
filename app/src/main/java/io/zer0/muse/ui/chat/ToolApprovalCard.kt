package io.zer0.muse.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.MuseSurface
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tool approval card.
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
@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod")
@Composable
fun ToolApprovalCard(
    toolName: String,
    argumentsPreview: String,
    /**
     * 全量参数(审批判定用的同一份)。
     *
     * [argumentsPreview] 只有 200 字符,写 4KB 正文时用户看到的只是开头;
     * 完整参数从这里取,卡片据此提示「还有 N 字符未显示」并支持展开查看。
     * 为空时退化到只用预览展示。
     */
    argumentsFull: Map<String, Any?> = emptyMap(),
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
                            imageVector = MuseIcons.pause,
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

            // v2.x: 参数展示 — 预览 + 明确的截断提示 + 可展开看全量。
            // 审批判定用的是全量 args,但卡片过去只拿到 200 字符预览:写 4KB 正文时
            // 用户只看到开头就点了批准。预览之外必须写清还有多少字符没显示,
            // 并把完整参数放到展开入口后面(展开后按 key 分行、框内可滚动)。
            val argumentEntries = remember(argumentsPreview, argumentsFull) {
                approvalArgumentEntries(argumentsPreview, argumentsFull)
            }
            val rawArguments = remember(argumentEntries) {
                approvalRawJson(argumentEntries) ?: argumentsPreview
            }
            val approvalFilePath = remember(argumentEntries) { approvalValueOf(argumentEntries, APPROVAL_FILE_KEYS) }
            val approvalContent = remember(argumentEntries) { approvalValueOf(argumentEntries, APPROVAL_CONTENT_KEYS) }
            val extraArgs = remember(argumentEntries) { approvalExtraArguments(argumentEntries) }
            // 结构化摘要(文件/内容)存在时才有「其余参数」与「高级详情」两段。
            val hasStructuredSummary = approvalFilePath != null || approvalContent != null
            var showRawDetails by remember(argumentsPreview) { mutableStateOf(false) }
            var showFullArguments by remember(argumentsPreview) { mutableStateOf(false) }
            val previewScroll = rememberScrollState()
            val rawScroll = rememberScrollState()

            // 预览文本和「省略了多少字符」用同一组数字,避免提示和预览各说各话。
            val previewTotalChars = approvalContent?.length ?: rawArguments.length
            val previewText = when {
                approvalContent != null ->
                    if (showFullArguments) approvalContent else approvalContent.take(APPROVAL_CONTENT_PREVIEW_CHARS)
                showFullArguments -> rawArguments
                else -> argumentsPreview
            }
            val hiddenChars = hiddenCharacters(previewTotalChars, previewText.length)

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (approvalFilePath != null) {
                    Text(
                        text = stringResource(
                            R.string.tool_approval_write_summary,
                            approvalFilePath.substringAfterLast('/'),
                            approvalContent?.length ?: 0,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (previewText.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MuseShapes.small)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .then(
                                if (showFullArguments) {
                                    Modifier.heightIn(max = APPROVAL_EXPANDED_MAX_HEIGHT).verticalScroll(previewScroll)
                                } else {
                                    Modifier
                                },
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = previewText,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = if (approvalContent != null) FontFamily.Default else FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (showFullArguments) {
                                Int.MAX_VALUE
                            } else if (approvalContent != null) {
                                APPROVAL_CONTENT_PREVIEW_LINES
                            } else {
                                APPROVAL_RAW_PREVIEW_LINES
                            },
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // 截断提示与展开入口:不告诉用户「这里被截断」,展开入口等于没做。
                if (hiddenChars > 0) {
                    ApprovalArgumentsToggle(
                        label = stringResource(R.string.tool_approval_show_full_args) + " · " +
                            stringResource(R.string.tool_approval_args_hidden, hiddenChars),
                        expanded = false,
                        highlighted = true,
                        onClick = { showFullArguments = true },
                    )
                } else if (showFullArguments) {
                    ApprovalArgumentsToggle(
                        label = stringResource(R.string.tool_approval_hide_full_args),
                        expanded = true,
                        highlighted = true,
                        onClick = { showFullArguments = false },
                    )
                }
                // 其余标量参数以 k: v 列出（最多 6 行）
                extraArgs.forEach { (k, v) ->
                    Text(
                        text = "$k: $v",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 高级详情:完整原始 JSON(框内可滚动)
                if (hasStructuredSummary) {
                    ApprovalArgumentsToggle(
                        label = stringResource(R.string.tool_approval_advanced_details),
                        expanded = showRawDetails,
                        highlighted = false,
                        onClick = { showRawDetails = !showRawDetails },
                    )
                    if (showRawDetails) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MuseShapes.small)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                .heightIn(max = APPROVAL_EXPANDED_MAX_HEIGHT)
                                .verticalScroll(rawScroll)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = rawArguments,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
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
                    leadingIcon = MuseIcons.check,
                    fillWidth = false,
                )

                // 拒绝 — 次级,OutlinedButton
                MuseCapsuleButton(
                    text = if (showDenyReason) {
                        stringResource(R.string.tool_approval_confirm_deny)
                    } else {
                        stringResource(R.string.tool_approval_deny)
                    },
                    onClick = {
                        if (showDenyReason) {
                            onDeny(denyReason)
                        } else {
                            showDenyReason = true
                        }
                    },
                    variant = IosCapsuleButtonVariant.Secondary,
                    leadingIcon = MuseIcons.x,
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
                            leadingIcon = MuseIcons.bookmark,
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
                            leadingIcon = MuseIcons.shieldCheck,
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
                        imageVector = MuseIcons.shieldCheck,
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
                            imageVector = MuseIcons.pause,
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
                    imageVector = if (expanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
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
                    argumentsFull = approval.argumentsFull,
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
private fun ReferenceImageSection(referenceImageOverride: String?, isLoading: Boolean, onPick: () -> Unit, onClear: () -> Unit) {
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
                    leadingIcon = MuseIcons.image,
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
                        icon = MuseIcons.x,
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
 * 倒计时秒数从审批编排器的超时常量派生(ChatToolApprovalCoordinator.TOOL_APPROVAL_TIMEOUT_MS),
 * 不在这里另存一份数字: 两份各写各的时候, 界面数到第 31 秒后台早就拒了,
 * 用户看到的那 30 秒是假的。
 */
private val DEFAULT_APPROVAL_COUNTDOWN_SECONDS: Int = (ChatToolApprovalCoordinator.TOOL_APPROVAL_TIMEOUT_MS / 1_000L).toInt()

/** 参考图大小上限 5MB(InputBar.ImageGenParamsPanel 里用的也是这个值)。 */
private const val MAX_REF_IMAGE_BYTES = 5L * 1024 * 1024

/** 内容预览字符数:折叠时的上限(200 的 JSON 预览撑不住一段正文,这里单独放宽到这一段)。 */
private const val APPROVAL_CONTENT_PREVIEW_CHARS = 300

/** 内容预览行数:折叠时的上限。 */
private const val APPROVAL_CONTENT_PREVIEW_LINES = 8

/** 原始 JSON 预览行数:折叠时的上限。 */
private const val APPROVAL_RAW_PREVIEW_LINES = 6

/** 展开后的参数框最大高度:超出后在框内滚动,不把整张卡片顶出屏幕。 */
private val APPROVAL_EXPANDED_MAX_HEIGHT = 320.dp

/** 作为「目标文件」展示的参数 key。 */
private val APPROVAL_FILE_KEYS = listOf("path", "file_path", "filepath", "filename", "file")

/** 作为「内容正文」展示的参数 key。 */
private val APPROVAL_CONTENT_KEYS = listOf("content", "text", "data", "message", "query", "url", "command")

/**
 * 预览之外仍未显示的字符数;<= 0 表示没有截断。
 *
 * 提示文案与预览共用同一组数字,不能一边显示前 300 字符、一边声称已经全部展示。
 */
internal fun hiddenCharacters(total: Int, shown: Int): Int = (total - shown).coerceAtLeast(0)

/**
 * 参数条目:优先用全量 args(结构化、不丢层次),缺失时回落到预览 JSON 的解析结果。
 *
 * 预览被截断时 JSON 通常已无法解析(尾部缺失),这就是大体积参数过去只能看到
 * 原始转义字符的原因;全量参数在场时才能给出「文件 + 字符数 + 正文」。
 */
internal fun approvalArgumentEntries(preview: String, full: Map<String, Any?>): List<Pair<String, String>> {
    if (full.isNotEmpty()) {
        return full.entries.map { (key, value) -> key to (value?.toString() ?: "") }
    }
    val obj = runCatching { org.json.JSONObject(preview) }.getOrNull() ?: return emptyList()
    return obj.keys().asSequence().map { key -> key to obj.optString(key) }.toList()
}

/** 条目拼成可读 JSON;无条目时返回 null(调用方回落到原始预览)。 */
internal fun approvalRawJson(entries: List<Pair<String, String>>): String? {
    if (entries.isEmpty()) return null
    val obj = org.json.JSONObject()
    entries.forEach { (key, value) -> runCatching { obj.put(key, value) } }
    return obj.toString(2)
}

/** 按 key 优先级取第一个非空值。 */
internal fun approvalValueOf(entries: List<Pair<String, String>>, keys: List<String>): String? =
    keys.firstNotNullOfOrNull { key ->
        entries.firstOrNull { it.first == key }?.second?.takeIf { it.isNotBlank() && it != "null" }
    }

/** 摘要之外的其余参数(最多 6 条,单值超过 100 字符先截断,完整值看展开视图)。 */
internal fun approvalExtraArguments(entries: List<Pair<String, String>>): List<Pair<String, String>> {
    val summaryKeys = (APPROVAL_FILE_KEYS + APPROVAL_CONTENT_KEYS).toSet()
    return entries
        .filterNot { it.first in summaryKeys }
        .filter { it.second.isNotBlank() && it.second != "null" }
        .take(6)
        .map { (key, value) -> key to (if (value.length > 100) value.take(100) + "…" else value) }
}

/**
 * 审批卡里的展开/收起入口:chevron + 文案,整行可点。
 *
 * [highlighted] 用于截断提示与展开入口(primary 色,告诉用户这里还有东西没看到),
 * 「高级详情」这种次要点用弱色。
 */
@Composable
private fun ApprovalArgumentsToggle(
    label: String,
    expanded: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = if (expanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
            contentDescription = null,
            modifier = Modifier.size(MuseIconSizes.iconSmall),
            tint = tint,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}
