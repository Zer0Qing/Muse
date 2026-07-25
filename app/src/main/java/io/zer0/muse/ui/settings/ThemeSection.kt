package io.zer0.muse.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.SettingsBrightness
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import io.zer0.muse.ui.common.IosSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import io.zer0.muse.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.IosSwitch
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.common.SectionLabel
import io.zer0.muse.ui.common.SegmentedControl
import io.zer0.muse.ui.common.SettingsGroup
import io.zer0.muse.ui.common.SettingsGroupDivider
import io.zer0.muse.ui.common.SettingsItemRow
import io.zer0.muse.ui.common.SettingsSwitchRow
import io.zer0.muse.ui.theme.CustomTheme
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.PresetThemes
import io.zer0.muse.ui.theme.PresetTheme
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.ui.theme.tiny
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 阶段 7: 外观 section — iOS 风格分组列表。
 *
 * 设计:
 *  - 用 [SettingsGroup] 包裹多行 item,行间细分割线
 *  - 主题模式(系统/浅色/深色)用胶囊分段控件
 *  - 字号选择(小/中/大/特大)用胶囊分段控件
 *  - 主题变体(warm_paper/amoled/ocean_blue)已收敛,只保留浅色 + 深色
 *  - 几乎不用 elevation,靠 surfaceVariant 色块分组
 */
