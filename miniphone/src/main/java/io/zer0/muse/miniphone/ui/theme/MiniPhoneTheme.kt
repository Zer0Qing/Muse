package io.zer0.muse.miniphone.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 小手机语义色槽。
 *
 * R0 重构：小手机不再持有独立色板/美术体系——这些槽位的**值**一律从宿主
 * `MaterialTheme.colorScheme` 派生（宿主外壳即主应用主题），从而与外面 UI 同一套
 * 设计令牌与主题；换肤/深色时小手机跟随宿主，不再自带一套纸感配色。槽位名保留，组件无需逐个改写。
 */
@Immutable
@Suppress("LongParameterList")
data class MiniPhoneColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val divider: Color,
    val accent: Color,
    val onAccent: Color,
    val unreadDot: Color,
    val unreadDotText: Color,
    val bubbleSelf: Color,
    val bubbleSelfText: Color,
    val bubbleOther: Color,
    val bubbleOtherText: Color,
    val bubbleOtherBorder: Color,
    val wallpaperTop: Color,
    val wallpaperBottom: Color,
)

/**
 * 字号阶梯:标题 / 正文 / 次要 / 时间戳。值从宿主 typography 派生（见 [MiniPhoneTheme]）。
 */
@Immutable
data class MiniPhoneTypography(
    val title: TextStyle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
    val body: TextStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp),
    val caption: TextStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp),
    val timestamp: TextStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp),
)

/**
 * 尺寸阶梯。
 *
 * 前五项是本模块的固定规格(气泡圆角、卡片圆角、两种头像、页边距);
 * 后面几项是外壳布局绕不开的高度与图标尺寸,单独散落在各处会更难对齐。
 */
@Immutable
@Suppress("LongParameterList")
data class MiniPhoneDimens(
    val bubbleRadius: Dp = 10.dp,
    val cardRadius: Dp = 14.dp,
    val avatarList: Dp = 44.dp,
    val avatarBubble: Dp = 36.dp,
    val pageMargin: Dp = 16.dp,
    val avatarLarge: Dp = 56.dp,
    val unreadBadge: Dp = 18.dp,
    val hairline: Dp = 1.dp,
    val statusBarHeight: Dp = 30.dp,
    val statusIcon: Dp = 15.dp,
    val pageHeaderHeight: Dp = 48.dp,
    val tabBarHeight: Dp = 58.dp,
    val tabIcon: Dp = 22.dp,
    val launcherTile: Dp = 56.dp,
    val launcherIcon: Dp = 30.dp,
    val dockHeight: Dp = 76.dp,
    /** 朋友圈封面的高度:压住一屏的顶部,又不把下面第一张卡片挤下去。 */
    val coverHeight: Dp = 160.dp,
)

val LocalMiniPhoneColors = staticCompositionLocalOf<MiniPhoneColors> { error("MiniPhoneTheme not applied") }
val LocalMiniPhoneTypography = staticCompositionLocalOf { MiniPhoneTypography() }
val LocalMiniPhoneDimens = staticCompositionLocalOf { MiniPhoneDimens() }

/**
 * 小手机主题入口：把宿主 MaterialTheme（= 主应用外面主题）映射进小手机语义槽。
 *
 * 不再自带色板：颜色读宿主 colorScheme、字号读宿主 typography。宿主换肤或切深色，
 * 小手机同步跟随，观感与外面近似。尺寸阶梯是结构规格，保持本模块定义。
 */
@Composable
fun MiniPhoneTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val hostTypography = MaterialTheme.typography
    val colors = remember(scheme, darkTheme) {
        MiniPhoneColors(
            isDark = darkTheme,
            background = scheme.background,
            surface = scheme.surface,
            textPrimary = scheme.onBackground,
            textSecondary = scheme.onSurfaceVariant,
            divider = scheme.outlineVariant,
            accent = scheme.primary,
            onAccent = scheme.onPrimary,
            unreadDot = scheme.error,
            unreadDotText = scheme.onError,
            bubbleSelf = scheme.primary,
            bubbleSelfText = scheme.onPrimary,
            bubbleOther = scheme.surfaceVariant,
            bubbleOtherText = scheme.onSurfaceVariant,
            bubbleOtherBorder = scheme.outlineVariant,
            wallpaperTop = scheme.background,
            wallpaperBottom = scheme.surfaceVariant,
        )
    }
    val typography = remember(hostTypography) {
        MiniPhoneTypography(
            title = hostTypography.titleMedium,
            body = hostTypography.bodyLarge,
            caption = hostTypography.bodySmall,
            timestamp = hostTypography.labelSmall,
        )
    }
    CompositionLocalProvider(
        LocalMiniPhoneColors provides colors,
        LocalMiniPhoneTypography provides typography,
        LocalMiniPhoneDimens provides MiniPhoneDimens(),
        content = content,
    )
}

/** token 取用口:写界面时只从这里拿颜色、字号、尺寸。 */
object MiniPhoneTheme {
    val colors: MiniPhoneColors
        @Composable @ReadOnlyComposable
        get() = LocalMiniPhoneColors.current

    val typography: MiniPhoneTypography
        @Composable @ReadOnlyComposable
        get() = LocalMiniPhoneTypography.current

    val dimens: MiniPhoneDimens
        @Composable @ReadOnlyComposable
        get() = LocalMiniPhoneDimens.current
}
