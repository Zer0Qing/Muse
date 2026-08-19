package io.zer0.muse.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import compose.icons.TablerIcons
import compose.icons.tablericons.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.ai.core.Model
import io.zer0.muse.R
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.muse.data.ImageGenConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseSelectionSheet
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.semiLarge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * v0.36: 图片生成默认参数设置。
 *
 * 用户可在此设置默认绘图供应商、模型、尺寸、质量、风格和生成数量。
 * 模型选择改为从已配置的 OPENAI / GEMINI 供应商中挑选。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImageGenSection(
    settings: SettingsRepository = koinInject(),
    scope: CoroutineScope,
) {
    val config by settings.imageGenConfigFlow.collectAsStateWithLifecycle(initialValue = ImageGenConfig())
    var localConfig by remember(config) { mutableStateOf(config) }
    var showModelSelector by remember { mutableStateOf(false) }
    val providers by settings.providersFlow.collectAsStateWithLifecycle(initialValue = null)

    val imageProviders = remember(providers) {
        providers?.filter { it.type == ProviderType.OPENAI || it.type == ProviderType.GEMINI } ?: emptyList()
    }
    val selectedProvider = remember(localConfig.providerId, imageProviders) {
        imageProviders.firstOrNull { it.id == localConfig.providerId }
    }
    val selectedModel = remember(localConfig.modelId, selectedProvider) {
        selectedProvider?.models?.firstOrNull { it.id == localConfig.modelId }
    }

    fun update(change: (ImageGenConfig) -> ImageGenConfig) {
        localConfig = change(localConfig)
        scope.launch { settings.saveImageGenConfig(localConfig) }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        SectionLabel(stringResource(R.string.settings_image_gen_section))
        SettingsGroup {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 模型选择卡片
                if (providers == null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                } else {
                    ModelSelectorCard(
                        provider = selectedProvider,
                        model = selectedModel,
                        onClick = { showModelSelector = true },
                    )
                }
            }
        }
    }

    if (showModelSelector) {
        ImageModelSelectorDialog(
            providers = imageProviders,
            selectedProviderId = localConfig.providerId,
            selectedModelId = localConfig.modelId,
            onSelect = { providerId, modelId ->
                update { it.copy(providerId = providerId, modelId = modelId) }
                showModelSelector = false
            },
            onDismiss = { showModelSelector = false },
        )
    }
}

@Composable
private fun ModelSelectorCard(
    provider: ProviderConfig?,
    model: Model?,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MuseShapes.medium,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = provider?.displayName?.firstOrNull()?.toString() ?: "?",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                val selectProviderText = stringResource(R.string.settings_image_gen_select_provider)
                val selectModelText = stringResource(R.string.settings_image_gen_select_model)
                val selectDefaultText = stringResource(R.string.settings_image_gen_select_default)
                Text(
                    text = when {
                        provider == null -> selectProviderText
                        model == null -> selectModelText
                        else -> model.name
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = provider?.displayName ?: selectDefaultText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Text(
                text = ">",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImageModelSelectorDialog(
    providers: List<ProviderConfig>,
    selectedProviderId: String,
    selectedModelId: String,
    onSelect: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var currentProviderId by remember(selectedProviderId) { mutableStateOf(selectedProviderId) }

    LaunchedEffect(providers) {
        if (providers.isNotEmpty() && providers.none { it.id == currentProviderId }) {
            currentProviderId = providers.first().id
        }
    }

    val currentProvider = remember(currentProviderId, providers) {
        providers.firstOrNull { it.id == currentProviderId } ?: providers.firstOrNull()
    }

    MuseSelectionSheet(
        title = stringResource(R.string.settings_image_gen_select_model),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val provider = currentProvider
            if (provider == null) {
                Text(
                    text = stringResource(R.string.settings_image_gen_no_provider),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(R.string.settings_image_gen_provider),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    providers.forEach { p ->
                        MuseChip(
                            selected = p.id == currentProviderId,
                            onClick = { currentProviderId = p.id },
                            label = p.displayName,
                        )
                    }
                }

                val models = provider.models.filter { it.supportsImageOutput() }
                Text(
                    text = stringResource(R.string.settings_image_gen_model_label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (models.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_image_gen_no_model),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(models, key = { it.id }) { model ->
                            val selected = model.id == selectedModelId && provider.id == selectedProviderId
                            ModelGridCard(
                                model = model,
                                selected = selected,
                                onClick = { onSelect(provider.id, model.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelGridCard(
    model: Model,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MuseShapes.medium,
        tonalElevation = if (selected) 2.dp else 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = model.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    imageVector = TablerIcons.Check,
                    contentDescription = stringResource(R.string.settings_image_gen_selected),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabeledChipGroup(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (value, display) ->
                // v1.134 P0-8: FilterChip → MuseChip
                MuseChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = display,
                )
            }
        }
    }
}
