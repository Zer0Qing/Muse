package io.zer0.muse.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Muse 形状令牌(对齐前端设计完整方案 §5.1)。
 *
 * 圆角体系(从大到小):
 *  - [extraLarge]: 20.dp — 大卡片 / 大型容器
 *  - [large]:     18.dp — 消息气泡主力(大圆角=柔和=情感 App 气质)
 *  - [medium]:     12.dp — 按钮 / 代码块 / 列表项(中等,不太圆也不太方)
 *  - [small]:       8.dp — 行内代码 / 小型标签
 *  - [extraSmall]:  6.dp — 气泡尾巴角(比其他角小,指示消息方向)
 *
 * 用法:
 * ```
 * Card(shape = MuseShapes.medium) { ... }
 * Surface(shape = MuseShapes.large) { ... }
 * ```
 *
 * 气泡尾巴需要非对称圆角,用 [BubbleShape] 而非 RoundedCornerShape.copy。
 *
 * 注:[MuseShapes] 受限于 [Shapes] 的 5 个固定槽位(extraSmall/small/medium/large/extraLarge),
 * 其余圆角档位(tiny / semiLarge / huge / mega / pill)见下方 [Shapes] 扩展属性。
 */
val MuseShapes: Shapes = Shapes(
    // L-SH2: 由 MuseCornerRadius 常量驱动,消除数值重复(改动只需改一处)。
    extraSmall = RoundedCornerShape(MuseCornerRadius.BUBBLE_TAIL.dp),  // 气泡尾巴角 / 小标签
    small = RoundedCornerShape(MuseCornerRadius.SMALL.dp),            // 行内代码 / 小型标签
    medium = RoundedCornerShape(MuseCornerRadius.BUTTON.dp),          // 按钮 / 代码块 / 列表项
    large = RoundedCornerShape(MuseCornerRadius.BUBBLE.dp),           // 消息气泡主力
    extraLarge = RoundedCornerShape(MuseCornerRadius.CARD.dp),        // 大卡片 / 大型容器
)

/**
 * Muse 形状令牌扩展(补全 [MuseShapes] 之外的圆角档位)。
 *
 * [MuseShapes] 是 [Shapes] 实例,只有 5 个固定槽位;全项目其余圆角取值
 * (50/28/24/16/4 等历史裸值)在此统一为令牌,通过 Shapes 扩展属性暴露,
 * 使 `MuseShapes.xxx` 形式与既有 `MuseShapes.medium` 等保持一致。
 *
 * 补全档位(从小到大):
 *  - [tiny]:      4.dp  — 标签 / 徽标
 *  - [semiLarge]: 16.dp — 输入框 / 中卡片
 *  - [huge]:      24.dp — 顶圆角 BottomSheet
 *  - [mega]:      28.dp — 特殊大圆角(FAB)
 *  - [pill]:      50%  — 胶囊形(按钮 / 搜索栏)
 *
 * 用法:
 * ```
 * FilledTonalButton(shape = MuseShapes.pill) { ... }
 * Surface(shape = MuseShapes.semiLarge) { ... }
 * ```
 */
// L-SH1: 以下扩展形状改为顶层 val 缓存,避免每次访问都新建 RoundedCornerShape。
// L-SH2: 数值统一由 MuseCornerRadius 常量驱动,与 MuseShapes 严格对应。
// L-8: 这些扩展属性定义在 Shapes 上,但语义上假设接收者为 MuseShapes
// (数值令牌来自 MuseCornerRadius,与 MuseShapes 的 5 个槽位配套)。其他 Shapes
// 实例调用这些扩展虽可编译,但取值与 MuseShapes 体系无关,不应混用。
/** 标签 / 徽标。 */
private val TinyShape = RoundedCornerShape(MuseCornerRadius.TINY.dp)
val Shapes.tiny: RoundedCornerShape get() = TinyShape

/** 输入框 / 中卡片。 */
private val SemiLargeShape = RoundedCornerShape(MuseCornerRadius.SEMI_LARGE.dp)
val Shapes.semiLarge: RoundedCornerShape get() = SemiLargeShape

