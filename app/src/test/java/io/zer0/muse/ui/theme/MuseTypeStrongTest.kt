package io.zer0.muse.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 强调变体角色的两条硬判据。
 *
 * 1) **视觉等价**：只允许字重变化，字号/行高/字距必须与基础角色完全一致
 *    —— 这是"把 33 处内联覆盖换成角色"能算零风险重构的前提；
 * 2) **必须跟随字号缩放**：主题会按档位跑 `Typography.scaled()`（0.85x…1.3x）。
 *    如果哪天有人把角色改写成 `val xxx = MuseTypography.labelLarge.copy(...)` 这种
 *    **静态常量**，测试必须当场红：静态值绕不开缩放，用户在设置里放大字号时
 *    这些文字会悄悄不跟着变大 —— 那是真回归，不是风格问题。
 */
class MuseTypeStrongTest {

    private val pairs: List<Pair<String, (androidx.compose.material3.Typography) -> androidx.compose.ui.text.TextStyle>> = listOf(
        "labelLargeStrong" to { t -> t.labelLargeStrong },
        "bodyMediumStrong" to { t -> t.bodyMediumStrong },
        "titleSmallStrong" to { t -> t.titleSmallStrong },
        "bodyLargeStrong" to { t -> t.bodyLargeStrong },
        "bodySmallStrong" to { t -> t.bodySmallStrong },
        "labelSmallStrong" to { t -> t.labelSmallStrong },
        "titleMediumStrong" to { t -> t.titleMediumStrong },
        "bodyLargeEmphasis" to { t -> t.bodyLargeEmphasis },
        "bodySmallEmphasis" to { t -> t.bodySmallEmphasis },
    )

    private val bases: Map<String, (androidx.compose.material3.Typography) -> androidx.compose.ui.text.TextStyle> = mapOf(
        "labelLargeStrong" to { t -> t.labelLarge },
        "bodyMediumStrong" to { t -> t.bodyMedium },
        "titleSmallStrong" to { t -> t.titleSmall },
        "bodyLargeStrong" to { t -> t.bodyLarge },
        "bodySmallStrong" to { t -> t.bodySmall },
        "labelSmallStrong" to { t -> t.labelSmall },
        "titleMediumStrong" to { t -> t.titleMedium },
        "bodyLargeEmphasis" to { t -> t.bodyLarge },
        "bodySmallEmphasis" to { t -> t.bodySmall },
    )

    @Test
    fun `强调角色只改字重其余全部继承基础角色`() {
        pairs.forEach { (name, pick) ->
            val strong = pick(MuseTypography)
            val base = bases.getValue(name)(MuseTypography)
            assertEquals("$name 的字号必须等于基础角色", base.fontSize, strong.fontSize)
            assertEquals("$name 的行高必须等于基础角色", base.lineHeight, strong.lineHeight)
            assertEquals("$name 的字距必须等于基础角色", base.letterSpacing, strong.letterSpacing)
            assertTrue(
                "$name 的字重必须严格比基础角色更重（否则它就是个空转角色，该删）",
                weightOf(strong) > weightOf(base),
            )
        }
    }

    /** 字重数值；没写就当 Normal(400)，比较语义与 Compose 一致。 */
    private fun weightOf(style: androidx.compose.ui.text.TextStyle): Int {
        val weight = style.fontWeight ?: return 400
        return weight.weight
    }

    @Test
    fun `强调角色必须跟随字号缩放档位`() {
        // 这一条专抓"改成静态常量"的写法：静态值不会随 scaled() 变大。
        listOf("large", "xlarge").forEach { scale ->
            val scaledType = MuseTypography.scaled(scale)
            val factor = if (scale == "large") 1.15f else 1.3f
            pairs.forEach { (name, pick) ->
                val baseSize = bases.getValue(name)(MuseTypography).fontSize.value
                val scaledSize = pick(scaledType).fontSize.value
                assertEquals(
                    "$name 在 $scale 档必须与基础角色一起放大（静态常量的话这里会不等）",
                    baseSize * factor,
                    scaledSize,
                    0.05f,
                )
                assertTrue(
                    "$name 在 $scale 档应比默认大",
                    scaledSize > pick(MuseTypography).fontSize.value,
                )
            }
        }
    }

    @Test
    fun `默认档位下强调角色与缩放后的自身一致`() {
        val medium = MuseTypography.scaled("medium")
        assertEquals(
            "medium 档就是原样，不该被二次缩放",
            medium.labelLargeStrong.fontSize,
            MuseTypography.labelLargeStrong.fontSize,
        )
    }
}
