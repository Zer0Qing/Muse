package io.zer0.muse.ui.common.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseSwitch

/**
 * 设置项开关行(iOS 风格设置开关)。
 *
 * 用法:`SwitchRow("启用功能", "描述文字", checked, onCheckedChange = { ... })`
 */
@Composable
fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    // stringResource 需在 @Composable 直接调用位置提取,不能在 semantics{} 内使用。
    val enabledText = stringResource(R.string.common_state_enabled)
    val disabledText = stringResource(R.string.common_state_disabled)
    val rowInteractionSource = remember { MutableInteractionSource() }
    val isRowPressed by rowInteractionSource.collectIsPressedAsState()
    val rowBgColor by animateColorAsState(
        targetValue = if (isRowPressed) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else Color.Transparent,
        label = "switchRowBg",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBgColor)
            // M-SC1: 整行可点击切换开关(对标 SettingsSwitchRow),旧实现仅 MuseSwitch 可切,
            // 点击文字区域无响应,不符合 iOS Settings 触发预期。
            .clickable(
                interactionSource = rowInteractionSource,
                indication = null,
            ) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        MuseSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics {
                stateDescription = if (checked) enabledText else disabledText
            },
        )
    }
}
