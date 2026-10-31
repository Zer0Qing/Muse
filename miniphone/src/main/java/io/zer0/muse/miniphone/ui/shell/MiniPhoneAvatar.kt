package io.zer0.muse.miniphone.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme

/**
 * 头像。
 *
 * 本模块没有图片加载能力(不引图片库),所以头像统一画成「首字 + 强调色薄底」的圆章;
 * 角色头像 uri 暂时不参与渲染,等模块接上图片加载再换真图。
 */
@Composable
internal fun MiniPhoneAvatar(name: String, size: Dp, modifier: Modifier = Modifier) {
    val colors = MiniPhoneTheme.colors
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.accent.copy(alpha = AVATAR_FILL_ALPHA))
            .border(AvatarStrokeWidth, colors.accent.copy(alpha = AVATAR_STROKE_ALPHA), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initialOf(name),
            style = TextStyle(
                fontSize = (size.value * INITIAL_SCALE).sp,
                fontWeight = FontWeight.Medium,
                color = colors.accent,
            ),
        )
    }
}

/** 取首字:拉丁字母转大写,汉字原样,空名字给一个占位符。 */
internal fun initialOf(name: String): String {
    val first = name.trim().firstOrNull() ?: return MISSING_INITIAL
    return first.uppercaseChar().toString()
}

/** 头像内文字相对直径的比例:小头像里也不会糊成一团。 */
private const val INITIAL_SCALE: Float = 0.4f
private const val AVATAR_FILL_ALPHA: Float = 0.14f
private const val AVATAR_STROKE_ALPHA: Float = 0.22f
private val AvatarStrokeWidth: Dp = 1.dp

private const val MISSING_INITIAL: String = "?"
