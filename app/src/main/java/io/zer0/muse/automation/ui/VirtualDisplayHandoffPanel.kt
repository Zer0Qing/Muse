package io.zer0.muse.automation.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * 虚拟屏预览 + 触摸接管（对比清单 P1-02 第二段）。
 *
 * 存在的意义：`ui_agent` 在虚拟屏上操作时用户看不见，任务结束后屏也被销毁。
 * 这个面板把画面轮询进来、把点击翻译成注入事件 —— 用户能看着它，也能自己上手。
 *
 * 三个刻意的取舍：
 *  1. **轮询而不是推流**：链路上只有"截图"是稳定能力，硬造推流会在弱设备上卡死；
 *     2.5 帧/秒够看清"在发生什么"，不够看电影 —— 但这不是看视频的场景。
 *  2. **点击必须按缩放换算**：预览等比缩放并居中，直接拿点击坐标当屏幕坐标会点偏，
 *     越靠边缘偏得越多（[mapTapToDisplay] 负责这段，单独可测）。
 *  3. **失败要说出来**：连续取不到画面就显示原因，而不是让用户对着空白等。
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
) {
    var frame by remember(displayId) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failures by remember(displayId) { mutableStateOf(0) }
    var lastTap by remember(displayId) { mutableStateOf<String?>(null) }
    var actionResult by remember(displayId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(displayId) {
        while (true) {
            val bytes = runCatching { screenshot() }.getOrNull()
            val bitmap = bytes?.let { raw ->
                runCatching { android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size) }.getOrNull()
            }
            if (bitmap != null) {
                frame = bitmap
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
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = if (packageName.isBlank()) "虚拟屏 #$displayId" else "虚拟屏 #$displayId · $packageName",
            style = MaterialTheme.typography.titleSmall,
        )
        val current = frame
        if (current == null) {
            Text(
                text = if (failures > 3) {
                    "取不到画面（虚拟屏服务可能已断开）。可以关闭后让助手重新执行一次。"
                } else {
                    "正在获取画面…"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val image = remember(current) { current.asImageBitmap() }
            Image(
                bitmap = image,
                contentDescription = "虚拟屏预览",
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
                            lastTap = "#$x,$y"
                            scope.launch { runCatching { tap(x, y) } }
                        }
                    },
            )
            Text(
                text = lastTap?.let { "已点击 $it（若界面没反应，可能该控件不接受注入）" } ?: "点击画面即可操作这块屏",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 能力边界必须写在按钮旁边：交回主屏**不保证**页面与输入状态还在。
        Text(
            text = androidx.compose.ui.res.stringResource(R.string.automation_handoff_bring_main_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        actionResult?.let { text ->
            Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        androidx.compose.foundation.layout.Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MuseCapsuleButton(
                text = androidx.compose.ui.res.stringResource(R.string.automation_handoff_bring_main),
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
                text = androidx.compose.ui.res.stringResource(R.string.automation_handoff_close_destroy),
                onClick = onClose,
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
