package io.zer0.muse.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import io.zer0.muse.ui.common.IosChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import io.zer0.muse.ui.common.IosSwitch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseCornerRadius
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import androidx.compose.ui.graphics.luminance

/**
 * Phase 12: 跨页面共享 UI 组件。
 *
 * 根治"SectionLabel / SwitchRow / EmptyStateCard / ConfirmDeleteDialog 在 4 个 Screen
 * 重复定义 4 次"的复制粘贴问题。
 *
 * 所有组件统一使用 [MuseShapes] / [MusePaddings] 令牌,保证视觉一致性。
 */

/**
 * Section 分组标题(iOS 风格设置页分组标题)。
 *
 * 用法:`SectionLabel("基础")`
 *
 * 样式:labelLarge + primary 色(蓝色),与 SettingsScreen / AssistantScreen 等 4 个
 * 管理页统一。
 */
@Composable
fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        ),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
    )
}

/**
 * 设置项开关行(iOS 风格设置开关)。
 *
 * 用法:`SwitchRow("启用功能", "描述文字", checked, onCheckedChange = { ... })`
 */
@Composable
fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    // stringResource 需在 @Composable 直接调用位置提取,不能在 semantics{} 内使用。
    val enabledText = stringResource(R.string.common_state_enabled)
    val disabledText = stringResource(R.string.common_state_disabled)
    val rowInteractionSource = remember { MutableInteractionSource() }
    val isRowPressed by rowInteractionSource.collectIsPressedAsState()
    val rowBgColor by animateColorAsState(
        targetValue = if (isRowPressed) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else Color.Transparent,
        label = "switchRowBg",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBgColor)
            // M-SC1: 整行可点击切换开关(对标 SettingsSwitchRow),旧实现仅 IosSwitch 可切,
            // 点击文字区域无响应,不符合 iOS Settings 触发预期。
            .clickable(
                interactionSource = rowInteractionSource,
                indication = null,
            ) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        IosSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics {
                stateDescription = if (checked) enabledText else disabledText
            },
        )
    }
}

/**
 * 删除确认对话框(iOS 风格删除确认)。
 *
 * 在 AssistantScreen / LorebookScreen / QuickMessageScreen / PromptInjectionScreen
 * 等 4 个页面重复使用的"确定删除 X 吗"对话框。
 *
 * @param title 对话框标题(如"删除 Lorebook")
 * @param itemName 被删除项名称(显示在"确定删除 \"X\" 吗?")
 * @param onConfirm 确认删除回调(已包含删除逻辑 + 关闭对话框)
 * @param onDismiss 取消回调(关闭对话框)
 */
@Composable
fun ConfirmDeleteDialog(
    title: String,
    itemName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    MuseDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = { Text(stringResource(R.string.common_confirm_delete_message, itemName)) },
        confirmText = stringResource(R.string.common_delete),
        onConfirm = onConfirm,
        dismissText = stringResource(R.string.common_cancel),
        onDismiss = onDismiss,
        destructive = true,
    )
}

// ──────────────────────────────────────────────────────────────────────────────
// 阶段 7: iOS 风格分组列表组件
// ──────────────────────────────────────────────────────────────────────────────

/**
 * iOS 风格分组容器 — 一组共用圆角容器,行间用细分割线分隔。
 *
 * v0.34: 自包含实现 — 渲染为 Surface 卡片(surfaceVariant 0.5f 背景 + 20dp 圆角),
 * 与 [io.zer0.muse.ui.components.CardGroup] 视觉一致。保留此兼容封装供现有二级页
 * (BackupSection / ThemeSection / AboutSection 等)继续使用;一级设置页与新页面
 * 直接用 CardGroup + DSL item。
 *
 * 对标 iOS SwiftUI `Form` / `List(in:)` 的分组视觉。
 *
 * 用法:
 * ```
 * SettingsGroup {
 *     SettingsItemRow(icon, "标题", "副标题") { onClick() }
 *     SettingsGroupDivider()
 *     SettingsSwitchRow(icon, "开关", checked) { onChange(it) }
 * }
 * ```
 *
 * @param modifier Modifier(默认 fillMaxWidth)
 * @param content 分组内容(由调用方插入 [SettingsItemRow] / [SettingsSwitchRow] / [SettingsGroupDivider])
 */
