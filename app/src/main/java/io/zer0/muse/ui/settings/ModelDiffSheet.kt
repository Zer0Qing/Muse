package io.zer0.muse.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Remove
import io.zer0.muse.ui.common.IosChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.Model
import io.zer0.muse.R
import io.zer0.muse.ui.common.MuseBottomSheet
import io.zer0.muse.ui.common.SectionLabel
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill

/**
 * P2-2: 上游模型差异 Sheet。
 *
 * 在 ProviderEditPage 拉取上游模型后,对比本地 models 列表与上游返回的列表,
 * 分三类透明展示差异:
 *  - 上游新增(本地没有):可勾选添加
 *  - 已删除(本地有但上游没有):标记为「已删除(保留)」,可勾选移除
 *  - 保持一致:仅展示数量标题,不列出具体项(任务要求"一致:不显示")
 *
 * 顶部提供「全部添加新增」「全部移除已删除」批量按钮(用 Surface + clickable,
 * 非 Material3 Button);底部「应用所选变更」一次性提交勾选结果。
 *
 * 视觉风格:warm-paper + iOS 风格,统一使用 [MuseShapes] / [MusePaddings] /
 * [MuseIconSizes] 等设计令牌;不使用 Material3 默认 Button / AlertDialog。
 *
 * 模型比较按 [Model.id] 字段(忽略大小写)。
 *
 * @param localModels 当前 Provider 本地保存的模型列表
 * @param upstreamModels 上游拉取返回的模型列表
 * @param onAdd 用户勾选要添加的新增模型,点击「应用」时回调
 * @param onRemove 用户勾选要移除的已删除模型,点击「应用」时回调
 * @param onDismiss 关闭 Sheet 回调(点击遮罩 / 返回键 / 应用完成后均会触发)
 */
