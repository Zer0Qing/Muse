package io.zer0.muse.ui.common.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * 右箭头 — iOS 风格设置项右侧的 ">" 指示符。
 *
 * 用在 [SettingsItemRow] 的 trailing 位置,提示该行可点击进入下一级。
 */
@Composable
fun ChevronRight() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.outline,
    )
}
