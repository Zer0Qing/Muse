package io.zer0.muse.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Phase 12: muse 图标尺寸令牌(MuseIconSizes)。
 *
 * 根治"IconButton size 24/28/32/36/44 混用"的不一致问题,
 * 同时满足 Material Design 可访问性红线(触摸目标 ≥ 48dp)。
 *
 * 令牌层级(从大到小):
 *  - [touchTarget]: 48.dp — 触摸目标最小尺寸(MD3 红线,所有 IconButton 必须用此尺寸)
 *  - [iconEmpty]:   64.dp — 空状态/错误态大图标(MuseEmptyState / MuseErrorStateBox 顶部)
 *  - [iconLarge]:   32.dp — 大图标(头像 / 强调图标)
 *  - [icon]:        24.dp — 图标视觉尺寸(MD3 标准)
 *  - [iconMedium]:  20.dp — 中图标(导航 / 操作行)
 *  - [iconSmall]:   18.dp — 小图标(列表项)
 *  - [iconTiny]:    14.dp — 微图标(标签内图标)
 *
 * 用法:
 * ```
 * IconButton(
 *     modifier = Modifier.size(MuseIconSizes.touchTarget),
 *     onClick = { ... },
 * ) {
 *     Icon(Icons.Default.Xxx, modifier = Modifier.size(MuseIconSizes.icon), ...)
 * }
 * ```
 */
object MuseIconSizes {
    /** 触摸目标最小尺寸(MD3 红线,所有 IconButton 必须用此尺寸)。 */
    val touchTarget = 48.dp
    /** 空状态/错误态大图标(MuseEmptyState / MuseErrorStateBox 顶部,L-ES2)。 */
    val iconEmpty = 64.dp
    /** 大图标(头像 / 强调图标)。 */
    val iconLarge = 32.dp
    /** 图标视觉尺寸(MD3 标准)。 */
    val icon = 24.dp
    /** 中图标(导航 / 操作行)。 */
    val iconMedium = 20.dp
    /** 小图标(列表项)。 */
    val iconSmall = 18.dp
    /** 微图标(标签内图标)。 */
    val iconTiny = 14.dp
    /** B7-07: 16dp 图标/进度圈。 */
    val iconSmallTiny = 16.dp
    /** B7-07: 28dp 视频缩略图标。 */
    val iconVideo = 28.dp
    /** B7-07: 32dp 紧凑触控区(移除小圆点/工具按钮)。 */
    val controlTouch = 32.dp
    /** B7-07: 36dp 停止/发送胶囊内部尺寸。 */
    val stopButton = 36.dp
    /** B7-07: 进度圈描边。 */
    val progressStroke = 2.dp
    /** B7-07: 输入框最小高度。 */
    val inputMinHeight = 36.dp
    /** B7-07: 录音波形条尺寸。 */
    val waveformHeight = 24.dp
    val waveformBarWidth = 3.dp
    val waveformBarRadius = 1.5.dp
}
