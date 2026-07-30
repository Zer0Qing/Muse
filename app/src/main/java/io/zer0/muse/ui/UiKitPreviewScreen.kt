@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.zer0.muse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.state.MuseEmotionalEmptyState
import io.zer0.muse.ui.common.state.MuseErrorStateBox
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseCapsuleTab
import io.zer0.muse.ui.common.form.MuseDropdown
import io.zer0.muse.ui.common.form.MuseFloatingButton
import io.zer0.muse.ui.common.form.MuseSlider
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.state.MuseLoadingState
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseSegmentedControl
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.MuseDivider
import io.zer0.muse.ui.common.surface.MuseSurface
import io.zer0.muse.ui.theme.MuseCornerRadius
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.mega

/**
 * v1.0.27: UI 库预览页 — 展示所有 Muse UI 组件和设计令牌。
 *
 * 用于开发期回归验证和视觉一致性检查。
 * 入口:设置 → 关于 → UI 库预览。
 *
 * ── 门禁规约(强制) ────────────────────────────────────────────────────
 * 新增/变更 Muse 组件库(common/surface|form|feedback|navigation|state|settings|media)
 * 内的组件时，必须同步在本文件登记对应预览，否则不予合并。
 *
 * 登记步骤:
 *  1. 在下方 item 列表添加 `item { XxxSection() }`
 *  2. 在文件末尾追加 `@Composable private fun XxxSection()` 实现
 *  3. 预览须覆盖:默认态 / 交互态(点击/聚焦/禁用) / 极端文本长度
 *  4. 组件签名变更时同步更新预览调用
 * ──────────────────────────────────────────────────────────────────────
 */
@Composable
fun UiKitPreviewScreen(
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        MuseTopBar(
            title = "UI 库预览",
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = MusePaddings.screen,
                vertical = MusePaddings.contentGap,
            ),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.sectionGap),
        ) {
            item { DesignTokensSection() }
            item { SurfaceSection() }
            item { DividerSection() }
            item { CardGroupSection() }
            item { StateComponentsSection() }
            item { EmotionalEmptyStateSection() }
            item { InteractiveComponentsSection() }
            item { InputComponentsSection() }
            item { ButtonsSection() }
            item { FloatingButtonSection() }
            item { ChipsAndTabsSection() }
        }
    }
}

// ============================================================
// Section 标题
// ============================================================

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = MusePaddings.contentGap),
    )
}

// ============================================================
// 1. 设计令牌
// ============================================================

@Composable
private fun DesignTokensSection() {
    Column {
        SectionTitle("设计令牌 / Design Tokens")

        // 颜色色板
        Text(
            "ColorScheme",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(MusePaddings.contentGap))
        ColorSwatchGrid()

        Spacer(Modifier.height(MusePaddings.sectionGap))

        // 圆角
        Text(
            "MuseShapes 圆角",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(MusePaddings.contentGap))
        Row(
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            modifier = Modifier.fillMaxWidth(),
        ) {
            CornerSample("tiny", 4.dp)
            CornerSample("small", 8.dp)
            CornerSample("medium", 12.dp)
            CornerSample("large", 18.dp)
            CornerSample("xLarge", 20.dp)
        }

        Spacer(Modifier.height(MusePaddings.sectionGap))

        // 海拔
        Text(
            "MuseElevation 海拔",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(MusePaddings.contentGap))
        Row(
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            modifier = Modifier.fillMaxWidth(),
        ) {
            ElevationSample("none", MuseElevation.none)
            ElevationSample("low", MuseElevation.low)
            ElevationSample("medium", MuseElevation.medium)
            ElevationSample("high", MuseElevation.high)
        }
    }
}

