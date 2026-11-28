package io.zer0.muse.automation.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.zer0.muse.R
import io.zer0.muse.automation.AutomationRuntime
import io.zer0.muse.automation.vdisplay.VirtualDisplayInputCommand
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MuseButtonSize
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * 虚拟屏预览 + 触摸/按键/文本接管面板。
 */
@Composable
fun VirtualDisplayHandoffPanel(
    displayId: Int,
    packageName: String,
    screenshot: suspend () -> ByteArray?,
    tap: suspend (x: Int, y: Int) -> Boolean,
    /** 把目标应用交回主屏。返回 false 表示服务不可用或应用起不来。 */
    bringToMain: suspend () -> Boolean,
    onClose: () -> Unit,
    swipe: suspend (x1: Int, y1: Int, x2: Int, y2: Int) -> Boolean = { x1, y1, x2, y2 ->
        AutomationRuntime.swipe(displayId, x1, y1, x2, y2)
    },
    keyEvent: suspend (String) -> Boolean = { key ->
        AutomationRuntime.keyEvent(displayId, key)
    },
    inputText: suspend (String) -> Boolean = { text ->
        AutomationRuntime.inputText(displayId, text)
    },
) {
    var frame by remember(displayId) { mutableStateOf<Bitmap?>(null) }
    var failures by remember(displayId) { mutableIntStateOf(0) }
    var lastAction by remember(displayId) { mutableStateOf<String?>(null) }
    var actionResult by remember(displayId) { mutableStateOf<String?>(null) }
    var textDraft by remember(displayId) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    DisposableEffect(displayId) {
        onDispose {
            frame?.recycle()
            frame = null
        }
    }

    LaunchedEffect(displayId) {
        while (true) {
            val bitmap = withContext(Dispatchers.Default) {
                val bytes = runCatching { screenshot() }.getOrNull() ?: return@withContext null
                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            }
            if (bitmap != null) {
                val previous = frame
                frame = bitmap
                if (previous != null && previous !== bitmap && !previous.isRecycled) {
                    previous.recycle()
                }
                failures = 0
            } else {
                failures += 1
            }
            delay(PREVIEW_INTERVAL_MS)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.screen),
        verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
    ) {
        Text(
            text = if (packageName.isBlank()) {
                stringResource(R.string.automation_vdisplay_title_no_pkg, displayId)
            } else {
                stringResource(R.string.automation_vdisplay_title_with_pkg, displayId, packageName)
            },
            style = MaterialTheme.typography.titleSmall,
        )
        val current = frame
        if (current == null) {
            Text(
                text = if (failures > 3) {
                    stringResource(R.string.automation_vdisplay_disconnected)
                } else {
                    stringResource(R.string.automation_vdisplay_loading)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val image = remember(current) { current.asImageBitmap() }
            var dragStart by remember(displayId) { mutableStateOf<Offset?>(null) }
            var dragEnd by remember(displayId) { mutableStateOf<Offset?>(null) }
            Image(
                bitmap = image,
                contentDescription = stringResource(R.string.automation_vdisplay_content_desc),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(displayId, current) {
                        detectTapGestures { offset ->
                            val (x, y) = mapTapToDisplay(
                                tap = offset,
                                viewSize = Size(size.width.toFloat(), size.height.toFloat()),
                                displaySize = Size(current.width.toFloat(), current.height.toFloat()),
                            )
                            lastAction = context.getString(R.string.automation_vdisplay_last_tap, x, y)
                            scope.launch { runCatching { tap(x, y) } }
                        }
                    }
                    .pointerInput(displayId, current) {
                        detectDragGestures(
                            onDragStart = { start ->
                                dragStart = start
                                dragEnd = start
                            },
                            onDrag = { change, _ ->
                                dragEnd = change.position
                            },
                            onDragCancel = {
                                dragStart = null
                                dragEnd = null
                            },
                            onDragEnd = {
                                val start = dragStart
                                val end = dragEnd
                                dragStart = null
                                dragEnd = null
                                if (start != null && end != null) {
                                    val viewSize = Size(size.width.toFloat(), size.height.toFloat())
                                    val dispSize = Size(current.width.toFloat(), current.height.toFloat())
                                    val (x1, y1) = mapTapToDisplay(start, viewSize, dispSize)
                                    val (x2, y2) = mapTapToDisplay(end, viewSize, dispSize)
                                    if (kotlin.math.hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()) >= MIN_SWIPE_DISTANCE_PX) {
                                        lastAction = context.getString(R.string.automation_vdisplay_last_swipe)
                                        scope.launch { runCatching { swipe(x1, y1, x2, y2) } }
                                    }
                                }
                            },
                        )
                    },
            )
            Text(
                text = lastAction ?: stringResource(R.string.automation_vdisplay_tap_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(MuseSpacing.smTight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MuseCapsuleButton(
                    text = stringResource(R.string.automation_vdisplay_key_back),
                    onClick = { scope.launch { runCatching { keyEvent("KEYCODE_BACK") } } },
                    variant = IosCapsuleButtonVariant.Secondary,
                    size = MuseButtonSize.Compact,
                    fillWidth = false,
                )
                MuseCapsuleButton(
                    text = stringResource(R.string.automation_vdisplay_key_home),
                    onClick = { scope.launch { runCatching { keyEvent("KEYCODE_HOME") } } },
                    variant = IosCapsuleButtonVariant.Secondary,
                    size = MuseButtonSize.Compact,
                    fillWidth = false,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(MuseSpacing.smTight),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                MuseTextField(
                    value = textDraft,
                    onValueChange = { textDraft = it },
                    placeholder = { Text(stringResource(R.string.automation_vdisplay_input_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                MuseCapsuleButton(
                    text = stringResource(R.string.automation_vdisplay_input_send),
                    onClick = {
                        val text = textDraft.trim()
                        if (text.isNotEmpty()) {
                            val rejection = VirtualDisplayInputCommand.textRejectionReason(text)
                            if (rejection != null) {
                                actionResult = context.getString(R.string.automation_vdisplay_input_rejected_non_ascii)
                            } else {
                                scope.launch {
                                    if (runCatching { inputText(text) }.getOrDefault(false)) {
                                        textDraft = ""
                                        actionResult = null
                                    }
                                }
                            }
                        }
                    },
                    leadingIcon = MuseIcons.send,
                    size = MuseButtonSize.Compact,
                    fillWidth = false,
                )
            }
        }
        // 能力边界必须写在按钮旁边：交回主屏**不保证**页面与输入状态还在。
        Text(
            text = stringResource(R.string.automation_handoff_bring_main_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        actionResult?.let { text ->
            Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(MuseSpacing.smTight),
        ) {
            MuseCapsuleButton(
                text = stringResource(R.string.automation_handoff_bring_main),
                onClick = {
                    scope.launch {
                        val ok = runCatching { bringToMain() }.getOrDefault(false)
                        actionResult = context.getString(
                            if (ok) {
                                R.string.automation_handoff_bring_main_ok
                            } else {
                                R.string.automation_handoff_bring_main_failed
                            },
                        )
                    }
                },
                fillWidth = false,
            )
            MuseCapsuleButton(
                text = stringResource(R.string.automation_handoff_close_destroy),
                onClick = onClose,
                variant = IosCapsuleButtonVariant.Secondary,
                fillWidth = false,
            )
        }
    }
}

/**
 * 把预览上的点击换算成虚拟屏坐标。
 *
 * `ContentScale.Fit` 会等比缩放并居中，两侧留黑边：直接拿点击坐标当屏幕坐标，
 * 宽高比不一致时会点偏，越靠边缘偏得越多。这里按 Fit 的实际渲染矩形反算并夹到屏内。
 */
internal fun mapTapToDisplay(tap: Offset, viewSize: Size, displaySize: Size): Pair<Int, Int> {
    if (viewSize.width <= 0f || viewSize.height <= 0f || displaySize.width <= 0f || displaySize.height <= 0f) {
        return 0 to 0
    }
    val scale = min(viewSize.width / displaySize.width, viewSize.height / displaySize.height)
    val renderedWidth = displaySize.width * scale
    val renderedHeight = displaySize.height * scale
    val offsetX = (viewSize.width - renderedWidth) / 2f
    val offsetY = (viewSize.height - renderedHeight) / 2f
    val x = ((tap.x - offsetX) / scale).toInt().coerceIn(0, (displaySize.width - 1).toInt())
    val y = ((tap.y - offsetY) / scale).toInt().coerceIn(0, (displaySize.height - 1).toInt())
    return x to y
}

/** 预览轮询间隔：2.5 帧/秒 —— 够看清"在发生什么"，又不至于把设备压垮。 */
private const val PREVIEW_INTERVAL_MS = 400L
private const val MIN_SWIPE_DISTANCE_PX = 24.0
