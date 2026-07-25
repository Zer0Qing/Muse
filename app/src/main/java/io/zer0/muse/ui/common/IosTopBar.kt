package io.zer0.muse.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * iOS 风格通用顶部栏 — 替代 Material TopAppBar / LargeTopAppBar。
 *
 * 结构:statusBarsPadding → 返回按钮行 → Large Title(可选)。
 * 适用于所有子页面(设置子页/助手/知识库/记忆/翻译等)。
 *
 * @param title 标题文本
 * @param onBack 返回回调(为 null 时不显示返回按钮)
 * @param largeTitle 是否使用 Large Title 样式(32dp 粗体)
 * @param actions 右侧操作区(如按钮/图标)
 * @param modifier 修饰符
 */
@Composable
fun IosTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    largeTitle: Boolean = false,
    actions: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // v1.132: background 必须在 statusBarsPadding 之前,否则背景只画在 padding
            // 之后的内容区域,状态栏区域会透明(二级页状态栏透明确认由该顺序问题导致)
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        // 返回按钮 + 标题同行(普通模式)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            }
            if (!largeTitle) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            actions()
        }
        // Large Title 独占一行(v1.0.19: 字号改用 displayLarge 令牌,符合 iOS Large Title 34sp 标准)
        if (largeTitle) {
            Text(
                text = title,
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
    }
}
