package io.zer0.muse.ui.common.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.theme.MusePaddings

/**
 * 多选 chip 行 — 高级感关联资源选择器。
 *
 * 设计:
 *  - 已选行:MuseChip(selected=false)+ Close 图标(点击移除)
 *  - 候选行:MuseChip(已选变 primary,未选变 surfaceVariant)
 *  - 两行用 8dp 间距分隔
 *  - 候选为空时显示"暂无可选项"灰色提示
 *
 * 用法:
 * ```
 * MultiSelectChipRow(
 *     label = "QuickMessage",
 *     items = allQuickMessages,
 *     selectedIds = selectedQmIds,
 *     itemId = { it.id },
 *     itemLabel = { it.name },
 *     onToggle = { id -> viewModel.toggleQuickMessage(id) },
 * )
 * ```
 *
 * @param label 顶部小标题(灰色 labelMedium)
 * @param items 候选项列表
 * @param selectedIds 已选项 ID 集合
 * @param itemId 从项提取 ID 的函数
 * @param itemLabel 从项提取显示文本的函数
 * @param onToggle 切换某项选中状态的回调(参数为 itemId)
 * @param emptyHint 候选为空时的提示文案
 */
@Composable
fun <T> MultiSelectChipRow(
    label: String,
    items: List<T>,
    selectedIds: Set<String>,
    itemId: (T) -> String,
    itemLabel: (T) -> String,
    onToggle: (String) -> Unit,
    emptyHint: String = stringResource(R.string.common_no_options),
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        // 标签
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )

        if (items.isEmpty()) {
            Text(
                text = emptyHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
            )
            return@Column
        }

        // 已选行(只显示已选项,带移除按钮)
        val selected = items.filter { itemId(it) in selectedIds }
        if (selected.isNotEmpty()) {
            ChipFlowRow(
                items = selected,
                itemLabel = itemLabel,
                isSelected = { true },
                onClick = { onToggle(itemId(it)) },
                showRemoveIcon = true,
            )
        }

        // 候选行(显示所有项,已选高亮 + check 图标)
        Text(
            text = stringResource(R.string.common_selectable),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
        )
        ChipFlowRow(
            items = items,
            itemLabel = itemLabel,
            isSelected = { itemId(it) in selectedIds },
            onClick = { onToggle(itemId(it)) },
            showRemoveIcon = false,
        )
    }
}

/**
 * chip 横向自动换行布局(基于 FlowRow)。
 */
@Composable
private fun <T> ChipFlowRow(
    items: List<T>,
    itemLabel: (T) -> String,
    isSelected: (T) -> Boolean,
    onClick: (T) -> Unit,
    showRemoveIcon: Boolean,
) {
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.labelVerticalGap),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.labelVerticalGap),
    ) {
        items.forEach { item ->
            val label = itemLabel(item)
            val selected = isSelected(item)
            if (selected && showRemoveIcon) {
                MuseChip(
                    selected = false,
                    onClick = { onClick(item) },
                    label = label,
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.common_remove),
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
            } else {
                MuseChip(
                    selected = selected,
                    onClick = { onClick(item) },
                    label = label,
                    leadingIcon = if (selected) {
                        {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else null,
                )
            }
        }
    }
}
