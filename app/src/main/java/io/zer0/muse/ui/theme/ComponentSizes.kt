package io.zer0.muse.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Muse 设计令牌 — 组件专属尺寸(CMP-08)。
 *
 * 与 [MusePaddings] / [MuseCornerRadius] / [MuseIconSizes] 互补:通用间距 / 圆角 / 图标
 * 走那三套令牌,而少数**只属于某个组件**、全局找不到同值的尺寸(弹窗宽度、开关轨道几何)
 * 在此按组件归属集中定义,组件内不再出现魔法数字,调整只需改这一处。
 *
 * 新增档位必须写明「用在哪」;能落进通用令牌的(间距、圆角、图标尺寸)一律落通用令牌。
 */
object MuseDialogSizes {
    /** 弹窗内容区内边距(22dp:比卡片 16dp 多一档呼吸感,iOS Alert 视觉)。 */
    val contentPadding = 22.dp
    /** 弹窗最大宽度(大屏居中;窄屏由系统宽度兜底)。 */
    val maxWidth = 340.dp
    /** 弹窗内容区最大高度(超出后内容区内部滚动,标题与按钮保持可见)。 */
    val contentMaxHeight = 420.dp
}

object MuseSwitchSizes {
    /** 开关轨道宽度(iOS 开关规格)。 */
    val trackWidth = 51.dp
    /** 开关轨道高度(iOS 开关规格)。 */
    val trackHeight = 31.dp
    /** 开关拇指直径(轨道高度 − 2×inset)。 */
    val thumbSize = 27.dp
    /** 拇指与轨道的内缩(复用通用紧凑间距令牌,不引入新值)。 */
    val trackPadding = MusePaddings.tinyGap
}
