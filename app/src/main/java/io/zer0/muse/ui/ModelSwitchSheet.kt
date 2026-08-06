package io.zer0.muse.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import io.zer0.muse.ui.common.form.MuseTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.BuiltInTool
import io.zer0.ai.core.FreeModelConfig
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.muse.R
import io.zer0.muse.ui.common.surface.MuseCardPress
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge

/**
 * 阶段 5: 模型切换底部面板 — iOS 风格。
 *
 * 设计:
 *  - 上半:Provider 横向 chip 行(单选,切换激活 Provider)
 *  - 下半:当前激活 Provider 的模型列表,按 [groupFor] 分组折叠展示
 *  - 选中态用 primary 月桂绿 Check 图标点缀(符合"深夜台灯"<5% 品牌色铁律)
 *  - 几乎不用 elevation,靠 surfaceVariant 色块和 divider 分组
 *
 * v1.x: 模型列表改造为分组折叠结构(既有实现 ModelGrouping 实现),
 *  - 每组头部用 [MuseCardPress] 风格的卡片,点击切换展开/折叠
 *  - 折叠状态用 [rememberSaveable] + 自定义 Saver 持久化(默认折叠)
 *  - 顶部加搜索框过滤模型 id / name
 *  - 多组时显示"全部展开 / 全部折叠"批量操作按钮
 *
 * 调用方:[ChatScreen] TopAppBar 的"模型切换"按钮触发展开。
 *
 * @param providers 全部已配置 Provider
 * @param activeProviderId 当前激活 Provider id
 * @param selectedModelId 当前选中模型 id(null 表示回退到 Provider 首个模型)
 * @param onPickProvider 切换激活 Provider(内部会清空 selectedModelId)
 * @param onPickModel 选择模型(传 null 回退到 Provider 首个模型)
 * @param onRefreshModels 手动刷新当前 Provider 的模型列表(传 provider id)
 * @param isFetchingModels 是否正在拉取模型(显示加载态)
 * @param fetchModelsError 拉取模型错误信息
 * @param onDismiss 关闭面板
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelSwitchSheet(
    providers: List<ProviderConfig>,
    activeProviderId: String?,
    selectedModelId: String?,
    onPickProvider: (String) -> Unit,
    onPickModel: (String?) -> Unit,
    onRefreshModels: (String) -> Unit,
    isFetchingModels: Boolean,
    fetchModelsError: String?,
    onDismiss: () -> Unit,
) {
    val activeProvider = providers.firstOrNull { it.id == activeProviderId }
    // 当前生效模型:显式选中优先,否则回退到 Provider 首个模型
    val effectiveModelId = selectedModelId ?: activeProvider?.models?.firstOrNull()?.id

    // 搜索框状态(rememberSaveable 持久化,避免旋转屏清空)
    var searchQuery by rememberSaveable { mutableStateOf("") }

    // 各组折叠状态:组名 → 是否折叠(true=折叠,false=展开);未在 Map 中的组默认折叠(true)
    // 选用父级 SnapshotStateMap 而非子级 rememberSaveable(组名),是因为"全部展开/折叠"按钮
    // 需一次性修改所有组状态,父级管理更直接;通过自定义 Saver 持久化到 Bundle,配置变更后状态保留。
    val collapsedMap = rememberSaveable(saver = CollapsedMapSaver) {
        mutableStateMapOf<String, Boolean>()
    }
    val isGroupCollapsed: (String) -> Boolean = { name -> collapsedMap[name] ?: true }

    // MuseDialog 替代原 ModalBottomSheet,避免真机 scrim 卡死
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.model_switch_title),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (providers.isEmpty()) {
                    // 空状态:无任何 Provider 配置
                    Text(
                        text = stringResource(R.string.model_switch_no_provider),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(vertical = MusePaddings.largeGap),
                    )
                    return@Column
                }

                // v1.0.28: Provider 改为纵向列排列,避免长名称(如 OpenCode Go)在横向 Row 中
                // 空间不足导致文字竖排崩坏,也便于展示更多 Provider。
                Text(
                    text = stringResource(R.string.model_switch_provider_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(vertical = MusePaddings.tightGap),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MusePaddings.tightGap),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    providers.forEach { provider ->
                        val isSelected = provider.id == activeProviderId
                        Surface(
                            color = if (isSelected)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            shape = MuseShapes.large,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPickProvider(provider.id) },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(MusePaddings.bubbleInner),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                // L-MSw1: Provider 类型单字母标识(项目无专属图标资源,用首字母替代 Emoji)
                                Text(
                                    text = providerEmoji(provider.type),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = provider.displayName.ifBlank { provider.id },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected)
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                // v1.0.18: SiliconFlow 免费供应商(未填 key)追加「免费」徽章
                                if (FreeModelConfig.isFreeProvider(
                                        provider.baseUrl,
                                        provider.apiKey,
                                    )
                                ) {
                                    FreeBadge()
                                }
                            }
                        }
                    }
                }

                // v1.0.18 / R-SEC-07: 当前激活供应商为 SiliconFlow 免费供应商时,展示免登录提示;
                // fallback key 为占位符/空时改为可见的不可用提示。
                if (activeProvider != null &&
                    FreeModelConfig.isFreeProvider(
                        activeProvider.baseUrl,
                        activeProvider.apiKey,
                    )
                ) {
                    val freeHint = if (FreeModelConfig.isFallbackKeyAvailable()) {
                        stringResource(R.string.model_switch_free_provider_hint)
                    } else {
                        stringResource(R.string.free_model_service_unavailable)
                    }
                    Text(
                        text = freeHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (FreeModelConfig.isFallbackKeyAvailable()) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.padding(vertical = MusePaddings.tightGap),
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = MusePaddings.itemGap),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )

                // 下半:当前 Provider 的模型列表
                if (activeProvider == null) {
                    Text(
                        text = stringResource(R.string.model_switch_no_provider_selected),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(vertical = MusePaddings.screen),
                    )
                    return@Column
                }

                // 标题行:模型数量 + 刷新按钮
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                            .padding(vertical = MusePaddings.tightGap),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.model_switch_models_label, activeProvider.displayName),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Surface(
                        onClick = {
                            if (!isFetchingModels) onRefreshModels(activeProvider.id)
                        },
                        enabled = !isFetchingModels,
                        shape = MuseShapes.pill,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isFetchingModels) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Text(
                                text = if (isFetchingModels) stringResource(R.string.model_switch_fetching) else stringResource(R.string.model_switch_refresh),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isFetchingModels)
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                if (activeProvider.models.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = MusePaddings.largeGap),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = if (isFetchingModels) {
                                stringResource(R.string.model_switch_fetching_upstream)
                            } else {
                                fetchModelsError ?: stringResource(R.string.model_switch_no_models)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (fetchModelsError != null)
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.outline,
                        )
                        // v1.0.4 (P2): 拉取失败时就近显示"重试"按钮,避免用户还得去找顶部刷新按钮
                        if (fetchModelsError != null && !isFetchingModels) {
                            TextButton(
                                onClick = { onRefreshModels(activeProvider.id) },
                            ) {
                                Text(text = stringResource(R.string.model_switch_retry))
                            }
                        }
                    }
                    return@Column
                }

                // === 新增:搜索框(按 id / name 过滤模型) ===
                MuseTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.model_switch_search_placeholder),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.common_cancel),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .size(MuseIconSizes.iconMedium)
                                    .clickable { searchQuery = "" },
                            )
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MusePaddings.contentGap),
                )

                // === 计算展示的模型 ===
                // 搜索为空时,首个模型作为"默认"项单独显示(调用 onPickModel(null) 回退到 Provider 默认),
                // 其余模型按分组折叠展示;搜索时显示全部过滤后模型,不单独显示默认项。
                val firstModel = activeProvider.models.first()
                val showDefault = searchQuery.isBlank()
                val displayModels = if (showDefault) {
                    activeProvider.models.drop(1)
                } else {
                    activeProvider.models
                }

                val query = searchQuery.lowercase()
                val filteredModels = if (query.isBlank()) {
                    displayModels
                } else {
                    displayModels.filter {
                        it.id.lowercase().contains(query) ||
                            it.name.lowercase().contains(query)
                    }
                }

                val groups = groupModels(filteredModels.map { it.id })
                val modelById = filteredModels.associateBy { it.id }

                // === 新增:全部展开 / 全部折叠(仅多组时显示) ===
                if (groups.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = MusePaddings.tightGap),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = {
                                groups.forEach { (name, _) -> collapsedMap[name] = false }
                            },
                        ) {
                            Text(
                                text = stringResource(R.string.model_switch_expand_all),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.width(MusePaddings.contentGap))
                        TextButton(
                            onClick = {
                                groups.forEach { (name, _) -> collapsedMap[name] = true }
                            },
                        ) {
                            Text(
                                text = stringResource(R.string.model_switch_collapse_all),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                // === 默认项(回退到 Provider 首个模型) ===
                // 仅在搜索为空时显示,避免与分组列表中的首个模型重复。
                if (showDefault) {
                    ModelRow(
                        model = firstModel,
                        isSelected = effectiveModelId == null || effectiveModelId == firstModel.id,
                        onClick = { onPickModel(null); onDismiss() },
                        isDefaultFallback = true,
                    )
                }

                // === 分组折叠列表 ===
                // 注:MuseDialog 内容区为 Box + verticalScroll,内部不能用 LazyColumn
                // (会触发"Vertically scrollable component was measured with an infinity maximum
                //  height constraints"错误),故用 Column + forEach 等效实现,功能与 LazyColumn 等价。
                if (filteredModels.isEmpty()) {
                    Text(
                        text = stringResource(R.string.model_switch_no_models),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = MusePaddings.largeGap),
                    )
                } else {
                    groups.forEach { (groupName, ids) ->
                        GroupHeader(
                            groupName = groupName,
                            modelCount = ids.size,
                            isCollapsed = isGroupCollapsed(groupName),
                            onToggle = {
                                collapsedMap[groupName] = !isGroupCollapsed(groupName)
                            },
                        )
                        if (!isGroupCollapsed(groupName)) {
                            ids.forEach { modelId ->
                                val model = modelById[modelId] ?: return@forEach
                                ModelRow(
                                    model = model,
                                    isSelected = model.id == effectiveModelId,
                                    onClick = { onPickModel(model.id); onDismiss() },
                                )
                            }
                        }
                    }
                }
            }
        },
        onConfirm = null,
        dismissText = stringResource(R.string.common_close),
        onDismiss = onDismiss,
    )
}

/**
 * 分组头部 — iOS 风格可点击卡片,显示组名 + 模型数量 + 展开/折叠箭头。
 *
 * 用 [MuseCardPress] 替代裸 clickable,获得 200ms easeOutCubic 颜色渐变按压效果
 * 和触觉反馈,与项目 既有实现 风格一致。
 *
 * @param groupName 组名(如 "GPT" / "Claude")
 * @param modelCount 该组模型数量
 * @param isCollapsed 当前是否折叠(true=折叠,显示向下箭头;false=展开,显示向上箭头)
 * @param onToggle 点击切换折叠状态
 */