/** 顶圆角 BottomSheet。 */
private val HugeShape = RoundedCornerShape(MuseCornerRadius.HUGE.dp)
val Shapes.huge: RoundedCornerShape get() = HugeShape

/** 特殊大圆角(FAB)。 */
private val MegaShape = RoundedCornerShape(MuseCornerRadius.MEGA.dp)
val Shapes.mega: RoundedCornerShape get() = MegaShape

/** 胶囊形:50% 圆角,任意高度下均为完整胶囊(按钮 / 搜索栏)。 */
// L-9: 显式用 percent 重载,避免误传 .dp 触发 Int→dp 重载产生非预期圆角。
private val PillShape = RoundedCornerShape(percent = MuseCornerRadius.PILL)
val Shapes.pill: RoundedCornerShape get() = PillShape

/**
 * v1.48 (h18): 气泡形状令牌(非对称圆角,尾巴角 6dp + 主体 20dp)。
 *
 * 落实第 23 行注释承诺,统一单聊与群聊的消息气泡圆角,根除裸值
 * `RoundedCornerShape(20.dp,20.dp,20.dp,6.dp)`(单聊)与
 * `RoundedCornerShape(18.dp,18.dp,4.dp,18.dp)`(群聊)混用。
 *
 *  - [userBubble]:      (20,20,20,6) — 用户气泡,右下小尾巴
 *  - [assistantBubble]: (6,20,20,20) — AI 气泡,左下小尾巴
 *
 * 数值对应 [MuseCornerRadius.BUBBLE_TAIL] (6) 与 [MuseCornerRadius.CARD] (20)。
 */
/** 用户气泡:右下角小圆角模拟尾巴(主体 20dp + 尾巴 6dp)。 */
private val UserBubbleShape = RoundedCornerShape(
    MuseCornerRadius.CARD.dp, MuseCornerRadius.CARD.dp,
    MuseCornerRadius.CARD.dp, MuseCornerRadius.BUBBLE_TAIL.dp,
)
val Shapes.userBubble: RoundedCornerShape get() = UserBubbleShape

/** AI 气泡:左下角小圆角模拟尾巴(主体 20dp + 尾巴 6dp)。 */
private val AssistantBubbleShape = RoundedCornerShape(
    MuseCornerRadius.BUBBLE_TAIL.dp, MuseCornerRadius.CARD.dp,
    MuseCornerRadius.CARD.dp, MuseCornerRadius.CARD.dp,
)
val Shapes.assistantBubble: RoundedCornerShape get() = AssistantBubbleShape

/**
 * 便捷常量(直接用于 RoundedCornerShape 场景或代码注释)。
 * 数值与 [MuseShapes] 严格对应,改动需同步。
 */
object MuseCornerRadius {
    /** 气泡尾巴角(非对称角)。 */
    const val BUBBLE_TAIL = 6
    /** 行内代码 / 小型标签。 */
    const val SMALL = 8
    /** 按钮 / 代码块 / 列表项。 */
    const val BUTTON = 12
    /** 消息气泡主力。 */
    const val BUBBLE = 18
    /** 卡片 / 大型容器。 */
    const val CARD = 20
    /** 底部弹出面板顶部。 */
    const val SHEET = 24

    // ── 补全令牌(标准化命名,数值与上方对应,改动需同步)──

    /** 标签 / 徽标。 */
    const val TINY = 4
    /** 中档圆角(等同 BUTTON)。 */
    const val MEDIUM = 12
    /** 输入框 / 中卡片。 */
    const val SEMI_LARGE = 16
    /** 大圆角(等同 BUBBLE)。 */
    const val LARGE = 18
    /** 大卡片(等同 CARD)。 */
    const val EXTRA_LARGE = 20
    /** 顶圆角 BottomSheet(等同 SHEET)。 */
    const val HUGE = 24
    /** 特殊大圆角(FAB)。 */
    const val MEGA = 28
    /** 胶囊形(50% 圆角)。 */
    const val PILL = 50
}
