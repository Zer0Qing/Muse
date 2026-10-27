package io.zer0.muse.ui.markdown.card

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * 卡片主题的精确值。
 *
 * 卡片外壳有一套静态默认变量（跟随 `prefers-color-scheme`），够用在「没有宿主」的场景。
 * 这里补的是**精确值**：当前 App 的实际主题，包括用户自己换过的配色。
 *
 * 做法是在静态默认之后注入同名变量，靠层叠覆盖 —— 于是：
 *
 *  - 有宿主时，卡片和原生界面的颜色是同一套；
 *  - 没有宿主时，静态默认仍然成立，不会变成一片透明。
 *
 * 变量名必须和既有那套保持一致，否则就是两个体系并存，模型不知道该用哪个。
 */
internal object CardTheme {

    /** 生成覆盖用的变量块。 */
    @Composable
    @ReadOnlyComposable
    fun css(): String {
        val scheme = MaterialTheme.colorScheme
        val dark = scheme.background.luminance() < 0.5f
        return buildString {
            append(":root{")
            appendSurfaces(scheme)
            appendInk(scheme)
            appendAccents(scheme)
            appendSemantic(scheme)
            appendDataColors(dark)
            appendMetrics()
            append("}")
        }
    }

    /** 面：卡片表面与下沉层。`--muse-bg` 保持透明，它是页面底，不是卡片面。 */
    private fun StringBuilder.appendSurfaces(scheme: ColorScheme) {
        declare("--muse-surface", scheme.surface)
        declare("--muse-surface-2", scheme.surfaceVariant)
        declare("--muse-surface-3", scheme.surfaceContainerHigh)
        declare("--muse-card-line-soft", scheme.outlineVariant.copy(alpha = 0.55f))
    }

    /** 墨：三级文字。 */
    private fun StringBuilder.appendInk(scheme: ColorScheme) {
        declare("--muse-fg", scheme.onSurface)
        declare("--muse-muted", scheme.onSurfaceVariant)
        declare("--muse-faint", scheme.outline)
        declare("--muse-border", scheme.outlineVariant)
    }

    /** 强调：月桂绿。交互与焦点的唯一颜色。 */
    private fun StringBuilder.appendAccents(scheme: ColorScheme) {
        declare("--muse-accent", scheme.primary)
        declare("--muse-accent-fg", scheme.onPrimary)
        declare("--muse-accent-soft", scheme.primaryContainer)
        declare("--muse-accent-soft-fg", scheme.onPrimaryContainer)
    }

    /** 语义：一个成功、一个警示、一个错误。数量克制。 */
    private fun StringBuilder.appendSemantic(scheme: ColorScheme) {
        declare("--muse-good", scheme.tertiary)
        declare("--muse-warn", Color(0xFFB07D20))
        declare("--muse-danger", scheme.error)
    }

    /**
     * 数据编码用的六色。
     *
     * 与强调色分离：强调色只管交互，数据色只编码数据。选色落在低饱和区间，
     * 并尽量贴近月桂绿的邻近色域，避免一屏里突然冒出高饱和的红紫。
     */
    private fun StringBuilder.appendDataColors(dark: Boolean) {
        val palette = if (dark) {
            listOf(
                Color(0xFF4A9F70),
                Color(0xFF5C8FA8),
                Color(0xFFC08A4E),
                Color(0xFF9A7FB0),
                Color(0xFFB06A62),
                Color(0xFF8C8A5A),
            )
        } else {
            listOf(
                Color(0xFF2A7A55),
                Color(0xFF416F98),
                Color(0xFFA86F35),
                Color(0xFF7D6394),
                Color(0xFF9D5750),
                Color(0xFF6D7843),
            )
        }
        palette.forEachIndexed { index, color -> declare("--muse-d${index + 1}", color) }
    }

    /** 间距、圆角、字体与度量。数值即定稿，用法一律走变量。 */
    private fun StringBuilder.appendMetrics() {
        append("--muse-gap-xs:4px;--muse-gap-sm:8px;")
        // 间距五档。卡片里所有 padding/gap 都从这里取，不写魔法数字。
        append("--muse-s1:4px;--muse-s2:8px;--muse-s3:12px;")
        append("--muse-s4:16px;--muse-s5:24px;--muse-s6:32px;")
        append("--muse-radius-lg:16px;")
        // 衬线只给标题与引文；正文用无衬线，保证小字号下的可读性。
        append("--muse-serif:'Noto Serif SC','Source Han Serif SC','Songti SC',serif;")
        append("--muse-sans:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;")
        append("--muse-mono:'JetBrains Mono',ui-monospace,monospace;")
        // 阅读宽度。runtime 会按声明的首选宽度覆写。
        append("--muse-measure:100%;")
    }

    /**
     * 写一个变量。
     *
     * 先给宿主覆写入口 `--muse-theme-<名字>`，再给当前主题算出来的值。
     * 宿主想整卡换皮时覆写前者即可，不用改卡片自己的样式。
     */
    private fun StringBuilder.declare(name: String, value: Color) {
        val themeKey = name.removePrefix("--muse-")
        append(name).append(":var(--muse-theme-").append(themeKey).append(',')
        append(value.toCssHex()).append(");")
    }
}

/** 颜色转 CSS 十六进制。带 alpha 时输出 rgba，保证半透明不被吞掉。 */
internal fun Color.toCssHex(): String {
    val a = (alpha * 255).toInt().coerceIn(0, 255)
    val r = (red * 255).toInt().coerceIn(0, 255)
    val g = (green * 255).toInt().coerceIn(0, 255)
    val b = (blue * 255).toInt().coerceIn(0, 255)
    return if (a >= 255) {
        "#%02X%02X%02X".format(r, g, b)
    } else {
        "rgba($r,$g,$b,${"%.3f".format(a / 255f)})"
    }
}