@Composable
internal fun ThemeSection(
    themeMode: String,
    fontSizeScale: String,
    settings: SettingsRepository,
) {
    val scope = rememberCoroutineScope()
    val themeId by settings.themeIdFlow.collectAsStateWithLifecycle(initialValue = "warm_paper")
    // 深色模式独立主题
    val darkThemeId by settings.darkThemeIdFlow.collectAsStateWithLifecycle(initialValue = "")
    // v1.65: 动态取色开关(代码早已就绪,此前 UI 无开关导致永远不可用)
    val dynamicColor by settings.dynamicColorFlow.collectAsStateWithLifecycle(initialValue = false)
    val context = LocalContext.current
    // Android 12+ 才支持动态取色,低版本灰显(版本号在设备生命周期内不变,用 remember 缓存避免重组时重复读取)
    val dynamicColorSupported = remember { android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S }
    // v1.97 gap7: 用户自定义主题列表 + 编辑/删除弹窗状态
    val customThemes by settings.customThemesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var editingTheme by remember { mutableStateOf<CustomTheme?>(null) }
    var showEditDialog by remember { mutableStateOf(false) }
    var deletingTheme by remember { mutableStateOf<CustomTheme?>(null) }
    // 自定义主题预览需要按当前主题模式生成 ColorScheme(system 模式跟随系统深浅)
    val isDark = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }

    // ── 主题模式 ──
    SectionLabel(stringResource(R.string.settings_theme_appearance))
    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        val followSystem = stringResource(R.string.settings_theme_follow_system)
        val lightLabel = stringResource(R.string.settings_theme_light)
        val darkLabel = stringResource(R.string.settings_theme_dark)
        // 分段控件替代胶囊按钮
        val modeOptions = listOf(followSystem, lightLabel, darkLabel)
        val selectedMode = when (themeMode) {
            "system" -> 0; "light" -> 1; "dark" -> 2; else -> 0
        }
        SegmentedControl(
            options = modeOptions,
            selectedIndex = selectedMode,
            onSelectedChange = { idx ->
                scope.launch {
                    settings.saveThemeMode(
                        when (idx) { 0 -> "system"; 1 -> "light"; 2 -> "dark"; else -> "system" }
                    )
                }
            },
            modifier = Modifier.padding(MusePaddings.cardInner),
        )
        SettingsGroupDivider()
        val modeLabel = when (themeMode) {
            "system" -> followSystem
            "light" -> lightLabel
            "dark" -> darkLabel
            else -> followSystem
        }
        val modeIcon = when (themeMode) {
            "system" -> Icons.Outlined.SettingsBrightness
            "light" -> Icons.Outlined.LightMode
            "dark" -> Icons.Outlined.DarkMode
            else -> Icons.Outlined.SettingsBrightness
        }
        SettingsItemRow(
            icon = modeIcon,
            title = stringResource(R.string.settings_theme_current_mode),
            subtitle = modeLabel,
        )
    }

    // ── v0.22: 主题切换(预设主题网格选择器) ──
    SectionLabel(stringResource(R.string.section_theme))
    // 主题网格: 3 列卡片,每张展示主色+表面色+名称+选中态
    ThemeGridPicker(
        themes = PresetThemes,
        selectedThemeId = themeId,
        isDark = isDark,
        onSelect = { theme -> scope.launch { settings.saveThemeId(theme.id) } },
    )
    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        // v1.65: 动态取色开关 — Android 12+ 从系统壁纸提取颜色,覆盖预设主题
        DynamicColorRow(
            enabled = dynamicColor,
            supported = dynamicColorSupported,
            onToggle = { v -> scope.launch { settings.saveDynamicColor(v) } },
        )
    }

    // ── v1.97 gap7: 自定义主题区(基于种子色生成 ColorScheme) ──
    CustomThemeSection(
        customThemes = customThemes,
        themeId = themeId,
        isDark = isDark,
        onSelect = { theme -> scope.launch { settings.saveThemeId(theme.id) } },
        onAdd = {
            editingTheme = null
            showEditDialog = true
        },
        onEdit = { theme ->
            editingTheme = theme
            showEditDialog = true
        },
        onDelete = { theme -> deletingTheme = theme },
    )

    // 编辑/创建弹窗(MuseDialog 替代 ModalBottomSheet — 真机上 ModalBottomSheet 有 scrim 卡死 bug)
    if (showEditDialog) {
        CustomThemeEditDialog(
            theme = editingTheme,
            isDark = isDark,
            onDismiss = {
                showEditDialog = false
                editingTheme = null
            },
            onSave = { theme ->
                scope.launch {
                    settings.upsertCustomTheme(theme)
                    // 保存后自动切换到该主题,与 rikkahub 行为一致
                    settings.saveThemeId(theme.id)
                }
                showEditDialog = false
                editingTheme = null
            },
        )
    }

    // 删除确认弹窗
    deletingTheme?.let { theme ->
        MuseDialog(
            onDismissRequest = { deletingTheme = null },
            title = stringResource(R.string.settings_theme_custom_delete_confirm),
            content = {
                Text(
                    text = stringResource(R.string.settings_theme_custom_delete_confirm_msg),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.settings_theme_custom_delete),
            onConfirm = {
                scope.launch {
                    settings.deleteCustomTheme(theme.id)
                    // 删除后若当前 themeId 指向被删主题,回退到默认预设
                    if (themeId == theme.id) {
                        settings.saveThemeId("warm_paper")
                    }
                }
                deletingTheme = null
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { deletingTheme = null },
            destructive = true,
        )
    }

    // ── 深色模式独立主题(空字符串表示跟随亮色主题的暗色版) ──
    SectionLabel("深色模式主题")
    val darkThemeLabel = if (darkThemeId.isNotBlank()) {
        PresetThemes.firstOrNull { it.id == darkThemeId }?.let {
            stringResource(it.nameResId)
        } ?: darkThemeId
    } else {
        "跟随亮色主题"
    }
    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        // 跟随亮色主题选项
        ThemeOptionRow(
            name = "跟随亮色主题",
            isSelected = darkThemeId.isEmpty(),
            onClick = { scope.launch { settings.saveDarkThemeId("") } },
        )
        PresetThemes.forEachIndexed { index, theme ->
            SettingsGroupDivider()
            ThemeOptionRow(
                name = stringResource(theme.nameResId),
                isSelected = theme.id == darkThemeId,
                onClick = { scope.launch { settings.saveDarkThemeId(theme.id) } },
            )
        }
        SettingsGroupDivider()
        // 当前选择状态
        val darkIcon = if (darkThemeId.isNotBlank()) Icons.Outlined.DarkMode else Icons.Outlined.LightMode
        SettingsItemRow(
            icon = darkIcon,
            title = "深色主题",
            subtitle = darkThemeLabel,
        )
    }

    // ── 主题定时切换(Feature 4) ──
    val schedule by settings.themeScheduleFlow.collectAsStateWithLifecycle(initialValue = io.zer0.muse.data.ThemeScheduleConfig())
    SectionLabel("定时切换")
    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        SettingsSwitchRow(
            icon = Icons.Outlined.SettingsBrightness,
            title = "自动切换",
            subtitle = "在起床/睡觉时间自动切换亮暗模式",
            checked = schedule.enabled,
            onCheckedChange = { v ->
                scope.launch { settings.saveThemeSchedule(schedule.copy(enabled = v)) }
            },
        )
        if (schedule.enabled) {
            SettingsGroupDivider()
            SettingsItemRow(
                icon = Icons.Outlined.LightMode,
                title = "起床时间(浅色)",
                subtitle = "%02d:%02d".format(schedule.wakeUpHour, schedule.wakeUpMinute),
                onClick = {
                    scope.launch {
                        val newHour = (schedule.wakeUpHour + 1) % 24
                        settings.saveThemeSchedule(schedule.copy(wakeUpHour = newHour))
                    }
                },
            )
            SettingsGroupDivider()
            SettingsItemRow(
                icon = Icons.Outlined.DarkMode,
                title = "睡觉时间(深色)",
                subtitle = "%02d:%02d".format(schedule.sleepHour, schedule.sleepMinute),
                onClick = {
                    scope.launch {
                        val newHour = (schedule.sleepHour + 1) % 24
                        settings.saveThemeSchedule(schedule.copy(sleepHour = newHour))
                    }
                },
            )
        }
    }

    // ── 字号选择 ──
    SectionLabel(stringResource(R.string.settings_theme_font_size))
    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        val smallLabel = stringResource(R.string.settings_theme_font_small)
        val mediumLabel = stringResource(R.string.settings_theme_font_medium)
        val largeLabel = stringResource(R.string.settings_theme_font_large)
        val xlargeLabel = stringResource(R.string.settings_theme_font_xlarge)
        val smallScale = stringResource(R.string.settings_theme_font_small_label)
        val largeScale = stringResource(R.string.settings_theme_font_large_label)
        val xlargeScale = stringResource(R.string.settings_theme_font_xlarge_label)
        val mediumScale = stringResource(R.string.settings_theme_font_medium_label)
        // 分段控件替代胶囊按钮
        val fontOptions = listOf(smallLabel, mediumLabel, largeLabel, xlargeLabel)
        val selectedFont = when (fontSizeScale) {
            "small" -> 0; "large" -> 2; "xlarge" -> 3; else -> 1
        }
        SegmentedControl(
            options = fontOptions,
            selectedIndex = selectedFont,
            onSelectedChange = { idx ->
                scope.launch {
                    settings.saveFontSizeScale(
                        when (idx) { 0 -> "small"; 1 -> "medium"; 2 -> "large"; 3 -> "xlarge"; else -> "medium" }
                    )
                }
            },
            modifier = Modifier.padding(MusePaddings.cardInner),
        )
        SettingsGroupDivider()
        val scaleLabel = when (fontSizeScale) {
            "small" -> smallScale
            "large" -> largeScale
            "xlarge" -> xlargeScale
            else -> mediumScale
        }
        SettingsItemRow(
            icon = Icons.Outlined.FormatSize,
            title = stringResource(R.string.settings_theme_current_font_size),
            subtitle = scaleLabel,
        )
        // 阶段 K: 字号预览(实时展示当前字号下的文本效果,高级感)
        SettingsGroupDivider()
        FontSizePreview(fontSizeScale)
    }
}