@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val isLight = colorScheme.surface.luminance() > 0.5f
    // iOS 分组卡片: light=white@96%, dark=white@10%; 12dp 圆角 + 0.6dp 描边
    val cardColor = if (isLight) {
        Color.White.copy(alpha = 0.96f)
    } else {
        Color.White.copy(alpha = 0.10f)
    }
    val borderColor = colorScheme.outlineVariant.copy(alpha = if (isLight) 0.08f else 0.06f)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(MuseCornerRadius.CARD.dp),
        color = cardColor,
        border = androidx.compose.foundation.BorderStroke(0.6.dp, borderColor),
    ) {
        Column {
            content()
        }
    }
}

/**
 * 分组内分隔线 — 用在 [SettingsGroup] 的两个 item 之间。
 *
 * 留 16dp 左缩进(对标 iOS 分组分隔线不到边缘的视觉)。
 */
@Composable
fun SettingsGroupDivider() {
    HorizontalDivider(
        // iOS 分隔线: 左缩进 54dp (图标槽 36dp + 间距 12dp + 余量)
        modifier = Modifier.padding(start = 54.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.08f),
        thickness = 0.6.dp,
    )
}

/**
 * 通用设置项行 — 左侧图标 + 标题 + 副标题,右侧 trailing 内容。
 *
 * 对标 iOS SwiftUI `NavigationLink` / `Button` in Form 的视觉。
 *
 * @param icon 左侧图标(null 则不显示)
 * @param title 主标题
 * @param subtitle 副标题(null 则不显示,灰色小字)
 * @param onClick 点击回调(null 则不可点击)
 * @param enabled 是否启用点击(默认 true;false 时视觉不变但点击无效)
 * @param trailing 右侧 trailing 内容(默认空,可放箭头 / 数值 / 开关等)
 */
@Composable
fun SettingsItemRow(
    icon: ImageVector? = null,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
) {
    val rowInteractionSource = remember { MutableInteractionSource() }
    val isRowPressed by rowInteractionSource.collectIsPressedAsState()
    // iOS 按压: 白/黑偏移 55%, 220ms easeOutCubic
    val colorScheme = MaterialTheme.colorScheme
    val isLight = colorScheme.surface.luminance() > 0.5f
    val pressColor = if (isLight) Color.Black.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.08f)
    val rowBgColor by animateColorAsState(
        targetValue = if (isRowPressed) pressColor else Color.Transparent,
        animationSpec = tween(MuseAnimation.NORMAL_MS, easing = MuseAnimation.EaseOutCubic),
        label = "settingsItemBg",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBgColor)
            .then(
                if (onClick != null && enabled) Modifier.clickable(
                    interactionSource = rowInteractionSource,
                    indication = null,
                    onClick = onClick,
                ) else Modifier,
            )
            .padding(MusePaddings.cardInner),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
    ) {
        if (icon != null) {
            // 36dp 图标槽(圆角 8dp 背景色块)
            Surface(
                modifier = Modifier.size(36.dp),
                shape = MuseShapes.small,
                color = colorScheme.primaryContainer.copy(alpha = 0.15f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        }
        if (trailing != null) {
            trailing()
        }
    }
}

/**
 * 带开关的设置项行 — 左侧图标 + 标题 + 副标题,右侧 Switch。
 *
 * 对标 iOS SwiftUI `Toggle` in Form 的视觉。比通用 [SwitchRow] 多了图标支持。
 *
 * @param icon 左侧图标(null 则不显示)
 * @param title 主标题
 * @param subtitle 副标题(null 则不显示)
 * @param checked 开关状态
 * @param onCheckedChange 开关切换回调
 */
@Composable
fun SettingsSwitchRow(
    icon: ImageVector? = null,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val enabledText = stringResource(R.string.common_state_enabled)
    val disabledText = stringResource(R.string.common_state_disabled)
    val rowInteractionSource = remember { MutableInteractionSource() }
    val isRowPressed by rowInteractionSource.collectIsPressedAsState()
    val colorScheme = MaterialTheme.colorScheme
    val isLight = colorScheme.surface.luminance() > 0.5f
    val pressColor = if (isLight) Color.Black.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.08f)
    val rowBgColor by animateColorAsState(
        targetValue = if (isRowPressed) pressColor else Color.Transparent,
        animationSpec = tween(MuseAnimation.NORMAL_MS, easing = MuseAnimation.EaseOutCubic),
        label = "settingsSwitchRowBg",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBgColor)
            .clickable(
                interactionSource = rowInteractionSource,
                indication = null,
            ) { onCheckedChange(!checked) }
            .padding(MusePaddings.cardInner),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
    ) {
        if (icon != null) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = MuseShapes.small,
                color = colorScheme.primaryContainer.copy(alpha = 0.15f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        }
        IosSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics {
                stateDescription = if (checked) enabledText else disabledText
            },
        )
    }
}