@Composable
private fun GroupHeader(
    groupName: String,
    modelCount: Int,
    isCollapsed: Boolean,
    onToggle: () -> Unit,
) {
    MuseCardPress(
        onClick = onToggle,
        shape = MuseShapes.medium,
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MusePaddings.tightGap),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.model_switch_group_count, groupName, modelCount),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Icon(
                imageVector = if (isCollapsed) Icons.Filled.KeyboardArrowDown
                else Icons.Filled.KeyboardArrowUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
        }
    }
}

/**
 * 单个模型行 — 左侧模型名 + 能力标签,右侧 Check(选中态)。
 */
@Composable
private fun ModelRow(
    model: Model?,
    isSelected: Boolean,
    onClick: () -> Unit,
    isDefaultFallback: Boolean = false,
) {
    if (model == null) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isDefaultFallback) stringResource(R.string.model_switch_default, model.name) else model.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected)
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // v1.0.18: SiliconFlow 免费白名单模型追加「免费」徽章(对齐 FreeModelConfig.FREE_MODEL_IDS)
                if (FreeModelConfig.FREE_MODEL_IDS.any { it.equals(model.id, ignoreCase = true) }) {
                    Spacer(Modifier.width(6.dp))
                    FreeBadge()
                }
            }
            // 能力标签行:视觉 / 推理 / 工具调用 / 绘图 / 联网
            // stringResource 需在 @Composable 直接调用位置提取,buildList 非 @Composable,故预提取到局部变量。
            val visionLabel = stringResource(R.string.model_switch_ability_vision)
            val reasoningLabel = stringResource(R.string.model_switch_ability_reasoning)
            val toolLabel = stringResource(R.string.model_switch_ability_tool)
            val imageLabel = stringResource(R.string.model_switch_ability_image)
            val searchLabel = stringResource(R.string.model_switch_ability_search)
            val abilities = buildList {
                if (model.supportsVisionInput()) add(visionLabel to Icons.Outlined.Visibility)
                if (model.supportsReasoning()) add(reasoningLabel to Icons.Outlined.Psychology)
                if (model.supportsToolCalling()) add(toolLabel to Icons.Outlined.Build)
                // v0.47: 补充绘图能力(模型支持图片输出)
                if (model.supportsImageOutput()) add(imageLabel to Icons.Outlined.Image)
                // v0.47: 补充联网搜索能力(model.tools 含 SEARCH)
                if (model.tools.contains(BuiltInTool.SEARCH)) add(searchLabel to Icons.Outlined.Language)
            }
            if (abilities.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    abilities.forEach { (label, icon) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = icon,
                                contentDescription = label,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }
            // v0.47: 显示上下文窗口大小(如 "128K context")
            val ctxWindow = model.contextWindow
            if (ctxWindow != null && ctxWindow > 0) {
                val contextText = if (ctxWindow >= 1000) {
                    "${ctxWindow / 1000}K"
                } else {
                    ctxWindow.toString()
                }
                Text(
                    text = "$contextText context",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        // 选中态:primary 月桂绿 Check
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = stringResource(R.string.model_switch_selected_cd),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * 按 ProviderType 返回 Emoji 标识(项目无专属图标资源,采用与 AssistantSection 一致的 Emoji 方案)。
 */
private fun providerEmoji(type: ProviderType): String = when (type) {
    ProviderType.OPENAI -> "O"
    ProviderType.ANTHROPIC -> "A"
    ProviderType.GEMINI -> "G"
    ProviderType.OPENAI_RESPONSES -> "R"
}

/**
 * v1.0.18: 「免费」徽章 — 用于标记 SiliconFlow 免费白名单模型/供应商。
 *
 * 设计:小号 pill 形状胶囊,primary 透明背景 + primary 文字,
 * 与能力标签(视觉/推理/工具)视觉层级一致但颜色更突出(品牌色)。
 */
@Composable
private fun FreeBadge() {
    Surface(
        shape = MuseShapes.pill,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
    ) {
        Text(
            text = stringResource(R.string.model_switch_free_badge),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/**
 * 折叠状态 Map 的 [listSaver] — 将 [SnapshotStateMap] 序列化为扁平 List,
 * 以满足 Bundle 可序列化要求(直接保存 Map<String, Boolean> 不被 Bundle 支持)。
 *
 * 序列化格式:[key1, value1, key2, value2, ...](基础类型 String + Boolean 均可被 Bundle 序列化)。
 */
private val CollapsedMapSaver = listSaver<SnapshotStateMap<String, Boolean>, Any>(
    save = { stateMap ->
        // 扁平化为 [k1, v1, k2, v2, ...] 列表,元素均为 Bundle 可序列化基础类型。
        buildList {
            stateMap.forEach { (k, v) ->
                add(k)
                add(v)
            }
        }
    },
    restore = { list ->
        mutableStateMapOf<String, Boolean>().apply {
            var i = 0
            while (i + 1 < list.size) {
                @Suppress("UNCHECKED_CAST")
                put(list[i] as String, list[i + 1] as Boolean)
                i += 2
            }
        }
    },
)
