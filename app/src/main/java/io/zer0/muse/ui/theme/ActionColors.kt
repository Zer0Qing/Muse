package io.zer0.muse.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 实心操作色令牌（UI-FIX A）。
 *
 * 背景：此前可点控件混用三种观感 —— 品牌绿主按钮、半透明灰容器
 * （surfaceVariant.copy(alpha = 0.5f)）、以及主色/裸图标，加上按压时叠的
 * 半透明深色 wash，整体像一层"黑色遮罩"盖在内容上，透出底下的东西很别扭。
 *
 * 统一口径：
 *  - 主操作（发送、确认、动作按钮）= [container] 实心 + [content] 反相文字/图标；
 *  - 选择类（胶囊 Tab、筛选 chip）= 选中用 [container]，未选中用不透明的 [neutralContainer]；
 *  - 所有容器一律不透明，不再用 alpha 叠色当"遮罩"。
 *
 * 容器取 onSurface：浅色下是近黑，深色下是近白，两种主题都保持与页面背景的强对比，
 * 同时天然满足对比度要求（onSurface 与 surface 是互为反相的一对）。
 */
object MuseActionColors {

    /** 主操作容器：浅色近黑 / 深色近白。 */
    val container: Color
        @Composable get() = MaterialTheme.colorScheme.onSurface

    /** 主操作内容：与 [container] 反相。 */
    val content: Color
        @Composable get() = MaterialTheme.colorScheme.surface

    /** 中性容器（未选中的选择类控件）：不透明，替换原来的半透明叠色。 */
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
