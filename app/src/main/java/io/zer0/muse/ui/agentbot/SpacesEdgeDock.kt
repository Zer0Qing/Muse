package io.zer0.muse.ui.agentbot

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import io.zer0.muse.R
import io.zer0.muse.data.card.CardEntity
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.markdown.CardAction
import io.zer0.muse.ui.markdown.RichContentCard
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseBorderWidth
import io.zer0.muse.ui.theme.MuseButtonSize
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge
import kotlin.math.roundToInt

/**
 * Agent 主舞台「小应用」三态控制器：
 *  1. 贴靠右缘的可拖动折叠胶囊拉手；
 *  2. 侧滑折叠小应用抽屉；
 *  3. 从抽屉浮出的一张可 2D 拖动、可单行折叠的画中画交互小窗（不拦截窗口外点击，可边聊边玩）。
 */
@Suppress("LongMethod", "LongParameterList")
@Composable
fun SpacesEdgeDock(
    cards: List<CardEntity>,
    refreshingIds: Set<String>,
    onRefresh: (String) -> Unit,
    onCardAction: (String, CardAction) -> Unit,
    onOpenSpacesTab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (cards.isEmpty()) return

    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    var handleOffsetFraction by rememberSaveable { mutableFloatStateOf(0f) }
    var floatingCardId by rememberSaveable { mutableStateOf<String?>(null) }
    var floatingCollapsed by rememberSaveable { mutableStateOf(false) }
    var floatingXFraction by rememberSaveable { mutableFloatStateOf(0f) }
    var floatingYFraction by rememberSaveable { mutableFloatStateOf(-0.16f) }

    val floatingCard = remember(cards, floatingCardId) {
        floatingCardId?.let { id -> cards.firstOrNull { it.id == id } }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val maxDragPx = with(density) { (maxHeight * 0.34f).toPx().coerceAtLeast(1f) }
        val topPadPx = with(density) { MuseIconSizes.topBarSolid.toPx() }
        val bottomPadPx = with(density) { (MuseIconSizes.topBarSolid + MuseSpacing.xxl).toPx() }
        val maxUpPx = -(maxDragPx - topPadPx).coerceAtLeast(0f)
        val maxDownPx = (maxDragPx - bottomPadPx).coerceAtLeast(0f)

        // 1. 悬浮小窗态（画中画 PiP）：可自由 2D 拖动、单行折叠，其余空白区域点击穿透回主对话。
        if (floatingCard != null && !drawerOpen) {
            val maxPipXPx = with(density) { (maxWidth * 0.12f).toPx().coerceAtLeast(1f) }
            val maxPipYPx = with(density) { (maxHeight * 0.36f).toPx().coerceAtLeast(1f) }
            val pipOffsetX = (floatingXFraction * maxPipXPx).roundToInt()
            val pipOffsetY = (floatingYFraction * maxPipYPx).roundToInt()
            FloatingSpaceWindow(
                card = floatingCard,
                collapsed = floatingCollapsed,
                refreshing = floatingCard.id in refreshingIds,
                maxBodyHeight = maxHeight * 0.50f,
                onToggleCollapse = { floatingCollapsed = !floatingCollapsed },
                onRefresh = { onRefresh(floatingCard.id) },
                onReturnToDock = {
                    floatingCardId = null
                    drawerOpen = true
                },
                onClose = { floatingCardId = null },
                onCardAction = { action ->
                    onCardAction(floatingCard.id, action)
                    if (action is CardAction.Send) {
                        floatingCollapsed = true
                    }
                },
                onDrag = { dx, dy ->
                    val nextX = ((floatingXFraction * maxPipXPx) + dx).coerceIn(-maxPipXPx, maxPipXPx)
                    val nextY = ((floatingYFraction * maxPipYPx) + dy).coerceIn(-maxPipYPx, maxPipYPx)
                    floatingXFraction = (nextX / maxPipXPx).coerceIn(-1f, 1f)
                    floatingYFraction = (nextY / maxPipYPx).coerceIn(-1f, 1f)
                },
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset { IntOffset(pipOffsetX, pipOffsetY) }
                    .padding(horizontal = MuseSpacing.md)
                    .fillMaxWidth(0.92f),
            )
        }

        // 2. 贴靠右缘的折叠拉手
        if (!drawerOpen && floatingCard == null) {
            val currentOffsetPx = (handleOffsetFraction * maxDragPx).coerceIn(maxUpPx, maxDownPx)
            Surface(
                onClick = { drawerOpen = true },
                shape = MuseShapes.pill,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = MuseElevation.high,
                border = BorderStroke(
                    width = MuseBorderWidth.hairline,
                    color = MaterialTheme.colorScheme.outlineVariant,
                ),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset { IntOffset(0, currentOffsetPx.roundToInt()) }
                    .padding(end = MuseSpacing.xs)
                    .defaultMinSize(
                        minWidth = MuseIconSizes.touchTarget,
                        minHeight = MuseIconSizes.touchTarget,
                    )
                    .pointerInput(maxDragPx, maxUpPx, maxDownPx) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val nextPx = ((handleOffsetFraction * maxDragPx) + dragAmount.y)
                                .coerceIn(maxUpPx, maxDownPx)
                            handleOffsetFraction = (nextPx / maxDragPx).coerceIn(-1f, 1f)
                        }
                    },
            ) {
                Row(
                    modifier = Modifier.padding(
                        horizontal = MuseSpacing.sm,
                        vertical = MuseSpacing.smTight,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xxs),
                ) {
                    Icon(
                        imageVector = MuseIcons.chevronLeft,
                        contentDescription = stringResource(R.string.agent_space_dock_handle_cd),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    )
                    Text(
                        text = cards.size.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = drawerOpen,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { drawerOpen = false },
                    ),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = MuseAlpha.handle),
                    modifier = Modifier.fillMaxSize(),
                    content = {},
                )
            }
        }

        // 3. 右缘侧滑抽屉
        AnimatedVisibility(
            visible = drawerOpen,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Surface(
                shape = MuseShapes.semiLarge,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = MuseElevation.high,
                border = BorderStroke(
                    width = MuseBorderWidth.hairline,
                    color = MaterialTheme.colorScheme.outlineVariant,
                ),
                modifier = Modifier
                    .systemBarsPadding()
                    .padding(end = MuseSpacing.sm, start = MuseSpacing.xl)
                    .fillMaxWidth(0.88f)
                    .fillMaxHeight(0.76f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(MusePaddings.cardInner),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                    ) {
                        Icon(
                            imageVector = MuseIcons.template,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                        Text(
                            text = stringResource(R.string.agent_space_dock_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        MuseCapsuleButton(
                            text = stringResource(R.string.agent_space_dock_open_all),
                            onClick = {
                                drawerOpen = false
                                onOpenSpacesTab()
                            },
                            variant = IosCapsuleButtonVariant.Secondary,
                            fillWidth = false,
                            size = MuseButtonSize.Compact,
                        )
                        MuseTactileButton(
                            icon = MuseIcons.x,
                            onClick = { drawerOpen = false },
                            contentDescription = stringResource(R.string.agent_space_dock_close_cd),
                            size = MuseIconSizes.controlTouch,
                            iconSize = MuseIconSizes.iconSmall,
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                    ) {
                        cards.forEachIndexed { index, card ->
                            DockCollapsibleCard(
                                card = card,
                                defaultExpanded = index == 0,
                                refreshing = card.id in refreshingIds,
                                onRefresh = { onRefresh(card.id) },
                                onPopOut = {
                                    floatingCardId = card.id
                                    floatingCollapsed = false
                                    drawerOpen = false
                                },
                                onCardAction = { action ->
                                    onCardAction(card.id, action)
                                    if (action is CardAction.Send) {
                                        drawerOpen = false
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingSpaceWindow(
    card: CardEntity,
    collapsed: Boolean,
    refreshing: Boolean,
    maxBodyHeight: Dp,
    onToggleCollapse: () -> Unit,
    onRefresh: () -> Unit,
    onReturnToDock: () -> Unit,
    onClose: () -> Unit,
    onCardAction: (CardAction) -> Unit,
    onDrag: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MuseShapes.semiLarge,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = MuseElevation.high,
        border = BorderStroke(MuseBorderWidth.thin, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.cardInner),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(card.id) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.x, dragAmount.y)
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
            ) {
                Icon(
                    imageVector = MuseIcons.template,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
                Text(
                    text = card.title.ifBlank { card.id },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!refreshing) {
                    MuseTactileButton(
                        icon = MuseIcons.refresh,
                        onClick = onRefresh,
                        contentDescription = stringResource(R.string.agent_space_dock_refresh_cd),
                        size = MuseIconSizes.controlTouch,
                        iconSize = MuseIconSizes.iconSmall,
                    )
                }
                MuseTactileButton(
                    icon = if (collapsed) MuseIcons.chevronDown else MuseIcons.chevronUp,
                    onClick = onToggleCollapse,
                    contentDescription = stringResource(
                        if (collapsed) R.string.agent_space_expand_cd else R.string.agent_space_collapse_cd,
                    ),
                    size = MuseIconSizes.controlTouch,
                    iconSize = MuseIconSizes.iconSmall,
                )
                MuseTactileButton(
                    icon = MuseIcons.chevronRight,
                    onClick = onReturnToDock,
                    contentDescription = stringResource(R.string.agent_space_dock_back_cd),
                    size = MuseIconSizes.controlTouch,
                    iconSize = MuseIconSizes.iconSmall,
                )
                MuseTactileButton(
                    icon = MuseIcons.x,
                    onClick = onClose,
                    contentDescription = stringResource(R.string.agent_space_dock_close_cd),
                    size = MuseIconSizes.controlTouch,
                    iconSize = MuseIconSizes.iconSmall,
                )
            }
            AnimatedVisibility(visible = !collapsed) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxBodyHeight)
                        .verticalScroll(rememberScrollState()),
                ) {
                    RichContentCard(
                        language = if (card.isSvg) "svg" else "html",
                        content = card.code,
                        modifier = Modifier.fillMaxWidth(),
                        showPreviewButton = true,
                        cardId = card.id,
                        inlineCardData = card.dataJson ?: "{}",
                        onCardAction = onCardAction,
                    )
                }
            }
        }
    }
}

@Composable
private fun DockCollapsibleCard(
    card: CardEntity,
    defaultExpanded: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onPopOut: () -> Unit,
    onCardAction: (CardAction) -> Unit,
) {
    var expanded by rememberSaveable(card.id) { mutableStateOf(defaultExpanded) }
    Surface(
        shape = MuseShapes.semiLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(MuseBorderWidth.hairline, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.cardInner),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
            ) {
                Text(
                    text = card.title.ifBlank { card.id },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                MuseTactileButton(
                    icon = MuseIcons.expand,
                    onClick = onPopOut,
                    contentDescription = stringResource(R.string.agent_space_dock_float_cd),
                    size = MuseIconSizes.controlTouch,
                    iconSize = MuseIconSizes.iconSmall,
                )
                if (!refreshing) {
                    MuseTactileButton(
                        icon = MuseIcons.refresh,
                        onClick = onRefresh,
                        contentDescription = stringResource(R.string.agent_space_dock_refresh_cd),
                        size = MuseIconSizes.controlTouch,
                        iconSize = MuseIconSizes.iconSmall,
                    )
                }
                MuseTactileButton(
                    icon = if (expanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
                    onClick = { expanded = !expanded },
                    contentDescription = stringResource(
                        if (expanded) R.string.agent_space_collapse_cd else R.string.agent_space_expand_cd,
                    ),
                    size = MuseIconSizes.controlTouch,
                    iconSize = MuseIconSizes.iconSmall,
                )
            }
            AnimatedVisibility(visible = expanded) {
                RichContentCard(
                    language = if (card.isSvg) "svg" else "html",
                    content = card.code,
                    modifier = Modifier.fillMaxWidth(),
                    showPreviewButton = true,
                    cardId = card.id,
                    inlineCardData = card.dataJson ?: "{}",
                    onCardAction = onCardAction,
                )
            }
        }
    }
}
