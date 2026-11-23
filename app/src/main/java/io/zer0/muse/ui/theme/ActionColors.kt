package io.zer0.muse.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 实心操作色令牌。
 *
 * 背景：此前可点控件混用三种观感 —— 品牌色主按钮、半透明灰容器
 * （surfaceVariant.copy(alpha = 0.5f)）、以及裸图标，加上按压时叠的
 * 半透明深色 wash，整体像一层"黑色遮罩"盖在内容上。
 *
 * 第一轮统一了口径（全部实心、去掉 alpha 遮罩），但容器取的是 onSurface，
 * 结果是所有按钮不论换哪套主题都只剩黑白灰。
 *
 * 现在的口径：**实心块一律走主题色**。
 *  - 主操作（发送、确认、动作按钮、选中态）= [container] 实心主色 + [content]；
 *  - 次级实心块（顶栏图标按钮、次要胶囊按钮）= [tonalContainer] 低饱和主色容器，
 *    跟随主题但不抢焦点，避免顶栏排满高饱和色块；
 *  - 选择类未选中 / 禁用态 = 不透明的 [neutralContainer]；
 *  - 所有容器一律不透明，不用 alpha 叠色当"遮罩"。
 *
 * [container]/[content] 与 [tonalContainer]/[tonalContent] 都是 ColorScheme 里成对的
 * 前景/背景组合，12 套预设主题 + 自定义主题都定义了这两对，换主题即换色，对比度由主题保证。
 */
/**
 * 全局透明度语义令牌，收口组件中散落的 `.copy(alpha = ...)` 魔法数字。
 * 与 `tokens.json` 的 `colors.alpha.*` 保持同源。
 */
object MuseAlpha {
    /** 列表行按压浅遮罩。 */
    const val rowPressed: Float = 0.06f

    /** 按钮 / 图标按压遮罩。 */
    const val pressedOverlay: Float = 0.08f

    /** 列表与卡片内发丝分隔线。 */
    const val divider: Float = 0.13f

    /** 状态色浅底叠层（优先使用 [MuseStatusColors] 的成对容器色，动态场景回退此档）。 */
    const val statusTint: Float = 0.14f

    /** 底部抽屉拖拽把手。 */
    const val handle: Float = 0.30f

    /** 控件禁用态容器透明度。 */
    const val disabled: Float = 0.38f

    /** 半透明柔和卡片/动作行容器底。 */
    const val mutedSurface: Float = 0.50f

    /** 柔和细描边透明度。 */
    const val subtleBorder: Float = 0.65f

    /** 控件禁用态文字透明度（保留浅底可读性）。 */
    const val disabledContent: Float = 0.75f

    /** 玻璃态面板基色透明度。 */
    const val glassSurface: Float = 0.78f

    /** 分组标题弱化透明度。 */
    const val titleMuted: Float = 0.85f
}

object MuseActionColors {

    /** 主操作容器：主题主色。 */
    val container: Color
        @Composable get() = MaterialTheme.colorScheme.primary

    /** 主操作内容：与 [container] 成对的反相色。 */
    val content: Color
        @Composable get() = MaterialTheme.colorScheme.onPrimary

    /** 次级实心容器：主色的低饱和容器色（顶栏图标按钮等）。 */
    val tonalContainer: Color
        @Composable get() = MaterialTheme.colorScheme.primaryContainer

    /** 次级实心内容：与 [tonalContainer] 成对。 */
    val tonalContent: Color
        @Composable get() = MaterialTheme.colorScheme.onPrimaryContainer

    /** 中性容器（未选中的选择类控件 / 禁用态）：不透明，替换原来的半透明叠色。 */
    val neutralContainer: Color
        @Composable get() = MaterialTheme.colorScheme.surfaceVariant

    /** 中性内容。 */
    val neutralContent: Color
        @Composable get() = MaterialTheme.colorScheme.onSurface

    /** 次要说明文字。 */
    val mutedContent: Color
        @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

    /** 危险操作容器（删除/清除）：主题错误色。 */
    val dangerContainer: Color
        @Composable get() = MaterialTheme.colorScheme.error

    /** 危险操作内容：与 [dangerContainer] 成对的反相色（浅色主题下通常是白字）。 */
    val dangerContent: Color
        @Composable get() = MaterialTheme.colorScheme.onError

    /** 禁用态透明度（统一指向 [MuseAlpha.disabled]）。 */
    const val disabledAlpha: Float = MuseAlpha.disabled
}
