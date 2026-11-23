@file:Suppress("FunctionNaming", "LongMethod")

package io.zer0.muse.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.ProviderCategory
import io.zer0.ai.core.ProviderConfig
import io.zer0.muse.R
import io.zer0.muse.data.ProviderCollisionDetector
import io.zer0.muse.data.catalog.ModelCatalogRepository
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseAnchoredMenu
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.MuseListItem
import io.zer0.muse.ui.theme.MuseButtonSize
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseListItemSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.ui.theme.statusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * 供应商批量健康检测状态。
 *
 * - [Idle]: 未测试，不占视觉空间
 * - [Testing]: 检测进行中
 * - [Success]: 检测通过，携带延迟与可用模型数
 * - [Failed]: 检测失败，携带分类错误原因
 */
sealed class ProviderTestStatus {
    data object Idle : ProviderTestStatus()
    data object Testing : ProviderTestStatus()
    data class Success(val latencyMs: Long, val modelCount: Int) : ProviderTestStatus()
    data class Failed(val error: String) : ProviderTestStatus()
}

/**
 * 供应商分组列表。
 *
 * 按官方 / 中转站 / 自定义以圆角分组卡片呈现；顶部仅在存在冲突时展示警告，
 * 能力目录作为低干扰工具行沉底放置。添加与扫码入口已收拢至页面顶栏。
 */
@Suppress("LongParameterList")
internal fun LazyListScope.providerListSection(
    providers: List<ProviderConfig>,
    activeProviderId: String?,
    onActivate: (String) -> Unit,
    onEdit: (ProviderConfig) -> Unit,
    onDelete: (String) -> Unit,
    onAddProvider: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onScanQr: () -> Unit = {},
    testStatuses: Map<String, ProviderTestStatus> = emptyMap(),
    isTestingAll: Boolean = false,
    onTestAll: () -> Unit = {},
    hasUsableProvider: Boolean = false,
) {
    if (providers.isEmpty()) {
        item(key = "providers_empty") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MuseSpacing.xxl, vertical = MuseSpacing.xxxl),
                contentAlignment = Alignment.Center,
            ) {
                // 内置免费网关在列表中隐藏，但用户已可直接对话，空态标题须区分可用状态。
                MuseEmptyState(
                    icon = MuseIcons.atom,
                    title = stringResource(
                        if (hasUsableProvider) {
                            R.string.settings_provider_free_ready_title
                        } else {
                            R.string.settings_provider_empty_title
                        },
                    ),
                    actionText = stringResource(R.string.settings_provider_empty_action),
                    onAction = onAddProvider,
                )
            }
        }
        return
    }

    item(key = "provider_collision_warning") {
        ProviderCollisionWarning(providers)
    }

    val sections = listOf(
        ProviderCategorySection(
            key = "official",
            titleRes = R.string.settings_provider_category_official,
            items = providers.filter { it.category == ProviderCategory.OFFICIAL },
        ),
        ProviderCategorySection(
            key = "relay",
            titleRes = R.string.settings_provider_category_relay,
            items = providers.filter { it.category == ProviderCategory.RELAY },
        ),
        ProviderCategorySection(
            key = "custom",
            titleRes = R.string.settings_provider_category_custom,
            items = providers.filter { it.category == ProviderCategory.CUSTOM },
        ),
    ).filter { it.items.isNotEmpty() }

    sections.forEachIndexed { index, section ->
        item(key = "provider_section_${section.key}") {
            Column(verticalArrangement = Arrangement.spacedBy(MuseSpacing.smTight)) {
                ProviderCategoryHeader(
                    title = stringResource(section.titleRes),
                    showTestAll = index == 0,
                    isTestingAll = isTestingAll,
                    onTestAll = onTestAll,
                )
                CardGroup {
                    section.items.forEach { config ->
                        item(key = config.id) {
                            ProviderRow(
                                config = config,
                                isActive = config.id == activeProviderId,
                                onEdit = { onEdit(config) },
                                onActivate = { onActivate(config.id) },
                                onDelete = { onDelete(config.id) },
                                testStatus = testStatuses[config.id] ?: ProviderTestStatus.Idle,
                            )
                        }
                    }
                }
            }
        }
    }

    // 模型能力目录作为低频维护项放在供应商列表下方，避免首屏噪声。
    item(key = "provider_model_catalog") {
        ModelCatalogCard()
    }
}

