package io.zer0.muse.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.AppearanceSettingsStore
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.ThemeSolarCondition
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseAnchoredMenu
import io.zer0.muse.ui.common.form.MuseCapsuleTab
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseSlider
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.MuseListItem
import io.zer0.muse.ui.common.surface.actionItem
import io.zer0.muse.ui.common.surface.navigationItem
import io.zer0.muse.ui.common.surface.switchItem
import io.zer0.muse.ui.theme.CustomTheme
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.ui.theme.PresetTheme
import io.zer0.muse.ui.theme.PresetThemes
import io.zer0.muse.ui.theme.ThemeAutoPolicy
import io.zer0.muse.ui.theme.ThemeReconcileReason
import io.zer0.muse.ui.theme.ThemeReconcileWorker
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.ui.theme.tiny
import io.zer0.muse.util.AppLocales
import io.zer0.muse.util.launchSafely
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/**
 * 阶段 7: 外观 section — 分组列表。
 *
 * 设计:
 *  - 用 [SettingsGroup] 包裹多行 item,行间细分割线
 *  - 主题模式(系统/浅色/深色)用胶囊分段控件
 *  - 字号选择(小/中/大/特大)用胶囊分段控件
 *  - 主题变体(mono/warm_paper/ocean_blue)已收敛,只保留浅色 + 深色
 *  - 几乎不用 elevation,靠 surfaceVariant 色块分组
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThemeSection(themeMode: String, fontSizeScale: String, settings: SettingsRepository) {
    val scope = rememberCoroutineScope()
    val themeId by settings.themeIdFlow.collectAsStateWithLifecycle(initialValue = AppearanceSettingsStore.DEFAULT_THEME_ID)

    // v1.65: 动态取色开关(代码早已就绪,此前 UI 无开关导致永远不可用)
    val dynamicColor by settings.dynamicColorFlow.collectAsStateWithLifecycle(initialValue = false)
    val context = LocalContext.current
    val schedule by settings.themeScheduleFlow.collectAsStateWithLifecycle(initialValue = io.zer0.muse.data.ThemeScheduleConfig())
    val scheduleDiagnostic by settings.themeScheduleDiagnosticFlow.collectAsStateWithLifecycle(initialValue = null)
    // Android 12+ 才支持动态取色,低版本灰显(版本号在设备生命周期内不变,用 remember 缓存避免重组时重复读取)
    val dynamicColorSupported = remember { android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S }
    // v1.97 gap7: 用户自定义主题列表 + 编辑/删除弹窗状态
    val customThemes by settings.customThemesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    // E2: 自定义正文字体路径(filesDir/fonts/ 下;null=系统默认)
    val customFontPath by settings.customFontPathFlow.collectAsStateWithLifecycle(initialValue = null)
    // H5: 高对比主题开关(增强前景/背景对比,面向弱视用户)
    val highContrast by settings.highContrastFlow.collectAsStateWithLifecycle(initialValue = false)
    // v2.5.0: 液态玻璃模式(off/water/frost)与强度(0..100) — 设置项已隐藏,保留收集供后续放出

    @Suppress("UnusedPrivateProperty")
    val liquidGlassMode by settings.liquidGlassModeFlow.collectAsStateWithLifecycle(initialValue = "off")

    @Suppress("UnusedPrivateProperty")
    val liquidGlassStrength by settings.liquidGlassStrengthFlow.collectAsStateWithLifecycle(initialValue = 55)
    // E2: SAF 打开字体文件(TTF/OTF) → 复制到应用私有目录 → 保存路径
    val fontImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val path = importFontFile(context, uri)
            if (path != null) {
                settings.setCustomFontPath(path)
            } else {
                MuseToast.show(context.getString(R.string.settings_font_import_failed))
            }
        }
    }
    val solarPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scope.launch {
            settings.saveThemeSchedule(
                schedule.copy(
                    autoPolicy = ThemeAutoPolicy.SUNRISE_SUNSET,
                    solarUnavailableReason = if (granted) {
                        null
                    } else {
                        io.zer0.muse.data.ThemeSolarUnavailableReason.LOCATION_PERMISSION_REQUIRED
                    },
                ),
            )
            ThemeReconcileWorker.enqueue(context, ThemeReconcileReason.SETTINGS_CHANGED)
        }
    }
    var editingTheme by remember { mutableStateOf<CustomTheme?>(null) }
    var showEditDialog by remember { mutableStateOf(false) }
    var deletingTheme by remember { mutableStateOf<CustomTheme?>(null) }
    // R-UI-05: 定时切换时间选择弹窗
    var wakeTimeDialog by remember { mutableStateOf(false) }
    var sleepTimeDialog by remember { mutableStateOf(false) }
    // 自定义主题预览需要按当前主题模式生成 ColorScheme(system 模式跟随系统深浅)
    val isDark = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> io.zer0.muse.ui.theme.LocalIsDarkTheme.current
    }

    // ── 主题模式 ──
    val followSystem = stringResource(R.string.settings_theme_follow_system)
    val lightLabel = stringResource(R.string.settings_theme_light)
    val darkLabel = stringResource(R.string.settings_theme_dark)
    val modeOptions = listOf(followSystem, lightLabel, darkLabel)
    val selectedMode = when (themeMode) {
        "system" -> 0
        "light" -> 1
        "dark" -> 2
        else -> 0
    }
    CardGroup(title = { Text(stringResource(R.string.settings_theme_appearance)) }) {
        content(key = "theme_mode_tab") {
            MuseCapsuleTab(
                tabs = modeOptions,
                selectedIndex = selectedMode,
                onSelect = { idx ->
                    scope.launch {
                        settings.saveThemeMode(
                            when (idx) {
                                0 -> "system"
                                1 -> "light"
                                2 -> "dark"
                                else -> "system"
                            },
                        )
                    }
                },
                modifier = Modifier.padding(MusePaddings.cardInner),
            )
        }
        switchItem(
            key = "theme_high_contrast",
            title = stringResource(R.string.settings_theme_high_contrast),
            subtitle = stringResource(R.string.settings_theme_high_contrast_desc),
            checked = highContrast,
            onCheckedChange = { v -> scope.launch { settings.saveHighContrast(v) } },
            leadingContent = {
                Icon(
                    imageVector = MuseIcons.eye,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
            },
        )
        content(key = "theme_liquid_glass") {
            LiquidGlassSection(
                mode = liquidGlassMode,
                strength = liquidGlassStrength,
                onModeChange = { m -> scope.launch { settings.saveLiquidGlassMode(m) } },
                onStrengthChange = { s -> scope.launch { settings.saveLiquidGlassStrength(s) } },
            )
        }
    }

    // ── 主题切换(预设主题网格选择器) ──
    SectionLabel(
        stringResource(R.string.section_theme),
        modifier = Modifier.padding(top = MusePaddings.cardGap),
    )
    ThemeGridPicker(
        themes = PresetThemes,
        selectedThemeId = themeId,
        isDark = isDark,
        onSelect = { theme -> scope.launch { settings.saveThemeId(theme.id) } },
    )
    CardGroup(modifier = Modifier.padding(top = MuseSpacing.sm)) {
        switchItem(
            key = "theme_dynamic_color",
            title = stringResource(R.string.settings_theme_dynamic_color),
            subtitle = stringResource(
                if (dynamicColorSupported) {
                    R.string.settings_theme_dynamic_color_supported
                } else {
                    R.string.settings_theme_dynamic_color_unsupported
                },
            ),
            checked = dynamicColor && dynamicColorSupported,
            enabled = dynamicColorSupported,
            onCheckedChange = { v -> scope.launch { settings.saveDynamicColor(v) } },
            leadingContent = {
                Icon(
                    imageVector = MuseIcons.palette,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
            },
        )
    }

    // ── v1.97 gap7: 自定义主题区(基于种子色生成 ColorScheme) ──
    // E1: 主题 JSON 导出/导入 — 分享为文本分享(ACTION_SEND),导入走 SAF 选文件
    val shareTheme: (CustomTheme) -> Unit = { theme ->
        val json = AppJson.encodeToString(CustomTheme.serializer(), theme)
        io.zer0.muse.util.ShareIntentHelper.shareTextOrFileSafely(
            context = context,
            mime = "text/plain",
            content = json,
            subject = "Muse Theme: ${theme.name}",
            chooserTitle = context.getString(R.string.settings_theme_custom_export),
            filePrefix = "muse-theme",
            fileExtension = "json",
        )
    }
    val importThemeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val theme = try {
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() }
            if (text == null) {
                MuseToast.show(context.getString(R.string.settings_theme_custom_import_failed))
                return@rememberLauncherForActivityResult
            }
            AppJson.decodeFromString(CustomTheme.serializer(), text)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            Logger.w("ThemeSection", "主题导入读取失败: ${e.message}")
            // 读不到文件和 JSON 不合法是两种问题，用户要据此决定换文件还是修内容。
            MuseToast.show(
                context.getString(R.string.settings_theme_custom_import_failed_with_reason, "读取失败（${e.message ?: "IO"}）"),
            )
            return@rememberLauncherForActivityResult
        } catch (e: kotlinx.serialization.SerializationException) {
            Logger.w("ThemeSection", "主题导入解析失败: ${e.message}")
            MuseToast.show(
                context.getString(R.string.settings_theme_custom_import_failed_with_reason, "格式不正确"),
            )
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            settings.upsertCustomTheme(theme)
            // 导入后自动切换到该主题,与创建行为一致
            settings.saveThemeId(theme.id)
        }
        MuseToast.show(context.getString(R.string.settings_theme_custom_import_success))
    }
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
        onImport = {
            importThemeLauncher.launchSafely(
                arrayOf("application/json", "text/plain", "application/octet-stream"),
                context,
            )
        },
        onExport = shareTheme,
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
                    // 保存后自动切换到该主题
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
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                scope.launch {
                    settings.deleteCustomTheme(theme.id)
                    // 删除后若当前 themeId 指向被删主题,回退到默认预设
                    if (themeId == theme.id) {
                        settings.saveThemeId(AppearanceSettingsStore.DEFAULT_THEME_ID)
                    }
                }
                deletingTheme = null
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { deletingTheme = null },
            destructive = true,
        )
    }

    // v1.0.92: 深色模式独立主题入口已下线 — 固定跟随亮色主题的暗色版,
    // 不再向用户展示深色主题选择(用户要求:只保留亮色主题体系)。

    // ── 自动主题策略 ──
    val policyOptions = listOf(
        stringResource(R.string.settings_theme_policy_off),
        stringResource(R.string.settings_theme_policy_custom_time),
        stringResource(R.string.settings_theme_policy_sunrise_sunset),
    )
    val selectedPolicy = when (schedule.autoPolicy) {
        ThemeAutoPolicy.OFF -> 0
        ThemeAutoPolicy.CUSTOM_TIME -> 1
        ThemeAutoPolicy.SUNRISE_SUNSET -> 2
    }
    CardGroup(
        title = { Text(stringResource(R.string.settings_theme_schedule)) },
        modifier = Modifier.padding(top = MusePaddings.cardGap),
    ) {
        content(key = "schedule_policy_tab") {
            MuseCapsuleTab(
                tabs = policyOptions,
                selectedIndex = selectedPolicy,
                onSelect = { index ->
                    when (index) {
                        0 -> saveThemePolicy(
                            settings = settings,
                            schedule = schedule,
                            policy = ThemeAutoPolicy.OFF,
                            context = context,
                            scope = scope,
                        )
                        1 -> saveThemePolicy(
                            settings = settings,
                            schedule = schedule,
                            policy = ThemeAutoPolicy.CUSTOM_TIME,
                            context = context,
                            scope = scope,
                        )
                        2 -> {
                            val permissionGranted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (permissionGranted) {
                                saveThemePolicy(
                                    settings = settings,
                                    schedule = schedule,
                                    policy = ThemeAutoPolicy.SUNRISE_SUNSET,
                                    context = context,
                                    scope = scope,
                                )
                            } else {
                                solarPermissionLauncher.launchSafely(
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                    context,
                                )
                            }
                        }
                    }
                },
                modifier = Modifier.padding(MusePaddings.cardInner),
            )
        }
        if (scheduleDiagnostic != null) {
            content(key = "schedule_diag") {
                Text(
                    text = stringResource(R.string.settings_theme_schedule_parse_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = MusePaddings.screen, vertical = MusePaddings.tightGap),
                )
            }
        }
        if (schedule.autoPolicy == ThemeAutoPolicy.CUSTOM_TIME) {
            navigationItem(
                key = "schedule_wake",
                title = stringResource(R.string.settings_theme_wake_time_title),
                value = "%02d:%02d".format(schedule.wakeUpHour, schedule.wakeUpMinute),
                leadingContent = {
                    Icon(
                        imageVector = MuseIcons.sun,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                },
                onClick = { wakeTimeDialog = true },
            )
            navigationItem(
                key = "schedule_sleep",
                title = stringResource(R.string.settings_theme_sleep_time_title),
                value = "%02d:%02d".format(schedule.sleepHour, schedule.sleepMinute),
                leadingContent = {
                    Icon(
                        imageVector = MuseIcons.moon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                },
                onClick = { sleepTimeDialog = true },
            )
        }
        if (schedule.autoPolicy == ThemeAutoPolicy.SUNRISE_SUNSET) {
            content(key = "schedule_solar") {
                val locationCache = schedule.locationCache
                val solarCache = schedule.solarCache
                val statusText = when {
                    schedule.solarUnavailableReason == io.zer0.muse.data.ThemeSolarUnavailableReason.LOCATION_PERMISSION_REQUIRED ->
                        stringResource(R.string.settings_theme_solar_permission_required)
                    schedule.solarUnavailableReason == io.zer0.muse.data.ThemeSolarUnavailableReason.LOCATION_UNAVAILABLE ->
                        stringResource(R.string.settings_theme_solar_location_unavailable)
                    schedule.solarUnavailableReason == io.zer0.muse.data.ThemeSolarUnavailableReason.SOLAR_EVENTS_UNAVAILABLE ->
                        stringResource(R.string.settings_theme_solar_events_unavailable)
                    solarCache == null -> stringResource(R.string.settings_theme_solar_cache_unavailable)
                    solarCache.condition == ThemeSolarCondition.POLAR_DAY -> stringResource(R.string.settings_theme_solar_polar_day)
                    solarCache.condition == ThemeSolarCondition.POLAR_NIGHT -> stringResource(R.string.settings_theme_solar_polar_night)
                    solarCache.condition == ThemeSolarCondition.NORMAL && solarCache.sunriseAt != null && solarCache.sunsetAt != null -> {
                        val sunrise = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(solarCache.sunriseAt))
                        val sunset = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(solarCache.sunsetAt))
                        stringResource(R.string.settings_theme_solar_times, sunrise, sunset)
                    }
                    else -> stringResource(R.string.settings_theme_solar_cache_unavailable)
                }
                Column(
                    modifier = Modifier.padding(
                        horizontal = MusePaddings.screen,
                        vertical = MusePaddings.contentGap,
                    ),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                ) {
                    Text(statusText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    if (locationCache != null) {
                        val updatedAt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(locationCache.updatedAt))
                        Text(
                            text = stringResource(R.string.settings_theme_solar_location_updated, updatedAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (wakeTimeDialog) {
        io.zer0.muse.ui.common.form.MuseTimePickerDialog(
            title = stringResource(R.string.settings_theme_wake_time_title),
            initialHour = schedule.wakeUpHour,
            initialMinute = schedule.wakeUpMinute,
            onDismiss = { wakeTimeDialog = false },
            onConfirm = { hour, minute ->
                scope.launch {
                    settings.saveThemeSchedule(
                        schedule.copy(wakeUpHour = hour, wakeUpMinute = minute),
                    )
                    ThemeReconcileWorker.enqueue(context, ThemeReconcileReason.SETTINGS_CHANGED)
                }
            },
        )
    }

    if (sleepTimeDialog) {
        io.zer0.muse.ui.common.form.MuseTimePickerDialog(
            title = stringResource(R.string.settings_theme_sleep_time_title),
            initialHour = schedule.sleepHour,
            initialMinute = schedule.sleepMinute,
            onDismiss = { sleepTimeDialog = false },
            onConfirm = { hour, minute ->
                scope.launch {
                    settings.saveThemeSchedule(
                        schedule.copy(sleepHour = hour, sleepMinute = minute),
                    )
                    ThemeReconcileWorker.enqueue(context, ThemeReconcileReason.SETTINGS_CHANGED)
                }
            },
        )
    }

    // ── 字号选择 ──
    val smallLabel = stringResource(R.string.settings_theme_font_small)
    val mediumLabel = stringResource(R.string.settings_theme_font_medium)
    val largeLabel = stringResource(R.string.settings_theme_font_large)
    val xlargeLabel = stringResource(R.string.settings_theme_font_xlarge)
    val fontOptions = listOf(smallLabel, mediumLabel, largeLabel, xlargeLabel)
    val selectedFont = when (fontSizeScale) {
        "small" -> 0
        "large" -> 2
        "xlarge" -> 3
        else -> 1
    }
    CardGroup(
        title = { Text(stringResource(R.string.settings_theme_font_size)) },
        modifier = Modifier.padding(top = MusePaddings.cardGap),
    ) {
        content(key = "font_size_tab") {
            Column {
                MuseCapsuleTab(
                    tabs = fontOptions,
                    selectedIndex = selectedFont,
                    onSelect = { idx ->
                        scope.launch {
                            settings.saveFontSizeScale(
                                when (idx) {
                                    0 -> "small"
                                    1 -> "medium"
                                    2 -> "large"
                                    3 -> "xlarge"
                                    else -> "medium"
                                },
                            )
                        }
                    },
                    modifier = Modifier.padding(MusePaddings.cardInner),
                )
                FontSizePreview(fontSizeScale)
            }
        }
    }

    // ── E2: 自定义正文字体导入/清除 ──
    FontSection(
        customFontPath = customFontPath,
        onImport = {
            fontImportLauncher.launchSafely(
                arrayOf("font/ttf", "font/otf", "application/octet-stream"),
                context,
            )
        },
        onClear = {
            scope.launch {
                // 清除路径后旧文件保留在 filesDir/fonts/,下次导入覆盖同名文件,无需主动删除
                settings.setCustomFontPath(null)
            }
        },
    )

    // ── Phase 4: 气泡皮肤选择/预览(与聊天页共用 BubbleSkinResolver) ──
    BubbleSkinSection(
        fontSizeScale = fontSizeScale,
        defaultDark = isDark,
    )
}

/** v2.5.0: 液态玻璃设置区 — 模式(关/水玻璃/磨砂) + 强度滑杆(0..100)。暂时隐藏,待效果调优后重新放出。 */
@Suppress("FunctionNaming", "UnusedPrivateMember")
@Composable
private fun LiquidGlassSection(mode: String, strength: Int, onModeChange: (String) -> Unit, onStrengthChange: (Int) -> Unit) {
    val glassOff = stringResource(R.string.settings_glass_off)
    val glassWater = stringResource(R.string.settings_glass_water)
    val glassFrost = stringResource(R.string.settings_glass_frost)
    val modeOptions = listOf(glassOff, glassWater, glassFrost)
    val modeValues = listOf(
        io.zer0.muse.ui.theme.LiquidGlassConfig.MODE_OFF,
        io.zer0.muse.ui.theme.LiquidGlassConfig.MODE_WATER,
        io.zer0.muse.ui.theme.LiquidGlassConfig.MODE_FROST,
    )
    val selectedMode = modeValues.indexOf(mode).coerceAtLeast(0)

    Column(modifier = Modifier.fillMaxWidth().padding(MusePaddings.cardInner)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = MuseIcons.sparkle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(MuseIconSizes.icon),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_theme_liquid_glass),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.settings_theme_liquid_glass_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        // 模式: 关 / 水玻璃 / 磨砂玻璃
        MuseCapsuleTab(
            tabs = modeOptions,
            selectedIndex = selectedMode,
            onSelect = { idx -> onModeChange(modeValues[idx]) },
        )
        // v2.6.0: 强度改为固定三档(低/中/高) —— 连续滑条在“看不出差”的区间里
        // 只能让人困惑；固定档位保证每一档都是肉眼可辨的跃迁。
        if (mode != io.zer0.muse.ui.theme.LiquidGlassConfig.MODE_OFF) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.settings_glass_level),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            val levelLabels =
                listOf(
                    stringResource(R.string.settings_glass_level_low),
                    stringResource(R.string.settings_glass_level_medium),
                    stringResource(R.string.settings_glass_level_high),
                )
            // 存 0..100 不变（兼容旧值）；档位值拉开到 20/55/95，保证观感跃迁明显。
            val levelValues = listOf(20, 55, 95)
            // 把当前值映射到最近的一档（选中态回显）。
            val currentLevel = levelValues.indices.minByOrNull { kotlin.math.abs(levelValues[it] - strength) } ?: 1
            MuseCapsuleTab(
                tabs = levelLabels,
                selectedIndex = currentLevel,
                onSelect = { idx -> onStrengthChange(levelValues[idx]) },
            )
        }
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * v0.22: 主题选项行 — 左侧主题名,右侧 Check icon(仅选中时显示)。
 *
 * 样式与 [io.zer0.muse.ui.common.surface.MuseListItem] 对齐(16/12 padding),作为"主题"分组内的单选项;
 * 选中时主题名与图标都用 primary 色高亮。
 */
