package io.zer0.muse.ui.agentbot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.computenode.BrowserFrame
import io.zer0.muse.computenode.BrowserSession
import io.zer0.muse.computenode.MuseNodeClient
import io.zer0.muse.data.computenode.RemoteComputeNodeEntity
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseBorderWidth
import io.zer0.muse.ui.theme.MuseButtonSize
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.ui.theme.statusColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * 远程浏览器观战状态。
 *
 * frame 只保留最新一帧：浏览器按 1~2 fps 推流，历史帧没有回看价值，
 * 留着只会把内存堆高。
 */
data class RemoteBrowserLiveState(
    val nodeId: String = "",
    val taskId: String = "",
    val sid: String = "",
    val url: String = "",
    val title: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val frame: Bitmap? = null,
    val loading: Boolean = false,
    val connected: Boolean = false,
    val takeover: Boolean = false,
    val error: String = "",
)

/**
 * 远程浏览器会话控制。
 *
 * 负责把 [MuseNodeClient.openBrowser] 的 [BrowserSession] 生命周期收敛到一处：
 * 打开、收帧、点按、输入、交回与关闭都由这里转发，界面只读 [state]。
 * 这样屏幕旋转或组合重建不会重复建立 WebSocket。
 */
class RemoteBrowserLiveController(
    private val client: MuseNodeClient,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<RemoteBrowserLiveState?>(null)
    val state: StateFlow<RemoteBrowserLiveState?> = _state.asStateFlow()

    private var session: BrowserSession? = null
    private var openJob: Job? = null

    /** 打开一个新会话；已有会话会先关闭，避免两个 WS 同时推帧。 */
    fun open(node: RemoteComputeNodeEntity, taskId: String, url: String = DEFAULT_BROWSER_URL) {
        close()
        _state.value = RemoteBrowserLiveState(
            nodeId = node.nodeId,
            taskId = taskId,
            url = url,
            loading = true,
        )
        openJob = scope.launch {
            try {
                val opened = client.openBrowser(node = node, url = url)
                session = opened
                _state.update { current ->
                    current?.copy(
                        sid = opened.sid,
                        url = opened.info.url.ifBlank { url },
                        title = opened.info.title,
                        width = opened.info.width,
                        height = opened.info.height,
                        loading = false,
                        connected = true,
                        error = "",
                    )
                }
                opened.frames.collect { frame -> applyFrame(opened.sid, frame) }
                _state.update { current ->
                    if (current?.sid == opened.sid) current.copy(connected = false, loading = false) else current
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "remote browser open failed: " + e.javaClass.simpleName)
                _state.update { current ->
                    current?.copy(
                        loading = false,
                        connected = false,
                        error = e.message.orEmpty().ifBlank { e.javaClass.simpleName },
                    )
                }
            }
        }
    }

    /** 没有可用节点或服务时也保留一张错误态，让面板说清楚原因。 */
    fun showError(taskId: String, message: String) {
        close()
        _state.value = RemoteBrowserLiveState(taskId = taskId, error = message)
    }

    fun tap(x: Float, y: Float) {
        if (_state.value?.takeover == true) session?.tap(x, y)
    }

    fun type(text: String) {
        if (_state.value?.takeover == true) session?.type(text)
    }

    /** 切换人工接管；交回时发送 handoff_done 让助手继续操作。 */
    fun setTakeover(takeover: Boolean) {
        val current = _state.value ?: return
        if (current.takeover == takeover) return
        _state.value = current.copy(takeover = takeover)
        if (!takeover) session?.handoffDone()
    }

    fun close() {
        openJob?.cancel()
        openJob = null
        session?.close()
        session = null
        _state.value = null
    }

    private suspend fun applyFrame(sid: String, frame: BrowserFrame) {
        if (_state.value?.sid != sid) return
        when (frame) {
            is BrowserFrame.Frame -> {
                val bitmap = decodeJpeg(frame.jpegBase64)
                _state.update { current ->
                    if (current?.sid != sid) {
                        current
                    } else {
                        current.copy(
                            url = frame.url.ifBlank { current.url },
                            title = frame.title.ifBlank { current.title },
                            width = frame.width.takeIf { it > 0 } ?: current.width,
                            height = frame.height.takeIf { it > 0 } ?: current.height,
                            frame = bitmap ?: current.frame,
                            loading = false,
                            connected = true,
                            error = "",
                        )
                    }
                }
            }
            is BrowserFrame.Closed -> _state.update { current ->
                if (current?.sid != sid) current else current.copy(connected = false, loading = false, error = frame.reason)
            }
            is BrowserFrame.Error -> _state.update { current ->
                if (current?.sid != sid) current else current.copy(connected = false, loading = false, error = frame.message)
            }
            is BrowserFrame.Ack -> Unit
        }
    }

    private suspend fun decodeJpeg(base64: String): Bitmap? = withContext(Dispatchers.Default) {
        try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Logger.w(TAG, "remote browser frame decode failed: " + e.javaClass.simpleName)
            null
        }
    }

    private companion object {
        private const val TAG = "RemoteBrowserLive"
        private const val DEFAULT_BROWSER_URL = "about:blank"
    }
}