private data class ProviderCategorySection(
    val key: String,
    val titleRes: Int,
    val items: List<ProviderConfig>,
)

@Composable
private fun ProviderCollisionWarning(providers: List<ProviderConfig>) {
    val collisions = remember(providers) { ProviderCollisionDetector.detect(providers) }
    if (collisions.isEmpty()) return

    val statusColors = MaterialTheme.statusColors
    Surface(
        shape = MuseShapes.medium,
        color = statusColors.warningContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
        ) {
            Icon(
                imageVector = MuseIcons.alertTriangle,
                contentDescription = null,
                tint = statusColors.warning,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
            )
            Text(
                text = stringResource(R.string.provider_collision_warning, collisions.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ProviderCategoryHeader(
    title: String,
    showTestAll: Boolean,
    isTestingAll: Boolean,
    onTestAll: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MuseSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (showTestAll) {
            if (isTestingAll) {
                MuseSpinner(
                    size = MuseIconSizes.iconTiny,
                    strokeWidth = 1.5.dp,
                )
                Spacer(Modifier.width(MuseSpacing.smTight))
            }
            MuseCapsuleButton(
                text = stringResource(R.string.settings_provider_test_all),
                onClick = onTestAll,
                enabled = !isTestingAll,
                variant = IosCapsuleButtonVariant.Text,
                size = MuseButtonSize.Compact,
                fillWidth = false,
            )
        }
    }
}

@Composable
private fun ModelCatalogCard() {
    val repository: ModelCatalogRepository = koinInject()
    var status by remember { mutableStateOf(repository.status()) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    CardGroup {
        item(key = "catalog_row") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MuseListItemSizes.minHeight)
                    .padding(MusePaddings.cardInner),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MuseSpacing.md),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(MuseSpacing.xxs),
                ) {
                    Text(
                        text = stringResource(R.string.settings_model_catalog_title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = buildString {
                            append(
                                context.getString(
                                    R.string.settings_model_catalog_counts,
                                    status.providerCount,
                                    status.modelCount,
                                ),
                            )
                            val ts = formatCatalogTimestamp(status.publishedAt)
                            if (ts != "—") {
                                append(" · ")
                                append(ts)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                MuseCapsuleButton(
                    text = stringResource(
                        if (refreshing) {
                            R.string.settings_model_catalog_refreshing
                        } else {
                            R.string.settings_model_catalog_refresh
                        },
                    ),
                    onClick = {
                        if (!refreshing) {
                            refreshing = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { repository.refresh() }
                                status = repository.status()
                                refreshing = false
                                MuseToast.show(result.message)
                            }
                        }
                    },
                    variant = IosCapsuleButtonVariant.Text,
                    size = MuseButtonSize.Compact,
                    fillWidth = false,
                )
            }
        }
    }
}

@Composable
internal fun ProviderRow(
    config: ProviderConfig,
    isActive: Boolean,
    onEdit: () -> Unit,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
    testStatus: ProviderTestStatus = ProviderTestStatus.Idle,
) {
    var actionsOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.settings_provider_delete_title),
            itemName = config.displayName.ifBlank { config.id },
            consequence = stringResource(R.string.settings_provider_delete_consequence),
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }

    val typeLabel = providerDisplayTypeName(config.type)
    val modelCountLabel = if (config.models.isNotEmpty()) {
        stringResource(R.string.settings_preset_model_count, config.models.size)
    } else {
        null
    }
    val disabledLabel = stringResource(R.string.settings_provider_status_disabled)
    val subtitleText = buildString {
        if (!config.enabled) {
            append(disabledLabel)
            append(" · ")
        }
        append(typeLabel)
        if (modelCountLabel != null) {
            append(" · ")
            append(modelCountLabel)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MuseListItemSizes.minHeight)
            .clickable(onClick = onEdit)
            .padding(MusePaddings.cardInner),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.md),
    ) {
        ProviderLogo(
            type = config.type,
            name = config.displayName,
            size = 36.dp,
            cornerRadius = 10.dp,
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(MuseSpacing.xxs),
        ) {
            Text(
                text = config.displayName.ifBlank { config.id },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (!config.enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitleText,
                style = MaterialTheme.typography.bodySmall,
                color = if (!config.enabled) {
                    MaterialTheme.colorScheme.outline
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (testStatus !is ProviderTestStatus.Idle) {
                Box(modifier = Modifier.padding(top = MuseSpacing.xxs)) {
                    ProviderTestStatusChip(testStatus)
                }
            }
        }

        if (isActive) {
            Icon(
                imageVector = MuseIcons.check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
            )
        }

        Box {
            MuseTactileButton(
                icon = MuseIcons.moreHorizontal,
                onClick = { actionsOpen = true },
                contentDescription = stringResource(R.string.action_more),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                compact = true,
            )
            MuseAnchoredMenu(
                expanded = actionsOpen,
                onDismissRequest = { actionsOpen = false },
                alignEnd = true,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(MuseSpacing.xxs),
                ) {
                    if (!isActive) {
                        MuseListItem(
                            onClick = {
                                actionsOpen = false
                                onActivate()
                            },
                            headlineContent = {
                                Text(stringResource(R.string.settings_provider_action_activate))
                            },
                        )
                    }
                    MuseListItem(
                        onClick = {
                            actionsOpen = false
                            confirmDelete = true
                        },
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.settings_provider_delete_title),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderTestStatusChip(status: ProviderTestStatus) {
    val statusColors = MaterialTheme.statusColors
    when (status) {
        ProviderTestStatus.Idle -> Unit
        ProviderTestStatus.Testing -> {
            Surface(
                color = statusColors.neutralContainer,
                shape = MuseShapes.small,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = MuseSpacing.sm, vertical = MuseSpacing.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                ) {
                    MuseSpinner(size = 10.dp, strokeWidth = 1.5.dp)
                    Text(
                        text = stringResource(R.string.settings_provider_test_row_testing),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        is ProviderTestStatus.Success -> {
            Surface(
                color = statusColors.successContainer,
                shape = MuseShapes.small,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = MuseSpacing.sm, vertical = MuseSpacing.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                ) {
                    Icon(
                        imageVector = MuseIcons.check,
                        contentDescription = null,
                        tint = statusColors.success,
                        modifier = Modifier.size(MuseIconSizes.iconTiny),
                    )
                    Text(
                        text = stringResource(R.string.settings_provider_test_result_ok, status.latencyMs.toInt()),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColors.success,
                    )
                }
            }
        }
        is ProviderTestStatus.Failed -> {
            Surface(
                color = statusColors.errorContainer,
                shape = MuseShapes.small,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = MuseSpacing.sm, vertical = MuseSpacing.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                ) {
                    Icon(
                        imageVector = MuseIcons.x,
                        contentDescription = null,
                        tint = statusColors.error,
                        modifier = Modifier.size(MuseIconSizes.iconTiny),
                    )
                    Text(
                        text = stringResource(R.string.settings_provider_test_result_failed),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColors.error,
                    )
                }
            }
        }
    }
}

private fun formatCatalogTimestamp(raw: String): String {
    if (raw.isBlank()) return "—"
    return runCatching {
        val instant = java.time.Instant.parse(raw)
        val local = instant.atZone(java.time.ZoneId.systemDefault())
        java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd")
            .format(local)
    }.getOrDefault(raw)
}
