package io.zer0.muse.ui.common.form

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.semiLarge

/**
 * iOS 风格填充式输入框 — 替代 Material3 默认 [OutlinedTextField]。
 *
 * 视觉差异:
 *  - 透明边框(无 outlined 框线),用 surfaceVariant 填充背景区分输入区域
 *  - 聚焦时背景色加深(surfaceContainerHigh),无边框动画
 *  - 圆角 16dp([MuseShapes.semiLarge]),与 iOS 设置页输入框一致
 *  - label 在聚焦时变为 onSurfaceVariant(灰色),不用品牌色
 *
 * 设计说明:GPT / MANUS / iOS 设置页的填充式输入框风格。
 * 与 [MuseDropdown]、[MuseChip] 等 Ios* 套件配套使用。
 *
 * 用法:
 * ```
 * MuseTextField(
 *     value = text,
 *     onValueChange = { text = it },
 *     label = { Text("名称") },
 *     modifier = Modifier.fillMaxWidth(),
 * )
 * ```
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MuseTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    /**
     * v1.0.72: 自定义容器背景色(默认 null = 使用主题默认填充色)。
     * 输入栏场景传 Color.Transparent,避免输入框实色块叠在岛背景上形成"白块"。
     */
    containerColor: androidx.compose.ui.graphics.Color? = null,
) {
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scheme = MaterialTheme.colorScheme

    Column(modifier = modifier) {
        // v1.0.29: label 统一显示在输入框上方,避免与输入框内文字/placeholder 重叠,
        // 提升可读性。这是全应用输入框的通用规范。
        if (label != null) {
            ProvideTextStyle(
                value = MaterialTheme.typography.labelMedium.copy(
                    color = scheme.onSurfaceVariant,
                ),
            ) {
                label()
            }
            Spacer(Modifier.height(MusePaddings.tinyGap))
        }

        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            // 审计修复 (2.x 回归修正): modifier 回挂外层 Column — 调用方传入的
            // RowScope.weight(1f) 必须作用于 Row 直接子级,挂到输入框本体后 weight
            // 失效,输入框失去宽度约束糊满整个页面(用户实测回归)。
            // focusRequester 挂 Column 不崩(自动聚焦不弹键盘,用户手动点即可)。
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            readOnly = readOnly,
            label = null,
            placeholder = placeholder,
            supportingText = null,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            isError = isError,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            interactionSource = interactionSource,
            shape = MuseShapes.semiLarge,
            colors = OutlinedTextFieldDefaults.colors(
                // 填充背景:聚焦时用 surfaceContainerHigh(略深),未聚焦用 surfaceVariant
                // v1.0.72: containerColor 传非 null 时全部用自定义色(输入栏场景透明)
                focusedContainerColor = containerColor ?: scheme.surfaceContainerHigh,
                unfocusedContainerColor = containerColor ?: scheme.surfaceVariant,
                disabledContainerColor = containerColor ?: scheme.surfaceVariant,
                errorContainerColor = containerColor ?: scheme.surfaceVariant,
                // 透明边框:不用 Material 默认的 outlined 框线
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                disabledBorderColor = Color.Transparent,
                errorBorderColor = if (isError) scheme.error else Color.Transparent,
                // 文字颜色
                focusedTextColor = scheme.onSurface,
                unfocusedTextColor = scheme.onSurface,
                disabledTextColor = scheme.onSurfaceVariant,
                // placeholder 颜色
                focusedPlaceholderColor = scheme.onSurfaceVariant,
                unfocusedPlaceholderColor = scheme.onSurfaceVariant,
                // supportingText 颜色
                focusedSupportingTextColor = scheme.onSurfaceVariant,
                unfocusedSupportingTextColor = scheme.onSurfaceVariant,
                // 前缀/后缀图标颜色
                focusedLeadingIconColor = scheme.onSurfaceVariant,
                unfocusedLeadingIconColor = scheme.onSurfaceVariant,
                focusedTrailingIconColor = scheme.onSurfaceVariant,
                unfocusedTrailingIconColor = scheme.onSurfaceVariant,
                // 光标颜色
                cursorColor = scheme.onSurface,
                errorCursorColor = scheme.error,
            ),
        )

        // v1.0.29: supportingText 也统一显示在输入框下方,与 label 对称。
        if (supportingText != null) {
            Spacer(Modifier.height(MusePaddings.tinyGap))
            ProvideTextStyle(
                value = MaterialTheme.typography.bodySmall.copy(
                    color = scheme.onSurfaceVariant,
                ),
            ) {
                supportingText()
            }
        }
    }
}
