package io.zer0.muse.miniphone.ui.chat

import android.content.ContentResolver
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.zer0.muse.miniphone.ui.icons.MiniPhoneIcons
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import java.io.File

/**
 * 图片气泡:一张圆角图,最大边长 [ImageMaxEdge],按原图比例显示。
 *
 * 加载中先占一块底色,不把行高从 0 撑开再跳;路径失效(文件被删、uri 读不到)画破图,
 * 而不是留一块永远转圈的空白 —— 聊天记录里「这里曾经有一张图」比空白更接近事实。
 *
 * @param onLongPress 长按弹操作菜单
 */
@Composable
internal fun ChatImageBubble(message: ChatMessageUi, maxWidth: Dp, onOpen: (String) -> Unit, onLongPress: () -> Unit = {}) {
    val colors = MiniPhoneTheme.colors
    val density = LocalDensity.current
    val path = message.mediaUri.orEmpty()
    // 本地路径却已然不存在:直接画破图,不必让加载器白跑一趟
    val missing = path.isEmpty() || isMissingLocalFile(path)
    var loadFailed by remember(path) { mutableStateOf(false) }
    var natural by remember(path) { mutableStateOf(IntSize.Zero) }
    val broken = missing || loadFailed
    val maxEdge = minOf(maxWidth, ImageMaxEdge)
    val size = if (natural == IntSize.Zero) PlaceholderSize else fitInside(natural, maxEdge, density)
    Box(
        modifier = Modifier
            .testTag(MiniPhoneChatTags.image(message.id))
            .clip(ImageShape)
            .background(colors.divider)
            .size(size)
            // 能不能点开只看路径在不在:缩略图解码失败不代表原图就打不开
            .combinedClickable(
                onClick = { if (!missing) onOpen(path) },
                onLongClick = onLongPress,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (broken) {
            BrokenImageContent(messageId = message.id)
        } else {
            AsyncImage(
                model = path,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onSuccess = { state -> natural = state.result.drawable.intrinsicSize() },
                onError = { loadFailed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 全屏看图:黑底、图片居中、双指缩放、点任意处关闭。
 *
 * 缩放只做等比:放大后不再缩放到底图以外,缩小到 1 倍即回到居中 —— 一次拖动留下的
 * 偏移不会在缩小之后把图推到屏幕外。
 */
@Composable
internal fun MiniPhoneImageViewer(uri: String, onDismiss: () -> Unit) {
    var scale by remember(uri) { mutableFloatStateOf(MIN_ZOOM) }
    var offset by remember(uri) { mutableStateOf(Offset.Zero) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag(MiniPhoneChatTags.IMAGE_VIEWER)
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    offset = if (scale <= MIN_ZOOM) Offset.Zero else offset + pan
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = uri,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}

/**
 * 图集查看器:一次看同一组图,左右滑动切换,顶部带「3 / 9」。
 *
 * [initialIndex] 是零基下标:点第几张就从第几张开始。手势与单图查看器同一套 -
 * 点任意处关闭;多一个下滑关闭 —— 横向手势归翻页,竖向留给退出,两者不抢。
 * 翻页容器自己处理左右滑动,所以这里不接管横向拖动。
 */
@Composable
internal fun MiniPhoneGalleryViewer(uris: List<String>, initialIndex: Int, onDismiss: () -> Unit) {
    if (uris.isEmpty()) return
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, uris.lastIndex)) { uris.size }
    var draggedDown by remember { mutableFloatStateOf(0f) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag(MiniPhoneChatTags.IMAGE_GALLERY)
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { draggedDown = 0f },
                    onDragEnd = {
                        if (draggedDown > GALLERY_DISMISS_DRAG_PX) onDismiss()
                        draggedDown = 0f
                    },
                    onDragCancel = { draggedDown = 0f },
                ) { _, dragAmount ->
                    draggedDown += dragAmount
                }
            },
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            AsyncImage(
                model = uris[page],
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            text = galleryCounterOf(pagerState.currentPage, uris.size),
            style = MiniPhoneTheme.typography.title.copy(color = Color.White),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = MiniPhoneTheme.dimens.statusBarHeight + GalleryCounterGap)
                .testTag(MiniPhoneChatTags.IMAGE_GALLERY_COUNTER),
        )
    }
}

/** 计数文本:当前第几张(一基)与总数。 */
internal fun galleryCounterOf(pageIndex: Int, pageCount: Int): String = "${pageIndex + 1} / $pageCount"

/** 破图:自绘图标 + 一句说明,和外框同一尺寸,列表不会因为它换行。 */
@Composable
private fun BrokenImageContent(messageId: String) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Column(
        modifier = Modifier
            .testTag(MiniPhoneChatTags.imageBroken(messageId))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = MiniPhoneIcons.BrokenImage,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(BrokenIconSize),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = BROKEN_IMAGE_TEXT,
            style = typography.timestamp.copy(color = colors.textSecondary),
            textAlign = TextAlign.Center,
        )
    }
}

/** 本地路径但文件已经不在了;`content://` 这类由外部提供的 uri 交给加载器去判断。 */
private fun isMissingLocalFile(path: String): Boolean {
    val parsed = Uri.parse(path)
    val local = parsed.scheme == null || parsed.scheme == ContentResolver.SCHEME_FILE
    return local && !File(parsed.path ?: path).exists()
}

/** 按原图比例缩进 [maxEdge] 的方框;小图不放大,填满方框反而糊。 */
private fun fitInside(natural: IntSize, maxEdge: Dp, density: Density): DpSize = with(density) {
    val width = natural.width.toDp()
    val height = natural.height.toDp()
    val ratio = minOf(1f, maxEdge / maxOf(width, height))
    DpSize(width * ratio, height * ratio)
}

private fun Drawable?.intrinsicSize(): IntSize {
    val width = this?.intrinsicWidth ?: 0
    val height = this?.intrinsicHeight ?: 0
    return if (width > 0 && height > 0) IntSize(width, height) else IntSize.Zero
}

private val ImageShape = RoundedCornerShape(8.dp)
private val ImageMaxEdge = 200.dp
private val PlaceholderSize = DpSize(132.dp, 132.dp)
private val BrokenIconSize = 24.dp

/** 计数与屏幕上沿的距离之外再留的一点空隙。 */
private val GalleryCounterGap = 8.dp

/** 下滑超过这个位移就算「要关掉」:轻扫不会被误判成退出。 */
private const val GALLERY_DISMISS_DRAG_PX = 120f

private const val MIN_ZOOM: Float = 1f
private const val MAX_ZOOM: Float = 5f
private const val BROKEN_IMAGE_TEXT: String = "图片无法显示"