/**
 * v1.65: 动态取色(Material You)开关行。
 * Android 12+ 从系统壁纸提取主色调,覆盖预设主题;低版本灰显并提示不支持。
 */
@Composable
private fun DynamicColorRow(
    enabled: Boolean,
    supported: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val dynamicColorLabel = stringResource(R.string.settings_theme_dynamic_color)
    val supportedText = stringResource(R.string.settings_theme_dynamic_color_supported)
    val unsupportedText = stringResource(R.string.settings_theme_dynamic_color_unsupported)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = supported) { onToggle(!enabled) }
            .padding(MusePaddings.cardInner),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Palette,
            contentDescription = null,
            tint = if (supported) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
            modifier = Modifier.size(MuseIconSizes.iconMedium),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = dynamicColorLabel,
                style = MaterialTheme.typography.bodyLarge,
                color = if (supported) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
            Text(
                text = if (supported) supportedText else unsupportedText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IosSwitch(
            checked = enabled && supported,
            onCheckedChange = if (supported) { { onToggle(it) } } else null,
            contentDescription = dynamicColorLabel,
        )
    }
}

/**
 * 阶段 K: 字号预览 — 用当前选中字号渲染样例文本,让用户直观感受字号变化。
 *
 * 在 surfaceVariant 半透明卡片中渲染三行样例(标题 / 正文 / 小字),
 * 字号按 scale 实时缩放,体现"所见即所得"。
 */
@Composable
private fun FontSizePreview(scale: String) {
    val factor = when (scale) {
        "small" -> 0.85f
        "large" -> 1.15f
        "xlarge" -> 1.3f
        else -> 1.0f
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = MuseShapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.settings_theme_font_preview_title),
                style = TextStyle(
                    fontSize = (MaterialTheme.typography.titleMedium.fontSize.value * factor).sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.settings_theme_font_preview_body),
                style = TextStyle(
                    fontSize = (MaterialTheme.typography.bodyLarge.fontSize.value * factor).sp,
                    lineHeight = (MaterialTheme.typography.bodyLarge.lineHeight.value * factor).sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = stringResource(R.string.settings_theme_font_preview_note),
                style = TextStyle(
                    fontSize = (MaterialTheme.typography.bodySmall.fontSize.value * factor).sp,
                ),
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * v0.22: 主题选项行 — 左侧主题名,右侧 Check icon(仅选中时显示)。
 *
 * 样式与 [SettingsItemRow] 对齐(16/12 padding),作为"主题"分组内的单选项;
 * 选中时主题名与图标都用 primary 色高亮。
 */
@Composable
private fun ThemeOptionRow(
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(MusePaddings.cardInner),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = stringResource(R.string.settings_theme_selected),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
        }
    }
}

/**
 * v0.29 P2-12: 主题实时预览卡片 — 展示当前主题的对话气泡样式。
 *
 * 用户切换主题/模式后,此卡片立即反映新配色(因为读的是 MaterialTheme.colorScheme)。
 */