@Composable
private fun ThemeOptionRow(name: String, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
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
            style = MaterialTheme.typography.titleMedium,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
            // LAYOUT-01: Row 内文本取宽 + 限行，防放大字号下被右侧选中图标挤成一列
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (isSelected) {
            Icon(
                imageVector = MuseIcons.check,
                contentDescription = stringResource(R.string.settings_theme_selected),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
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
private fun ThemeGridPicker(themes: List<PresetTheme>, selectedThemeId: String, isDark: Boolean, onSelect: (PresetTheme) -> Unit) {
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
            .heightIn(min = 80.dp)
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
                    imageVector = MuseIcons.check,
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
 * 语言选择区 — 跟随系统 + [AppLocales.Language] 全集，标签用语言自称。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun LanguageSection(language: String, settings: SettingsRepository) {
    val scope = rememberCoroutineScope()
    val followSystem = stringResource(R.string.settings_theme_follow_system)
    val languageOptions = AppLocales.Language.entries.map { it.code to stringResource(it.labelRes) }
    val switchLanguage: (String) -> Unit = { lang ->
        scope.launch { settings.saveLanguage(lang) }
    }

    CardGroup(title = { Text(stringResource(R.string.settings_theme_language)) }) {
        content(key = "language_chips") {
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MusePaddings.cardInner),
                horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
            ) {
                MuseChip(
                    selected = language == "system",
                    onClick = { switchLanguage("system") },
                    label = followSystem,
                )
                languageOptions.forEach { (code, label) ->
                    MuseChip(
                        selected = language == code,
                        onClick = { switchLanguage(code) },
                        label = label,
                    )
                }
            }
        }
    }
}

/**
 * 启动默认页选择区 — 仅在启动时决定初始页，运行中切换设置不会重置当前页。
 */
@Composable
internal fun DefaultHomePageSection(settings: SettingsRepository) {
    val scope = rememberCoroutineScope()
    val defaultPage by settings.defaultHomePageFlow.collectAsStateWithLifecycle(initialValue = 0)
    val taskLabel = stringResource(R.string.home_tab_tasks)
    val agentLabel = "Agent"
    val groupChatLabel = stringResource(R.string.home_tab_group_chat)

    CardGroup(title = { Text(stringResource(R.string.settings_default_home_page_title)) }) {
        content(key = "default_home_tab") {
            MuseCapsuleTab(
                tabs = listOf(taskLabel, agentLabel, groupChatLabel),
                selectedIndex = defaultPage.coerceIn(0, 2),
                onSelect = { idx -> scope.launch { settings.saveDefaultHomePage(idx) } },
                modifier = Modifier.padding(MusePaddings.cardInner),
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 自定义主题 UI（基于种子色生成 ColorScheme）
// ──────────────────────────────────────────────────────────────────────────────

@Composable
@Suppress("LongParameterList", "LongMethod")
private fun CustomThemeSection(
    customThemes: List<CustomTheme>,
    themeId: String,
    isDark: Boolean,
    onSelect: (CustomTheme) -> Unit,
    onAdd: () -> Unit,
    onEdit: (CustomTheme) -> Unit,
    onDelete: (CustomTheme) -> Unit,
    onImport: () -> Unit,
    onExport: (CustomTheme) -> Unit,
) {
    CardGroup(
        title = { Text(stringResource(R.string.settings_theme_custom_section)) },
        modifier = Modifier.padding(top = MusePaddings.cardGap),
    ) {
        content(key = "custom_theme_actions") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MusePaddings.cardInner),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onAdd),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.md),
                ) {
                    Icon(
                        imageVector = MuseIcons.plus,
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
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onImport),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.md),
                ) {
                    Icon(
                        imageVector = MuseIcons.upload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                    Text(
                        text = stringResource(R.string.settings_theme_custom_import),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        customThemes.forEach { theme ->
            content(key = "custom_theme_${theme.id}") {
                CustomThemeItemRow(
                    theme = theme,
                    isSelected = theme.id == themeId,
                    isDark = isDark,
                    onSelect = { onSelect(theme) },
                    onEdit = { onEdit(theme) },
                    onDelete = { onDelete(theme) },
                    onExport = { onExport(theme) },
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
 * 圆角用本项目的 [MuseShapes] 令牌。
 */
@Composable
@Suppress(
    "LongParameterList",
    "LongMethod",
    // 单行含 4 个回调(选择/编辑/删除/导出)+ 主题与选中态,属屏幕级固有参数集合
)
private fun CustomThemeItemRow(
    theme: CustomTheme,
    isSelected: Boolean,
    isDark: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    /** E1: 导出该主题 JSON。 */
    onExport: () -> Unit,
) {
    val scheme = remember(theme, isDark) { theme.generateColorScheme(isDark) }
    val unnamedLabel = stringResource(R.string.settings_theme_custom_unnamed)
    // v2.x 统一化: 行内动作收敛为「更多」菜单(防止窄屏文本列被多个按钮挤压)
    val moreCd = stringResource(R.string.settings_mcp_more)
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 4 色象限色板(40dp)
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
                    imageVector = MuseIcons.check,
                    contentDescription = stringResource(R.string.settings_theme_selected),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        // 主题名称(空名回退"未命名")
        Text(
            text = theme.name.ifEmpty { unnamedLabel },
            style = MaterialTheme.typography.titleMedium,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )
        Box {
            MuseTactileButton(
                icon = MuseIcons.moreHorizontal,
                onClick = { menuExpanded = true },
                contentDescription = moreCd,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                compact = true,
            )
            MuseAnchoredMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                alignEnd = true,
            ) {
                MuseListItem(
                    onClick = {
                        menuExpanded = false
                        onExport()
                    },
                    leadingContent = {
                        Icon(
                            imageVector = MuseIcons.share,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                    },
                    headlineContent = { Text(stringResource(R.string.settings_theme_custom_export)) },
                )
                MuseListItem(
                    onClick = {
                        menuExpanded = false
                        onEdit()
                    },
                    leadingContent = {
                        Icon(
                            imageVector = MuseIcons.edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                    },
                    headlineContent = { Text(stringResource(R.string.settings_theme_custom_edit)) },
                )
                MuseListItem(
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    },
                    leadingContent = {
                        Icon(
                            imageVector = MuseIcons.trash,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                    },
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.common_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                )
            }
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
 * 保存按钮在名称为空时禁用。
 */
@Composable
private fun CustomThemeEditDialog(theme: CustomTheme?, isDark: Boolean, onDismiss: () -> Unit, onSave: (CustomTheme) -> Unit) {
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
                MuseTextField(
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            name = stringResource(scheme.nameRes),
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
        confirmText = stringResource(R.string.common_save),
        onConfirm = { onSave(currentTheme) },
        dismissText = stringResource(R.string.common_cancel),
        onDismiss = onDismiss,
    )
}

/**
 * gap7: HSL 颜色选择器 — 圆形色板 + H/S/L 三滑块 + HSL 文本输入。
 *
 * 使用 [androidx.core.graphics.ColorUtils] 进行
 * HSL ↔ Color 转换(与 Android 系统色彩选择器同一实现)。
 *
 * 文本输入支持 `hsl(267 36% 48%)` / `hsl(267, 36%, 48%)` / `267 36 48` 等格式,
 * 解析失败时显示错误提示但不阻塞滑块操作。
 */
@Composable
private fun ColorPickerRow(color: Color, onColorChange: (Color) -> Unit) {
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
                        // LAYOUT-01: 定宽 16.dp 刻度标注限 1 行，放大字号下不再折成两行被裁
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MuseSlider(
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
                        // LAYOUT-01: 同上，刻度标注限 1 行
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MuseSlider(
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
                        // LAYOUT-01: 同上，刻度标注限 1 行
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MuseSlider(
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
        MuseTextField(
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
private fun ThemeColorPreview(theme: CustomTheme, isDark: Boolean) {
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

// ── HSL 解析辅助函数 ──

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
    val nameRes: Int,
    val primary: Int,
    val secondary: Int,
    val tertiary: Int,
)

/** v1.97: 8 套精选配色(色盲友好,覆盖暖/冷/中性色调)。 */
private val PRESET_COLOR_SCHEMES = listOf(
    PresetColorScheme(R.string.audit_color_laurel, 0xFF2E7D32.toInt(), 0xFF66BB6A.toInt(), 0xFFA5D6A7.toInt()),
    PresetColorScheme(R.string.audit_color_ocean, 0xFF1565C0.toInt(), 0xFF42A5F5.toInt(), 0xFF90CAF9.toInt()),
    PresetColorScheme(R.string.audit_color_dusk, 0xFF6A1B9A.toInt(), 0xFFAB47BC.toInt(), 0xFFCE93D8.toInt()),
    PresetColorScheme(R.string.audit_color_sunset, 0xFFC62828.toInt(), 0xFFEF5350.toInt(), 0xFFFFCDD2.toInt()),
    PresetColorScheme(R.string.audit_color_amber, 0xFFE65100.toInt(), 0xFFFF7043.toInt(), 0xFFFFCC80.toInt()),
    PresetColorScheme(R.string.audit_color_celadon, 0xFF00695C.toInt(), 0xFF26A69A.toInt(), 0xFF80CBC4.toInt()),
    PresetColorScheme(R.string.audit_color_ink, 0xFF424242.toInt(), 0xFF757575.toInt(), 0xFFBDBDBD.toInt()),
    PresetColorScheme(R.string.audit_color_cherry, 0xFFAD1457.toInt(), 0xFFEC407A.toInt(), 0xFFF8BBD0.toInt()),
)

/**
 * v1.97: 精选配色快捷芯片 — 显示三色小圆点 + 名称,点击一键填充。
 */
@Composable
private fun PresetColorChip(name: String, primary: Color, secondary: Color, tertiary: Color, onClick: () -> Unit) {
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

/**
 * E2: 自定义正文字体设置区。
 *
 * 导入后立即经 [io.zer0.muse.ui.theme.loadCustomFontFamily] 全局生效
 * (MainActivity 收集 customFontPathFlow 重建 MuseTheme Typography)。
 */
@Composable
private fun FontSection(customFontPath: String?, onImport: () -> Unit, onClear: () -> Unit) {
    val currentName = customFontPath?.let { File(it).name }
    CardGroup(
        title = { Text(stringResource(R.string.settings_font_section_title)) },
        modifier = Modifier.padding(top = MusePaddings.cardGap),
    ) {
        item(
            key = "custom_font_current",
            leadingContent = {
                Icon(
                    imageVector = MuseIcons.typography,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
            },
            headlineContent = { Text(stringResource(R.string.settings_font_body_label)) },
            supportingContent = {
                Text(
                    text = currentName ?: stringResource(R.string.settings_font_body_default),
                )
            },
        )
        actionItem(
            key = "custom_font_import",
            title = stringResource(R.string.settings_font_import),
            onClick = onImport,
        )
        if (customFontPath != null) {
            actionItem(
                key = "custom_font_clear",
                title = stringResource(R.string.settings_font_clear),
                onClick = onClear,
            )
        }
    }
}

/**
 * E2: 将 SAF 选中的字体文件复制到应用私有目录 filesDir/fonts/custom_body_font.ttf。
 *
 * 固定目标文件名,下次导入直接覆盖;失败返回 null(调用方提示用户)。
 * 复制与目录创建均为磁盘 IO,在 IO 调度器执行避免卡主线程。
 */
private suspend fun importFontFile(context: android.content.Context, uri: Uri): String? {
    return withContext(Dispatchers.IO) {
        try {
            val dir = File(context.filesDir, "fonts").apply { mkdirs() }
            val target = File(dir, "custom_body_font.ttf")
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext null
            input.use { source ->
                target.outputStream().use { sink -> source.copyTo(sink) }
            }
            target.absolutePath
        } catch (e: java.io.IOException) {
            Logger.w("ThemeSection", "字体文件复制失败: ${e.message}")
            null
        } catch (e: SecurityException) {
            Logger.w("ThemeSection", "字体文件读取被拒绝: ${e.message}")
            null
        }
    }
}

private fun saveThemePolicy(
    settings: SettingsRepository,
    schedule: io.zer0.muse.data.ThemeScheduleConfig,
    policy: ThemeAutoPolicy,
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    scope.launch {
        settings.saveThemeSchedule(
            schedule.copy(
                autoPolicy = policy,
                solarUnavailableReason = null,
            ),
        )
        ThemeReconcileWorker.enqueue(context, ThemeReconcileReason.SETTINGS_CHANGED)
    }
}
