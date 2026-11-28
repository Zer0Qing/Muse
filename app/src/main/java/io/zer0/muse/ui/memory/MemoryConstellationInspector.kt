@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList")

package io.zer0.muse.ui.memory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseConstellationPalette
import io.zer0.muse.ui.theme.MuseCornerRadius
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.tiny

/** 星座图顶部悬浮控件：左侧分类星群图例 Chips，右侧观星台控制条。 */
@Composable
internal fun ConstellationTopOverlay(
    clusters: List<ClusterAnchor>,
    focusedCategory: String?,
    palette: MuseConstellationPalette,
    useObservatorySky: Boolean,
    isFullscreen: Boolean,
    onSelectCategory: (String?) -> Unit,
    onToggleSkyTheme: () -> Unit,
    onRebuildEdges: (() -> Unit)?,
    onOpenSettings: (() -> Unit)?,
    onToggleFullscreen: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(MusePaddings.contentGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(MuseSpacing.smTight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConstellationCategoryChip(
                label = stringResource(R.string.memory_constellation_all_clusters),
                dotColor = palette.selectionGlow,
                selected = focusedCategory == null,
                palette = palette,
                onClick = { onSelectCategory(null) },
            )
            clusters.forEach { anchor ->
                val active = focusedCategory.equals(anchor.category, ignoreCase = true)
                val catLabel = memoryCategoryLabel(anchor.category)
                ConstellationCategoryChip(
                    label = stringResource(R.string.memory_constellation_cluster_chip, catLabel, anchor.memberCount),
                    dotColor = memoryCategoryColor(anchor.category, palette),
                    selected = active,
                    palette = palette,
                    onClick = {
                        onSelectCategory(if (active) null else anchor.category)
                    },
                )
            }
        }
        Surface(
            shape = MuseShapes.pill,
            color = palette.overlaySurface,
            border = BorderStroke(0.8.dp, palette.overlayBorder),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OverlayIconButton(
                    icon = if (useObservatorySky) MuseIcons.sun else MuseIcons.moonStars,
                    contentDescription = stringResource(
                        if (useObservatorySky) {
                            R.string.memory_constellation_theme_paper_cd
                        } else {
                            R.string.memory_constellation_theme_observatory_cd
                        },
                    ),
                    tint = palette.overlayContent,
                    onClick = onToggleSkyTheme,
                )
                if (onRebuildEdges != null) {
                    OverlayIconButton(
                        icon = MuseIcons.refresh,
                        contentDescription = stringResource(R.string.memory_constellation_rebuild_cd),
                        tint = palette.overlayContent,
                        onClick = onRebuildEdges,
                    )
                }
                if (onOpenSettings != null) {
                    OverlayIconButton(
                        icon = MuseIcons.sliders,
                        contentDescription = stringResource(R.string.memory_graph_settings_title),
                        tint = palette.overlayContent,
                        onClick = onOpenSettings,
                    )
                }
                if (onToggleFullscreen != null) {
                    OverlayIconButton(
                        icon = if (isFullscreen) MuseIcons.x else MuseIcons.maximize,
                        contentDescription = stringResource(
                            if (isFullscreen) {
                                R.string.memory_constellation_exit_fullscreen_cd
                            } else {
                                R.string.memory_constellation_fullscreen_cd
                            },
                        ),
                        tint = palette.overlayContent,
                        onClick = onToggleFullscreen,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConstellationCategoryChip(
    label: String,
    dotColor: androidx.compose.ui.graphics.Color,
    selected: Boolean,
    palette: MuseConstellationPalette,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MuseShapes.pill,
        color = if (selected) dotColor.copy(alpha = 0.24f) else palette.overlaySurface,
        border = BorderStroke(
            width = if (selected) 1.2.dp else 0.7.dp,
            color = if (selected) dotColor else palette.overlayBorder,
        ),
        modifier = Modifier.heightIn(min = MuseSpacing.xxxl),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = MuseSpacing.md, vertical = MuseSpacing.smTight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MuseSpacing.smTight),
        ) {
            Box(
                modifier = Modifier
                    .size(MuseSpacing.sm)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = palette.overlayContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 选目标连线引导条：开始手动连线时悬浮顶部，清晰指引用户点击第二颗星或取消。 */
@Composable
internal fun ConstellationLinkBanner(
    sourceNode: MemoryGraphNode,
    palette: MuseConstellationPalette,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.contentGap),
        shape = MuseShapes.large,
        color = palette.overlaySurface,
        border = BorderStroke(1.dp, palette.edgeCauses),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = MuseSpacing.md, vertical = MuseSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
        ) {
            Icon(
                imageVector = MuseIcons.link,
                contentDescription = null,
                tint = palette.edgeCauses,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
            )
            Text(
                text = stringResource(R.string.memory_constellation_link_banner, sourceNode.title),
                style = MaterialTheme.typography.labelMedium,
                color = palette.overlayContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            OverlayIconButton(
                icon = MuseIcons.x,
                contentDescription = stringResource(R.string.common_cancel),
                tint = palette.overlayContent,
                onClick = onCancel,
            )
        }
    }
}

/** 底部统计与缩放控件（无选中节点时显示）。 */
@Composable
internal fun ConstellationBottomControls(
    nodeCount: Int,
    edgeCount: Int,
    palette: MuseConstellationPalette,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onResetView: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(MusePaddings.contentGap),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Surface(
            shape = MuseShapes.pill,
            color = palette.overlaySurface,
            border = BorderStroke(0.7.dp, palette.overlayBorder),
        ) {
            Text(
                text = stringResource(R.string.memory_constellation_stats, nodeCount, edgeCount),
                style = MaterialTheme.typography.labelMedium,
                color = palette.overlayContent,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = MuseSpacing.md, vertical = MuseSpacing.smTight),
            )
        }
        Surface(
            shape = MuseShapes.large,
            color = palette.overlaySurface,
            border = BorderStroke(0.8.dp, palette.overlayBorder),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                OverlayIconButton(
                    icon = MuseIcons.plus,
                    contentDescription = stringResource(R.string.memory_constellation_zoom_in_cd),
                    tint = palette.overlayContent,
                    onClick = onZoomIn,
                )
                HorizontalDivider(
                    color = palette.overlayBorder,
                    modifier = Modifier.width(MuseSpacing.xxl),
                )
                OverlayIconButton(
                    icon = MuseIcons.minus,
                    contentDescription = stringResource(R.string.memory_constellation_zoom_out_cd),
                    tint = palette.overlayContent,
                    onClick = onZoomOut,
                )
                HorizontalDivider(
                    color = palette.overlayBorder,
                    modifier = Modifier.width(MuseSpacing.xxl),
                )
                OverlayIconButton(
                    icon = MuseIcons.compass,
                    contentDescription = stringResource(R.string.memory_graph_reset_view),
                    tint = palette.overlayContent,
                    onClick = onResetView,
                )
            }
        }
    }
}

/**
 * 选中星体后的详细检视浮层：
 * 展示完整记忆内容、重要度/置顶/分类徽章、可跳转的关联记忆与连线管理（确认/删除）、以及核心编辑动作。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConstellationNodeInspectorCard(
    node: MemoryGraphNode,
    fullContent: String?,
    loadingFullContent: Boolean,
    nodesById: Map<Long, MemoryGraphNode>,
    relatedEdges: List<MemoryGraphEdge>,
    palette: MuseConstellationPalette,
    canManualLink: Boolean,
    onSelectNeighbor: (MemoryGraphNode) -> Unit,
    onEdgeAction: ((MemoryGraphEdge, EdgeAction) -> Unit)?,
    onStartLink: () -> Unit,
    onNodeAction: ((MemoryGraphNode, NodeAction) -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val starTone = memoryCategoryColor(node.category, palette)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(MusePaddings.contentGap),
        shape = RoundedCornerShape(MuseCornerRadius.CARD.dp),
        color = palette.overlaySurface,
        border = BorderStroke(1.dp, starTone.copy(alpha = 0.45f)),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(MuseSpacing.md),
            verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MuseSpacing.smTight),
            ) {
                Box(
                    modifier = Modifier
                        .size(MuseSpacing.smTight)
                        .clip(CircleShape)
                        .background(starTone),
                )
                Surface(
                    shape = MuseShapes.pill,
                    color = starTone.copy(alpha = 0.18f),
                ) {
                    Text(
                        text = memoryCategoryLabel(node.category),
                        style = MaterialTheme.typography.labelSmall,
                        color = starTone,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = MuseSpacing.sm, vertical = MuseSpacing.xxs),
                    )
                }
                if (node.importance > 0) {
                    Surface(
                        shape = MuseShapes.pill,
                        color = palette.overlayContent.copy(alpha = MuseAlpha.statusTint),
                    ) {
                        Text(
                            text = stringResource(
                                if (node.importance >= 2) R.string.memory_importance_critical else R.string.memory_importance_important,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.overlayContent,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = MuseSpacing.sm, vertical = MuseSpacing.xxs),
                        )
                    }
                }
                if (node.isPinned) {
                    Surface(
                        shape = MuseShapes.pill,
                        color = palette.edgeCauses.copy(alpha = 0.20f),
                    ) {
                        Text(
                            text = stringResource(R.string.memory_card_pinned_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.edgeCauses,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = MuseSpacing.sm, vertical = MuseSpacing.xxs),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                OverlayIconButton(
                    icon = MuseIcons.x,
                    contentDescription = stringResource(R.string.common_close),
                    tint = palette.overlayContent,
                    onClick = onClose,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 112.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (loadingFullContent && fullContent == null) {
                    Text(
                        text = stringResource(R.string.memory_constellation_loading_detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.overlayContent.copy(alpha = 0.65f),
                    )
                }
                Text(
                    text = fullContent?.takeIf { it.isNotBlank() } ?: node.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.overlayContent,
                )
            }

            if (relatedEdges.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.memory_constellation_related_section, relatedEdges.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.overlayContent.copy(alpha = 0.72f),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 132.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                ) {
                    relatedEdges.forEach { edge ->
                        val neighborId = if (edge.sourceFactId == node.factId) edge.targetFactId else edge.sourceFactId
                        val neighbor = nodesById[neighborId]
                        val fallbackTitle = if (edge.sourceFactId == node.factId) edge.targetTitle else edge.sourceTitle
                        val neighborTitle = neighbor?.title
                            ?: fallbackTitle.ifBlank { stringResource(R.string.memory_constellation_untitled_node, neighborId) }
                        val relColor = memoryRelationColor(edge.relationType, palette)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MuseShapes.medium)
                                .background(palette.overlayContent.copy(alpha = 0.06f))
                                .clickable(enabled = neighbor != null) { neighbor?.let(onSelectNeighbor) }
                                .padding(start = MuseSpacing.sm, end = MuseSpacing.xs, top = MuseSpacing.xxs, bottom = MuseSpacing.xxs),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MuseSpacing.smTight),
                        ) {
                            Surface(
                                shape = MuseShapes.tiny,
                                color = relColor.copy(alpha = 0.22f),
                            ) {
                                Text(
                                    text = stringResource(memoryRelationStringRes(edge.relationType)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = relColor,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = MuseSpacing.smTight, vertical = MuseSpacing.xxs),
                                )
                            }
                            Text(
                                text = neighborTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.overlayContent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (onEdgeAction != null) {
                                MuseTactileButton(
                                    icon = MuseIcons.check,
                                    onClick = { onEdgeAction(edge, EdgeAction.CONFIRM) },
                                    contentDescription = stringResource(R.string.memory_constellation_confirm_edge_cd),
                                    tint = palette.goal,
                                    size = MuseIconSizes.touchTarget,
                                    iconSize = MuseIconSizes.iconSmallTiny,
                                )
                                MuseTactileButton(
                                    icon = MuseIcons.x,
                                    onClick = { onEdgeAction(edge, EdgeAction.DELETE) },
                                    contentDescription = stringResource(R.string.memory_constellation_delete_edge_cd),
                                    tint = palette.edgeContradicts,
                                    size = MuseIconSizes.touchTarget,
                                    iconSize = MuseIconSizes.iconSmallTiny,
                                )
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.memory_constellation_no_links),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.overlayContent.copy(alpha = 0.62f),
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
            ) {
                if (canManualLink) {
                    InspectorActionPill(
                        icon = MuseIcons.link,
                        label = stringResource(R.string.memory_constellation_action_link),
                        tint = palette.edgeCauses,
                        palette = palette,
                        onClick = onStartLink,
                    )
                }
                onNodeAction?.let { action ->
                    InspectorActionPill(
                        icon = MuseIcons.edit,
                        label = stringResource(R.string.common_edit),
                        tint = palette.overlayContent,
                        palette = palette,
                        onClick = { action(node, NodeAction.EDIT) },
                    )
                    InspectorActionPill(
                        icon = MuseIcons.pin,
                        label = stringResource(if (node.isPinned) R.string.memory_menu_unpin else R.string.memory_menu_pin),
                        tint = if (node.isPinned) palette.edgeCauses else palette.overlayContent,
                        palette = palette,
                        onClick = { action(node, NodeAction.PIN) },
                    )
                    InspectorActionPill(
                        icon = MuseIcons.trash,
                        label = stringResource(R.string.common_delete),
                        tint = palette.edgeContradicts,
                        palette = palette,
                        onClick = {
                            action(node, NodeAction.DELETE)
                            onClose()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun InspectorActionPill(
    icon: ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    palette: MuseConstellationPalette,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MuseShapes.pill,
        color = palette.overlayContent.copy(alpha = 0.08f),
        border = BorderStroke(0.7.dp, palette.overlayBorder),
        modifier = Modifier.sizeIn(minHeight = MuseIconSizes.touchTarget),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = MuseSpacing.md, vertical = MuseSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MuseSpacing.smTight),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(MuseIconSizes.iconSmallTiny),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = tint,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun OverlayIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    MuseTactileButton(
        icon = icon,
        onClick = onClick,
        contentDescription = contentDescription,
        tint = tint,
        size = MuseIconSizes.touchTarget,
        iconSize = MuseIconSizes.iconSmall,
    )
}