@Composable
fun ModelDiffSheet(
    localModels: List<Model>,
    upstreamModels: List<Model>,
    onAdd: (List<Model>) -> Unit,
    onRemove: (List<Model>) -> Unit,
    onDismiss: () -> Unit,
) {
    // 按 id(忽略大小写)做集合差异
    val newModels = remember(localModels, upstreamModels) {
        val localLowerIds = localModels.map { it.id.lowercase() }.toHashSet()
        // 上游若有多个 id 仅大小写不同的项,保留首次出现者(去重避免视觉重复)
        val seen = HashSet<String>()
        upstreamModels.filter {
            val key = it.id.lowercase()
            key !in localLowerIds && seen.add(key)
        }
    }
    val removedModels = remember(localModels, upstreamModels) {
        val upstreamLowerIds = upstreamModels.map { it.id.lowercase() }.toHashSet()
        val seen = HashSet<String>()
        localModels.filter {
            val key = it.id.lowercase()
            key !in upstreamLowerIds && seen.add(key)
        }
    }
    val unchangedCount = remember(localModels, upstreamModels) {
        val localLowerIds = localModels.map { it.id.lowercase() }.toHashSet()
        val upstreamLowerIds = upstreamModels.map { it.id.lowercase() }.toHashSet()
        localLowerIds.intersect(upstreamLowerIds).size
    }

    // 勾选状态:addSelected 标记"待添加的新增模型",removeSelected 标记"待移除的已删除模型"
    val addSelected = remember { mutableStateMapOf<String, Boolean>() }
    val removeSelected = remember { mutableStateMapOf<String, Boolean>() }

    // 初始勾选:默认所有新增项与所有已删除项均预选(参考 iOS 风格"默认接受全部变更")
    // 用 LaunchedEffect 一次性初始化,避免每次重组覆盖用户的取消操作
    LaunchedEffect(newModels, removedModels) {
        if (addSelected.isEmpty()) newModels.forEach { addSelected[it.id] = true }
        if (removeSelected.isEmpty()) removedModels.forEach { removeSelected[it.id] = true }
    }

    val applyEnabled by remember {
        derivedStateOf {
            addSelected.values.any { it } || removeSelected.values.any { it }
        }
    }

    MuseBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // v1.0.16: 兜底处理 Dialog 内 navigationBarsPadding 偶发失效,
                // 确保底部按钮不被系统导航栏/手势条遮挡
                .navigationBarsPadding(),
        ) {
            // 标题区
            Text(
                text = stringResource(R.string.model_diff_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(MusePaddings.contentGap))

            // 顶部批量操作行:「全部添加新增」+「全部移除已删除」
            // 任务约束:用 Surface + clickable,不用 Material3 Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                DiffActionButton(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.model_diff_add_all),
                    icon = Icons.Default.Add,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    enabled = newModels.isNotEmpty(),
                    onClick = { newModels.forEach { addSelected[it.id] = true } },
                )
                DiffActionButton(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.model_diff_remove_all),
                    icon = Icons.Default.Remove,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    enabled = removedModels.isNotEmpty(),
                    onClick = { removedModels.forEach { removeSelected[it.id] = true } },
                )
            }
            Spacer(Modifier.size(MusePaddings.contentGap))

            // 主体列表区(LazyColumn 限高,避免内容过多撑爆 Sheet)
            // v1.0.16: 高度从 420dp 降到 340dp,给底部按钮 + 导航栏留出足够空间
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 340.dp),
                contentPadding = PaddingValues(vertical = MusePaddings.contentGap),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                // 分组 1:上游新增
                item {
                    DiffSectionHeader(
                        label = stringResource(R.string.model_diff_new, newModels.size),
                        chipText = stringResource(R.string.model_diff_new, newModels.size),
                        chipIcon = Icons.Default.Add,
                        chipColor = MaterialTheme.colorScheme.primaryContainer,
                        chipTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                if (newModels.isEmpty()) {
                    item { DiffEmptyHint(stringResource(R.string.model_diff_empty_new)) }
                } else {
                    items(newModels, key = { "new-${it.id}" }) { model ->
                        DiffModelRow(
                            model = model,
                            selected = addSelected[model.id] == true,
                            onToggle = {
                                addSelected[model.id] = !(addSelected[model.id] == true)
                            },
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            thickness = 0.5.dp,
                        )
                    }
                }

                // 分组 2:已删除(本地保留,上游没有)
                item {
                    DiffSectionHeader(
                        label = stringResource(R.string.model_diff_removed, removedModels.size),
                        chipText = stringResource(R.string.model_diff_removed, removedModels.size),
                        chipIcon = Icons.Default.DeleteOutline,
                        chipColor = MaterialTheme.colorScheme.errorContainer,
                        chipTextColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                if (removedModels.isEmpty()) {
                    item { DiffEmptyHint(stringResource(R.string.model_diff_empty_removed)) }
                } else {
                    items(removedModels, key = { "rem-${it.id}" }) { model ->
                        DiffModelRow(
                            model = model,
                            selected = removeSelected[model.id] == true,
                            badgeText = stringResource(R.string.model_diff_kept_badge),
                            onToggle = {
                                removeSelected[model.id] = !(removeSelected[model.id] == true)
                            },
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            thickness = 0.5.dp,
                        )
                    }
                }

                // 分组 3:保持一致(仅展示数量标题,不列出具体项 — 任务要求"一致:不显示")
                item {
                    DiffSectionHeader(
                        label = stringResource(R.string.model_diff_unchanged, unchangedCount),
                        chipText = stringResource(R.string.model_diff_unchanged, unchangedCount),
                        chipIcon = Icons.Default.Check,
                        chipColor = MaterialTheme.colorScheme.surfaceVariant,
                        chipTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.size(MusePaddings.contentGap))

            // 底部「应用所选变更」按钮(用 Surface + clickable,非 Material3 Button)
            DiffActionButton(
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.model_diff_apply),
                icon = Icons.Default.Check,
                containerColor = if (applyEnabled) MaterialTheme.colorScheme.inverseSurface
                else MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.12f),
                contentColor = if (applyEnabled) MaterialTheme.colorScheme.inverseOnSurface
                else MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.5f),
                enabled = applyEnabled,
                onClick = {
                    val toAdd = newModels.filter { addSelected[it.id] == true }
                    val toRemove = removedModels.filter { removeSelected[it.id] == true }
                    if (toAdd.isNotEmpty()) onAdd(toAdd)
                    if (toRemove.isNotEmpty()) onRemove(toRemove)
                    onDismiss()
                },
            )
            // v1.0.16: 底部额外 spacer 等于系统导航栏高度,防止 Dialog 内 insets 失效
            // 导致按钮被三键导航栏/手势小白条遮挡
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

/**
 * 差异 Sheet 顶部 / 底部的胶囊按钮(用 Surface + clickable,非 Material3 Button)。
 *
 * 设计参考 [ProviderEditBottomBar] 的胶囊风格:warm-paper inverseSurface 主操作色,
 * 48dp 触摸目标,半透明灰显不可用态。
 */
@Composable
private fun DiffActionButton(
    label: String,
    containerColor: Color,
    contentColor: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        shape = MuseShapes.pill,
        color = if (enabled) containerColor else containerColor.copy(alpha = 0.4f),
        modifier = modifier
            .height(MuseIconSizes.touchTarget)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MusePaddings.screen),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
            )
            Spacer(Modifier.size(MusePaddings.iconPadding))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = contentColor,
            )
        }
    }
}

