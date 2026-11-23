package io.zer0.muse.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 边框厚度令牌，替代全仓零散借用高程值或硬编码 `0.5.dp / 1.dp`。
 */
object MuseBorderWidth {
    /** 发丝线（分隔线 / 细卡片描边）。 */
    val hairline: Dp = 0.5.dp

    /** 常规细边框（未选中 Chip / 描边按钮）。 */
    val thin: Dp = 1.dp

    /** 强调描边（焦点态 / 选中高亮）。 */
    val medium: Dp = 1.5.dp

    /** 粗描边（进度圆环 / 显著框线）。 */
    val thick: Dp = 2.dp
}

/**
 * 胶囊按钮尺寸三档，避免全应用所有按钮被迫共用 52dp 大尺寸。
 */
enum class MuseButtonSize {
    /** 页面底部主 CTA / 引导确认按钮。 */
    Large,

    /** 常规表单 / 面板默认按钮。 */
    Medium,

    /** 卡片行内 / 快捷操作 / 次要小按钮。 */
    Compact,
}

/**
 * 按钮专属几何令牌（视觉高度与触控热区分层，彻底摆脱对列表间距令牌的借用）。
 */
object MuseButtonSizes {
    val largeHeight: Dp = 46.dp
    val mediumHeight: Dp = 38.dp
    val compactHeight: Dp = 32.dp

    val largePaddingH: Dp = 20.dp
    val mediumPaddingH: Dp = 16.dp
    val compactPaddingH: Dp = 12.dp

    val largePaddingV: Dp = 10.dp
    val mediumPaddingV: Dp = 8.dp
    val compactPaddingV: Dp = 6.dp

    val iconGap: Dp = MuseSpacing.smTight
    val compactIconGap: Dp = MuseSpacing.xs

    /** 轻量级悬浮操作按钮尺寸（默认主 FAB 仍走 [MuseIconSizes.fab] = 56dp）。 */
    val fabCompact: Dp = 44.dp
}

/**
 * 胶囊标签（[io.zer0.muse.ui.common.form.MuseChip]）专属几何令牌。
 * 视觉主体收紧至 32dp，外层点击盒维持 40dp 舒适热区。
 */
object MuseChipSizes {
    val visualHeight: Dp = 32.dp
    val minTouchHeight: Dp = 40.dp
    val paddingH: Dp = MuseSpacing.md
    val paddingV: Dp = MuseSpacing.smTight
    const val pressScale: Float = 0.95f
}

/**
 * 胶囊 Tab 选择器（[io.zer0.muse.ui.common.form.MuseCapsuleTab]）几何令牌。
 */
object MuseTabSizes {
    val minHeight: Dp = 38.dp
    val maxHeight: Dp = 42.dp
    val minSegmentWidth: Dp = 60.dp
}

/**
 * 底部抽屉（[io.zer0.muse.ui.common.form.MuseBottomSheet]）拖拽把手与阻尼阈值令牌。
 */
object MuseSheetSizes {
    val handleBandHeight: Dp = 28.dp
    val handleWidth: Dp = 36.dp
    val handleHeight: Dp = 4.dp
    val handleCornerRadius: Dp = 2.dp
    val dragDismissThreshold: Dp = 32.dp
    val rubberBandMax: Dp = 24.dp
}

/**
 * Muse 设计令牌 — 组件专属尺寸(CMP-08)。
 *
 * 与 [MuseSpacing] / [MusePaddings] / [MuseCornerRadius] / [MuseIconSizes] 互补:
 * 通用间距 / 圆角 / 图标走那几套令牌,而少数**只属于某个组件**的尺寸集中在此定义,
 * 调整只需改这一处并由 `DesignTokenParityTest` 与 Web `tokens.json` 保持同源。
 */
object MuseDialogSizes {
    /** 紧凑弹窗内边距；系统安全区由外壳负责，不计入卡片。 */
    val contentPadding = 20.dp

    /** 小屏也保留侧边留白，大屏不拉伸。 */
    val maxWidth = 320.dp
    val edgePadding = 24.dp

    /** 内容超出后内部滚动，标题和操作保持可见。 */
    val contentMaxHeight = 520.dp
    val buttonVisualHeight = 40.dp
}

object MuseSwitchSizes {
    /** 开关轨道宽度(开关标准规格)。 */
    val trackWidth = 51.dp

    /** 开关轨道高度(开关标准规格)。 */
    val trackHeight = 31.dp

    /** 开关拇指直径(轨道高度 − 2×inset)。 */
    val thumbSize = 27.dp

    /** 拇指与轨道的内缩(复用通用紧凑间距令牌,不引入新值)。 */
    val trackPadding = MusePaddings.tinyGap
}

/**
 * 列表行几何([io.zer0.muse.ui.common.surface.MuseListItem])。
 *
 * 与 `tokens.json` 的 `component.listRow.*` 同源。
 */
object MuseListItemSizes {
    /** 常规列表行最小高度（54dp 兼顾可点性与信息密度；双行由内容自然撑开）。 */
    val minHeight = 54.dp

    /** 紧凑列表(菜单 / 选择面板)的行最小高度。 */
    val compactMinHeight = 44.dp
}

/**
 * 胶囊按钮几何([io.zer0.muse.ui.common.form.MuseCapsuleButton])。
 *
 * 与 `tokens.json` 的 `component.button.capsulePressScale` 同源。
 */
object MuseCapsuleButtonSizes {
    /** 按压时的缩放比例(1f 为原尺寸)。 */
    const val pressScale = 0.97f
}
