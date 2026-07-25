package io.zer0.muse.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes

/**
 * v1.51: iOS 风格下拉选择器 — 替代 Material3 [androidx.compose.material3.ExposedDropdownMenuBox]。
 *
 * 视觉差异:
 *  - 点击 OutlinedTextField 弹出 MuseDialog(iOS 风格圆角列表)而非 Material 方块菜单
 *  - 选项列表用 Surface + MuseShapes.large 圆角
 *  - 选中项用 primary 色高亮
 *
 * 用法:
 * ```
 * IosDropdown(
 *     value = mode,
 *     onValueChange = { mode = it },
 *     label = "模式",
 *     options = PromptInjectionRepository.PRESET_MODES.entries.map { (id, label) -> id to "$label ($id)" },
 * )
 * ```
 *
 * @param value 当前选中值(对应 options 中的 first)
 * @param onValueChange 选中回调(返回 options 中的 first)
 * @param label 输入框标签
 * @param options 选项列表,List<Pair<value, displayText>>
 * @param modifier Modifier
 */
@Composable
fun IosDropdown(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    // L-DD3: options 用 Pair<value, displayText> 承载,缺乏命名,可读性差。
    // 未来重构建议改为 data class IosDropdownOption(val value: String, val displayText: String),
    // 当前保持 Pair 以兼容现有调用方(PromptInjectionRepository.PRESET_MODES 等)。
    options: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    // M-DD1: expanded 用 rememberSaveable 持久化,配置变更(旋转/暗色切换)时不丢失展开状态。
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selectedDisplay = options.firstOrNull { it.first == value }?.second ?: value
    // stringResource 需在 @Composable 直接调用位置提取,不能在 semantics{} 内使用。
    val dropdownCd = stringResource(R.string.common_dropdown_cd, label, selectedDisplay)

    // L-DD5: 用 TextField 自己的 InteractionSource 监听按压,避免父 Box clickable 被子
    // OutlinedTextField 的焦点/输入处理拦截,导致点击下拉框无反应。
    val interactionSource = remember { MutableInteractionSource() }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Press) {
                expanded = true
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .semantics {
                role = Role.Button
                contentDescription = dropdownCd
            },
    ) {
        OutlinedTextField(
            value = selectedDisplay,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            modifier = Modifier.fillMaxWidth(),
            shape = MuseShapes.medium,
            singleLine = true,
            interactionSource = interactionSource,
        )

        if (expanded) {
            MuseDialog(
                onDismissRequest = { expanded = false },
                title = label,
                content = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            // L-DD4: 360.dp → DropdownListMaxHeight 常量。
                            .heightIn(max = DropdownListMaxHeight),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        options.forEach { (optionValue, optionLabel) ->
                            val isSelected = optionValue == value
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onValueChange(optionValue)
                                        expanded = false
                                    }
                                    // L-DD4: 裸 12/8dp → MusePaddings 令牌。
                                    .padding(
                                        vertical = MusePaddings.inputPadding,
                                        horizontal = MusePaddings.iconPadding,
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = optionLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                if (isSelected) {
                                    Spacer(Modifier.width(MusePaddings.iconPadding))
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        // L-DD4: 20.dp → MuseIconSizes.iconMedium 令牌。
                                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                                    )
                                }
                            }
                        }
                    }
                },
                confirmText = stringResource(R.string.common_close),
                onConfirm = { expanded = false },
                onDismiss = { expanded = false },
            )
        }
    }
}

/** L-DD4: 下拉选项列表最大高度,避免选项过多撑满屏幕。 */
private val DropdownListMaxHeight = 360.dp
