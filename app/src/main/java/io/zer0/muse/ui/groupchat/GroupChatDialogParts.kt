package io.zer0.muse.ui.groupchat

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.groupchat.GroupChatMessageEntity
import io.zer0.muse.ui.chat.AssistantVideoCard
import io.zer0.muse.ui.chat.SmartImage
import io.zer0.muse.ui.chat.StickerAwareMarkdownBody
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseAnchoredMenu
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseSlider
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.AssistantAvatar
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.MuseListItem
import io.zer0.muse.ui.chat.formatMessageTime
import io.zer0.muse.ui.theme.MuseAvatarSize
import io.zer0.muse.ui.theme.MuseBubbleStyles
import io.zer0.muse.ui.theme.MuseHaptics
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.bodyMediumStrong
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.ByteArrayOutputStream
import io.zer0.muse.ui.chat.ThinkingIndicator

/**
 * 群聊的待发送附件行、成员与编辑对话框、媒体卡、相机预览、相册读取。
 *
 * 从 `GroupChatDetailComponents.kt` 切出——与消息渲染组件无耦合。
 */
/**
 * v2.6.7: 待发送附件预览行。
 *
 * 之前 `pendingFileAttachments` 只参与 canSend 判断，界面完全不渲染 ——
 * 用户选了附件看不到，也删不掉（removePendingFileAttachment 零调用）。
 * 这里给一个可横向滚动的文件名 + 删除按钮行。
 */
@Composable
fun PendingAttachmentsRow(attachments: List<FileAttachment>, onRemove: (Int) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 2.dp),
    ) {
        itemsIndexed(attachments, key = { index, file -> "${file.name}_$index" }) { index, file ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .clip(MuseShapes.semiLarge)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 140.dp),
                )
                io.zer0.muse.ui.common.media.AttachmentRemoveButton(
                    contentDescription = stringResource(R.string.common_delete),
                    onClick = { onRemove(index) },
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
            }
        }
    }
}

/**
 * 待发送图片预览行 — 可点击右上角删除。
 */
