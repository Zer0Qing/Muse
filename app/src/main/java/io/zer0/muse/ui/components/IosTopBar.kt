package io.zer0.muse.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MusePaddings

/**
 * Muse UI Kit — iOS 风格大标题导航栏 [IosTopBar]。
 *
 * 设计稿对齐:
 *  - 大标题 34sp Bold,左对齐(非居中)
 *  - 可选返回箭头(线性图标)
 *  - 右侧操作区(线性图标按钮)
 *  - 无底部阴影/分割线(与内容自然衔接)
 *
 * 用法:
 * ```
 * IosTopBar(
 *     title = "设置",
 *     onBack = { navController.popBackStack() },
 *     actions = {
 *         IconButton(onClick = { ... }) {
 *             Icon(Icons.Outlined.Search, contentDescription = "搜索")
 *         }
 *     },
 * )
 * ```
 *
 * @param title 大标题文字
 * @param modifier 修饰符
 * @param onBack 返回按钮回调(null 则不显示返回箭头)
 * @param actions 右侧操作按钮区
 * @param subtitle 可选副标题(大标题下方灰色小字)
 */
@Composable
fun IosTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    subtitle: String? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen),
    ) {
        // 返回按钮(线性图标)
        if (onBack != null) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        // 标题区(居中或左对齐取决于是否有返回按钮)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = if (onBack != null) 48.dp else 0.dp,
                    end = 48.dp,
                ),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 右侧操作区
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            horizontalArrangement = Arrangement.End,
            content = actions,
        )
    }
}

/**
 * Muse UI Kit — iOS 风格大标题(页面内嵌,非 TopBar)。
 *
 * 用于页面内容顶部的大标题展示(如首页"早上好"、记忆页"记忆")。
 * 比 IosTopBar 更灵活,可嵌入 ScrollableColumn 中随内容滚动。
 *
 * @param title 大标题文字(34sp Bold)
 * @param modifier 修饰符
 * @param subtitle 可选副标题(灰色小字)
 */
@Composable
fun IosLargeTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen)
            .padding(top = 8.dp, bottom = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 36.dp),
                    )
                }
            }
        }
    }
}
