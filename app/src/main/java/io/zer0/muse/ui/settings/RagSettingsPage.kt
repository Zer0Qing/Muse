package io.zer0.muse.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Splitscreen
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import io.zer0.muse.ui.common.IosSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.ai.core.ProviderConfig
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.ChevronRight
import io.zer0.muse.rag.RagConfig
import io.zer0.muse.ui.common.SectionLabel
import io.zer0.muse.ui.common.SettingsGroup
import io.zer0.muse.ui.common.SettingsGroupDivider
import io.zer0.muse.ui.common.SettingsItemRow
import io.zer0.muse.ui.common.SettingsSwitchRow
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/**
 * v1.56: RAG 知识库检索配置页。
 *
 * 配置项:
 *  - RAG 总开关(自动注入)
 *  - Embedding 来源(云端 API / 本地 ONNX)
 *  - 云端 Provider 选择(从已配置的 Provider 列表中选)
 *  - 云端 embedding 模型名
 *  - 检索 topK(1-10)
 *  - 相似度阈值(0.0-1.0)
 *  - 分块大小(100-2000 字符)
 *  - 分块重叠(0-200 字符)
 */
@Composable
fun RagSettingsPage(
    onBack: () -> Unit,
    onManageKbs: () -> Unit = {},
) {
    val settings: SettingsRepository = koinInject()
    val config by settings.ragConfigFlow.collectAsStateWithLifecycle(initialValue = RagConfig())
    val providers by settings.providersFlow.collectAsStateWithLifecycle(initialValue = emptyList<ProviderConfig>())
    val scope = rememberCoroutineScope()

    // M-RAG1: 临时状态,避免每次 Slider/TextField 变更即写 DataStore,仅在松手(debounce)时保存
    var cloudModelTemp by remember(config.cloudModel) { mutableStateOf(config.cloudModel) }
    var topKTemp by remember(config.topK) { mutableStateOf(config.topK) }
    var thresholdTemp by remember(config.threshold) { mutableStateOf(config.threshold) }
    var chunkSizeTemp by remember(config.chunkSize) { mutableStateOf(config.chunkSize) }
    var chunkOverlapTemp by remember(config.chunkOverlap) { mutableStateOf(config.chunkOverlap) }
    // v1.133: 高级检索临时状态
    var mmrLambdaTemp by remember(config.mmrLambda) { mutableStateOf(config.mmrLambda) }
    var tokenBudgetTemp by remember(config.tokenBudget) { mutableStateOf(config.tokenBudget) }
    // TextField 无 onValueChangeFinished,用 debounce 保存
    LaunchedEffect(cloudModelTemp) {
        delay(300)
        if (cloudModelTemp != config.cloudModel) {
            scope.launch { settings.saveRagConfig(config.copy(cloudModel = cloudModelTemp)) }
        }
    }

    SettingsSubPageScaffold(title = stringResource(R.string.settings_rag_page_title), onBack = onBack) {
        // ── 总开关 ──
        item { SectionLabel(stringResource(R.string.settings_rag_injection_section)) }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = Icons.Outlined.GraphicEq,
                    title = stringResource(R.string.settings_rag_auto_inject),
                    subtitle = stringResource(R.string.settings_rag_auto_inject_subtitle),
                    checked = config.enabled,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveRagConfig(config.copy(enabled = v)) }
                    },
                )
            }
        }

        // ── Embedding 来源 ──
        item { SectionLabel(stringResource(R.string.settings_rag_embedding_source_section)) }
        item {
            SettingsGroup {
                EmbeddingSourceOption(
                    icon = Icons.Outlined.Cloud,
                    title = stringResource(R.string.settings_rag_cloud_api),
                    subtitle = stringResource(R.string.settings_rag_cloud_api_subtitle),
                    selected = config.embeddingSource == RagConfig.EmbeddingSource.CLOUD,
                    onClick = {
                        scope.launch {
                            settings.saveRagConfig(config.copy(embeddingSource = RagConfig.EmbeddingSource.CLOUD))
                        }
                    },
                )
                SettingsGroupDivider()
                EmbeddingSourceOption(
                    icon = Icons.Outlined.CloudOff,
                    title = stringResource(R.string.settings_rag_local_keyword),
                    subtitle = stringResource(R.string.settings_rag_local_keyword_subtitle),
                    selected = config.embeddingSource == RagConfig.EmbeddingSource.LOCAL,
                    onClick = {
                        scope.launch {
                            settings.saveRagConfig(config.copy(embeddingSource = RagConfig.EmbeddingSource.LOCAL))
                        }
                    },
                )
            }
        }

        // ── 云端配置(仅 CLOUD 模式显示)──
        if (config.embeddingSource == RagConfig.EmbeddingSource.CLOUD) {
            item { SectionLabel(stringResource(R.string.settings_rag_cloud_provider_section)) }
            item {
                SettingsGroup {
                    if (providers.isEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(MusePaddings.cardInner),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Science,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                            )
                            Text(
                                text = stringResource(R.string.settings_rag_no_provider_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    } else {
                        val baseUrlNotSetText = stringResource(R.string.settings_rag_baseurl_not_set)
                        providers.forEach { provider ->
                            EmbeddingSourceOption(
                                icon = Icons.Outlined.Cloud,
                                title = provider.displayName.ifBlank { provider.id },
                                subtitle = provider.baseUrl.ifBlank { baseUrlNotSetText },
                                selected = config.cloudProviderId == provider.id,
                                onClick = {
                                    scope.launch {
                                        settings.saveRagConfig(
                                            if (config.cloudProviderId == provider.id) {
                                                config.copy(cloudProviderId = "")
                                            } else {
                                                config.copy(cloudProviderId = provider.id)
                                            },
                                        )
                                    }
                                },
                            )
                        }
                        if (config.cloudProviderId.isNotBlank()) {
                            SettingsGroupDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(MusePaddings.cardInnerSpaced),
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_rag_deselect_hint),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // 模型名输入
            item { SectionLabel(stringResource(R.string.settings_rag_embedding_model_section)) }
            item {
                SettingsGroup {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MusePaddings.cardInner),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_rag_model_name_hint),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            // M-RAG1: 绑定临时状态,由 LaunchedEffect debounce 后写 DataStore
                            value = cloudModelTemp,
                            onValueChange = { v ->
                                cloudModelTemp = v
                            },
                            placeholder = {
                                Text(
                                    when {
                                        providers.isEmpty() -> "text-embedding-3-small"
                                        config.cloudProviderId.isNotBlank() -> {
                                            val p = providers.find { it.id == config.cloudProviderId }
                                            if (p?.displayName?.contains("openai", true) == true) "text-embedding-3-small"
                                            else "embedding-2"
                                        }
                                        else -> "text-embedding-3-small"
                                    },
                                )
                            },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                        Text(
                            text = stringResource(R.string.settings_rag_common_models),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }

        // ── 检索参数 ──
        item { SectionLabel(stringResource(R.string.settings_rag_search_params_section)) }
        item {
            SettingsGroup {
                SliderRow(
                    icon = Icons.Outlined.Tune,
                    title = stringResource(R.string.settings_rag_top_k),
                    // M-RAG1: 绑定临时状态,仅在松手时写 DataStore
                    value = topKTemp.toFloat(),
                    valueRange = 1f..10f,
                    steps = 8,
                    valueText = "${topKTemp}",
                    onValueChange = { v ->
                        topKTemp = v.roundToInt()
                    },
                    onValueChangeFinished = {
                        if (topKTemp != config.topK) {
                            scope.launch { settings.saveRagConfig(config.copy(topK = topKTemp)) }
                        }
                    },
                )
                SettingsGroupDivider()
                SliderRow(
                    icon = Icons.Outlined.Tune,
                    title = stringResource(R.string.settings_rag_similarity_threshold),
                    subtitle = stringResource(R.string.settings_rag_similarity_threshold_subtitle),
                    // M-RAG1: 绑定临时状态,仅在松手时写 DataStore
                    value = thresholdTemp,
                    valueRange = 0f..1f,
                    steps = 19,
                    valueText = "%.2f".format(thresholdTemp),
                    onValueChange = { v ->
                        thresholdTemp = v
                    },
                    onValueChangeFinished = {
                        if (thresholdTemp != config.threshold) {
                            scope.launch { settings.saveRagConfig(config.copy(threshold = thresholdTemp)) }
                        }
                    },
                )
            }
        }

        // ── 分块参数 ──
        item { SectionLabel(stringResource(R.string.settings_rag_chunk_params_section)) }
        item {
            SettingsGroup {
                SliderRow(
                    icon = Icons.Outlined.Splitscreen,
                    title = stringResource(R.string.settings_rag_chunk_size),
                    subtitle = stringResource(R.string.settings_rag_chunk_size_subtitle),
                    // M-RAG1: 绑定临时状态,仅在松手时写 DataStore
                    value = chunkSizeTemp.toFloat(),
                    valueRange = 100f..2000f,
                    steps = 18,
                    valueText = "${chunkSizeTemp}",
                    onValueChange = { v ->
                        chunkSizeTemp = v.roundToInt()
                    },
                    onValueChangeFinished = {
                        if (chunkSizeTemp != config.chunkSize) {
                            scope.launch { settings.saveRagConfig(config.copy(chunkSize = chunkSizeTemp)) }
                        }
                    },
                )
                SettingsGroupDivider()
                SliderRow(
                    icon = Icons.Outlined.Splitscreen,
                    title = stringResource(R.string.settings_rag_chunk_overlap),
                    subtitle = stringResource(R.string.settings_rag_chunk_overlap_subtitle),
                    // M-RAG1: 绑定临时状态,仅在松手时写 DataStore
                    value = chunkOverlapTemp.toFloat(),
                    valueRange = 0f..200f,
                    steps = 19,
                    valueText = "${chunkOverlapTemp}",
                    onValueChange = { v ->
                        chunkOverlapTemp = v.roundToInt()
                    },
                    onValueChangeFinished = {
                        if (chunkOverlapTemp != config.chunkOverlap) {
                            scope.launch { settings.saveRagConfig(config.copy(chunkOverlap = chunkOverlapTemp)) }
                        }
                    },
                )
                SettingsGroupDivider()
                // v1.133: Markdown 感知分块
                SettingsSwitchRow(
                    icon = Icons.Outlined.Splitscreen,
                    title = stringResource(R.string.settings_rag_markdown_aware),
                    subtitle = stringResource(R.string.settings_rag_markdown_aware_subtitle),
                    checked = config.markdownAware,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveRagConfig(config.copy(markdownAware = v)) }
                    },
                )
                SettingsGroupDivider()
                // v1.133: 按 token 分块
                SettingsSwitchRow(
                    icon = Icons.Outlined.Splitscreen,
                    title = stringResource(R.string.settings_rag_chunk_by_token),
                    subtitle = stringResource(R.string.settings_rag_chunk_by_token_subtitle),
                    checked = config.chunkByToken,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveRagConfig(config.copy(chunkByToken = v)) }
                    },
                )
            }
        }

        // ── v1.133: 高级检索 ──
        item { SectionLabel(stringResource(R.string.settings_rag_advanced_section)) }
        item {
            SettingsGroup {
                // MMR 多样性
                SliderRow(
                    icon = Icons.Outlined.Tune,
                    title = stringResource(R.string.settings_rag_mmr_lambda),
                    subtitle = stringResource(R.string.settings_rag_mmr_lambda_subtitle),
                    value = mmrLambdaTemp,
                    valueRange = 0f..1f,
                    steps = 19,
                    valueText = "%.2f".format(mmrLambdaTemp),
                    onValueChange = { v ->
                        mmrLambdaTemp = v
                    },
                    onValueChangeFinished = {
                        if (mmrLambdaTemp != config.mmrLambda) {
                            scope.launch { settings.saveRagConfig(config.copy(mmrLambda = mmrLambdaTemp)) }
                        }
                    },
                )
                SettingsGroupDivider()
                // 混合检索
                SettingsSwitchRow(
                    icon = Icons.Outlined.GraphicEq,
                    title = stringResource(R.string.settings_rag_hybrid),
                    subtitle = stringResource(R.string.settings_rag_hybrid_subtitle),
                    checked = config.hybridEnabled,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveRagConfig(config.copy(hybridEnabled = v)) }
                    },
                )
                SettingsGroupDivider()
                // Rerank
                SettingsSwitchRow(
                    icon = Icons.Outlined.GraphicEq,
                    title = stringResource(R.string.settings_rag_rerank),
                    subtitle = stringResource(R.string.settings_rag_rerank_subtitle),
                    checked = config.rerankEnabled,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveRagConfig(config.copy(rerankEnabled = v)) }
                    },
                )
                SettingsGroupDivider()
                // Token 预算
                SliderRow(
                    icon = Icons.Outlined.Tune,
                    title = stringResource(R.string.settings_rag_token_budget),
                    subtitle = stringResource(R.string.settings_rag_token_budget_subtitle),
                    value = tokenBudgetTemp.toFloat(),
                    valueRange = 0f..8000f,
                    steps = 79,
                    valueText = if (tokenBudgetTemp == 0) "∞" else "$tokenBudgetTemp",
                    onValueChange = { v ->
                        tokenBudgetTemp = v.roundToInt()
                    },
                    onValueChangeFinished = {
                        if (tokenBudgetTemp != config.tokenBudget) {
                            scope.launch { settings.saveRagConfig(config.copy(tokenBudget = tokenBudgetTemp)) }
                        }
                    },
                )
            }
        }

        // ── 说明 ──
        // v1.133: 多知识库管理入口(放在说明 section 标题之前,作为独立 section)
        item { SectionLabel(stringResource(R.string.settings_rag_manage_kbs)) }
        item {
            SettingsGroup {
                SettingsItemRow(
                    icon = Icons.Outlined.Folder,
                    title = stringResource(R.string.settings_rag_manage_kbs),
                    subtitle = stringResource(R.string.settings_rag_manage_kbs_subtitle),
                    onClick = onManageKbs,
                ) {
                    ChevronRight()
                }
            }
        }

        item { SectionLabel(stringResource(R.string.settings_rag_notes_section)) }
        item {
            SettingsGroup {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_rag_note1),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.settings_rag_note2),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.settings_rag_note3),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmbeddingSourceOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    SettingsItemRow(
        icon = icon,
        title = title,
        subtitle = subtitle.takeIf { it.isNotBlank() },
        onClick = onClick,
        trailing = {
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
    )
}

@Composable
private fun SliderRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String = "",
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueText: String,
    onValueChange: (Float) -> Unit,
    // M-RAG1: 仅在松手时写 DataStore,避免拖动过程中频繁 IO
    onValueChangeFinished: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = valueText,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        IosSlider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            showValueLabel = false,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
    }
}
