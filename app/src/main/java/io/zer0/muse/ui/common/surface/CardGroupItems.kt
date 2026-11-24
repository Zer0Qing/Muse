@file:Suppress("FunctionNaming", "LongParameterList")

package io.zer0.muse.ui.common.surface

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseSlider
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseSpacing

/**
 * [CardGroupScope] 的标准行 DSL。
 *
 * 设置页的行形态只有几种：左标题右开关、导航行、居中动作行、居中破坏性行、滑块行。
 * 把它们固化成扩展函数，页面里就不再各自手写 Row / padding / 字号，
 * 行高、字号、分隔线全部由 [CardGroup] + [MuseListItem] 统一承担。
 */

/** 左标题 + 右开关。整行可点，点击等同切换开关。 */
fun CardGroupScope.switchItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    key: Any? = null,
    subtitle: String? = null,
    enabled: Boolean = true,
    leadingContent: (@Composable () -> Unit)? = null,
) {
    item(
        key = key,
        onClick = if (enabled) ({ onCheckedChange(!checked) }) else null,
        leadingContent = leadingContent,
        supportingContent = subtitle?.let { { Text(text = it, maxLines = SUBTITLE_MAX_LINES, overflow = TextOverflow.Ellipsis) } },
        trailingContent = {
            val enabledText = stringResource(R.string.common_state_enabled)
            val disabledText = stringResource(R.string.common_state_disabled)
            MuseSwitch(
                checked = checked,
                onCheckedChange = if (enabled) onCheckedChange else null,
                modifier = Modifier.semantics { stateDescription = if (checked) enabledText else disabledText },
            )
        },
        headlineContent = {
            Text(
                text = title,
                maxLines = TITLE_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

/** 导航行：标题（可带副标题与当前值）+ 右侧箭头。 */
fun CardGroupScope.navigationItem(
    title: String,
    onClick: () -> Unit,
    key: Any? = null,
    subtitle: String? = null,
    value: String? = null,
    enabled: Boolean = true,
    leadingContent: (@Composable () -> Unit)? = null,
) {
    item(
        key = key,
        onClick = if (enabled) onClick else null,
        leadingContent = leadingContent,
        supportingContent = subtitle?.let { { Text(text = it, maxLines = SUBTITLE_MAX_LINES, overflow = TextOverflow.Ellipsis) } },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (value != null) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = MuseSpacing.xs),
                    )
                }
                ChevronRight()
            }
        },
        headlineContent = {
            Text(text = title, maxLines = TITLE_MAX_LINES, overflow = TextOverflow.Ellipsis)
        },
    )
}

/** 居中主色动作行（测试连接、刷新、导入等）。[loading] 时在文字前显示转圈并忽略点击。 */
fun CardGroupScope.actionItem(
    title: String,
    onClick: () -> Unit,
    key: Any? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    item(
        key = key,
        onClick = if (enabled && !loading) onClick else null,
        headlineContent = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (loading) {
                    MuseSpinner(size = MuseIconSizes.iconSmall)
                    Spacer(Modifier.width(MuseSpacing.sm))
                }
                Text(
                    text = title,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = MuseAlpha.disabled)
                    },
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    )
}

/** 居中错误色破坏性行。应单独放在页面最底部的独立 [CardGroup] 里，并配确认弹窗。 */
fun CardGroupScope.destructiveItem(title: String, onClick: () -> Unit, key: Any? = null, enabled: Boolean = true) {
    item(
        key = key,
        onClick = if (enabled) onClick else null,
        headlineContent = {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.error.copy(alpha = if (enabled) 1f else MuseAlpha.disabled),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

/** 滑块行：首行左标题右数值，下方通栏滑块。 */
fun CardGroupScope.sliderItem(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    key: Any? = null,
    steps: Int = 0,
    subtitle: String? = null,
    onValueChangeFinished: () -> Unit = {},
) {
    item(
        key = key,
        headlineContent = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f),
                        maxLines = TITLE_MAX_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = valueText,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.padding(start = MuseSpacing.sm),
                    )
                }
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = SUBTITLE_MAX_LINES,
                        overflow = TextOverflow.Ellipsis,
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
                        .padding(top = MuseSpacing.sm),
                )
            }
        },
    )
}

private const val TITLE_MAX_LINES = 2
private const val SUBTITLE_MAX_LINES = 3
