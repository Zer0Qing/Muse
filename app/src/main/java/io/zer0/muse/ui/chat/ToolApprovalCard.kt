package io.zer0.muse.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.tools.ToolApprovalPolicy
import io.zer0.muse.ui.SmartImage
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tool approval card (RikkaHub GenerationHandler.kt port).
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
 */
@Composable
fun ToolApprovalCard(
    toolName: String,
    argumentsPreview: String,
    onApprove: () -> Unit,
    onDeny: (reason: String) -> Unit,
    alwaysAllow: Boolean,
    onAlwaysAllowChanged: (Boolean) -> Unit,
    /** v1.0.16: 本次开启期间批准全部工具 */
    appRunAllowAll: Boolean = false,
    /** v1.0.16: 本次开启期间批准全部 复选状态变更回调 */
    onAppRunAllowAllChanged: (Boolean) -> Unit = {},
    /**
     * v1.0.20: 持久化单工具策略回调。
     *
     * "始终允许"按钮点击时传入 [ToolApprovalPolicy.ALWAYS_ALLOW],
     * "始终拒绝"按钮点击时传入 [ToolApprovalPolicy.ALWAYS_DENY]。
     * 调用方应在此回调内:
     *  1. 调用 [io.zer0.muse.tools.ToolConfigStore.setPolicy] 持久化策略
     *  2. 同步触发 onApprove / onDeny 处理本次调用
     *
     * 默认空实现(向后兼容,不接通持久化时按钮仅触发本次批准/拒绝)。
     */
    onPersistPolicy: (ToolApprovalPolicy) -> Unit = {},
    /**
     * v1.x: "本会话允许"按钮回调 — 把工具加入会话级临时允许缓存。
     *
     * 调用方应在此回调内调用 [io.zer0.muse.tools.SessionPermissionStore.allowToolForSession]
     * 把工具名加入当前会话的内存缓存;同时由按钮内部触发 [onApprove] 处理本次调用。
     * 本会话内该工具不再弹审批卡片,切换会话/冷启动后自动失效。
     *
     * 默认空实现(向后兼容)。
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
    modifier: Modifier = Modifier,
) {
    var showDenyReason by remember { mutableStateOf(false) }
    var denyReason by remember { mutableStateOf("") }
    // 参考图读取中标志(避免大图阻塞主线程时按钮无响应)
    var isLoadingRefImage by remember { mutableStateOf(false) }

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

    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.tool_approval_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    text = toolName,
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }

            // Arguments preview (truncated)
            val preview = if (argumentsPreview.length > 200) {
                argumentsPreview.take(200) + "..."
            } else {
                argumentsPreview
            }
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 6,
            )

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

            // "始终允许"复选框(本次批准时附带勾选,与"始终允许"按钮的区别:
            // 复选框是 onApprove 时附带 alwaysAllow=true,按钮是直接持久化策略)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = alwaysAllow,
                    onCheckedChange = onAlwaysAllowChanged,
                )
                Text(
                    text = stringResource(R.string.tool_approval_always_allow, toolName),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // v1.0.16: "本次开启期间批准全部工具"复选框(内存态,不持久化,冷启动后失效)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = appRunAllowAll,
                    onCheckedChange = onAppRunAllowAllChanged,
                )
                Text(
                    text = stringResource(R.string.tool_approval_allow_all_this_run),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 操作按钮行(三行布局:本次操作 + 会话级 + 持久化策略)
            // v1.0.20: 第一行 — 本次批准 / 本次拒绝
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onApprove,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text(stringResource(R.string.tool_approval_approve), style = MaterialTheme.typography.labelMedium)
                }

                TextButton(
                    onClick = {
                        if (showDenyReason) {
                            onDeny(denyReason)
                        } else {
                            showDenyReason = true
                        }
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text(if (showDenyReason) stringResource(R.string.tool_approval_confirm_deny) else stringResource(R.string.tool_approval_deny), style = MaterialTheme.typography.labelMedium)
                }
            }

            // v1.x: 第二行 — 本会话允许(会话级临时允许,切换会话/冷启动后自动失效)
            // 介于"批准本次"(单次)与"始终允许"(持久)之间的中间地带,平衡安全与流畅
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        // 把工具加入会话级临时允许缓存,并触发本次批准
                        onAllowThisSession()
                        onApprove()
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Default.Bookmark, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text(stringResource(R.string.tool_approval_allow_this_session), style = MaterialTheme.typography.labelMedium)
                }
            }

            // v1.0.20: 第三行 — 始终允许(持久化)/ 始终拒绝(持久化)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        // 持久化 ALWAYS_ALLOW 策略,并触发本次批准
                        onPersistPolicy(ToolApprovalPolicy.ALWAYS_ALLOW)
                        onApprove()
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text(stringResource(R.string.tool_approval_always_approve), style = MaterialTheme.typography.labelMedium)
                }

                TextButton(
                    onClick = {
                        // 持久化 ALWAYS_DENY 策略,并触发本次拒绝
                        onPersistPolicy(ToolApprovalPolicy.ALWAYS_DENY)
                        onDeny("")
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text(stringResource(R.string.tool_approval_always_deny), style = MaterialTheme.typography.labelMedium)
                }
            }

            // 拒绝理由输入框
            if (showDenyReason) {
                MuseTextField(
                    value = denyReason,
                    onValueChange = { denyReason = it },
                    label = { Text(stringResource(R.string.tool_approval_deny_reason)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
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
                    CircularProgressIndicator(
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = stringResource(R.string.chat_ref_image_cd),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            referenceImageOverride.isNullOrBlank() -> {
                OutlinedButton(
                    onClick = onPick,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Default.Photo, contentDescription = null, modifier = Modifier.size(MuseIconSizes.iconSmall))
                    Text(
                        text = stringResource(R.string.chat_ref_image_add),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
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
                    IconButton(
                        onClick = onClear,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(MuseIconSizes.touchTarget)
                            .background(
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                shape = CircleShape,
                            ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.tool_approval_deny),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                    }
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

/** 参考图大小上限 5MB(对齐 InputBar.ImageGenParamsPanel 中现有约束)。 */
private const val MAX_REF_IMAGE_BYTES = 5L * 1024 * 1024
