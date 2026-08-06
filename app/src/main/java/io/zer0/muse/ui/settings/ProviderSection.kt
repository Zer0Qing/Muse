package io.zer0.muse.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import compose.icons.TablerIcons
import compose.icons.tablericons.AlertTriangle
import compose.icons.tablericons.Atom
import compose.icons.tablericons.Check
import compose.icons.tablericons.ChevronRight
import compose.icons.tablericons.Plus
import compose.icons.tablericons.Qrcode
import compose.icons.tablericons.X
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.ProviderConfig
import io.zer0.muse.R
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.data.ProviderCollisionDetector

/**
 * v1.133: 供应商批量健康检测状态(列表头部"全部检测"按钮触发)。
 *
 * - [Idle]: 未测试(默认),不显示状态 chip
 * - [Testing]: 检测进行中(显示 CircularProgressIndicator)
 * - [Success]: 检测通过(显示延迟 ms,绿色 ✓)
 * - [Failed]: 检测失败(显示错误简述,红色 ✗)
 *
 * 与 [ModelTestStatus] 区分:本状态针对整个 provider 的 /models 端点连通性,
 * 不发真实 chat 消息;且携带延迟数据用于排序/对比。
 */
sealed class ProviderTestStatus {
    data object Idle : ProviderTestStatus()
    data object Testing : ProviderTestStatus()
    data class Success(val latencyMs: Long, val modelCount: Int) : ProviderTestStatus()
    data class Failed(val error: String) : ProviderTestStatus()
}

/**
 * Provider 列表 section — iOS 风格分组列表。
 *
 * 用 [SettingsGroup] 包裹所有 Provider,每个 Provider 一行。
 * 行内左侧品牌图标、中间名称与类型、右侧启用/禁用状态标签 + 右箭头。
 *
 * v1.133: 新增 [testStatuses] / [isTestingAll] / [onTestAll] 用于"全部检测"批量并发
 * 健康检查(按 [ProviderEditPage] 的 testConnection 单测逻辑,提升为批量并发)。
 */