/**
 * 带分段控件的设置项行 — 左侧图标 + 标题 + 副标题,下方嵌入 [SegmentedControl]。
 *
 * 用于聊天风格、语气、主题模式等需要在分组卡片内做单选的场景,
 * 统一分段控件与卡片其他行的视觉间距。
 *
 * @param icon 左侧图标
 * @param title 主标题
 * @param subtitle 副标题
 * @param options 选项文本列表
 * @param selectedIndex 当前选中索引
 * @param onSelectedChange 选中变更回调
 */
@Composable
fun SettingsSegmentedRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    options: List<String>,
    selectedIndex: Int,
    onSelectedChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(MuseIconSizes.iconMedium),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            SegmentedControl(
                options = options,
                selectedIndex = selectedIndex,
                onSelectedChange = onSelectedChange,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/**
 * 右箭头 — iOS 风格设置项右侧的 ">" 指示符。
 *
 * 用在 [SettingsItemRow] 的 trailing 位置,提示该行可点击进入下一级。
 */
@Composable
fun ChevronRight() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.outline,
    )
}

// ──────────────────────────────────────────────────────────────────────────────
// 阶段 A/B: 高级感组件 — 折叠分区 + 多选 chip + 状态指示
// ──────────────────────────────────────────────────────────────────────────────

/**
 * 折叠分区 — iOS Settings 高级感核心组件。
 *
 * 设计:
 *  - 标题行(图标 + 标题 + 副标题 + 旋转箭头)
 *  - 点击标题行展开/折叠内容区
 *  - 箭头旋转动画:tween 250ms,展开时 180°(向上)
 *  - 内容区 expandVertically + fadeIn,带柔和过渡
 *  - 标题行用 surfaceVariant 圆角容器,展开时容器包裹内容
 *
 * 用法:
 * ```
 * CollapsibleSection(
 *     title = "模型高级",
 *     subtitle = "temperature / topP / maxTokens",
 *     icon = Icons.Outlined.Tune,
 * ) {
 *     // 内容
 * }
 * ```
 *
 * @param title 分区标题
 * @param subtitle 副标题(可选,灰色小字)
 * @param icon 左侧图标(可选)
 * @param defaultExpanded 默认是否展开(默认 false)
 * @param content 分区内容
 */
@Composable
fun CollapsibleSection(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    defaultExpanded: Boolean = false,
    // M-SC3: 增加 key 参数 — 当同一页面存在多个 title 相同的分区时,
    // rememberSaveable(title) 会共享状态导致展开/折叠互相串扰,key 用于区分实例。
    key: Any? = null,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(title, key) { mutableStateOf(defaultExpanded) }
    // 箭头旋转动画(180° → 0°)
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(250),
        label = "section_arrow",
    )

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = MuseShapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            // 标题行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    // M-SC2: 裸 16/14dp 替换为 MusePaddings.cardInner 令牌(与 SettingsItemRow 一致)。
                    .padding(MusePaddings.cardInner),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = if (expanded) stringResource(R.string.common_collapse) else stringResource(R.string.common_expand),
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .size(24.dp)
                        .rotate(rotation),
                )
            }
            // 内容区(带过渡动画)
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(250)) + fadeIn(tween(MuseAnimation.TACTILE_MS)),
                exit = shrinkVertically(tween(250)) + fadeOut(tween(MuseAnimation.TACTILE_MS)),
            ) {
                Column {
                    if (subtitle != null) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            thickness = 0.5.dp,
                        )
                    }
                    content()
                }
            }
        }
    }
}

