package io.zer0.muse.ui.common.form

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.ui.theme.pill
import java.util.Locale

/**
 * 24 小时制时间选择弹窗（Muse 设计系统统一件）。
 *
 * 替代原生 Material3 TimePicker 表盘：小屏弹窗内表盘易裁切且风格割裂；
 * 这里采用「时 / 分」双列步进器 + 刻钟快捷胶囊，全部复用设计令牌与触觉按钮。
 */
@Composable
fun MuseTimePickerDialog(
    title: String,
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (hour: Int, minute: Int) -> Unit,
) {
    var hour by remember(initialHour) { mutableIntStateOf(initialHour.coerceIn(0, MAX_HOUR)) }
    var minute by remember(initialMinute) { mutableIntStateOf(initialMinute.coerceIn(0, MAX_MINUTE)) }

    val hourLabel = stringResource(R.string.time_picker_hour)
    val minuteLabel = stringResource(R.string.time_picker_minute)
    val increaseLabel = stringResource(R.string.time_picker_increase)
    val decreaseLabel = stringResource(R.string.time_picker_decrease)

    MuseDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.md),
                ) {
                    TimeUnitStepper(
                        label = hourLabel,
                        value = hour,
                        increaseCd = "$increaseLabel $hourLabel",
                        decreaseCd = "$decreaseLabel $hourLabel",
                        onIncrement = { hour = (hour + 1) % HOURS_IN_DAY },
                        onDecrement = { hour = (hour + HOURS_IN_DAY - 1) % HOURS_IN_DAY },
                    )
                    Text(
                        text = ":",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = MuseSpacing.lg),
                    )
                    TimeUnitStepper(
                        label = minuteLabel,
                        value = minute,
                        increaseCd = "$increaseLabel $minuteLabel",
                        decreaseCd = "$decreaseLabel $minuteLabel",
                        onIncrement = { minute = (minute + 1) % MINUTES_IN_HOUR },
                        onDecrement = { minute = (minute + MINUTES_IN_HOUR - 1) % MINUTES_IN_HOUR },
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    QUARTER_MINUTES.forEach { preset ->
                        val selected = minute == preset
                        Surface(
                            onClick = { minute = preset },
                            shape = MuseShapes.pill,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ) {
                            Text(
                                text = String.format(Locale.US, ":%02d", preset),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                modifier = Modifier.padding(
                                    horizontal = MuseSpacing.md,
                                    vertical = MuseSpacing.xs,
                                ),
                            )
                        }
                    }
                }
            }
        },
        onConfirm = {
            onConfirm(hour, minute)
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

@Composable
private fun TimeUnitStepper(
    label: String,
    value: Int,
    increaseCd: String,
    decreaseCd: String,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        MuseTactileButton(
            icon = MuseIcons.chevronUp,
            onClick = onIncrement,
            contentDescription = increaseCd,
            container = MuseIconContainer.Tonal,
        )
        Surface(
            shape = MuseShapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                text = String.format(Locale.US, "%02d", value),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .widthIn(min = MuseSpacing.xxxl + MuseSpacing.xl)
                    .padding(horizontal = MuseSpacing.md, vertical = MuseSpacing.sm),
            )
        }
        MuseTactileButton(
            icon = MuseIcons.chevronDown,
            onClick = onDecrement,
            contentDescription = decreaseCd,
            container = MuseIconContainer.Tonal,
        )
    }
}

private const val HOURS_IN_DAY = 24
private const val MINUTES_IN_HOUR = 60
private const val MAX_HOUR = 23
private const val MAX_MINUTE = 59
private val QUARTER_MINUTES = listOf(0, 15, 30, 45)