/**
 * 分组标题:左侧 [SectionLabel] 显示分组名(含数量),右侧 [FilterChip] 作分类徽标。
 *
 * 任务要求"每组用 SectionLabel + FilterChip":SectionLabel 作为视觉标题,
 * FilterChip 作为分类徽标(不可点击,selected=true 固定),配合图标强化分类语义。
 */
@Composable
private fun DiffSectionHeader(
    label: String,
    chipText: String,
    chipIcon: ImageVector,
    chipColor: Color,
    chipTextColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        SectionLabel(label)
        IosChip(
            selected = true,
            onClick = { /* 只读徽标,不响应点击 */ },
            label = chipText,
            leadingIcon = {
                Icon(
                    imageVector = chipIcon,
                    contentDescription = null,
                    modifier = Modifier.size(MuseIconSizes.iconTiny),
                )
            },
        )
    }
}

/**
 * 空分组提示文字。
 */
@Composable
private fun DiffEmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(
            horizontal = MusePaddings.screen,
            vertical = MusePaddings.contentGap,
        ),
    )
}

/**
 * 单条模型差异行。
 *
 * 视觉:warm-paper + iOS 风格 — 左侧勾选圆圈(选中态填充主色 + 对勾),
 * 中间模型 ID + 名称(副标题仅当与 id 不同时显示),右侧可选徽标(已删除项显示「已删除(保留)」)。
 *
 * @param selected 当前是否勾选(用于"待添加"或"待移除"标记)
 * @param badgeText 右侧徽标文字(可选,仅已删除项传入)
 * @param onToggle 点击行或勾选圈时触发
 */
@Composable
private fun DiffModelRow(
    model: Model,
    selected: Boolean,
    onToggle: () -> Unit,
    badgeText: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(
                horizontal = MusePaddings.screen,
                vertical = MusePaddings.contentGap,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        // 左侧勾选圈(iOS 风格 22dp 对勾圆圈)
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(MuseIconSizes.iconTiny),
                )
            }
        }

        // 中间:模型 ID + 名称
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.id,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (model.name != model.id) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        // 右侧徽标(已删除项显示「已删除(保留)」)
        if (badgeText != null) {
            Surface(
                shape = MuseShapes.small,
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
            ) {
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(
                        horizontal = MusePaddings.contentGap,
                        vertical = 4.dp,
                    ),
                )
            }
        }
    }
}
