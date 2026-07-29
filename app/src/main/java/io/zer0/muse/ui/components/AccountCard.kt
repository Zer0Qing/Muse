package io.zer0.muse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes

/**
 * Muse UI Kit — 用户信息卡片 [AccountCard]。
 *
 * 设计稿对齐:
 *  - 白色圆角卡片容器(20dp 圆角)
 *  - 左侧: 品牌绿圆形头像(首字母白色)
 *  - 中间: 用户名(粗体) + 会员信息(灰色小字)
 *  - 右侧: ChevronRight 箭头
 *
 * 用法:
 * ```
 * AccountCard(
 *     name = "Zer0",
 *     subtitle = "高级会员 · 到期 2026-12-31",
 *     onClick = { navController.navigate(MuseRoutes.ACCOUNT) },
 * )
 * ```
 *
 * @param name 用户名
 * @param subtitle 副标题(会员信息/邮箱等)
 * @param modifier 修饰符
 * @param onClick 点击回调(进入账户详情)
 * @param avatarLetter 头像首字母(默认取 name 首字符)
 */
@Composable
fun AccountCard(
    name: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    avatarLetter: String = name.take(1).uppercase(),
) {
    MuseSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MuseShapes.extraLarge,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.screen),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 品牌绿圆形头像
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = avatarLetter,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }

            Spacer(Modifier.width(12.dp))

            // 用户名 + 副标题
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 右箭头
            ChevronRight()
        }
    }
}