internal fun LazyListScope.providerListSection(
    providers: List<ProviderConfig>,
    activeProviderId: String?,
    onActivate: (String) -> Unit,
    onEdit: (ProviderConfig) -> Unit,
    onDelete: (String) -> Unit,
    onAddProvider: () -> Unit = {},
    onScanQr: () -> Unit = {},
    // v1.133: 批量健康检测(列表头部"全部检测"按钮触发)
    testStatuses: Map<String, ProviderTestStatus> = emptyMap(),
    isTestingAll: Boolean = false,
    onTestAll: () -> Unit = {},
) {
    item {
        ProviderListHeader(
            totalCount = providers.size,
            isTestingAll = isTestingAll,
            onTestAll = onTestAll,
        )
    }

    // 按 category 分组渲染(官方 / 中转站 / 自定义)
    val officialProviders = providers.filter { it.category == io.zer0.ai.core.ProviderCategory.OFFICIAL }
    val relayProviders = providers.filter { it.category == io.zer0.ai.core.ProviderCategory.RELAY }
    val customProviders = providers.filter { it.category == io.zer0.ai.core.ProviderCategory.CUSTOM }

    if (providers.isEmpty()) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 64.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                MuseEmptyState(
                    icon = TablerIcons.Atom,
                    title = stringResource(R.string.settings_provider_empty_title),
                    subtitle = stringResource(R.string.settings_provider_empty_subtitle),
                    actionText = stringResource(R.string.settings_provider_empty_action),
                    onAction = onAddProvider,
                )
            }
        }
        return
    }

    // P2-1: Provider 冲突检测 — 在列表顶部展示警告卡片(仅当存在重复配置时)
    item { ProviderCollisionWarning(providers) }

    // 官方厂商分组
    if (officialProviders.isNotEmpty()) {
        item { ProviderCategoryHeader(stringResource(R.string.settings_provider_category_official), officialProviders.size) }
        item {
            SettingsGroup {
                officialProviders.forEachIndexed { index, config ->
                    if (index > 0) SettingsGroupDivider()
                    ProviderRow(
                        config = config,
                        isActive = config.id == activeProviderId,
                        onEdit = { onEdit(config) },
                        testStatus = testStatuses[config.id] ?: ProviderTestStatus.Idle,
                    )
                }
            }
        }
    }

    // 中转站分组
    if (relayProviders.isNotEmpty()) {
        item { ProviderCategoryHeader(stringResource(R.string.settings_provider_category_relay), relayProviders.size) }
        item {
            SettingsGroup {
                relayProviders.forEachIndexed { index, config ->
                    if (index > 0) SettingsGroupDivider()
                    ProviderRow(
                        config = config,
                        isActive = config.id == activeProviderId,
                        onEdit = { onEdit(config) },
                        testStatus = testStatuses[config.id] ?: ProviderTestStatus.Idle,
                    )
                }
            }
        }
    }

    // 自定义分组
    if (customProviders.isNotEmpty()) {
        item { ProviderCategoryHeader(stringResource(R.string.settings_provider_category_custom), customProviders.size) }
        item {
            SettingsGroup {
                customProviders.forEachIndexed { index, config ->
                    if (index > 0) SettingsGroupDivider()
                    ProviderRow(
                        config = config,
                        isActive = config.id == activeProviderId,
                        onEdit = { onEdit(config) },
                        testStatus = testStatuses[config.id] ?: ProviderTestStatus.Idle,
                    )
                }
            }
        }
    }

    // "添加 Provider" 入口(始终在列表末尾)
    item {
        SettingsGroup(
            modifier = Modifier.padding(top = MusePaddings.itemGap),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAddProvider() }
                    .padding(horizontal = MusePaddings.screen, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
            ) {
                Icon(
                    imageVector = TablerIcons.Plus,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
                Text(
                    text = stringResource(R.string.settings_provider_add),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
            }
            // v1.97: 扫描二维码导入 Provider
            SettingsGroupDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onScanQr() }
                    .padding(horizontal = MusePaddings.screen, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
            ) {
                Icon(
                    imageVector = TablerIcons.Qrcode,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
                Text(
                    text = stringResource(R.string.qr_scan_btn),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * P2-1: Provider 冲突(重复配置)警告卡片。
 *
 * 调用 [ProviderCollisionDetector.detect] 检测当前 provider 列表中的重复配置,
 * 仅当检测到冲突时渲染警告卡片。卡片样式:
 *  - 形状:[MuseShapes.medium] 圆角
 *  - 背景:MaterialTheme.colorScheme.errorContainer
 *  - 图标:TablerIcons.AlertTriangle(着色 onErrorContainer)
 *  - 文案:provider_collision_warning(带冲突数量 %1$d)
 *
 * 仅提示,不展开详情,不阻断用户操作。
 */
@Composable
private fun ProviderCollisionWarning(providers: List<ProviderConfig>) {
    val collisions = remember(providers) { ProviderCollisionDetector.detect(providers) }
    if (collisions.isEmpty()) return

    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MusePaddings.itemGap),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.iconPadding),
        ) {
            Icon(
                imageVector = TablerIcons.AlertTriangle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
            Text(
                text = stringResource(R.string.provider_collision_warning, collisions.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun ProviderCategoryHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun ProviderRow(
    config: ProviderConfig,
    isActive: Boolean,
    onEdit: () -> Unit,
    // v1.133: 批量健康检测状态(由列表头部"全部检测"按钮触发,写入后行内显示)
    testStatus: ProviderTestStatus = ProviderTestStatus.Idle,
) {
    val brandColor = providerBrandColor(config.type, config.displayName)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() }
            .padding(MusePaddings.cardInner),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
    ) {
        // 左侧:品牌图标(圆形背景)
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(brandColor.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = providerBrandIcon(config.type, config.displayName),
                contentDescription = null,
                tint = brandColor,
                modifier = Modifier.size(MuseIconSizes.icon),
            )
        }

        // 中间:名称 + 类型
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = config.displayName.ifBlank { config.id },
                style = MaterialTheme.typography.bodyLarge,
                color = if (isActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = providerDisplayTypeName(config.type),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        // v1.133: 批量检测结果 chip(测试中显示转圈,成功显示延迟,失败显示错误简述)
        ProviderTestStatusChip(testStatus)

        // 右侧:启用/禁用状态标签 + 右箭头
        if (config.enabled) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MuseShapes.medium,
            ) {
                Text(
                    text = stringResource(R.string.settings_provider_status_enabled),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MuseShapes.medium,
            ) {
                Text(
                    text = stringResource(R.string.settings_provider_status_disabled),
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        Icon(
            imageVector = TablerIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(MuseIconSizes.iconMedium),
        )
    }
}

/**
 * v1.133: 供应商列表头部 — section 标题 + "全部检测"按钮。
 *
 * 替代原 [SectionLabel] 单文本。检测进行中按钮内显示 CircularProgressIndicator,
 * 点击触发 [onTestAll] 并发测试所有已配置的供应商。
 *
 * 按 UI:
 * ```
 * Row {
 *     Text("Provider 列表")
 *     Spacer(Modifier.weight(1f))
 *     if (testing) { CircularProgressIndicator(...) }
 *     TextButton(onClick = onTestAll) { Text("全部检测") }
 * }
 * ```
 */
@Composable
private fun ProviderListHeader(
    totalCount: Int,
    isTestingAll: Boolean,
    onTestAll: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionLabel(stringResource(R.string.settings_provider_list_title))
        Spacer(Modifier.weight(1f))
        // v1.133: 检测进行中显示转圈(尺寸 14dp,与按钮文字高度匹配)
        if (isTestingAll) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 1.5.dp,
            )
            Spacer(Modifier.width(8.dp))
        }
        TextButton(
            onClick = onTestAll,
            enabled = !isTestingAll && totalCount > 0,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 8.dp,
                vertical = 0.dp,
            ),
        ) {
            Text(
                text = stringResource(R.string.settings_provider_test_all),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * v1.133: 单个供应商检测结果 chip。
 *
 * - [ProviderTestStatus.Idle]: 不渲染(保持行内整洁)
 * - [ProviderTestStatus.Testing]: 小转圈 + "检测中"
 * - [ProviderTestStatus.Success]: 绿色 ✓ + "{latency} ms"(展示延迟)
 * - [ProviderTestStatus.Failed]: 红色 ✗ + "失败"(短文案,详情点入编辑页查看)
 */
@Composable
private fun ProviderTestStatusChip(status: ProviderTestStatus) {
    when (status) {
        ProviderTestStatus.Idle -> Unit
        ProviderTestStatus.Testing -> {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MuseShapes.small,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(10.dp),
                        strokeWidth = 1.5.dp,
                    )
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
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MuseShapes.small,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = TablerIcons.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = stringResource(R.string.settings_provider_test_result_ok, status.latencyMs.toInt()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
        is ProviderTestStatus.Failed -> {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MuseShapes.small,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = TablerIcons.X,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = stringResource(R.string.settings_provider_test_result_failed),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}
