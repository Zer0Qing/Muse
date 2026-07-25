package io.zer0.muse.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings

/**
 * 统一加载状态组件(v1.0.20 新建)。
 *
 * 替代全项目 44 处散落的裸 [CircularProgressIndicator],统一参数:
 *  - size: [MuseIconSizes.iconLarge](32dp),平衡视觉存在感与不突兀
 *  - strokeWidth: 2.dp,纤细质感(对标 iOS UIActivityIndicator 细线风格)
 *  - 可选 message 文案,居中显示在指示器下方
 *
 * 用法:
 * ```
 * if (isLoading) {
 *     LoadingState(message = "正在加载会话...")
 * }
 * ```
 *
 * @param message 加载文案(可选,提供后显示在指示器下方)
 * @param modifier 修饰符
 */
@Composable
fun LoadingState(
    message: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(MusePaddings.screen * 2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(MuseIconSizes.iconLarge),
            strokeWidth = 2.dp,
        )
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
