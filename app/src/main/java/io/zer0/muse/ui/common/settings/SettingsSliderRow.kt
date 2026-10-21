package io.zer0.muse.ui.common.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.MuseSlider
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings

/**
 * 带滑块的设置项行 — 左侧图标 + 标题 + 副标题 + 右侧数值,下方全宽滑块。
 *
 * CMP-10: 全项目滑块设置行唯一实现。原 MemorySettingsPage / RagSettingsPage /
 * MediaSettingsPage 各自私有的 `SliderRow` 已删除并全部迁到此处,形态差异只由参数表达。
 *
 * 视觉:图标(20dp,[MuseIconSizes.iconMedium],可不传)与标题行对齐,滑块在下方通栏,
 * 数值贴行尾(primary + SemiBold,与其它设置行的强调数值一致)。
 *
 * 用法:
 * ```
 * SettingsSliderRow(
 *     icon = MuseIcons.sliders,
 *     title = stringResource(R.string.settings_rag_top_k),
 *     subtitle = stringResource(R.string.settings_rag_top_k_subtitle),
 *     value = topK.toFloat(),
 *     valueRange = 1f..10f,
 *     steps = 8,
 *     valueText = "$topK",
 *     onValueChange = { topK = it.roundToInt() },
 *     onValueChangeFinished = { save() },
 * )
 * ```
 *
 * @param icon 左侧图标(null 则不显示,用于无图标的滑块行)
 * @param title 主标题
 * @param value 当前值
 * @param valueRange 取值范围
 * @param steps 离散档位数(Material 语义:start/end 之间的中间停点个数)
 * @param valueText 右侧数值文案(格式由调用方决定,保持各设置项既有精度)
 * @param onValueChange 拖动中的值变更回调
 * @param modifier 修饰符
 * @param subtitle 副标题(null 则不显示)
 * @param iconContentDescription 图标无障碍描述(null 表示装饰性图标)
 * @param onValueChangeFinished 松手回调(需要把值写入持久层时使用,避免拖动过程频繁 IO)
 */
@Composable
fun SettingsSliderRow(
    icon: ImageVector? = null,
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueText: String,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    iconContentDescription: String? = null,
    onValueChangeFinished: () -> Unit = {},
    /**
     * P2-1（审计报告）: 允许点击数值区手动输入精确值。
     *
     * 旧实现只能拖滑块，而滑块受 steps 限制：分块大小步长 100（380 设不了）、
     * top-K 上限只有 10、token 预算上限 8000 等，用户需求落在格子之间时完全无解。
     *
     * 传入本参数即开启手动输入；不传则保持原行为（部分行位不适用）。
     */
    onValueInput: ((Float) -> Unit)? = null,
    /** 手动输入时使用的数值格式化/解析器（如整数、一位小数）。 */
    valueInputFormatter: ((Float) -> String)? = null,
    valueInputParser: ((String) -> Float?)? = null,
) {
    // P2-1: 手动输入对话框
    var showInput by remember { mutableStateOf(false) }
    if (showInput && onValueInput != null) {
        val fmt = valueInputFormatter ?: { v: Float -> v.toString() }
        val parse = valueInputParser ?: { s: String -> s.trim().toFloatOrNull() }
        var text by remember { mutableStateOf(fmt(value)) }
        MuseDialog(
            onDismissRequest = { showInput = false },
            title = title,
            content = {
                MuseTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    label = { Text(stringResource(R.string.settings_slider_input_hint)) },
                )
            },
            confirmText = stringResource(R.string.settings_slider_input_confirm),
            onConfirm = {
                val parsed = parse(text)
                if (parsed != null) {
                    // 夹到合法区间，避免手输越界
                    onValueInput(parsed.coerceIn(valueRange.start, valueRange.endInclusive))
                    onValueChangeFinished()
                }
                showInput = false
            },
            dismissText = stringResource(R.string.settings_common_cancel),
            onDismiss = { showInput = false },
        )
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = iconContentDescription,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    // LAYOUT-01: 限行兜底(见 SettingsSwitchRow 同处说明)
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier
                    .padding(start = MusePaddings.contentGap)
                    .then(
                        // P2-1: 有手动输入能力时，数值区可点击
                        if (onValueInput != null) {
                            Modifier.clickable { showInput = true }
                        } else {
                            Modifier
                        },
                    ),
            )
        }
        MuseSlider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            showValueLabel = false,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MusePaddings.contentGap),
        )
    }
}
