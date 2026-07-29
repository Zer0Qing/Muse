package io.zer0.muse.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Muse UI Kit — 语义化配色令牌。
 *
 * 为 [io.zer0.muse.ui.components] 下的通用 UI 组件 (RiskBadge / StatusDot 等)
 * 提供与品牌一致的语义颜色,避免组件硬编码色值。
 *
 * 色值来源:复用 [Color.kt] 中已定义的品牌色与情感色板,保持深浅模式一致。
 * - 风险等级:安全=鼠尾草绿 / 中等=琥珀橙 / 高风险=暖砖红
 * - 状态圆点:活跃=品牌绿提亮 / 历史=半透明绿 / 离线=雾灰
 */
object MuseColors {

    // ── 风险等级 ──────────────────────────────────────────────────────

    /** 安全:只读操作,无副作用。鼠尾草绿,平静、成长。 */
    val riskSafe: Color = SageCalm

    /** 中等:有副作用但可逆。琥珀橙,温暖、注意。 */
    val riskNormal: Color = AmberWarmth

    /** 高风险:不可逆操作(删除/发送/拨号)。暖砖红,与 Danger 一致。 */
    val riskHigh: Color = Danger

    // ── 状态圆点 ──────────────────────────────────────────────────────

    /** 活跃/在线:品牌绿提亮版,在深浅模式下均有良好可见度。 */
    val dotActive: Color = LaurelGreenBright

    /** 历史:半透明绿,表示已结束但仍可追溯。 */
    val dotHistory: Color = LaurelGreenBright.copy(alpha = 0.4f)

    /** 离线:中性雾灰,不抢眼。 */
    val dotOffline: Color = MistGray
}
