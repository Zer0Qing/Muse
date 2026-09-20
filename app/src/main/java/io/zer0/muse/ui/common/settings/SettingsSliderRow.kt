package io.zer0.muse.ui.common.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import io.zer0.muse.ui.common.form.MuseSlider
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
 *     icon = TablerIcons.Adjustments,
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
) {
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
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = MusePaddings.contentGap),
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
