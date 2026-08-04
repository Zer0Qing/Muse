package io.zer0.muse.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import io.zer0.muse.ui.theme.presets.*

/**
 * 预设主题注册入口。
 *
 * 每套主题已拆到 [io.zer0.muse.ui.theme.presets] 包下独立文件，
 * 这里保留类型定义、查找函数与注册表，外部引用路径不变。
 */
@Immutable
data class PresetTheme(
    val id: String,
    val nameResId: Int,
    val lightScheme: ColorScheme,
    val darkScheme: ColorScheme,
)

/**
 * 按 id 查找预设主题，找不到则回退到默认（warm_paper）。
 */
fun findPresetTheme(id: String): PresetTheme =
    PresetThemes.firstOrNull { it.id == id } ?: WarmPaperTheme

val PresetThemes: List<PresetTheme> = listOf(
    MonoTheme,
    WarmPaperTheme,
    SakuraTheme,
    OceanTheme,
    SpringTheme,
    AutumnTheme,
    SumiTheme,
    WashiTheme,
    AizomeTheme,
    TwilightPurpleTheme,
    AmberGoldTheme,
    DuskRoseTheme,
)