/**
 * 多选 chip 行 — 高级感关联资源选择器。
 *
 * 设计:
 *  - 已选行:IosChip(selected=false)+ Close 图标(点击移除)
 *  - 候选行:IosChip(已选变 primary,未选变 surfaceVariant)
 *  - 两行用 8dp 间距分隔
 *  - 候选为空时显示"暂无可选项"灰色提示
 *
 * 用法:
 * ```
 * MultiSelectChipRow(
 *     label = "QuickMessage",
 *     items = allQuickMessages,
 *     selectedIds = selectedQmIds,
 *     itemId = { it.id },
 *     itemLabel = { it.name },
 *     onToggle = { id -> viewModel.toggleQuickMessage(id) },
 * )
 * ```
 *
 * @param label 顶部小标题(灰色 labelMedium)
 * @param items 候选项列表
 * @param selectedIds 已选项 ID 集合
 * @param itemId 从项提取 ID 的函数
 * @param itemLabel 从项提取显示文本的函数
 * @param onToggle 切换某项选中状态的回调(参数为 itemId)
 * @param emptyHint 候选为空时的提示文案
 */
@Composable
fun <T> MultiSelectChipRow(
    label: String,
    items: List<T>,
    selectedIds: Set<String>,
    itemId: (T) -> String,
    itemLabel: (T) -> String,
    onToggle: (String) -> Unit,
    emptyHint: String = stringResource(R.string.common_no_options),
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 标签
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )

        if (items.isEmpty()) {
            Text(
                text = emptyHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
            )
            return@Column
        }

        // 已选行(只显示已选项,带移除按钮)
        val selected = items.filter { itemId(it) in selectedIds }
        if (selected.isNotEmpty()) {
            ChipFlowRow(
                items = selected,
                itemLabel = itemLabel,
                isSelected = { true },
                onClick = { onToggle(itemId(it)) },
                showRemoveIcon = true,
            )
        }

        // 候选行(显示所有项,已选高亮 + check 图标)
        Text(
            text = stringResource(R.string.common_selectable),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
        )
        ChipFlowRow(
            items = items,
            itemLabel = itemLabel,
            isSelected = { itemId(it) in selectedIds },
            onClick = { onToggle(itemId(it)) },
            showRemoveIcon = false,
        )
    }
}

/**
 * chip 横向自动换行布局(基于 FlowRow)。
 */
@Composable
private fun <T> ChipFlowRow(
    items: List<T>,
    itemLabel: (T) -> String,
    isSelected: (T) -> Boolean,
    onClick: (T) -> Unit,
    showRemoveIcon: Boolean,
) {
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { item ->
            val label = itemLabel(item)
            val selected = isSelected(item)
            if (selected && showRemoveIcon) {
                IosChip(
                    selected = false,
                    onClick = { onClick(item) },
                    label = label,
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.common_remove),
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
            } else {
                IosChip(
                    selected = selected,
                    onClick = { onClick(item) },
                    label = label,
                    leadingIcon = if (selected) {
                        {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else null,
                )
            }
        }
    }
}

/**
 * 状态指示小圆点 — 用于 MCP server / Provider 连接状态。
 *
 * @param color 状态颜色(CONNECTED 绿 / CONNECTING 黄 / ERROR 红 / DISCONNECTED 灰)
 * @param size 圆点尺寸(默认 8dp)
 * @param pulse 是否启用脉冲动画(CONNECTING 状态用)
 * @param contentDescription 无障碍描述(如"已连接"),为 null 时不注册语义
 */
@Composable
fun StatusDot(
    color: Color,
    size: Dp = 8.dp,
    pulse: Boolean = false,
    contentDescription: String? = null,
) {
    val alpha by animateFloatAsState(
        targetValue = if (pulse) 0.3f else 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = tween(MuseAnimation.LOOP_SLOW_MS),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "status_dot_pulse",
    )
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
            // L-SC6: 注册 contentDescription 语义, TalkBack 可朗读连接状态(如"已连接")。
            .semantics(mergeDescendants = false) {
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                }
            },
    )
}