@Composable
private fun ColorSwatchGrid() {
    val cs = MaterialTheme.colorScheme
    val swatches = listOf(
        "primary" to cs.primary,
        "onPrimary" to cs.onPrimary,
        "primaryContainer" to cs.primaryContainer,
        "onPrimaryContainer" to cs.onPrimaryContainer,
        "secondary" to cs.secondary,
        "surface" to cs.surface,
        "surfaceVariant" to cs.surfaceVariant,
        "background" to cs.background,
        "onSurface" to cs.onSurface,
        "onSurfaceVariant" to cs.onSurfaceVariant,
        "outline" to cs.outline,
        "outlineVariant" to cs.outlineVariant,
        "error" to cs.error,
        "errorContainer" to cs.errorContainer,
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
    ) {
        swatches.forEach { (name, color) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(MuseCornerRadius.SMALL.dp))
                        .background(color),
                )
                Spacer(Modifier.height(MusePaddings.tightGap))
                Text(
                    name,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CornerSample(label: String, radius: androidx.compose.ui.unit.Dp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(radius))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
        )
        Spacer(Modifier.height(MusePaddings.tightGap))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ElevationSample(label: String, elevation: androidx.compose.ui.unit.Dp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(MuseCornerRadius.MEDIUM.dp))
                .background(MaterialTheme.colorScheme.surface)
                .then(
                    Modifier.padding(0.dp),
                ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface),
            )
        }
        Spacer(Modifier.height(MusePaddings.tightGap))
        Text(
            "$label\n${elevation.value}",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ============================================================
// 2. MuseSurface
// ============================================================

@Composable
private fun SurfaceSection() {
    Column {
        SectionTitle("MuseSurface 容器基元")

        Row(
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            modifier = Modifier.fillMaxWidth(),
        ) {
            MuseSurface(
                modifier = Modifier.weight(1f),
                shape = MuseShapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier.padding(MusePaddings.cardInner),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(MusePaddings.contentGap))
                    Text("静态容器", style = MaterialTheme.typography.labelMedium)
                }
            }
            MuseSurface(
                onClick = { MuseToast.show("点击了 MuseSurface") },
                modifier = Modifier.weight(1f),
                shape = MuseShapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                enableScale = true,
            ) {
                Column(
                    modifier = Modifier.padding(MusePaddings.cardInner),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Outlined.Palette,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(MusePaddings.contentGap))
                    Text("可点击 + 缩放", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

// ============================================================
// 3. MuseDivider
// ============================================================

@Composable
private fun DividerSection() {
    Column {
        SectionTitle("MuseDivider 分隔线基元")

        MuseSurface(
            shape = MuseShapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
                Text("列表项 A", style = MaterialTheme.typography.bodyMedium)
                MuseDivider()
                Text("列表项 B", style = MaterialTheme.typography.bodyMedium)
                MuseDivider()
                Text("列表项 C", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ============================================================
// 4. CardGroup
// ============================================================

@Composable
private fun CardGroupSection() {
    var switchChecked by remember { mutableStateOf(true) }

    Column {
        SectionTitle("CardGroup 卡片分组")

        CardGroup(
            title = { Text("通用设置") },
        ) {
            item(
                onClick = { MuseToast.show("点击了模型设置") },
                leadingContent = { Icon(Icons.Outlined.Cloud, contentDescription = null) },
                headlineContent = { Text("模型设置") },
                supportingContent = { Text("配置 AI 模型与服务") },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
            )
            item(
                leadingContent = { Icon(Icons.Outlined.Notifications, contentDescription = null) },
                headlineContent = { Text("通知") },
                supportingContent = { Text("推送与提醒") },
                trailingContent = {
                    MuseSwitch(
                        checked = switchChecked,
                        onCheckedChange = { switchChecked = it },
                    )
                },
            )
            item(
                onClick = { MuseToast.show("点击了外观") },
                leadingContent = { Icon(Icons.Outlined.Palette, contentDescription = null) },
                headlineContent = { Text("外观") },
                supportingContent = { Text("主题、字体、圆角") },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
            )
        }
    }
}

// ============================================================
// 5. 状态页组件
// ============================================================

@Composable
private fun StateComponentsSection() {
    Column {
        SectionTitle("状态页组件")

        // MuseEmptyState
        Text(
            "MuseEmptyState",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(MusePaddings.contentGap))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(MuseShapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center,
        ) {
            MuseEmptyState(
                icon = Icons.Outlined.Inbox,
                title = "暂无内容",
                subtitle = "下拉刷新或创建新项目",
            )
        }

        Spacer(Modifier.height(MusePaddings.sectionGap))

        // MuseErrorStateBox
        Text(
            "MuseErrorStateBox",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(MusePaddings.contentGap))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(MuseShapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center,
        ) {
            MuseErrorStateBox(
                message = "加载失败，请检查网络连接",
                onRetry = { MuseToast.show("重试中...") },
            )
        }

        Spacer(Modifier.height(MusePaddings.sectionGap))

        // MuseLoadingState
        Text(
            "MuseLoadingState",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(MusePaddings.contentGap))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(MuseShapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center,
        ) {
            MuseLoadingState(message = "加载中...")
        }
    }
}

// ============================================================
// 6. 交互组件
// ============================================================

@Composable
private fun InteractiveComponentsSection() {
    var switchChecked by remember { mutableStateOf(true) }
    var sliderValue by remember { mutableStateOf(0.6f) }
    var selectedIndex by remember { mutableStateOf(0) }
    var segIndex by remember { mutableStateOf(0) }

    Column {
        SectionTitle("交互组件")

        CardGroup(title = { Text("开关与滑块") }) {
            item(
                leadingContent = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                headlineContent = { Text("生物识别锁") },
                trailingContent = {
                    MuseSwitch(
                        checked = switchChecked,
                        onCheckedChange = { switchChecked = it },
                    )
                },
            )
            item(
                headlineContent = { Text("音量") },
                supportingContent = {
                    MuseSlider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                    )
                },
            )
        }

        Spacer(Modifier.height(MusePaddings.sectionGap))

        // MuseSegmentedControl
        Text(
            "MuseSegmentedControl",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(MusePaddings.contentGap))
        MuseSegmentedControl(
            options = listOf("全部", "未读", "已读"),
            selectedIndex = segIndex,
            onSelectedChange = { segIndex = it },
        )
    }
}

// ============================================================
// 7. 按钮
// ============================================================

@Composable
private fun ButtonsSection() {
    Column {
        SectionTitle("按钮")

        Row(
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Button(
                onClick = { MuseToast.show("Button") },
                modifier = Modifier.weight(1f),
                shape = MuseShapes.mega,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Text("浅绿按钮")
            }
            Button(
                onClick = { MuseToast.show("Button") },
                modifier = Modifier.weight(1f),
                shape = MuseShapes.mega,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text("深绿按钮")
            }
        }
        Spacer(Modifier.height(MusePaddings.contentGap))
        Row(
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = { MuseToast.show("OutlinedButton") },
                modifier = Modifier.weight(1f),
                shape = MuseShapes.mega,
            ) {
                Text("描边按钮")
            }
            TextButton(
                onClick = { MuseToast.show("TextButton") },
                modifier = Modifier.weight(1f),
            ) {
                Text("文字按钮")
            }
        }
    }
}

// ============================================================
// 8. Chip 与 Tab
// ============================================================

@Composable
private fun ChipsAndTabsSection() {
    var chipSelected by remember { mutableStateOf(0) }
    var tabSelected by remember { mutableStateOf(0) }

    Column {
        SectionTitle("Chip 与 Tab")

        // MuseChip
        Text(
            "MuseChip",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(MusePaddings.contentGap))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            listOf("月桂绿", "樱花粉", "海洋蓝", "琥珀金").forEachIndexed { index, label ->
                MuseChip(
                    selected = chipSelected == index,
                    onClick = { chipSelected = index },
                    label = label,
                    leadingIcon = { Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp)) },
                )
            }
        }

        Spacer(Modifier.height(MusePaddings.sectionGap))

        // MuseCapsuleTab
        Text(
            "MuseCapsuleTab",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(MusePaddings.contentGap))
        MuseCapsuleTab(
            tabs = listOf("任务", "Agent", "群聊"),
            selectedIndex = tabSelected,
            onSelect = { tabSelected = it },
        )
    }
}

// ============================================================
// 9. MuseEmotionalEmptyState
// ============================================================

@Composable
private fun EmotionalEmptyStateSection() {
    Column {
        SectionTitle("MuseEmotionalEmptyState 情感空状态")

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(MuseShapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        ) {
            MuseEmotionalEmptyState(
                onChatWithMuse = { MuseToast.show("跳转聊天") },
                onMeetCharacters = { MuseToast.show("浏览角色") },
            )
        }
    }
}

// ============================================================
// 10. 输入组件 (MuseTextField / MuseDropdown)
// ============================================================

@Composable
private fun InputComponentsSection() {
    var textValue by remember { mutableStateOf("") }
    var dropdownValue by remember { mutableStateOf("option2") }

    Column {
        SectionTitle("输入组件")

        // MuseTextField
        Text(
            "MuseTextField",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(MusePaddings.contentGap))
        MuseTextField(
            value = textValue,
            onValueChange = { textValue = it },
            label = { Text("用户名") },
            placeholder = { Text("请输入用户名") },
            singleLine = true,
        )

        Spacer(Modifier.height(MusePaddings.sectionGap))

        // MuseDropdown
        Text(
            "MuseDropdown",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(MusePaddings.contentGap))
        MuseDropdown(
            value = dropdownValue,
            onValueChange = { dropdownValue = it },
            label = "选项",
            options = listOf(
                "option1" to "选项一",
                "option2" to "选项二",
                "option3" to "选项三",
            ),
        )
    }
}

// ============================================================
// 11. MuseFloatingButton
// ============================================================

@Composable
private fun FloatingButtonSection() {
    Column {
        SectionTitle("MuseFloatingButton 浮动按钮")

        Row(
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.sectionGap),
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MuseFloatingButton(
                icon = Icons.Filled.Add,
                onClick = { MuseToast.show("默认 FAB") },
                contentDescription = "添加",
            )
            MuseFloatingButton(
                icon = Icons.Filled.Add,
                onClick = { MuseToast.show("小尺寸 FAB") },
                contentDescription = "添加",
                size = 48.dp,
                iconSize = MuseIconSizes.iconMedium,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
