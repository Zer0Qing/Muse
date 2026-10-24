package io.zer0.muse.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import io.zer0.common.resultOf
import io.zer0.muse.data.sticker.StickerItem
import io.zer0.muse.data.sticker.StickerLibraryRepository
import io.zer0.muse.ui.common.media.FullScreenMediaViewer
import io.zer0.muse.ui.markdown.CardAction
import io.zer0.muse.ui.markdown.MarkdownText
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import org.koin.compose.koinInject

/**
 * v2.x: 消息流内的表情包卡片 —— 只渲染图片本身(无气泡/无文字/无边框)。
 *
 * 设计要点:
 *  - 分类容错解析:模型写错分类名时降级忽略(整个卡片不渲染,不占位不报错)
 *  - 种子随机:同一 [seed] 稳定选同一张(重进会话/重组不跳变)
 *  - 点击全屏查看(复用 [FullScreenMediaViewer],与设置页预览同一交互)
 *  - 动图(GIF/WebP)由全局 Coil ImageLoader 的 GifDecoder 自动播放
 */
@Composable
internal fun StickerMessageCard(category: String, seed: Long, modifier: Modifier = Modifier) {
    val repo: StickerLibraryRepository = koinInject()
    // 容错解析分类 + 种子选取;分类不存在/为空 → item 为 null → 不渲染
    val item by
        produceState<StickerItem?>(initialValue = null, category, seed) {
            value =
                resultOf {
                    val resolved = repo.resolveCategory(category)
                    if (resolved == null) {
                        null
                    } else {
                        repo.pickSticker(repo.snapshot(), resolved, seed)
                    }
                }.getOrNull()
        }
    val sticker = item ?: return
    val file =
        remember(sticker) {
            repo.getStickerFileByPath(sticker.relativePath).takeIf { it.exists() }
        } ?: return

    var previewing by remember { mutableStateOf(false) }
    val painter = rememberAsyncImagePainter(model = file)
    // v2.2.0: 修复 "Size is unspecified" 崩溃 — 旧注释假设 Unspecified 时宽高为 NaN,
    // 但部分 Compose 版本的 Size.width 访问器对 Unspecified 直接抛 IllegalStateException。
    // 用 runCatching + isSpecified 双重防护: 任何异常回退 1f(方形占位), 渲染不再中断。
    val ratio = runCatching {
        val size = painter.intrinsicSize
        if (size.isSpecified && size.width > 0f && size.height > 0f) {
            size.width / size.height
        } else {
            1f
        }
    }.getOrDefault(1f)
    Image(
        painter = painter,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier =
        modifier
            .widthIn(max = 160.dp)
            .heightIn(max = 200.dp)
            .aspectRatio(ratio.coerceIn(0.4f, 2.5f))
            .clip(MuseShapes.small)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { previewing = true },
    )
    if (previewing) {
        FullScreenMediaViewer(
            images = listOf("file://${file.absolutePath}"),
            initialIndex = 0,
            onDismiss = { previewing = false },
        )
    }
}

/**
 * v2.x: 带表情标记的 Markdown 正文。
 *
 * 把正文按 `[[sticker:分类名]]` 切成"文本段 + 表情段"逐段渲染;
 * 无标记时直通 [MarkdownText](行为与旧版完全一致,零额外开销);
 * 末尾未闭合标记由 [StickerMarkup.split] 吞掉,流式时不会露出半截文本。
 */
@Composable
internal fun StickerAwareMarkdownBody(
    text: String,
    stickerSeed: Long,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.onBackground,
    citationUrls: List<String> = emptyList(),
    isStreaming: Boolean = false,
    disableLinks: Boolean = false,
    onLongPressOutside: (() -> Unit)? = null,
    onHtmlPreview: (String) -> Unit = {},
    onCardAction: ((CardAction) -> Unit)? = null,
    /** v2.6.13: 卡片身份（聊天场景传消息 id）；必须直通到每段 MarkdownText。 */
    cardId: String? = null,
) {
    val segments = remember(text) { StickerMarkup.split(text) }
    val single = segments.singleOrNull()
    if (single is StickerMarkup.Segment.Text) {
        MarkdownText(
            text = single.text,
            modifier = modifier,
            style = style,
            color = color,
            citationUrls = citationUrls,
            isStreaming = isStreaming,
            disableLinks = disableLinks,
            onLongPressOutside = onLongPressOutside,
            onHtmlPreview = onHtmlPreview,
            onCardAction = onCardAction,
            cardId = cardId,
        )
        return
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        segments.forEachIndexed { idx, seg ->
            when (seg) {
                is StickerMarkup.Segment.Text ->
                    MarkdownText(
                        text = seg.text,
                        modifier = Modifier.fillMaxWidth(),
                        style = style,
                        color = color,
                        citationUrls = citationUrls,
                        isStreaming = isStreaming,
                        disableLinks = disableLinks,
                        onLongPressOutside = onLongPressOutside,
                        onHtmlPreview = onHtmlPreview,
                        onCardAction = onCardAction,
                        cardId = cardId,
                    )
                is StickerMarkup.Segment.Sticker ->
                    StickerMessageCard(
                        category = seg.category,
                        seed = stickerSeed + idx,
                    )
            }
        }
    }
}