/**
 * 远程浏览器实时观战与人工接管面板。
 *
 * 画面按 ContentScale.Fit 显示，点按坐标由 [mapRemoteBrowserTap] 反算回服务器
 * 页面坐标系；只有进入接管态后才允许点按与输入，避免看的时候误触。
 */
@Composable
fun RemoteBrowserLivePanel(
    state: RemoteBrowserLiveState,
    onTap: (Float, Float) -> Unit,
    onType: (String) -> Unit,
    onTakeoverChange: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var textOpen by rememberSaveable(state.sid) { mutableStateOf(false) }
    var textDraft by rememberSaveable(state.sid) { mutableStateOf("") }

    LaunchedEffect(state.takeover) {
        if (!state.takeover) textOpen = false
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Icon(
                imageVector = MuseIcons.browser,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.title.ifBlank { stringResource(R.string.settings_screen_browser) },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.url.ifBlank { "about:blank" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MuseTactileButton(
                icon = MuseIcons.x,
                contentDescription = stringResource(R.string.common_close),
                onClick = onClose,
                compact = true,
            )
        }

        Surface(
            shape = MuseShapes.semiLarge,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = MuseAlpha.mutedSurface),
            border = androidx.compose.foundation.BorderStroke(
                width = MuseBorderWidth.hairline,
                color = MaterialTheme.colorScheme.outlineVariant,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            BrowserViewport(
                state = state,
                onTap = onTap,
            )
        }

        if (state.error.isNotBlank()) {
            Text(
                text = stringResource(R.string.compute_node_error_generic, state.error),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.statusColors.error,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            MuseCapsuleButton(
                text = if (state.takeover) {
                    stringResource(R.string.agent_bot_continue)
                } else {
                    stringResource(R.string.agent_hub_computer_vdisplay_takeover)
                },
                onClick = { onTakeoverChange(!state.takeover) },
                leadingIcon = MuseIcons.handFinger,
                variant = if (state.takeover) IosCapsuleButtonVariant.Secondary else IosCapsuleButtonVariant.Primary,
                fillWidth = false,
                size = MuseButtonSize.Compact,
                enabled = state.connected,
            )
            MuseCapsuleButton(
                text = stringResource(R.string.agent_hub_mission_steer),
                onClick = { textOpen = !textOpen },
                leadingIcon = MuseIcons.edit,
                variant = IosCapsuleButtonVariant.Secondary,
                fillWidth = false,
                size = MuseButtonSize.Compact,
                enabled = state.connected && state.takeover,
            )
        }

        if (textOpen && state.takeover) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                MuseTextField(
                    value = textDraft,
                    onValueChange = { textDraft = it },
                    placeholder = { Text(stringResource(R.string.agent_hub_mission_steer_hint)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                MuseCapsuleButton(
                    text = stringResource(R.string.agent_hub_mission_steer_send),
                    onClick = {
                        val value = textDraft.trim()
                        if (value.isNotBlank()) {
                            onType(value)
                            textDraft = ""
                        }
                    },
                    enabled = textDraft.isNotBlank(),
                    fillWidth = false,
                    size = MuseButtonSize.Compact,
                )
            }
        }
    }
}

@Composable
private fun BrowserViewport(
    state: RemoteBrowserLiveState,
    onTap: (Float, Float) -> Unit,
) {
    val bitmap = state.frame
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MusePaddings.previewThumb, max = MusePaddings.dialogContentMaxHeight)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = MuseAlpha.mutedSurface)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            Image(
                bitmap = image,
                contentDescription = stringResource(R.string.settings_screen_browser),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(state.sid, state.takeover, bitmap) {
                        if (state.takeover) {
                            detectTapGestures { offset ->
                                val (x, y) = mapRemoteBrowserTap(
                                    tap = offset,
                                    viewSize = Size(size.width.toFloat(), size.height.toFloat()),
                                    frameSize = Size(bitmap.width.toFloat(), bitmap.height.toFloat()),
                                )
                                onTap(x, y)
                            }
                        }
                    },
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                Icon(
                    imageVector = MuseIcons.browser,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconLarge),
                )
                Text(
                    text = if (state.error.isNotBlank()) {
                        stringResource(R.string.compute_node_error_generic, state.error)
                    } else {
                        stringResource(R.string.compute_node_files_loading)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 远程浏览器任务卡里的紧凑缩略窗；点一下进入完整观战面板。 */
@Composable
fun RemoteBrowserThumbnailStrip(
    state: RemoteBrowserLiveState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MuseShapes.pill,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = MuseAlpha.statusTint),
        modifier = modifier
            .fillMaxWidth()
            .clip(MuseShapes.pill)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = MusePaddings.contentGap,
                vertical = MusePaddings.tightGap,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            val bitmap = state.frame
            if (bitmap != null) {
                val image = remember(bitmap) { bitmap.asImageBitmap() }
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(MusePaddings.previewThumb)
                        .clip(MuseShapes.small),
                )
            } else {
                Icon(
                    imageVector = MuseIcons.browser,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(MuseIconSizes.iconLarge),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.title.ifBlank { stringResource(R.string.settings_screen_browser) },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.url.ifBlank { "about:blank" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = MuseIcons.chevronRight,
                contentDescription = stringResource(R.string.agent_hub_mission_vdisplay_open_cd),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(MuseIconSizes.iconTiny),
            )
        }
    }
}

/**
 * 把预览上的点按换算成远程页面坐标。
 *
 * ContentScale.Fit 会等比缩放并居中，两侧可能留黑边；直接拿点击坐标当页面坐标
 * 会点偏，越靠边缘偏得越多。这里按实际渲染矩形反算并夹到页面范围内。
 */
internal fun mapRemoteBrowserTap(tap: Offset, viewSize: Size, frameSize: Size): Pair<Float, Float> {
    if (viewSize.width <= 0f || viewSize.height <= 0f || frameSize.width <= 0f || frameSize.height <= 0f) {
        return 0f to 0f
    }
    val scale = min(viewSize.width / frameSize.width, viewSize.height / frameSize.height)
    if (scale <= 0f) return 0f to 0f
    val renderedWidth = frameSize.width * scale
    val renderedHeight = frameSize.height * scale
    val offsetX = (viewSize.width - renderedWidth) / 2f
    val offsetY = (viewSize.height - renderedHeight) / 2f
    val x = ((tap.x - offsetX) / scale).coerceIn(0f, frameSize.width)
    val y = ((tap.y - offsetY) / scale).coerceIn(0f, frameSize.height)
    return x to y
}
