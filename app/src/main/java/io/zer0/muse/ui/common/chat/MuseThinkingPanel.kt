package io.zer0.muse.ui.common.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.animation.MuseInkGlyph
import io.zer0.muse.ui.common.animation.MuseThinkingGlyphSize
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.markdown.MarkdownText
import io.zer0.muse.ui.theme.LocalLiquidGlass
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseBorderWidth
import io.zer0.muse.ui.theme.MuseBubbleStyles
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.ui.theme.MuseSpringTier
import io.zer0.muse.ui.theme.glassBorder
import io.zer0.muse.ui.theme.glassEdgeHighlight
import io.zer0.muse.ui.theme.glassFakeSurfaceColor
import io.zer0.muse.ui.theme.pill
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 全应用统一的思考过程展示组件（单聊 / Agent / 群聊共用）。
 *
 * 三态演进：
 * 1. **思考进行中 · 默认收起**：标题栏展示呼吸动效 + 实时焦点 + 秒表，下方展示 3–4 行自动滚底的
 *    实时思考区间（左侧细竖线 + 顶部柔边淡出），直观呈现模型此刻在思考什么；
 * 2. **思考完毕 · 默认收起**：自动平滑收拢为简洁单行「已深度思考 · 用时 X 秒 ⌄」，不占用正文阅读空间；
 * 3. **点击展开**：平滑展开完整思考过程（支持选字复制与轻量 Markdown 排版）。
 */
