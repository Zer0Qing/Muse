package io.zer0.muse.ui.theme

import kotlinx.serialization.Serializable
import kotlin.math.pow

/** Roles rendered by the host-controlled message bubble surface. */
@Serializable
enum class BubbleRole {
    USER,
    ASSISTANT,
    GROUP_ASSISTANT,
    SYSTEM,
    TOOL,
}

/** Declarative tail geometry; plugins never provide drawing code. */
@Serializable
enum class BubbleTailMode {
    NONE,
    ASYMMETRIC,
}

/** A bounded, serializable bubble style for one role. Colors are ARGB longs. */
@Serializable
data class BubbleRoleStyle(
    val surfaceArgb: Long,
    val contentArgb: Long,
    val outlineArgb: Long = 0L,
    val outlineWidthDp: Float = 0f,
    val radiusDp: Float = 18f,
    val paddingHorizontalDp: Float = 16f,
    val paddingVerticalDp: Float = 12f,
    val maxWidthFraction: Float = 0.78f,
    val fontScale: Float = 1f,
    val tail: BubbleTailMode = BubbleTailMode.NONE,
)

/**
 * Host-owned bubble skin. A plugin may distribute this data, but the app validates
 * and renders it; no plugin code or Compose object crosses this boundary.
 */
@Serializable
data class BubbleSkin(
    val schemaVersion: Int = 1,
    val id: String,
    val name: String,
    val author: String = "",
    val light: Map<BubbleRole, BubbleRoleStyle> = emptyMap(),
    val dark: Map<BubbleRole, BubbleRoleStyle> = emptyMap(),
)

/** Resolved style after theme and role selection, still free of executable code. */
data class ResolvedBubbleStyle(
    val role: BubbleRole,
    val style: BubbleRoleStyle,
    val sourceSkinId: String,
)

object BubbleSkinValidator {
    const val CURRENT_SCHEMA = 1
    /**
     * 正文与气泡底色之间的最低 WCAG 对比度。
     *
     * 低于该值的皮肤一律判为非法,由 [BubbleSkinResolver] 回退内置 default,
     * 保证插件皮肤永远不会把消息正文渲染成不可读的配色(AA 正文 4.5:1)。
     */
    const val MIN_CONTRAST_RATIO = 4.5
    private const val MAX_ID_LENGTH = 64
    private const val MAX_NAME_LENGTH = 128
    private const val MIN_RADIUS_DP = 0f
    private const val MAX_RADIUS_DP = 40f
    private const val MIN_PADDING_DP = 0f
    private const val MAX_PADDING_DP = 48f
    private const val MIN_WIDTH_FRACTION = 0.35f
    private const val MAX_WIDTH_FRACTION = 1f
    private const val MIN_FONT_SCALE = 0.75f
    private const val MAX_FONT_SCALE = 1.5f

    fun validate(skin: BubbleSkin): List<String> {
        val errors = mutableListOf<String>()
        if (skin.schemaVersion != CURRENT_SCHEMA) errors += "unsupported schemaVersion"
        if (!Regex("^[a-z0-9][a-z0-9_-]{0,63}$").matches(skin.id) || skin.id.length > MAX_ID_LENGTH) {
            errors += "invalid skin id"
        }
        if (skin.name.isBlank() || skin.name.length > MAX_NAME_LENGTH) errors += "invalid skin name"
        validateRoles(skin.light, "light", errors)
        validateRoles(skin.dark, "dark", errors)
        return errors
    }

    fun isValid(skin: BubbleSkin): Boolean = validate(skin).isEmpty()

    private fun validateRoles(
        roles: Map<BubbleRole, BubbleRoleStyle>,
        mode: String,
        errors: MutableList<String>,
    ) {
        roles.forEach { (role, style) ->
            if (style.radiusDp !in MIN_RADIUS_DP..MAX_RADIUS_DP) errors += "$mode/$role radius out of range"
            if (style.paddingHorizontalDp !in MIN_PADDING_DP..MAX_PADDING_DP) errors += "$mode/$role horizontal padding out of range"
            if (style.paddingVerticalDp !in MIN_PADDING_DP..MAX_PADDING_DP) errors += "$mode/$role vertical padding out of range"
            if (style.maxWidthFraction !in MIN_WIDTH_FRACTION..MAX_WIDTH_FRACTION) errors += "$mode/$role width out of range"
            if (style.fontScale !in MIN_FONT_SCALE..MAX_FONT_SCALE) errors += "$mode/$role font scale out of range"
            if (style.outlineWidthDp !in 0f..8f) errors += "$mode/$role outline width out of range"
            // 对比度不达标的皮肤(含插件包自带 JSON)在这里被拒绝,resolver 回退内置 default。
            if (contrastRatio(style.contentArgb, style.surfaceArgb) < MIN_CONTRAST_RATIO) {
                errors += "$mode/$role contrast out of range"
            }
        }
    }

    /**
     * WCAG 2.x 相对亮度对比度,输入为 ARGB 长整型(只取低 24 位 RGB)。
     *
     * 纯函数,不依赖 Android 图形库,便于单测与插件包校验复用。
     */
    fun contrastRatio(foregroundArgb: Long, backgroundArgb: Long): Double {
        val first = relativeLuminance(foregroundArgb)
        val second = relativeLuminance(backgroundArgb)
        val lighter = maxOf(first, second)
        val darker = minOf(first, second)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(argb: Long): Double {
        val red = linearize((argb shr 16 and 0xFF) / 255.0)
        val green = linearize((argb shr 8 and 0xFF) / 255.0)
        val blue = linearize((argb and 0xFF) / 255.0)
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue
    }

    private fun linearize(channel: Double): Double =
        if (channel <= 0.03928) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
}

object BubbleSkinResolver {
    fun resolve(
        skin: BubbleSkin?,
        role: BubbleRole,
        darkTheme: Boolean,
    ): ResolvedBubbleStyle {
        val fallback = defaultSkin(darkTheme)
        val candidate = skin?.takeIf(BubbleSkinValidator::isValid)
        val styles = if (darkTheme) candidate?.dark.orEmpty() else candidate?.light.orEmpty()
        val style = styles[role]
            ?: styles[BubbleRole.ASSISTANT]
            ?: fallbackStyle(fallback, role)
        return ResolvedBubbleStyle(role, style, candidate?.id ?: fallback.id)
    }

    fun defaultSkin(darkTheme: Boolean): BubbleSkin {
        val user = BubbleRoleStyle(
            surfaceArgb = if (darkTheme) 0xFF2E2E2E else 0xFFF0F0EC,
            contentArgb = if (darkTheme) 0xFFF5F5F2 else 0xFF252522,
            radiusDp = 18f,
            maxWidthFraction = 0.78f,
            tail = BubbleTailMode.ASYMMETRIC,
        )
        val assistant = BubbleRoleStyle(
            surfaceArgb = if (darkTheme) 0xFF20211F else 0xFFF8F8F5,
            contentArgb = if (darkTheme) 0xFFF5F5F2 else 0xFF252522,
            radiusDp = 18f,
            maxWidthFraction = 1f,
        )
        val all = BubbleRole.values().associateWith { role -> if (role == BubbleRole.USER) user else assistant }
        return BubbleSkin(
            id = "builtin-default",
            name = "Muse default",
            light = all,
            dark = all,
        )
    }

    private fun fallbackStyle(skin: BubbleSkin, role: BubbleRole): BubbleRoleStyle =
        skin.light[role] ?: skin.light[BubbleRole.ASSISTANT] ?: BubbleRoleStyle(
            surfaceArgb = 0xFFF8F8F5,
            contentArgb = 0xFF252522,
        )
}
