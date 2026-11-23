package io.zer0.muse.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 纯数字刻度间距令牌（不绑定具体业务语义，消除「想写 12dp 找不到令牌只能硬编码或乱借语义令牌」的问题）。
 *
 * 4dp 基准网格 + 紧凑微调档（2/6/10dp）：
 *  - [xxs] = 2dp, [xs] = 4dp, [smTight] = 6dp, [sm] = 8dp, [mdTight] = 10dp,
 *  - [md] = 12dp, [lg] = 16dp, [xl] = 20dp, [xxl] = 24dp, [xxxl] = 32dp
 */
object MuseSpacing {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val smTight: Dp = 6.dp
    val sm: Dp = 8.dp
    val mdTight: Dp = 10.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 20.dp
    val xxl: Dp = 24.dp
    val xxxl: Dp = 32.dp
}

/**
 * 语义间距令牌([MusePaddings])。
 *
 * 相比 [MuseSpacing] 的纯数值刻度，此处给出页面/卡片/列表等场景语义档位，
 * 并保持「组内项距([itemGap]=12dp) < 节间距([sectionGap]=16dp) = 卡片组距([cardGap]=16dp)」的紧致层级。
 */
object MusePaddings {
    /** 屏幕水平边距(Scaffold padding)。 */
    val screen = MuseSpacing.lg

    /**
     * 设置类页面水平外边距(对齐 [screen]=16dp，避免小屏两侧过度压缩内容宽度)。
     */
    val pageMargin = MuseSpacing.lg

    /** 卡片内边距(统一所有 Card 的 padding：紧致但不局促)。 */
    val cardInner = PaddingValues(horizontal = 14.dp, vertical = 10.dp)

    /** section 之间间距。 */
    val sectionGap = MuseSpacing.lg

    /** 卡片组之间的间距（收紧至 16dp，消除过宽空沟）。 */
    val cardGap = MuseSpacing.lg

    /** 搜索栏胶囊高度（对齐顶栏胶囊节奏）。 */
    val searchBarHeight = 42.dp

    /** 列表项之间间距(LazyColumn spacedBy：12dp，与 sectionGap=16dp 拉开清晰层级)。 */
    val itemGap = MuseSpacing.md

    /** 卡片内组件之间间距。 */
    val contentGap = MuseSpacing.sm

    /** 紧凑间距(图标 + 文字)。 */
    val tightGap = MuseSpacing.xs

    /** 徽标 / 行间极紧间距。 */
    val tinyGap = MuseSpacing.xxs

    /** 小徽标 / 紧凑行间距。 */
    val labelVerticalGap = MuseSpacing.smTight

    /** 中等徽标 / 卡片内辅助间距。 */
    val auxGap = MuseSpacing.mdTight

    /** BottomSheet 内部 / 空状态大间距。 */
    val largeGap = MuseSpacing.xl

    /** 可滚动的详情对话框正文视口高度。 */
    val dialogContentMaxHeight = 320.dp

    /** 空状态 / 大留白。 */
    val emptyStateGap = MuseSpacing.xxxl

    /**
     * 触摸目标尺寸(最小触控区，统一委托 [MuseIconSizes.touchTarget] 为单一真源)。
     */
    val touchTarget: Dp get() = MuseIconSizes.touchTarget

    /** 输入框内边距。 */
    val inputPadding = MuseSpacing.md

    /** 输入栏横向/纵向外边距。 */
    val inputHorizontal = MuseSpacing.lg
    val inputVertical = MuseSpacing.xxs

    /** 输入栏内部紧凑行距(快捷入口/文档/视频预览)。 */
    val inputStackGap = MuseSpacing.smTight

    /** 待发送预览缩略图尺寸。 */
    val previewThumb = 72.dp

    /** 小圆点移除按钮内边距。 */
    val removeDotPadding = 3.dp

    /** 内联预览最大宽度。 */
    val maxInlineWidth = 120.dp

    /** 紧凑 chip 垂直内边距。 */
    val compactChipVertical = 1.dp

    /** 聊天输入岛外边距,让胶囊更轻量且略抬高。 */
    val inputIslandHorizontal = MuseSpacing.md
    val inputIslandVertical = MuseSpacing.xxs

    /** 输入岛底部悬浮间距(抬高输入岛,不贴底边)。 */
    val inputIslandBottomGap = MuseSpacing.mdTight

    /** 输入岛行内左右内边距(比 contentGap 更紧)。 */
    val inputIslandInnerHorizontal = MuseSpacing.smTight

    /** 输入岛内部行垂直间距;触控目标仍由 IconSizes.touchTarget 保证。 */
    val inputIslandRowVertical = 0.dp

    /** 消息输入框最大高度。 */
    val maxMessageFieldHeight = 160.dp

    /** 工具面板分隔线宽度/厚度。 */
    val dividerWidth = 1.dp
    val dividerThickness = 0.5.dp

    /** 加号工具面板列表最大高度。 */
    val maxToolSheetListHeight = 360.dp

    /** 图标内边距(图标与相邻文字间距)。 */
    val iconPadding = MuseSpacing.sm

    /** 消息间距(聊天列表 LazyColumn spacedBy，紧致自然的对话节奏)。 */
    val messageGap = 14.dp

    /** 屏幕宽 + contentGap 垂直(气泡/卡片)。 */
    val cardInnerSpaced = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

    /** 气泡/紧凑卡片内边距。 */
    val bubbleInner = PaddingValues(horizontal = 10.dp, vertical = 8.dp)

    /** 较宽松的 Chip 内边距(含图标+文本的复合标签,如视觉辅助/语速标签)。 */
    val chipInnerLoose = PaddingValues(horizontal = 8.dp, vertical = 4.dp)

    /** 较紧凑的 Chip 内边距(如 TopBar 行内边距)。 */
    val chipInnerTight = PaddingValues(horizontal = 4.dp, vertical = 1.dp)

    /** 列表底部留白(FAB / 输入栏让位)。 */
    val listBottomClearance = 76.dp

    /** section 标题起始缩进 — 对齐会话行标题文字列(行内边距 16dp + 图标列 26dp = 42dp)。 */
    val sectionTitleIndent = 42.dp
}