@Composable
fun PendingImagesRow(images: List<String>, onRemove: (String) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 2.dp),
    ) {
        itemsIndexed(images, key = { idx, image -> "$idx:${image.hashCode()}" }) { _, image ->
            Box(modifier = Modifier.size(64.dp)) {
                SmartImage(
                    model = image,
                    contentDescription = stringResource(R.string.groupchat_pending_image),
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(MuseShapes.semiLarge),
                )
                io.zer0.muse.ui.common.media.AttachmentRemoveButton(
                    contentDescription = stringResource(R.string.common_delete),
                    onClick = { onRemove(image) },
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
    }
}

/**
 * 成员列表对话框。
 */
@Composable
internal fun MembersDialog(memberNames: List<String>, memberCount: Int, onDismiss: () -> Unit) {
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.groupchat_members_title, memberCount),
        content = {
            if (memberNames.isEmpty()) {
                Text(
                    text = stringResource(R.string.groupchat_no_members),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    memberNames.forEach { name ->
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmText = stringResource(R.string.common_close),
        onConfirm = onDismiss,
        dismissText = null,
    )
}

/**
 * v1.97: 编辑群聊对话框 — 改名 + 改成员。
 *
 * 按 [CreateGroupChatDialog] 的样式,预填当前群聊名与已选成员,
 * 保存时调用 [GroupChatViewModel.updateChat]。
 *
 * @param initialName 当前群聊名(预填)
 * @param assistants 全部助手列表(成员候选)
 * @param initialMemberIds 当前成员 id 列表(预选)
 * @param onDismiss 关闭回调
 * @param onConfirm 确认回调(newName, newMemberIds)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditGroupChatDialog(
    // v1.0.74 fix (前端审计 6.3): 稳定 key — 用 chatId 而非 initialMemberIds,
    // 否则群聊元数据 Flow 刷新时 key 变化重置用户编辑中的成员/模式选择。
    dialogKey: String,
    initialName: String,
    assistants: List<AssistantEntity>,
    initialMemberIds: List<String>,
    initialDiscussionMode: String = "round_robin",
    initialAutoMaxRounds: Int = 5,
    initialHostId: String? = null,
    initialReplyLengthMode: String = "standard",
    initialObserverIds: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onConfirm: (
        newName: String,
        newMemberIds: List<String>,
        newDiscussionMode: String,
        newAutoMaxRounds: Int,
        newHostId: String?,
        newReplyLengthMode: String,
        newObserverIds: List<String>,
    ) -> Unit,
) {
    // v1.97: 用 rememberSaveable 持久化编辑中的状态,旋转屏不丢
    // v1.0.74 fix (前端审计 6.3): key 统一用 dialogKey(chatId),避免元数据刷新重置编辑
    var name by rememberSaveable(dialogKey) { mutableStateOf(initialName) }
    var selectedMemberIds by rememberSaveable(dialogKey) {
        mutableStateOf(initialMemberIds.toSet())
    }
    var showErrors by rememberSaveable(dialogKey) { mutableStateOf(false) }
    // v2.x: 讨论模式状态
    var discussionMode by rememberSaveable(dialogKey) { mutableStateOf(initialDiscussionMode) }
    var autoMaxRounds by rememberSaveable(dialogKey) { mutableStateOf(initialAutoMaxRounds) }
    var hostId by rememberSaveable(dialogKey) { mutableStateOf(initialHostId ?: "") }
    // v2.x: 发言长度档位状态(brief/standard/detailed)
    var replyLengthMode by rememberSaveable(dialogKey) { mutableStateOf(initialReplyLengthMode) }
    // v2.x: 观察者成员(不参与默认轮转发言)
    var observerIds by rememberSaveable(dialogKey) { mutableStateOf(initialObserverIds.toSet()) }

    val maxNameLength = 30
    val nameError = showErrors && name.isBlank()
    val memberError = showErrors && selectedMemberIds.isEmpty()

    // 讨论模式列表
    val modeOptions = listOf(
        "round_robin" to R.string.groupchat_mode_round_robin,
        "auto" to R.string.groupchat_mode_auto,
        "debate" to R.string.groupchat_mode_debate,
        "host" to R.string.groupchat_mode_host,
    )
    val modeDescMap = mapOf(
        "round_robin" to R.string.groupchat_mode_round_robin_desc,
        "auto" to R.string.groupchat_mode_auto_desc,
        "debate" to R.string.groupchat_mode_debate_desc,
        "host" to R.string.groupchat_mode_host_desc,
    )

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.groupchat_edit_title),
        content = {
            // 群聊名输入框
            MuseTextField(
                value = name,
                onValueChange = { newName ->
                    showErrors = false
                    name = newName.take(maxNameLength)
                },
                label = { Text(stringResource(R.string.groupchat_edit_name_hint)) },
                singleLine = true,
                isError = nameError,
                supportingText = if (nameError) {
                    { Text(stringResource(R.string.groupchat_name_required), color = MaterialTheme.colorScheme.error) }
                } else {
                    { Text("${name.length}/$maxNameLength") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))

            // 成员选择
            Text(
                text = stringResource(R.string.groupchat_select_members),
                style = MaterialTheme.typography.labelMedium,
                color = if (memberError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.outline
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            if (assistants.isEmpty()) {
                Text(
                    text = stringResource(R.string.groupchat_no_assistants),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp),
                ) {
                    items(assistants, key = { it.id }) { assistant ->
                        val selected = assistant.id in selectedMemberIds
                        MuseChip(
                            selected = selected,
                            onClick = {
                                showErrors = false
                                selectedMemberIds = if (selected) {
                                    selectedMemberIds - assistant.id
                                } else {
                                    selectedMemberIds + assistant.id
                                }
                            },
                            label = assistant.name,
                        )
                    }
                }
            }
            // v2.x: 观察者成员 — 从已选成员中指定,不参与默认轮转发言
            val selectedAssistants = assistants.filter { it.id in selectedMemberIds }
            if (selectedAssistants.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.groupchat_observers),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp),
                ) {
                    items(selectedAssistants, key = { "obs_${it.id}" }) { assistant ->
                        val isObserver = assistant.id in observerIds
                        MuseChip(
                            selected = isObserver,
                            onClick = {
                                observerIds = if (isObserver) {
                                    observerIds - assistant.id
                                } else {
                                    observerIds + assistant.id
                                }
                            },
                            label = assistant.name,
                        )
                    }
                }
            }
            if (memberError) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.groupchat_edit_member_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = pluralStringResource(R.plurals.groupchat_selected_members, selectedMemberIds.size, selectedMemberIds.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth(),
            )

            // ── v2.x: 讨论模式选择 ──
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.groupchat_mode_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 2.dp),
            ) {
                items(modeOptions, key = { it.first }) { (mode, labelRes) ->
                    MuseChip(
                        selected = discussionMode == mode,
                        onClick = { discussionMode = mode },
                        label = stringResource(labelRes),
                    )
                }
            }
            // 当前模式描述
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(modeDescMap[discussionMode] ?: R.string.groupchat_mode_round_robin_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )

            // ── v2.x: 发言长度档位(简短/标准/详尽) ──
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.groupchat_length_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "brief" to R.string.groupchat_length_brief,
                    "standard" to R.string.groupchat_length_standard,
                    "detailed" to R.string.groupchat_length_detailed,
                ).forEach { (lengthMode, labelRes) ->
                    MuseChip(
                        selected = replyLengthMode == lengthMode,
                        onClick = { replyLengthMode = lengthMode },
                        label = stringResource(labelRes),
                    )
                }
            }

            // Auto 模式:最大轮数滑块
            if (discussionMode == "auto") {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.groupchat_mode_auto_rounds),
                        style = MaterialTheme.typography.bodyMedium,
                        // LAYOUT-01: 设置项名称取剩余宽度 + 限行,右侧只保留档位数值,
                        // 防止放大字号/窄窗口下名称被数值挤成一列。
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "$autoMaxRounds",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                        // LAYOUT-01: 数值列限 1 行。
                        maxLines = 1,
                    )
                }
                MuseSlider(
                    value = autoMaxRounds.toFloat(),
                    onValueChange = { autoMaxRounds = it.toInt().coerceIn(1, 20) },
                    valueRange = 1f..20f,
                    steps = 18,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // 主持人模式:选择主持人
            if (discussionMode == "host") {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.groupchat_mode_select_host),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp),
                ) {
                    // "不选"选项
                    item(key = "no_host") {
                        MuseChip(
                            selected = hostId.isBlank(),
                            onClick = { hostId = "" },
                            label = stringResource(R.string.groupchat_mode_no_host),
                        )
                    }
                    // 只能选已选成员做主持人
                    items(assistants.filter { it.id in selectedMemberIds }, key = { it.id }) { assistant ->
                        MuseChip(
                            selected = hostId == assistant.id,
                            onClick = { hostId = assistant.id },
                            label = assistant.name,
                        )
                    }
                }
            }
        },
        confirmText = stringResource(R.string.common_save),
        onConfirm = {
            val trimmedName = name.trim()
            if (trimmedName.isNotBlank() && selectedMemberIds.isNotEmpty()) {
                onConfirm(
                    trimmedName,
                    selectedMemberIds.toList(),
                    discussionMode,
                    autoMaxRounds,
                    hostId.ifBlank { null },
                    replyLengthMode,
                    observerIds.toList(),
                )
            } else {
                showErrors = true
            }
        },
        dismissText = stringResource(R.string.common_cancel),
        onDismiss = onDismiss,
    )
}

/**
 * H5: 把 Bitmap 编码为 base64 字符串。
 *
 * 用 JPEG 递减 quality 压缩,直到 base64 字符串不超过 2MB。
 * 最终仍超过 2MB 则抛异常,提示用户选择较小图片。
 */
internal fun readImageBytes(context: android.content.Context, uri: Uri): ByteArray {
    val stream = context.contentResolver.openInputStream(uri)
    if (stream != null) return stream.use { it.readBytes() }
    val fd = context.contentResolver.openFileDescriptor(uri, "r")
        ?: throw IllegalStateException("无法读取图片")
    return fd.use { java.io.FileInputStream(it.fileDescriptor).use { fis -> fis.readBytes() } }
}

internal fun encodeBitmapToBase64(bitmap: Bitmap): String {
    val maxBase64Length = 2 * 1024 * 1024 // 2MB(base64 字符串长度上限)
    val baos = ByteArrayOutputStream()
    var quality = 90
    var base64: String
    do {
        baos.reset()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        base64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.DEFAULT)
        if (base64.length > maxBase64Length) {
            quality -= 10
        } else {
            break
        }
    } while (quality >= 20)

    if (base64.length > maxBase64Length) {
        throw IllegalStateException("图片过大,请选择较小图片")
    }
    return base64
}

/** v1.0.72: 群聊加号菜单媒体卡(相机/授权占位)。 */
@Composable
internal fun GroupMediaCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMediumStrong,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

/** v1.0.72: 相机实时取景预览(CameraX,复用单聊加号菜单实现)。 */
@Composable
internal fun CameraLivePreviewBox(modifier: Modifier, onTap: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnTap by rememberUpdatedState(onTap)
    // 持有 provider 引用;面板离开组合(onRelease)时解绑,避免相机在菜单关闭后仍绑定
    // 在 RESUMED Activity 上(指示灯常亮/耗电/阻塞其他相机应用)。
    val cameraProviderHolder = remember { arrayOfNulls<ProcessCameraProvider>(1) }
    // 2026-11-20(P0-2):与单聊加号菜单同修 —— 相机不可用时不留纯黑方块,
    // 退回带图标与文字的入口卡片(点击行为不变)。
    var previewUnavailable by remember { mutableStateOf(false) }

    if (previewUnavailable) {
        Surface(
            shape = MuseShapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f),
            modifier = modifier.clickable { currentOnTap() },
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = MuseIcons.camera,
                    contentDescription = stringResource(R.string.chat_tool_camera),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(MuseIconSizes.iconLarge),
                )
                Spacer(Modifier.height(MusePaddings.auxGap))
                Text(
                    text = stringResource(R.string.chat_tool_camera),
                    style = MaterialTheme.typography.bodyMediumStrong,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        return
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                setOnClickListener { currentOnTap() }
            }
            runCatching {
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    runCatching {
                        val provider = providerFuture.get()
                        cameraProviderHolder[0] = provider
                        val hasBack = provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                        val hasFront = provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                        if (!hasBack && !hasFront) {
                            previewUnavailable = true
                            return@runCatching
                        }
                        val selector =
                            if (hasBack) {
                                CameraSelector.DEFAULT_BACK_CAMERA
                            } else {
                                CameraSelector.DEFAULT_FRONT_CAMERA
                            }
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, selector, preview)
                    }.onFailure { e ->
                        Logger.w("GroupChatToolSheet", "相机预览绑定失败,降级为图标卡片", e)
                        previewUnavailable = true
                    }
                }, ContextCompat.getMainExecutor(ctx))
            }.onFailure { e ->
                Logger.w("GroupChatToolSheet", "相机预览初始化失败,降级为图标卡片", e)
                previewUnavailable = true
            }
            previewView
        },
        onRelease = {
            cameraProviderHolder[0]?.unbindAll()
            cameraProviderHolder[0] = null
        },
        modifier = modifier,
    )
}

/** v1.0.72: 查询系统相册最近图片。 */
internal fun queryRecentGalleryImages(context: Context, maxCount: Int): List<Uri> {
    return runCatching {
        val uris = mutableListOf<Uri>()
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            var count = 0
            while (cursor.moveToNext() && count < maxCount) {
                val id = cursor.getLong(idColumn)
                uris.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id))
                count++
            }
        }
        uris
    }.onFailure { e -> Logger.w("GroupChatToolSheet", "查询相册失败", e) }.getOrDefault(emptyList())
}