@Composable
private fun ThemePreviewCard() {
    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.screen),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_theme_preview),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            // 用户气泡(右对齐,品牌色)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Surface(
                    shape = MuseShapes.large,
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Text(
                        text = stringResource(R.string.settings_theme_preview_hello),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
            // AI 气泡(左对齐,surfaceVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
            ) {
                Surface(
                    shape = MuseShapes.large,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Text(
                        text = stringResource(R.string.settings_theme_preview_reply),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/**
 * 主题网格选择器 — 3 列卡片展示预设主题,每张卡片显示主色+表面色+名称。
 *
 * 选中态: 主题色边框 + 勾号。支持浅色/深色模式预览。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ThemeGridPicker(
    themes: List<PresetTheme>,
    selectedThemeId: String,
    isDark: Boolean,
    onSelect: (PresetTheme) -> Unit,
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        maxItemsInEachRow = 3,
    ) {
        themes.forEach { theme ->
            val isSelected = theme.id == selectedThemeId
            val scheme = if (isDark) theme.darkScheme else theme.lightScheme
            ThemeGridCard(
                name = stringResource(theme.nameResId),
                primaryColor = scheme.primary,
                surfaceColor = scheme.surface,
                isSelected = isSelected,
                onClick = { onSelect(theme) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 单张主题网格卡片 — 色板预览 + 名称 + 选中勾号。
 */
@Composable
private fun ThemeGridCard(
    name: String,
    primaryColor: Color,
    surfaceColor: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (isSelected) primaryColor else Color.Transparent
    Surface(
        modifier = modifier
            .height(80.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        shape = MuseShapes.medium,
        color = surfaceColor,
        border = androidx.compose.foundation.BorderStroke(
            width = if (isSelected) 2.dp else 0.dp,
            color = borderColor,
        ),
    ) {
        Box(modifier = Modifier.padding(10.dp)) {
            Column {
                // 色板: 主色圆点 + 表面色条
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(primaryColor),
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(8.dp)
                            .clip(MuseShapes.tiny)
                            .background(primaryColor.copy(alpha = 0.3f)),
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
            // 选中勾号
            if (isSelected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = primaryColor,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(16.dp),
                )
            }
        }
    }
}

/**
 * v1.60-C: 语言选择区 — 胶囊分段控件,仿 ThemeModeOption 模式。
 * 选项:跟随系统 / 中文 / 英文 / 日语 / 韩语 / 俄语。
 * v1.132: 新增 ja/ko/ru 三种语言,6 选项改用 FlowRow 两行三列布局,避免窄屏挤压。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun LanguageSection(
    language: String,
    settings: SettingsRepository,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    SectionLabel(stringResource(R.string.settings_theme_language))
    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        val followSystem = stringResource(R.string.settings_theme_follow_system)
        val zhLabel = stringResource(R.string.settings_theme_language_zh)
        val enLabel = stringResource(R.string.settings_theme_language_en)
        val jaLabel = stringResource(R.string.settings_theme_language_ja)
        val koLabel = stringResource(R.string.settings_theme_language_ko)
        val ruLabel = stringResource(R.string.settings_theme_language_ru)
        // v1.132: 6 个选项用 FlowRow 包装,每行最多 3 个,避免窄屏挤压。
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 3,
        ) {
            // v1.133: 修复语言切换卡死 + 不生效问题。
            // 原方案用 recreate() 在协程中触发,旧 Activity 的 Compose 重组未结束就被销毁,
            // 新 Activity 重建过程中 Compose state 死锁 → UI 卡死(ANR)。
            // 同时 createConfigurationContext 在部分 ROM 上 locale 不生效 → 重启后看到新选项但实际未切换。
            //
            // 新方案:saveLanguage 后用 Intent + finish() 干净重启 Activity,
            // 让旧 Activity 完整走 onDestroy,新 Activity 走完整 attachBaseContext 流程。
            // 同时在 wrapWithLanguage 中额外调用 updateConfiguration 兜底,确保 locale 强制生效。
            val restartActivity: (String) -> Unit = { lang ->
                scope.launch {
                    settings.saveLanguage(lang)
                    val activity = context as? android.app.Activity ?: return@launch
                    val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
                    if (intent != null) {
                        intent.addFlags(
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                        )
                        activity.startActivity(intent)
                        activity.finish()
                        // 跳过重启动画,避免黑屏感知
                        activity.overridePendingTransition(0, 0)
                    } else {
                        // 兜底:无法拿到 launch Intent 时退回 recreate
                        activity.recreate()
                    }
                }
            }
            ThemeModeOption(followSystem, language == "system") { restartActivity("system") }
            ThemeModeOption(zhLabel, language == "zh") { restartActivity("zh") }
            ThemeModeOption(enLabel, language == "en") { restartActivity("en") }
            // v1.132: 新增日语/韩语/俄语三种语言选项
            ThemeModeOption(jaLabel, language == "ja") { restartActivity("ja") }
            ThemeModeOption(koLabel, language == "ko") { restartActivity("ko") }
            ThemeModeOption(ruLabel, language == "ru") { restartActivity("ru") }
        }
        SettingsGroupDivider()
        val langLabel = when (language) {
            "zh" -> zhLabel
            "en" -> enLabel
            "ja" -> jaLabel
            "ko" -> koLabel
            "ru" -> ruLabel
            else -> followSystem
        }
        SettingsItemRow(
            icon = Icons.Outlined.Language,
            title = stringResource(R.string.settings_theme_current_language),
            subtitle = langLabel,
        )
    }
}

/**
 * v1.95: 启动默认页选择区 — 胶囊分段控件,仿 LanguageSection 模式。
 * 选项:任务 / Agent / 群聊。仅在启动时决定初始页,运行中切换设置不会重置当前页。
 */
@Composable
internal fun DefaultHomePageSection(
    settings: SettingsRepository,
) {
    val scope = rememberCoroutineScope()
    val defaultPage by settings.defaultHomePageFlow.collectAsStateWithLifecycle(initialValue = 0)

    SectionLabel(stringResource(R.string.settings_default_home_page_title))
    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        val taskLabel = stringResource(R.string.home_tab_tasks)
        val agentLabel = "Agent"
        val groupChatLabel = stringResource(R.string.home_tab_group_chat)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThemeModeOption(taskLabel, defaultPage == 0) {
                scope.launch { settings.saveDefaultHomePage(0) }
            }
            ThemeModeOption(agentLabel, defaultPage == 1) {
                scope.launch { settings.saveDefaultHomePage(1) }
            }
            ThemeModeOption(groupChatLabel, defaultPage == 2) {
                scope.launch { settings.saveDefaultHomePage(2) }
            }
        }
        SettingsGroupDivider()
        val pageLabel = when (defaultPage) {
            1 -> agentLabel
            2 -> groupChatLabel
            else -> taskLabel
        }
        SettingsItemRow(
            icon = Icons.Outlined.Home,
            title = stringResource(R.string.settings_default_home_page_desc),
            subtitle = pageLabel,
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// v1.97 gap7: 自定义主题 UI(基于种子色生成 ColorScheme)
// 参考实现:rikkahub SettingThemePage.kt(Apache 2.0)
// ──────────────────────────────────────────────────────────────────────────────

/**
 * gap7: 自定义主题区 — 列表 + 添加按钮 + 空列表提示。
 *
 * 用户在此创建/编辑/删除/选择基于种子色的自定义主题,与上方预设主题区视觉对齐:
 *  - 用 [SectionLabel] + [SettingsGroup] 包裹,与主题模式 / 字号选择区一致
 *  - 顶部固定一行"添加主题"操作行,与 rikkahub 的 FilledTonalButton 等价
 *  - 空列表时显示提示文字,避免空白卡片
 *  - 每项 [CustomThemeItemRow] 含 4 色象限色板 + 名称 + 编辑/删除按钮
 */
@Composable
private fun CustomThemeSection(
    customThemes: List<CustomTheme>,
    themeId: String,
    isDark: Boolean,
    onSelect: (CustomTheme) -> Unit,
    onAdd: () -> Unit,
    onEdit: (CustomTheme) -> Unit,
    onDelete: (CustomTheme) -> Unit,
) {
    SectionLabel(stringResource(R.string.settings_theme_custom_section))
    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        // 添加主题按钮行(全宽可点击,左 + 图标,右文字)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onAdd)
                .padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
            Text(
                text = stringResource(R.string.settings_theme_custom_add),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        // 空列表提示 / 主题列表
        if (customThemes.isEmpty()) {
            SettingsGroupDivider()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.settings_theme_custom_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            customThemes.forEachIndexed { index, theme ->
                if (index > 0) SettingsGroupDivider()
                CustomThemeItemRow(
                    theme = theme,
                    isSelected = theme.id == themeId,
                    isDark = isDark,
                    onSelect = { onSelect(theme) },
                    onEdit = { onEdit(theme) },
                    onDelete = { onDelete(theme) },
                )
            }
        }
    }
}

/**
 * gap7: 单个自定义主题行 — 左侧 4 色象限色板,中间名称,右侧编辑/删除按钮。
 *
 * 色板用 [Canvas] 绘制 2x2 网格:primaryContainer / secondaryContainer /
 * tertiaryContainer / 中心 primary 圆点(选中时圆点放大并叠加白色对勾)。
 * 与 rikkahub CustomThemeItem 视觉一致,但用 muse 自己的 [MuseShapes] 圆角令牌。
 */
@Composable
private fun CustomThemeItemRow(
    theme: CustomTheme,
    isSelected: Boolean,
    isDark: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = remember(theme, isDark) { theme.generateColorScheme(isDark) }
    val unnamedLabel = stringResource(R.string.settings_theme_custom_unnamed)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 4 色象限色板(40dp,与 rikkahub 一致)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(40.dp),
        ) {
            Canvas(
                modifier = Modifier
                    .clip(CircleShape)
                    .size(40.dp),
            ) {
                // 左上:primaryContainer
                drawRect(color = scheme.primaryContainer, size = size)
                // 右上:secondaryContainer
                drawRect(
                    color = scheme.secondaryContainer,
                    size = size,
                    topLeft = Offset(x = size.width / 2f, y = 0f),
                )
                // 左下:tertiaryContainer
                drawRect(
                    color = scheme.tertiaryContainer,
                    size = size,
                    topLeft = Offset(x = 0f, y = size.height / 2f),
                )
                // 右下:surface(留白感)
                drawRect(
                    color = scheme.surface,
                    size = size,
                    topLeft = Offset(x = size.width / 2f, y = size.height / 2f),
                )
                // 中心 primary 圆点(选中时放大)
                drawCircle(
                    color = scheme.primary,
                    radius = if (isSelected) 12.dp.toPx() else 6.dp.toPx(),
                    center = Offset(x = size.width / 2f, y = size.height / 2f),
                )
            }
            // 选中时叠加白色对勾
            if (isSelected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = stringResource(R.string.settings_theme_selected),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        // 主题名称(空名回退"未命名")
        Text(
            text = theme.name.ifEmpty { unnamedLabel },
            style = MaterialTheme.typography.bodyLarge,
            color = if (isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        // 编辑 / 删除按钮
        IconButton(onClick = onEdit) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = stringResource(R.string.settings_theme_custom_edit),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.settings_theme_custom_delete),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
        }
    }
}

/**
 * gap7: 自定义主题编辑/创建弹窗 — 基于 [MuseDialog] 而非 ModalBottomSheet。
 *
 * muse 项目中 ModalBottomSheet 在真机上有 scrim 卡死 bug,全部替换为 MuseDialog。
 * 弹窗内容可滚动(防长内容溢出),包含:
 *  - 主题名称输入框
 *  - 3 个 [ColorPickerRow](primary/secondary/tertiary)
 *  - [ThemeColorPreview] 实时配色预览
 *
 * 保存按钮在名称为空时禁用(与 rikkahub 行为一致)。
 */
@Composable
private fun CustomThemeEditDialog(
    theme: CustomTheme?,
    isDark: Boolean,
    onDismiss: () -> Unit,
    onSave: (CustomTheme) -> Unit,
) {
    // 编辑现有主题时沿用其值;创建新主题时用默认值(月桂绿种子色)
    var currentTheme by remember {
        mutableStateOf(theme ?: CustomTheme())
    }
    val title = if (theme == null) {
        stringResource(R.string.settings_theme_custom_create)
    } else {
        stringResource(R.string.settings_theme_custom_edit)
    }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = {
            // 编辑表单(滚动由外层 MuseDialog 的 Box.verticalScroll 统一负责,
            // 这里再套一层 verticalScroll 会触发 infinite height constraints 崩溃,
            // 故仅用普通 Column + spacedBy 排版)。
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 主题名称
                OutlinedTextField(
                    value = currentTheme.name,
                    onValueChange = { currentTheme = currentTheme.copy(name = it) },
                    label = { Text(stringResource(R.string.settings_theme_custom_name)) },
                    placeholder = { Text(stringResource(R.string.settings_theme_custom_name_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                // v1.97: 精选配色快捷按钮 — 点击后一键填充 primary/secondary/tertiary 三色
                Text(
                    text = stringResource(R.string.settings_theme_custom_presets),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PRESET_COLOR_SCHEMES.forEach { scheme ->
                        PresetColorChip(
                            name = scheme.name,
                            primary = Color(scheme.primary),
                            secondary = Color(scheme.secondary),
                            tertiary = Color(scheme.tertiary),
                            onClick = {
                                currentTheme = currentTheme.copy(
                                    primaryColorArgb = scheme.primary.toLong() and 0xFFFFFFFFL,
                                    secondaryColorArgb = scheme.secondary.toLong() and 0xFFFFFFFFL,
                                    tertiaryColorArgb = scheme.tertiary.toLong() and 0xFFFFFFFFL,
                                )
                            },
                        )
                    }
                }
                // 主色(必填)
                Text(
                    text = stringResource(R.string.settings_theme_custom_primary_color),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
                ColorPickerRow(
                    color = Color(currentTheme.primaryColorArgb.toInt()),
                    onColorChange = {
                        currentTheme = currentTheme.copy(
                            primaryColorArgb = it.toArgb().toLong() and 0xFFFFFFFFL,
                        )
                    },
                )
                // 辅色(可选,未指定时显示自动派生结果)
                Text(
                    text = stringResource(R.string.settings_theme_custom_secondary_color),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
                ColorPickerRow(
                    color = currentTheme.secondaryColorArgb?.toInt()?.let { Color(it) }
                        ?: run {
                            // 未指定时显示由 primary 自动派生的 secondary,让用户看到默认值
                            Color(currentTheme.generateColorScheme(isDark).secondary.toArgb())
                        },
                    onColorChange = {
                        currentTheme = currentTheme.copy(
                            secondaryColorArgb = it.toArgb().toLong() and 0xFFFFFFFFL,
                        )
                    },
                )
                // 强调色(可选)
                Text(
                    text = stringResource(R.string.settings_theme_custom_tertiary_color),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
                ColorPickerRow(
                    color = currentTheme.tertiaryColorArgb?.toInt()?.let { Color(it) }
                        ?: Color(currentTheme.generateColorScheme(isDark).tertiary.toArgb()),
                    onColorChange = {
                        currentTheme = currentTheme.copy(
                            tertiaryColorArgb = it.toArgb().toLong() and 0xFFFFFFFFL,
                        )
                    },
                )
                // 实时配色预览
                ThemeColorPreview(theme = currentTheme, isDark = isDark)
            }
        },
        confirmText = stringResource(R.string.settings_theme_custom_save),
        onConfirm = { onSave(currentTheme) },
        dismissText = stringResource(R.string.common_cancel),
        onDismiss = onDismiss,
    )
}

/**
 * gap7: HSL 颜色选择器 — 圆形色板 + H/S/L 三滑块 + HSL 文本输入。
 *
 * 参考 rikkahub ColorPickerRow,使用 [androidx.core.graphics.ColorUtils] 进行
 * HSL ↔ Color 转换(与 Android 系统色彩选择器同一实现)。
 *
 * 文本输入支持 `hsl(267 36% 48%)` / `hsl(267, 36%, 48%)` / `267 36 48` 等格式,
 * 解析失败时显示错误提示但不阻塞滑块操作。
 */
@Composable
private fun ColorPickerRow(
    color: Color,
    onColorChange: (Color) -> Unit,
) {
    val hsl = remember(color) {
        FloatArray(3).also { ColorUtils.colorToHSL(color.toArgb(), it) }
    }
    var hue by remember(color) { mutableFloatStateOf(hsl[0]) }
    var saturation by remember(color) { mutableFloatStateOf(hsl[1]) }
    var lightness by remember(color) { mutableFloatStateOf(hsl[2]) }
    var hslCode by remember(color) { mutableStateOf(formatHslCode(hsl[0], hsl[1], hsl[2])) }
    var hslCodeError by remember(color) { mutableStateOf(false) }

    fun updateColor(newHue: Float, newSaturation: Float, newLightness: Float) {
        hue = newHue
        saturation = newSaturation
        lightness = newLightness
        hslCode = formatHslCode(newHue, newSaturation, newLightness)
        hslCodeError = false
        onColorChange(Color(ColorUtils.HSLToColor(floatArrayOf(newHue, newSaturation, newLightness))))
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 圆形色板预览(40dp)
            Canvas(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape),
            ) {
                drawCircle(color = color)
            }
            Column(modifier = Modifier.weight(1f)) {
                // H 滑块(0-360)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.settings_theme_custom_color_h),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(16.dp),
                    )
                    IosSlider(
                        value = hue,
                        onValueChange = { updateColor(it, saturation, lightness) },
                        valueRange = 0f..360f,
                        showValueLabel = false,
                        modifier = Modifier.weight(1f),
                    )
                }
                // S 滑块(0-1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.settings_theme_custom_color_s),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(16.dp),
                    )
                    IosSlider(
                        value = saturation,
                        onValueChange = { updateColor(hue, it, lightness) },
                        valueRange = 0f..1f,
                        showValueLabel = false,
                        modifier = Modifier.weight(1f),
                    )
                }
                // L 滑块(0-1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.settings_theme_custom_color_l),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(16.dp),
                    )
                    IosSlider(
                        value = lightness,
                        onValueChange = { updateColor(hue, saturation, it) },
                        valueRange = 0f..1f,
                        showValueLabel = false,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        // HSL 文本输入(支持 hsl(267 36% 48%) 等格式)
        OutlinedTextField(
            value = hslCode,
            onValueChange = { value ->
                hslCode = value
                val parsedHsl = parseHslCode(value)
                hslCodeError = parsedHsl == null
                if (parsedHsl != null) {
                    hue = parsedHsl[0]
                    saturation = parsedHsl[1]
                    lightness = parsedHsl[2]
                    onColorChange(Color(ColorUtils.HSLToColor(parsedHsl)))
                }
            },
            label = { Text("HSL") },
            placeholder = { Text("hsl(267 36% 48%)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = hslCodeError,
        )
    }
}

/**
 * gap7: 自定义主题配色预览 — 显示 6 个关键色块。
 *
 * 用当前编辑中的 [theme] 实时生成 ColorScheme,展示 primary / secondary / tertiary /
 * primaryContainer / secondaryContainer / surface 六色,让用户在保存前预览效果。
 */
@Composable
private fun ThemeColorPreview(
    theme: CustomTheme,
    isDark: Boolean,
) {
    val scheme = remember(theme, isDark) { theme.generateColorScheme(isDark) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_theme_custom_preview),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MuseShapes.medium)
                .background(scheme.surface)
                .padding(MusePaddings.screen),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ColorSwatch(scheme.primary, "P")
            ColorSwatch(scheme.secondary, "S")
            ColorSwatch(scheme.tertiary, "T")
            ColorSwatch(scheme.primaryContainer, "PC")
            ColorSwatch(scheme.secondaryContainer, "SC")
            ColorSwatch(scheme.surface, "Sf")
        }
    }
}

/**
 * gap7: 单个色块 — 圆形色板 + 下方标签。
 */
@Composable
private fun ColorSwatch(color: Color, label: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Canvas(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape),
        ) {
            drawCircle(color = color)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── HSL 解析辅助函数(参考 rikkahub SettingThemePage.kt 实现) ──

/** HSL 文本输入中匹配数字的正则(支持整数与小数)。 */
private val hslNumberRegex = Regex("""[-+]?\d*\.?\d+""")

/**
 * 解析 HSL 文本为 FloatArray(长度 3:[hue, saturation, lightness])。
 *
 * 支持格式:
 *  - `hsl(267 36% 48%)` (CSS Color 4 现代语法)
 *  - `hsl(267, 36%, 48%)` (CSS Color 3 传统语法)
 *  - `267 36 48` (纯数字空格分隔)
 *
 * 解析失败返回 null(调用方据此显示错误提示)。
 */
private fun parseHslCode(value: String): FloatArray? {
    val values = buildList {
        for (match in hslNumberRegex.findAll(value)) {
            add(match.value.toFloatOrNull() ?: return null)
            if (size == 3) break
        }
    }
    if (values.size != 3) return null
    val hue = values[0].coerceIn(0f, 360f)
    val saturation = parseHslPercentOrFraction(values[1]) ?: return null
    val lightness = parseHslPercentOrFraction(values[2]) ?: return null
    return floatArrayOf(hue, saturation, lightness)
}

/**
 * 解析 HSL 饱和度/明度分量 — 支持百分比(>1 当作百分数)或小数(0-1)。
 *
 * 例:`36%` → 0.36;`36` → 0.36;`0.36` → 0.36;`1.5` → 1.0(饱和度上限)。
 */
private fun parseHslPercentOrFraction(value: Float): Float? {
    if (!value.isFinite()) return null
    return if (value > 1f) {
        (value / 100f).coerceIn(0f, 1f)
    } else {
        value.coerceIn(0f, 1f)
    }
}

/** 格式化 HSL 三元组为 `hsl(267 36% 48%)` 字符串(用于文本输入框回显)。 */
private fun formatHslCode(hue: Float, saturation: Float, lightness: Float): String {
    return "hsl(${hue.roundToInt()} ${(saturation * 100).roundToInt()}% ${(lightness * 100).roundToInt()}%)"
}

/**
 * v1.97: 精选配色方案数据。
 * ARGB int 值,用于一键填充自定义主题的三色。
 */
private data class PresetColorScheme(
    val name: String,
    val primary: Int,
    val secondary: Int,
    val tertiary: Int,
)

/** v1.97: 8 套精选配色(色盲友好,覆盖暖/冷/中性色调)。 */
private val PRESET_COLOR_SCHEMES = listOf(
    PresetColorScheme("月桂绿", 0xFF2E7D32.toInt(), 0xFF66BB6A.toInt(), 0xFFA5D6A7.toInt()),
    PresetColorScheme("深海蓝", 0xFF1565C0.toInt(), 0xFF42A5F5.toInt(), 0xFF90CAF9.toInt()),
    PresetColorScheme("暮色紫", 0xFF6A1B9A.toInt(), 0xFFAB47BC.toInt(), 0xFFCE93D8.toInt()),
    PresetColorScheme("赤霞红", 0xFFC62828.toInt(), 0xFFEF5350.toInt(), 0xFFFFCDD2.toInt()),
    PresetColorScheme("琥珀橙", 0xFFE65100.toInt(), 0xFFFF7043.toInt(), 0xFFFFCC80.toInt()),
    PresetColorScheme("青瓷青", 0xFF00695C.toInt(), 0xFF26A69A.toInt(), 0xFF80CBC4.toInt()),
    PresetColorScheme("墨纸灰", 0xFF424242.toInt(), 0xFF757575.toInt(), 0xFFBDBDBD.toInt()),
    PresetColorScheme("樱花粉", 0xFFAD1457.toInt(), 0xFFEC407A.toInt(), 0xFFF8BBD0.toInt()),
)

/**
 * v1.97: 精选配色快捷芯片 — 显示三色小圆点 + 名称,点击一键填充。
 */
@Composable
private fun PresetColorChip(
    name: String,
    primary: Color,
    secondary: Color,
    tertiary: Color,
    onClick: () -> Unit,
) {
    Surface(
        shape = MuseShapes.semiLarge,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(primary))
            Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(secondary))
            Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(tertiary))
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
