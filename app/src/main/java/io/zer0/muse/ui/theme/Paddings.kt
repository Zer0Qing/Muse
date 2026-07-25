package io.zer0.muse.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp

/**
 * Phase 12: muse 间距令牌(MusePaddings)。
 *
 * 根治"padding 8/12/14/16/24 混用"的不一致问题。
 * 全项目统一引用 [MusePaddings] 令牌。
 *
 * 令牌层级(iOS 风格留白节奏):
 *  - [screen]:         16.dp — 屏幕水平边距(Scaffold padding)
 *  - [cardInner]:      horizontal=16, vertical=12 — 卡片内边距
 *  - [cardInnerLoose]: horizontal=16, vertical=14 — 略松的卡片内边距
 *  - [sectionGap]:     16.dp — section 之间间距
 *  - [itemGap]:        12.dp — 列表项之间间距(LazyColumn spacedBy)
 *  - [contentGap]:     8.dp — 卡片内组件之间间距
 *  - [tightGap]:       4.dp — 紧凑间距(图标 + 文字)
 *  - [tinyGap]:        2.dp — 徽标 / 行间极紧间距
 *  - [labelVerticalGap]: 6.dp — 小徽标 / 紧凑行间距
 *  - [auxGap]:         10.dp — 中等徽标 / 卡片内辅助间距
 *  - [largeGap]:       24.dp — BottomSheet 内部 / 空状态大间距
 *  - [emptyStateGap]:  32.dp — 空状态 / 大留白
 *  v1.0.17 扩展(复合 PaddingValues 令牌,收敛高频组合模式):
 *  - [cardInnerSpaced]: horizontal=16, vertical=8 — 屏幕宽 + contentGap 垂直
 *  - [bubbleInner]:     horizontal=12, vertical=8 — 气泡/紧凑卡片内边距
 *  - [cardInnerTight]:  horizontal=16, vertical=4 — 列表行紧凑内边距
 *  - [cardInnerMedium]: horizontal=14, vertical=10 — 中等卡片内边距
 *  - [cardInnerAux]:    horizontal=12, vertical=10 — auxGap 垂直变体
 *  - [chipInner]:       horizontal=6, vertical=2 — 小徽标/Chip 内边距
 *  - [chipInnerLoose]:  horizontal=8, vertical=4 — 略大的 Chip 内边距
 *  - [chipInnerTight]:  horizontal=4, vertical=2 — 极小 Chip 内边距
 */
object MusePaddings {
    /** 屏幕水平边距(Scaffold padding)。 */
    val screen = 16.dp
    /** 卡片内边距(统一所有 Card 的 padding)。 */
    val cardInner = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    /** 略松的卡片内边距(列表项大留白场景)。 */
    val cardInnerLoose = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    /** section 之间间距。 */
    val sectionGap = 16.dp
    /** 列表项之间间距(LazyColumn spacedBy)。 */
    val itemGap = 12.dp
    /** 卡片内组件之间间距。 */
    val contentGap = 8.dp
    /** 紧凑间距(图标 + 文字)。 */
    val tightGap = 4.dp
    /** v1.0.15: 徽标 / 行间极紧间距。 */
    val tinyGap = 2.dp
    /** v1.0.15: 小徽标 / 紧凑行间距。 */
    val labelVerticalGap = 6.dp
    /** v1.0.15: 中等徽标 / 卡片内辅助间距。 */
    val auxGap = 10.dp
    /** v1.0.15: BottomSheet 内部 / 空状态大间距。 */
    val largeGap = 24.dp
    /** v1.0.15: 空状态 / 大留白。 */
    val emptyStateGap = 32.dp
    /**
     * 触摸目标尺寸(MD3 红线,IconButton / 行高最小值)。
     * L-PD1: 统一以 [MuseIconSizes.touchTarget] 为唯一数据源,此处委托引用,
     * 避免两处分别定义 48.dp 造成双数据源漂移。
     */
    val touchTarget: androidx.compose.ui.unit.Dp get() = MuseIconSizes.touchTarget
    /** 输入框内边距。 */
    val inputPadding = 12.dp
    /** 图标内边距(图标与相邻文字间距)。 */
    val iconPadding = 8.dp
    /** M-CS5: 消息间距(聊天列表 LazyColumn spacedBy,iOS 风格呼吸感)。 */
    val messageGap = 20.dp
    /** v1.0.17: 屏幕宽 + contentGap 垂直(气泡/卡片)。 */
    val cardInnerSpaced = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    /** v1.0.17: 气泡/紧凑卡片内边距。 */
    val bubbleInner = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    /** v1.0.17: 列表行紧凑内边距。 */
    val cardInnerTight = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
    /** v1.0.17: 中等卡片内边距。 */
    val cardInnerMedium = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    /** v1.0.17: auxGap 垂直变体。 */
    val cardInnerAux = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
    /** v1.0.17: 小徽标/Chip 内边距。 */
    val chipInner = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
    /** v1.0.17: 略大的 Chip 内边距。 */
    val chipInnerLoose = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
    /** v1.0.17: 极小 Chip 内边距。 */
    val chipInnerTight = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
}
