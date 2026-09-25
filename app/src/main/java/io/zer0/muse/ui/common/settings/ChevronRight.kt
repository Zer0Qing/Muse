package io.zer0.muse.ui.common.settings

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import io.zer0.muse.ui.common.icons.MuseIcons

/**
 * 右箭头 — iOS 风格设置项右侧的 ">" 指示符。
 *
 * 用在 [SettingsItemRow] 的 trailing 位置,提示该行可点击进入下一级。
 */
@Composable
fun ChevronRight() {
    Icon(
        imageVector = MuseIcons.chevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.outline,
    )
}
