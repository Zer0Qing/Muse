package io.zer0.muse.miniphone.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
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
 * 小手机自有色板。
 *
 * 本模块不继承宿主主题:宿主的配色是为聊天工作台调的(玻璃、渐变、表情皮肤),
 * 而小手机要的是纸的质感 —— 暖白打底,黛绿做强调,砖红只留给未读。
 *
 * 深色不是浅色的简单反转:背景压到近黑,卡片抬一档,强调色提亮保证可读,
 * 自己气泡改浅底深字(深底上再压白字对比不够)。
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

/** 浅色四档:底、卡、主字、次字。 */
private val LightBackground = Color(0xFFF5F3EF)
private val LightSurface = Color(0xFFFFFFFF)
private val LightTextPrimary = Color(0xFF1C1B19)
private val LightTextSecondary = Color(0xFF8A857D)
private val LightDivider = Color(0xFFE6E2DA)
private val LightAccent = Color(0xFF4F6E5A)
private val LightUnread = Color(0xFFC4644F)

/** 深色四档。 */
private val DarkBackground = Color(0xFF12110F)
private val DarkSurface = Color(0xFF24211D)
private val DarkTextPrimary = Color(0xFFEDE9E3)
private val DarkTextSecondary = Color(0xFF8F887D)
private val DarkDivider = Color(0xFF332F29)
private val DarkAccent = Color(0xFF7C9B85)

/** 浅色色板:宣纸暖白 + 黛绿。 */
fun lightMiniPhoneColors(): MiniPhoneColors = MiniPhoneColors(
    isDark = false,
    background = LightBackground,
    surface = LightSurface,
    textPrimary = LightTextPrimary,
    textSecondary = LightTextSecondary,
    divider = LightDivider,
    accent = LightAccent,
    onAccent = LightSurface,
    unreadDot = LightUnread,
    unreadDotText = LightSurface,
    bubbleSelf = LightAccent,
    bubbleSelfText = LightSurface,
    bubbleOther = LightSurface,
    bubbleOtherText = LightTextPrimary,
    bubbleOtherBorder = LightDivider,
    wallpaperTop = Color(0xFFF8F6F2),
    wallpaperBottom = Color(0xFFE4DED3),
)

/** 深色色板:同一套语义,亮度全部重算。 */
fun darkMiniPhoneColors(): MiniPhoneColors = MiniPhoneColors(
    isDark = true,
    background = DarkBackground,
    surface = DarkSurface,
    textPrimary = DarkTextPrimary,
    textSecondary = DarkTextSecondary,
    divider = DarkDivider,
    accent = DarkAccent,
    onAccent = DarkBackground,
    unreadDot = LightUnread,
    unreadDotText = LightSurface,
    bubbleSelf = DarkAccent,
    bubbleSelfText = DarkBackground,
    bubbleOther = DarkSurface,
    bubbleOtherText = DarkTextPrimary,
    bubbleOtherBorder = DarkDivider,
    wallpaperTop = Color(0xFF191713),
    wallpaperBottom = Color(0xFF100F0D),
)

/**
 * 字号阶梯:标题 / 正文 / 次要 / 时间戳。
 *
 * 这里只定义字号与字重,颜色一律由使用处从 [MiniPhoneColors] 取,
 * 避免同一个 TextStyle 在不同底色上带着错误的颜色。
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

val LocalMiniPhoneColors = staticCompositionLocalOf { lightMiniPhoneColors() }
val LocalMiniPhoneTypography = staticCompositionLocalOf { MiniPhoneTypography() }
val LocalMiniPhoneDimens = staticCompositionLocalOf { MiniPhoneDimens() }

/**
 * 小手机主题入口:按系统深色开关下发色板,同时下发字号与尺寸。
 *
 * 只提供本模块自有的三套 token,不桥接宿主 MaterialTheme —— 小手机的界面不读宿主的
 * colorScheme / typography,读不到也就不会在宿主换肤时被带跑。
 */
@Composable
fun MiniPhoneTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = remember(darkTheme) { if (darkTheme) darkMiniPhoneColors() else lightMiniPhoneColors() }
    CompositionLocalProvider(
        LocalMiniPhoneColors provides colors,
        LocalMiniPhoneTypography provides MiniPhoneTypography(),
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
