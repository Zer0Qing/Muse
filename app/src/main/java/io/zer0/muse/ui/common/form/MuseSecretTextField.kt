@file:Suppress("FunctionNaming", "LongParameterList")

package io.zer0.muse.ui.common.form

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import io.zer0.muse.R
import io.zer0.muse.ui.common.icons.MuseIcons

/**
 * 密钥 / Token 输入框：默认掩码，右侧眼睛按钮切换明文。
 *
 * 显隐状态默认由组件自己持有（不进 saved state 之外的持久层）；
 * 需要外部控制时传入 [visible] 与 [onVisibleChange]。
 */
@Composable
fun MuseSecretTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
    visible: Boolean? = null,
    onVisibleChange: ((Boolean) -> Unit)? = null,
) {
    var innerVisible by rememberSaveable { mutableStateOf(false) }
    val shown = visible ?: innerVisible
    val toggle: (Boolean) -> Unit = { next ->
        if (onVisibleChange != null) onVisibleChange(next) else innerVisible = next
    }
    MuseTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it, maxLines = 1) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            MuseTactileButton(
                icon = if (shown) MuseIcons.eyeOff else MuseIcons.eye,
                onClick = { toggle(!shown) },
                contentDescription = stringResource(
                    if (shown) R.string.settings_common_hide else R.string.settings_common_show,
                ),
                compact = true,
            )
        },
    )
}
