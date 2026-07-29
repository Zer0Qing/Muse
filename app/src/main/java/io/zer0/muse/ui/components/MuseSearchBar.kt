package io.zer0.muse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseCornerRadius

/**
 * Muse UI Kit — 圆角搜索栏 [MuseSearchBar]。
 *
 * 设计稿对齐:
 *  - surfaceVariant 圆角容器(12dp 圆角)
 *  - 左侧线性放大镜图标(灰色)
 *  - 占位文字(灰色)
 *  - 无涟漪,无底部边框
 *
 * 用法:
 * ```
 * MuseSearchBar(
 *     query = searchText,
 *     onQueryChange = { searchText = it },
 *     placeholder = "搜索文档...",
 * )
 * ```
 *
 * @param query 当前搜索文字
 * @param onQueryChange 文字变更回调
 * @param modifier 修饰符
 * @param placeholder 占位文字
 * @param enabled 是否可编辑
 */
@Composable
fun MuseSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索...",
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(MuseCornerRadius.BUTTON.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 线性放大镜图标
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = "搜索",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(18.dp),
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        ) {
            if (query.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