@Composable
fun MuseThinkingPanel(
    reasoning: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    isThinkingActive: Boolean = false,
    reasoningStartedAtMs: Long? = null,
    reasoningDurationMs: Long? = null,
    processOpsCount: Int = 0,
    cacheKey: String? = null,
    hasBackgroundImage: Boolean = false,
    onLongPressOutside: (() -> Unit)? = null,
) {
    val reducedMotion = MuseMotion.isReducedMotion()
    val isMono = MuseBubbleStyles.isMonoBubbleTheme()
    val glassConfig = LocalLiquidGlass.current
    val useFakeGlass = glassConfig.enabled

    // 实时计时：仅在 isThinkingActive=true 时每秒推进；完成后优先采用已冻结的 reasoningDurationMs
    var liveElapsedSec by remember(cacheKey, reasoningStartedAtMs) {
        mutableIntStateOf(
            when {
                reasoningDurationMs != null && reasoningDurationMs > 0L ->
                    (reasoningDurationMs / 1_000.0).roundToInt().coerceAtLeast(1)
                reasoningStartedAtMs != null ->
                    ((System.currentTimeMillis() - reasoningStartedAtMs).coerceAtLeast(0L) / 1_000L).toInt()
                else -> 0
            },
        )
    }
    LaunchedEffect(isThinkingActive, reasoningStartedAtMs, reasoningDurationMs) {
        if (isThinkingActive) {
            val anchor = reasoningStartedAtMs ?: System.currentTimeMillis()
            while (true) {
                liveElapsedSec = ((System.currentTimeMillis() - anchor).coerceAtLeast(0L) / 1_000L).toInt()
                delay(1_000L)
            }
        } else if (reasoningDurationMs != null && reasoningDurationMs > 0L) {
            liveElapsedSec = (reasoningDurationMs / 1_000.0).roundToInt().coerceAtLeast(1)
        }
    }

    // 思考中节流刷新焦点副标（>= 1.2s，含尾缘补刷），避免标题随每 token 频繁跳动且不漏末条焦点
    var throttledFocus by remember(cacheKey) { mutableStateOf("") }
    var lastFocusUpdateMs by remember(cacheKey) { mutableLongStateOf(0L) }
    LaunchedEffect(reasoning, isThinkingActive) {
        if (!isThinkingActive) {
            throttledFocus = ""
            return@LaunchedEffect
        }
        val elapsed = System.currentTimeMillis() - lastFocusUpdateMs
        if (throttledFocus.isNotEmpty() && elapsed < FOCUS_THROTTLE_MS) {
            delay(FOCUS_THROTTLE_MS - elapsed)
        }
        val next = ReasoningPreviewExtractor.extractCurrentFocus(reasoning)
        if (next.isNotBlank()) {
            throttledFocus = next
            lastFocusUpdateMs = System.currentTimeMillis()
        }
    }

    val displaySec = when {
        !isThinkingActive && reasoningDurationMs != null && reasoningDurationMs > 0L ->
            (reasoningDurationMs / 1_000.0).roundToInt().coerceAtLeast(1)
        liveElapsedSec > 0 -> liveElapsedSec
        else -> 0
    }

    val titleText = when {
        isThinkingActive && throttledFocus.isNotBlank() ->
            stringResource(R.string.chat_thinking_active_topic, throttledFocus)
        isThinkingActive ->
            stringResource(R.string.chat_thinking_active)
        processOpsCount > 0 ->
            stringResource(R.string.chat_process_thinking) + " · " +
                pluralStringResource(R.plurals.chat_process_ops, processOpsCount, processOpsCount)
        displaySec > 0 ->
            stringResource(R.string.chat_thinking_done_duration, displaySec)
        else ->
            stringResource(R.string.chat_thinking_done)
    }

    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = MuseMotion.springSpec(MuseSpringTier.STEADY),
        label = "thinkingChevron",
    )

    val containerAlpha = when {
        isMono -> 1f
        hasBackgroundImage -> MuseAlpha.glassSurface
        else -> MuseAlpha.mutedSurface
    }
    val baseContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = containerAlpha)
    val accentBarColor = if (isMono) {
        MaterialTheme.colorScheme.outline
    } else if (isThinkingActive) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = MuseAlpha.handle)
    }
    val borderColor = if (isMono) {
        MaterialTheme.colorScheme.outlineVariant
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = MuseAlpha.subtleBorder)
    }

    val surfaceModifier = when {
        !useFakeGlass -> modifier
        isMono -> modifier
            .clip(MuseShapes.medium)
            .glassEdgeHighlight(MuseShapes.medium, glassConfig)
            .glassBorder(MuseShapes.medium, glassConfig)
        else -> modifier
            .clip(MuseShapes.medium)
            .background(glassFakeSurfaceColor(baseContainerColor, glassConfig))
            .glassEdgeHighlight(MuseShapes.medium, glassConfig)
            .glassBorder(MuseShapes.medium, glassConfig)
    }

    Surface(
        color = if (useFakeGlass && !isMono) Color.Transparent else baseContainerColor,
        shape = MuseShapes.medium,
        tonalElevation = MuseElevation.none,
        border = if (useFakeGlass) null else BorderStroke(MuseBorderWidth.hairline, borderColor),
        modifier = surfaceModifier,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ── 顶部摘要标题行（点击整行展开/收起完整思考过程）──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(
                        horizontal = MusePaddings.inputPadding,
                        vertical = MusePaddings.contentGap,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MuseSpacing.smTight),
            ) {
                if (isThinkingActive) {
                    MuseInkGlyph(
                        modifier = Modifier.size(MuseThinkingGlyphSize),
                        color = if (isMono) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        animate = !reducedMotion,
                    )
                } else {
                    Icon(
                        imageVector = MuseIcons.sparkle,
                        contentDescription = null,
                        tint = if (isMono) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary.copy(alpha = MuseAlpha.disabledContent)
                        },
                        modifier = Modifier.size(MuseIconSizes.iconTiny),
                    )
                }
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (isThinkingActive && displaySec > 0) {
                    Text(
                        text = stringResource(R.string.chat_reasoning_elapsed, displaySec),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = MuseAlpha.disabledContent),
                        maxLines = 1,
                    )
                }
                Icon(
                    imageVector = MuseIcons.chevronDown,
                    contentDescription = if (expanded) {
                        stringResource(R.string.chat_reasoning_collapse_cd)
                    } else {
                        stringResource(R.string.chat_reasoning_expand_cd)
                    },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = MuseAlpha.disabledContent),
                    modifier = Modifier
                        .size(MuseIconSizes.iconTiny)
                        .rotate(chevronRotation),
                )
            }

            // ── 思考中默认折叠态：3–4 行实时滚动思考区间（展示模型此刻在想什么）──
            AnimatedVisibility(
                visible = isThinkingActive && !expanded && reasoning.isNotBlank(),
                enter = MuseMotion.expandFadeEnter(),
                exit = MuseMotion.expandFadeExit(),
            ) {
                val previewText = remember(reasoning) {
                    ReasoningPreviewExtractor.extractLivePreviewWindow(reasoning)
                }
                val previewScroll = rememberScrollState()
                LaunchedEffect(previewText) {
                    previewScroll.scrollTo(previewScroll.maxValue)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onToggle)
                        .padding(
                            start = MusePaddings.inputPadding,
                            end = MusePaddings.inputPadding,
                            bottom = MusePaddings.contentGap,
                        ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .heightIn(max = LIVE_PREVIEW_MAX_HEIGHT),
                        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(MuseSpacing.xxs)
                                .fillMaxHeight()
                                .clip(MuseShapes.pill)
                                .background(accentBarColor),
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(max = LIVE_PREVIEW_MAX_HEIGHT),
                        ) {
                            Text(
                                text = previewText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = MuseAlpha.titleMuted),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(previewScroll),
                            )
                            if (previewScroll.value > 0 && !useFakeGlass) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(MuseSpacing.lg)
                                        .align(Alignment.TopCenter)
                                        .background(
                                            Brush.verticalGradient(
                                                colors = listOf(
                                                    baseContainerColor,
                                                    Color.Transparent,
                                                ),
                                            ),
                                        ),
                                )
                            }
                        }
                    }
                }
            }

            // ── 点击展开态：完整思考过程（可自由选字复制 + 轻量 Markdown 渲染）──
            AnimatedVisibility(
                visible = expanded && reasoning.isNotBlank(),
                enter = MuseMotion.expandFadeEnter(),
                exit = MuseMotion.expandFadeExit(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .padding(
                            start = MusePaddings.inputPadding,
                            end = MusePaddings.inputPadding,
                            bottom = MusePaddings.inputPadding,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                ) {
                    Box(
                        modifier = Modifier
                            .width(MuseSpacing.xxs)
                            .fillMaxHeight()
                            .clip(MuseShapes.pill)
                            .background(accentBarColor),
                    )
                    SelectionContainer(modifier = Modifier.weight(1f)) {
                        MarkdownText(
                            text = reasoning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            isStreaming = isThinkingActive,
                            disableLinks = true,
                            cardId = cacheKey?.let { "$it:reasoning" },
                            onLongPressOutside = onLongPressOutside,
                        )
                    }
                }
            }
        }
    }
}

private const val FOCUS_THROTTLE_MS = 1_200L
private val LIVE_PREVIEW_MAX_HEIGHT = 76.dp
