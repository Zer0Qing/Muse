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

    /** 禁用态透明度（与既有组件约定一致）。 */
    const val disabledAlpha = 0.38f
}
