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
 *  CMP-02: 其余复合组合(cardInnerTight / cardInnerMedium / cardInnerAux / chipInner)
 *  与文档档位重复,已**删除**;调用点一律由上方单值档位显式组合,不保留第二套组合令牌。
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
    /** B7-07: 输入栏横向/纵向外边距。 */
    val inputHorizontal = 24.dp
    val inputVertical = 2.dp
    /** B7-07: 输入栏内部紧凑行距(快捷入口/文档/视频预览)。 */
    val inputStackGap = 6.dp
    /** B7-07: 待发送预览缩略图尺寸。 */
    val previewThumb = 72.dp
    /** B7-07: 小圆点移除按钮内边距。 */
    val removeDotPadding = 3.dp
    /** B7-07: 内联预览最大宽度。 */
    val maxInlineWidth = 120.dp
    /** B7-07: 紧凑 chip 垂直内边距。 */
    val compactChipVertical = 1.dp
    /** B7-07: 消息输入框最大高度。 */
    val maxMessageFieldHeight = 160.dp
    /** B7-07: 工具面板分隔线宽度/厚度。 */
    val dividerWidth = 1.dp
    val dividerThickness = 0.5.dp
    /** B7-07: 加号工具面板列表最大高度。 */
    val maxToolSheetListHeight = 360.dp
    /** B7-07: 工具列表行垂直内边距。 */
    val listRowVertical = 14.dp
    /** 图标内边距(图标与相邻文字间距)。 */
    val iconPadding = 8.dp
    /** M-CS5: 消息间距(聊天列表 LazyColumn spacedBy,iOS 风格呼吸感)。 */
    val messageGap = 20.dp
    /** v1.0.17: 屏幕宽 + contentGap 垂直(气泡/卡片)。 */
    val cardInnerSpaced = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    /** v1.0.17: 气泡/紧凑卡片内边距。 */
    val bubbleInner = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    /**
     * CMP-02: 复合组合别名(cardInnerTight / cardInnerMedium / cardInnerAux / chipInner)
     * 已全部删除 — 调用点改为由上方单值档位显式组合
     * (例:`padding(horizontal = itemGap, vertical = auxGap)`),不再保留第二套组合令牌。
     */
    /** 较宽松的 Chip 内边距(含图标+文本的复合标签,如视觉辅助/语速标签)。 */
    val chipInnerLoose = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
    /** 较紧凑的 Chip 内边距(如 TopBar 行内边距)。 */
    val chipInnerTight = PaddingValues(horizontal = 4.dp, vertical = 1.dp)
    /** CHAT-18: 列表底部留白(FAB / 输入栏让位)。 */
    val listBottomClearance = 88.dp
    /** CHAT-18: section 标题起始缩进(对齐行内容)。 */
    val sectionTitleIndent = 56.dp
}
