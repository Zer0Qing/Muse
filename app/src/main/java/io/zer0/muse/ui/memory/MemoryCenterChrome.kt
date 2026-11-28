@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList")

package io.zer0.muse.ui.memory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.memory.space.MemorySpaceEntity
import io.zer0.memory.summary.MemoryDbArchiveRecovery
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.MuseSurface
import io.zer0.muse.ui.theme.MuseButtonSize
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.titleSmallStrong

/** 顶部紧凑概览与筛选栏：一行看清总数 / 置顶数 / 待裁决提醒，右侧直达多维筛选。 */
@Composable
internal fun MemoryCompactHeaderStrip(
    factCount: Int,
    pinnedCount: Int,
    conflictCount: Int,
    activeFilterCount: Int,
    onOpenFilter: () -> Unit,
    onConflictClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen, vertical = MuseSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MuseSpacing.smTight),
        ) {
            Text(
                text = stringResource(R.string.memory_hub_summary_count, factCount),
                style = MaterialTheme.typography.titleSmallStrong,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (pinnedCount > 0) {
                Surface(
                    shape = MuseShapes.pill,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                ) {
                    Text(
                        text = stringResource(R.string.memory_hub_summary_pinned, pinnedCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = MuseSpacing.sm, vertical = MuseSpacing.xxs),
                    )
                }
            }
            if (conflictCount > 0) {
                Surface(
                    onClick = onConflictClick,
                    shape = MuseShapes.pill,
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        text = stringResource(R.string.memory_hub_summary_conflicts, conflictCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = MuseSpacing.sm, vertical = MuseSpacing.xxs),
                    )
                }
            }
        }
        Surface(
            onClick = onOpenFilter,
            shape = MuseShapes.pill,
            color = if (activeFilterCount > 0) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
            },
            modifier = Modifier.heightIn(min = MuseSpacing.xxxl),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = MuseSpacing.md, vertical = MuseSpacing.smTight),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
            ) {
                Icon(
                    imageVector = MuseIcons.filter,
                    contentDescription = stringResource(R.string.memory_center_filter_title),
                    tint = if (activeFilterCount > 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(MuseIconSizes.iconSmallTiny),
                )
                Text(
                    text = if (activeFilterCount > 0) {
                        stringResource(R.string.memory_hub_filter_active_count, activeFilterCount)
                    } else {
                        stringResource(R.string.memory_center_filter_title)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (activeFilterCount > 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

/** 已启用筛选条件提示条，支持逐项或一键清除。 */
@Composable
internal fun MemoryActiveFiltersRow(
    scopeLabel: String?,
    spaceLabel: String?,
    importance: Int?,
    timeRange: MemoryTimeRange?,
    onClearScope: () -> Unit,
    onClearSpace: () -> Unit,
    onClearImportance: () -> Unit,
    onClearTimeRange: () -> Unit,
    onClearAll: () -> Unit,
) {
    val hasAny = scopeLabel != null || spaceLabel != null || importance != null ||
        (timeRange != null && timeRange != MemoryTimeRange.ALL)
    if (!hasAny) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = MusePaddings.screen, vertical = MuseSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        scopeLabel?.let { label ->
            MuseChip(selected = true, onClick = onClearScope, label = "$label ×")
        }
        spaceLabel?.let { label ->
            MuseChip(selected = true, onClick = onClearSpace, label = "$label ×")
        }
        importance?.let { level ->
            val label = stringResource(
                when {
                    level >= 2 -> R.string.memory_importance_critical
                    level == 1 -> R.string.memory_importance_important
                    else -> R.string.memory_importance_normal
                },
            )
            MuseChip(selected = true, onClick = onClearImportance, label = "$label ×")
        }
        timeRange?.takeIf { it != MemoryTimeRange.ALL }?.let { range ->
            val label = stringResource(
                when (range) {
                    MemoryTimeRange.ALL -> R.string.memory_center_filter_time_all
                    MemoryTimeRange.LAST_7 -> R.string.memory_center_filter_time_7
                    MemoryTimeRange.LAST_30 -> R.string.memory_center_filter_time_30
                    MemoryTimeRange.LAST_90 -> R.string.memory_center_filter_time_90
                },
            )
            MuseChip(selected = true, onClick = onClearTimeRange, label = "$label ×")
        }
        MuseChip(
            selected = false,
            onClick = onClearAll,
            label = stringResource(R.string.memory_hub_filter_clear_all),
        )
    }
}

/** 多维筛选抽屉：允许一次调好重要度 / 时间 / 助手 / 空间，点击应用生效，不再点一下就关闭。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MemoryMultiFilterSheet(
    scopes: List<ScopeOption>,
    spaces: List<MemorySpaceEntity>,
    selectedScope: String?,
    selectedSpace: String,
    importanceFilter: Int?,
    timeRangeFilter: MemoryTimeRange?,
    onApply: (scope: String?, spaceId: String, importance: Int?, timeRange: MemoryTimeRange?) -> Unit,
    onDismiss: () -> Unit,
) {
    var draftScope by remember(selectedScope) { mutableStateOf(selectedScope) }
    var draftSpace by remember(selectedSpace) { mutableStateOf(selectedSpace) }
    var draftImportance by remember(importanceFilter) { mutableStateOf(importanceFilter) }
    var draftTime by remember(timeRangeFilter) { mutableStateOf(timeRangeFilter) }

    MuseBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = MuseSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(MuseSpacing.md),
        ) {
            Text(
                text = stringResource(R.string.memory_center_filter_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            Column(verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs)) {
                Text(
                    text = stringResource(R.string.memory_center_filter_importance),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                ) {
                    MuseChip(
                        selected = draftImportance == null,
                        onClick = { draftImportance = null },
                        label = stringResource(R.string.common_all),
                    )
                    listOf(
                        0 to R.string.memory_importance_normal,
                        1 to R.string.memory_importance_important,
                        2 to R.string.memory_importance_critical,
                    ).forEach { (level, labelRes) ->
                        MuseChip(
                            selected = draftImportance == level,
                            onClick = { draftImportance = level },
                            label = stringResource(labelRes),
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs)) {
                Text(
                    text = stringResource(R.string.memory_center_filter_time),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                ) {
                    listOf(
                        null to R.string.memory_center_filter_time_all,
                        MemoryTimeRange.LAST_7 to R.string.memory_center_filter_time_7,
                        MemoryTimeRange.LAST_30 to R.string.memory_center_filter_time_30,
                        MemoryTimeRange.LAST_90 to R.string.memory_center_filter_time_90,
                    ).forEach { (range, labelRes) ->
                        MuseChip(
                            selected = draftTime == range,
                            onClick = { draftTime = range },
                            label = stringResource(labelRes),
                        )
                    }
                }
            }

            if (scopes.size > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs)) {
                    Text(
                        text = stringResource(R.string.memory_center_filter_scope),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                        verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                    ) {
                        scopes.forEach { option ->
                            MuseChip(
                                selected = option.id == draftScope,
                                onClick = { draftScope = option.id },
                                label = option.displayName,
                            )
                        }
                    }
                }
            }

            if (spaces.size > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs)) {
                    Text(
                        text = stringResource(R.string.memory_center_filter_space),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                        verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                    ) {
                        val defaultName = stringResource(R.string.memory_space_default_name)
                        spaces.forEach { space ->
                            MuseChip(
                                selected = space.id == draftSpace,
                                onClick = { draftSpace = space.id },
                                label = memorySpaceDisplayName(space.name, defaultName),
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = MuseSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
            ) {
                MuseCapsuleButton(
                    text = stringResource(R.string.memory_hub_filter_reset),
                    onClick = {
                        draftScope = null
                        draftSpace = "default"
                        draftImportance = null
                        draftTime = null
                    },
                    variant = IosCapsuleButtonVariant.Text,
                    modifier = Modifier.weight(1f),
                )
                MuseCapsuleButton(
                    text = stringResource(R.string.memory_hub_filter_apply),
                    onClick = {
                        onApply(draftScope, draftSpace, draftImportance, draftTime)
                        onDismiss()
                    },
                    variant = IosCapsuleButtonVariant.Primary,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(MuseSpacing.sm))
        }
    }
}

/** 记忆维护抽屉：收敛原首页杂乱的整理 / Dream / 归档恢复三张大卡。 */
@Composable
internal fun MemoryMaintenanceSheet(
    organizing: Boolean,
    organizeStage: String?,
    dreamAvailable: Boolean,
    dreamRunning: Boolean,
    dreamLastRevisionId: String?,
    dreamLastError: String?,
    archiveInfo: MemoryDbArchiveRecovery.ArchiveInfo?,
    archiveRunning: Boolean,
    onOrganize: () -> Unit,
    onStartDream: () -> Unit,
    onRestoreDream: (String) -> Unit,
    onOpenArchiveConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    MuseBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = MuseSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(MuseSpacing.md),
        ) {
            Text(
                text = stringResource(R.string.memory_hub_maintenance_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.memory_hub_maintenance_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            MuseSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = MuseShapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            ) {
                Column(
                    modifier = Modifier.padding(MuseSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                ) {
                    Text(
                        text = stringResource(R.string.memory_organize_action),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.memory_hub_organize_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MuseCapsuleButton(
                        text = when (organizeStage) {
                            "prepare" -> stringResource(R.string.memory_organize_stage_prepare)
                            "compile" -> stringResource(R.string.memory_organize_stage_compile)
                            "dedup" -> stringResource(R.string.memory_organize_stage_dedup)
                            else -> stringResource(R.string.memory_organize_action)
                        },
                        onClick = onOrganize,
                        enabled = !organizing,
                        loading = organizing,
                        variant = IosCapsuleButtonVariant.Primary,
                        leadingIcon = MuseIcons.refresh,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (dreamAvailable) {
                MuseSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MuseShapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ) {
                    Column(
                        modifier = Modifier.padding(MuseSpacing.md),
                        verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                    ) {
                        Text(
                            text = stringResource(R.string.memory_dream_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = stringResource(R.string.memory_dream_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!dreamLastError.isNullOrBlank()) {
                            Text(
                                text = stringResource(R.string.memory_dream_skipped, dreamLastError),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm)) {
                            if (dreamRunning) {
                                MuseSpinner(size = MuseIconSizes.iconSmall)
                            } else {
                                MuseCapsuleButton(
                                    text = stringResource(R.string.memory_dream_start),
                                    onClick = onStartDream,
                                    variant = IosCapsuleButtonVariant.Secondary,
                                    size = MuseButtonSize.Compact,
                                    fillWidth = false,
                                )
                            }
                            if (!dreamLastRevisionId.isNullOrBlank() && !dreamRunning) {
                                MuseCapsuleButton(
                                    text = stringResource(R.string.memory_dream_rollback),
                                    onClick = { onRestoreDream(dreamLastRevisionId) },
                                    variant = IosCapsuleButtonVariant.Text,
                                    size = MuseButtonSize.Compact,
                                    fillWidth = false,
                                )
                            }
                        }
                    }
                }
            }

            archiveInfo?.let { info ->
                MuseSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MuseShapes.large,
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                ) {
                    Column(
                        modifier = Modifier.padding(MuseSpacing.md),
                        verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                    ) {
                        Text(
                            text = stringResource(R.string.memory_archive_recovery_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            text = stringResource(
                                R.string.memory_archive_recovery_message,
                                info.sessionSummaries,
                                info.compiledSections + info.scopedCompiledSections,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        MuseCapsuleButton(
                            text = stringResource(R.string.memory_archive_recovery_action),
                            onClick = {
                                onDismiss()
                                onOpenArchiveConfirm()
                            },
                            enabled = !archiveRunning,
                            loading = archiveRunning,
                            variant = IosCapsuleButtonVariant.Secondary,
                            leadingIcon = MuseIcons.refresh,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            Spacer(Modifier.height(MuseSpacing.sm))
        }
    }
}

/** 矛盾记忆确认卡片（原生 MuseSurface，替换旧裸 ElevatedCard）。 */
@Composable
internal fun MemoryContradictionsCard(
    contradictions: List<MemoryConflictUi>,
    onResolveConflict: (MemoryConflictUi, Long) -> Unit,
    onDismissConflict: (MemoryConflictUi) -> Unit,
) {
    MuseSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen, vertical = MuseSpacing.xs),
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier.padding(MuseSpacing.md),
            verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
        ) {
            Text(
                text = stringResource(R.string.memory_conflicts_banner_title, contradictions.size),
                style = MaterialTheme.typography.titleSmallStrong,
                color = MaterialTheme.colorScheme.onSurface,
            )
            contradictions.forEach { conflict ->
                MuseSurface(
                    shape = MuseShapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(MuseSpacing.md),
                        verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = conflict.a,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            MuseTactileButton(
                                icon = MuseIcons.x,
                                onClick = { onDismissConflict(conflict) },
                                contentDescription = stringResource(R.string.memory_contradictions_dismiss_cd),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = conflict.b,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = MuseSpacing.xs),
                            horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                        ) {
                            MuseCapsuleButton(
                                text = stringResource(R.string.memory_contradictions_keep_a),
                                onClick = { onResolveConflict(conflict, conflict.idA) },
                                variant = IosCapsuleButtonVariant.Secondary,
                                size = MuseButtonSize.Compact,
                                modifier = Modifier.weight(1f),
                            )
                            MuseCapsuleButton(
                                text = stringResource(R.string.memory_contradictions_keep_b),
                                onClick = { onResolveConflict(conflict, conflict.idB) },
                                variant = IosCapsuleButtonVariant.Secondary,
                                size = MuseButtonSize.Compact,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 多选态底部悬浮栏，展示选中条数与快捷操作。 */
@Composable
internal fun MemoryFloatingSelectionBar(
    selectedCount: Int,
    mergeRunning: Boolean,
    onMerge: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen, vertical = MuseSpacing.sm),
        shape = MuseShapes.pill,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = MuseSpacing.md, vertical = MuseSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
        ) {
            Text(
                text = stringResource(R.string.memory_selection_count, selectedCount),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = MuseSpacing.xs),
            )
            MuseCapsuleButton(
                text = stringResource(R.string.memory_center_merge_action),
                onClick = onMerge,
                enabled = selectedCount >= 2 && !mergeRunning,
                loading = mergeRunning,
                variant = IosCapsuleButtonVariant.Secondary,
                size = MuseButtonSize.Compact,
                fillWidth = false,
            )
            MuseTactileButton(
                icon = MuseIcons.trash,
                onClick = onDelete,
                contentDescription = stringResource(R.string.common_delete),
                enabled = selectedCount > 0 && !mergeRunning,
                tint = MaterialTheme.colorScheme.error,
            )
            MuseTactileButton(
                icon = MuseIcons.x,
                onClick = onCancel,
                contentDescription = stringResource(R.string.common_cancel),
            )
        }
    }
}
