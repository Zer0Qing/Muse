package io.zer0.muse.ui.media

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.image.ImageModelCatalog
import io.zer0.muse.R
import io.zer0.muse.data.media.MediaGenTaskEntity
import io.zer0.muse.data.media.StudioImageRequest
import io.zer0.muse.data.media.StudioVideoRequest
import io.zer0.muse.ui.chat.SmartImage
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.util.launchSafely
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MediaStudioImageTab(
    providers: List<ProviderConfig>,
    activeTasks: List<MediaGenTaskEntity>,
    prefillRequest: StudioImageRequest?,
    onConsumePrefill: () -> Unit,
    onOpenMediaSettings: () -> Unit,
    onSubmit: suspend (StudioImageRequest) -> String,
    onCancelTask: (String) -> Unit,
    scope: CoroutineScope,
) {
    val context = LocalContext.current
    val enabledProviders = remember(providers) {
        providers.filter { it.enabled && (it.apiKey.isNotBlank() || it.allowMissingApiKey) }
    }

    var prompt by rememberSaveable { mutableStateOf("") }
    var selectedProviderId by rememberSaveable { mutableStateOf("") }
    var selectedModelId by rememberSaveable { mutableStateOf("") }
    var selectedSize by rememberSaveable { mutableStateOf("1024x1024") }
    var selectedQuality by rememberSaveable { mutableStateOf("standard") }
    var selectedStyle by rememberSaveable { mutableStateOf("") }
    var selectedCount by rememberSaveable { mutableIntStateOf(1) }
    var referenceUri by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(prefillRequest) {
        val req = prefillRequest ?: return@LaunchedEffect
        prompt = req.prompt
        if (req.providerId.isNotBlank()) selectedProviderId = req.providerId
        if (req.modelId.isNotBlank()) selectedModelId = req.modelId
        selectedSize = req.size
        selectedQuality = req.quality
        selectedStyle = req.style
        selectedCount = req.n.coerceIn(1, 4)
        onConsumePrefill()
    }

    LaunchedEffect(enabledProviders) {
        if (selectedProviderId.isBlank() || enabledProviders.none { it.id == selectedProviderId }) {
            selectedProviderId = enabledProviders.firstOrNull { p ->
                p.models.any { it.supportsImageOutput() }
            }?.id ?: enabledProviders.firstOrNull()?.id.orEmpty()
        }
    }

    val selectedProvider = enabledProviders.firstOrNull { it.id == selectedProviderId }
        ?: enabledProviders.firstOrNull()

    val modelIds = remember(selectedProvider) {
        val configured = selectedProvider?.models?.filter { it.supportsImageOutput() }?.map { it.id }.orEmpty()
        configured.ifEmpty { ImageModelCatalog.all().map { it.id } }
    }

    LaunchedEffect(modelIds) {
        if (selectedModelId.isBlank() || selectedModelId !in modelIds) {
            selectedModelId = modelIds.firstOrNull().orEmpty()
        }
    }

    val modelMeta = remember(selectedModelId) { ImageModelCatalog.resolveById(selectedModelId) }
    val sizeOptions = modelMeta?.supportedSizes?.takeIf { it.isNotEmpty() }
        ?: listOf("1024x1024", "16:9", "9:16", "4:3")
    val qualityOptions = modelMeta?.supportedQualities.orEmpty()
    val styleOptions = modelMeta?.supportedStyles.orEmpty()
    val maxN = (modelMeta?.maxN ?: 4).coerceAtMost(4)
    val supportsRef = modelMeta?.supportsReferenceImage ?: true

    LaunchedEffect(sizeOptions) {
        if (selectedSize !in sizeOptions) {
            selectedSize = modelMeta?.defaultSize ?: sizeOptions.first()
        }
    }
    LaunchedEffect(maxN) {
        if (selectedCount > maxN) selectedCount = maxN.coerceAtLeast(1)
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { picked ->
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) { compressMediaReferenceImage(picked, context) }
                }.onSuccess { dataUri ->
                    referenceUri = dataUri
                }.onFailure { e ->
                    MuseToast.show(context.getString(R.string.video_gen_image_load_failed, e.message ?: ""))
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(MuseSpacing.md),
    ) {
        if (enabledProviders.isEmpty()) {
            MuseEmptyState(
                icon = MuseIcons.provider,
                title = stringResource(R.string.media_studio_provider_label),
                subtitle = stringResource(R.string.media_studio_no_provider_hint),
                actionText = stringResource(R.string.media_studio_go_configure),
                onAction = onOpenMediaSettings,
            )
            return@Column
        }

        StudioSectionLabel(stringResource(R.string.media_studio_prompt_label))
        MuseTextField(
            value = prompt,
            onValueChange = { prompt = it },
            placeholder = { Text(stringResource(R.string.media_studio_image_prompt_placeholder)) },
            maxLines = 6,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 112.dp),
        )

        StudioSectionLabel(stringResource(R.string.media_studio_provider_label))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
        ) {
            enabledProviders.forEach { provider ->
                MuseChip(
                    selected = provider.id == selectedProvider?.id,
                    onClick = { selectedProviderId = provider.id },
                    label = provider.displayName.ifBlank { provider.id },
                )
            }
        }

        StudioSectionLabel(stringResource(R.string.media_studio_model_label))
        if (selectedProvider?.models?.none { it.supportsImageOutput() } == true) {
            Text(
                text = stringResource(R.string.media_studio_no_image_model_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
        ) {
            modelIds.forEach { id ->
                MuseChip(
                    selected = id == selectedModelId,
                    onClick = { selectedModelId = id },
                    label = id,
                )
            }
        }

        StudioSectionLabel(stringResource(R.string.media_studio_size_label))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
        ) {
            sizeOptions.forEach { size ->
                MuseChip(
                    selected = size == selectedSize,
                    onClick = { selectedSize = size },
                    label = size,
                )
            }
        }

        if (qualityOptions.isNotEmpty()) {
            StudioSectionLabel(stringResource(R.string.media_studio_quality_label))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
            ) {
                qualityOptions.forEach { q ->
                    MuseChip(
                        selected = q == selectedQuality,
                        onClick = { selectedQuality = q },
                        label = q,
                    )
                }
            }
        }

        if (styleOptions.isNotEmpty()) {
            StudioSectionLabel(stringResource(R.string.media_studio_style_label))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
            ) {
                styleOptions.forEach { st ->
                    MuseChip(
                        selected = st == selectedStyle,
                        onClick = { selectedStyle = st },
                        label = st,
                    )
                }
            }
        }

        if (maxN > 1) {
            StudioSectionLabel(stringResource(R.string.media_studio_count_label))
            Row(horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs)) {
                listOf(1, 2, 4).filter { it <= maxN }.forEach { count ->
                    MuseChip(
                        selected = selectedCount == count,
                        onClick = { selectedCount = count },
                        label = stringResource(R.string.media_studio_count_chip, count),
                    )
                }
            }
        }

        if (supportsRef) {
            StudioReferenceImageBlock(
                referenceUri = referenceUri,
                onPick = { imagePicker.launchSafely("image/*", context) },
                onClear = { referenceUri = "" },
            )
        }

        val submitEnabled = prompt.isNotBlank() && selectedProvider != null && selectedModelId.isNotBlank()
        MuseCapsuleButton(
            text = stringResource(R.string.media_studio_submit_image),
            enabled = submitEnabled,
            leadingIcon = MuseIcons.wand,
            onClick = {
                val p = selectedProvider ?: return@MuseCapsuleButton
                val currentPrompt = prompt.trim()
                if (currentPrompt.isEmpty()) return@MuseCapsuleButton
                scope.launch {
                    onSubmit(
                        StudioImageRequest(
                            prompt = currentPrompt,
                            providerId = p.id,
                            modelId = selectedModelId,
                            size = selectedSize,
                            quality = selectedQuality,
                            style = selectedStyle,
                            n = selectedCount,
                            referenceImageUri = referenceUri.takeIf { it.isNotBlank() },
                        ),
                    )
                    MuseToast.show(context.getString(R.string.media_studio_task_queued_hint))
                }
            },
        )

        ActiveStudioTasksList(
            tasks = activeTasks,
            onCancel = onCancelTask,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MediaStudioVideoTab(
    providers: List<ProviderConfig>,
    activeTasks: List<MediaGenTaskEntity>,
    prefillRefUri: String?,
    onConsumePrefillRef: () -> Unit,
    onOpenMediaSettings: () -> Unit,
    onSubmit: suspend (StudioVideoRequest) -> String,
    onCancelTask: (String) -> Unit,
    scope: CoroutineScope,
) {
    val context = LocalContext.current
    val videoProviders = remember(providers) {
        providers.filter { p ->
            p.enabled && (p.apiKey.isNotBlank() || p.allowMissingApiKey) &&
                p.models.any { it.supportsVideoOutput() }
        }
    }

    var prompt by rememberSaveable { mutableStateOf("") }
    var selectedProviderId by rememberSaveable { mutableStateOf("") }
    var selectedModelId by rememberSaveable { mutableStateOf("") }
    var duration by rememberSaveable { mutableIntStateOf(5) }
    var resolution by rememberSaveable { mutableStateOf("720p") }
    var aspectRatio by rememberSaveable { mutableStateOf("16:9") }
    var referenceUri by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(prefillRefUri) {
        val incoming = prefillRefUri ?: return@LaunchedEffect
        referenceUri = incoming
        onConsumePrefillRef()
    }

    LaunchedEffect(videoProviders) {
        if (selectedProviderId.isBlank() || videoProviders.none { it.id == selectedProviderId }) {
            selectedProviderId = videoProviders.firstOrNull()?.id.orEmpty()
        }
    }

    val selectedProvider = videoProviders.firstOrNull { it.id == selectedProviderId }
        ?: videoProviders.firstOrNull()
    val videoModels = remember(selectedProvider) {
        selectedProvider?.models?.filter { it.supportsVideoOutput() }.orEmpty()
    }

    LaunchedEffect(videoModels) {
        if (selectedModelId.isBlank() || videoModels.none { it.id == selectedModelId }) {
            selectedModelId = videoModels.firstOrNull()?.id.orEmpty()
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { picked ->
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) { compressMediaReferenceImage(picked, context) }
                }.onSuccess { dataUri ->
                    referenceUri = dataUri
                }.onFailure { e ->
                    MuseToast.show(context.getString(R.string.video_gen_image_load_failed, e.message ?: ""))
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(MuseSpacing.md),
    ) {
        if (videoProviders.isEmpty()) {
            MuseEmptyState(
                icon = MuseIcons.video,
                title = stringResource(R.string.media_studio_no_video_provider),
                subtitle = stringResource(R.string.video_no_video_model_hint),
                actionText = stringResource(R.string.media_studio_go_configure),
                onAction = onOpenMediaSettings,
            )
            return@Column
        }

        StudioSectionLabel(stringResource(R.string.media_studio_prompt_label))
        MuseTextField(
            value = prompt,
            onValueChange = { prompt = it },
            placeholder = { Text(stringResource(R.string.media_studio_video_prompt_placeholder)) },
            maxLines = 6,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 112.dp),
        )

        StudioSectionLabel(stringResource(R.string.media_studio_provider_label))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
        ) {
            videoProviders.forEach { provider ->
                MuseChip(
                    selected = provider.id == selectedProvider?.id,
                    onClick = { selectedProviderId = provider.id },
                    label = provider.displayName.ifBlank { provider.id },
                )
            }
        }

        StudioSectionLabel(stringResource(R.string.media_studio_model_label))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
        ) {
            videoModels.forEach { model ->
                MuseChip(
                    selected = model.id == selectedModelId,
                    onClick = { selectedModelId = model.id },
                    label = model.name.ifBlank { model.id },
                )
            }
        }

        StudioSectionLabel(stringResource(R.string.video_gen_duration))
        Row(horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs)) {
            listOf(5, 10).forEach { sec ->
                MuseChip(
                    selected = duration == sec,
                    onClick = { duration = sec },
                    label = "${sec}s",
                )
            }
        }

        StudioSectionLabel(stringResource(R.string.video_gen_resolution))
        Row(horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs)) {
            listOf("720p", "1080p").forEach { res ->
                MuseChip(
                    selected = resolution == res,
                    onClick = { resolution = res },
                    label = res,
                )
            }
        }

        StudioSectionLabel(stringResource(R.string.media_studio_aspect_label))
        Row(horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs)) {
            listOf("16:9", "9:16", "1:1").forEach { ratio ->
                MuseChip(
                    selected = aspectRatio == ratio,
                    onClick = { aspectRatio = ratio },
                    label = ratio,
                )
            }
        }

        StudioReferenceImageBlock(
            referenceUri = referenceUri,
            onPick = { imagePicker.launchSafely("image/*", context) },
            onClear = { referenceUri = "" },
        )

        val submitEnabled = prompt.isNotBlank() && selectedProvider != null && selectedModelId.isNotBlank()
        MuseCapsuleButton(
            text = stringResource(R.string.media_studio_submit_video),
            enabled = submitEnabled,
            leadingIcon = MuseIcons.video,
            onClick = {
                val p = selectedProvider ?: return@MuseCapsuleButton
                val currentPrompt = prompt.trim()
                if (currentPrompt.isEmpty()) return@MuseCapsuleButton
                scope.launch {
                    onSubmit(
                        StudioVideoRequest(
                            prompt = currentPrompt,
                            providerId = p.id,
                            modelId = selectedModelId,
                            duration = duration,
                            resolution = resolution,
                            aspectRatio = aspectRatio,
                            referenceImageUri = referenceUri.takeIf { it.isNotBlank() },
                        ),
                    )
                    MuseToast.show(context.getString(R.string.media_studio_task_queued_hint))
                }
            },
        )

        ActiveStudioTasksList(
            tasks = activeTasks,
            onCancel = onCancelTask,
        )
    }
}

@Composable
private fun StudioSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StudioReferenceImageBlock(referenceUri: String, onPick: () -> Unit, onClear: () -> Unit) {
    StudioSectionLabel(stringResource(R.string.media_studio_reference_label))
    if (referenceUri.isBlank()) {
        MuseCapsuleButton(
            text = stringResource(R.string.media_studio_reference_pick),
            onClick = onPick,
            variant = IosCapsuleButtonVariant.Secondary,
            leadingIcon = MuseIcons.image,
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp)
                .clip(MuseShapes.semiLarge),
        ) {
            SmartImage(
                model = referenceUri,
                contentDescription = stringResource(R.string.media_studio_reference_label),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth(),
            )
            MuseTactileButton(
                icon = MuseIcons.x,
                onClick = onClear,
                contentDescription = stringResource(R.string.media_studio_reference_clear),
                size = MuseIconSizes.touchTarget,
                iconSize = MuseIconSizes.iconSmall,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(MuseSpacing.xs)
                    .background(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        shape = CircleShape,
                    ),
            )
        }
    }
}

@Composable
private fun ActiveStudioTasksList(tasks: List<MediaGenTaskEntity>, onCancel: (String) -> Unit) {
    if (tasks.isEmpty()) return
    StudioSectionLabel(stringResource(R.string.media_studio_active_tasks_title))
    Column(verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs)) {
        tasks.forEach { task ->
            Surface(
                shape = MuseShapes.semiLarge,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                ) {
                    MuseSpinner(size = MuseIconSizes.iconMedium)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = task.prompt,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(R.string.media_studio_task_elapsed, task.elapsedSeconds),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    MuseCapsuleButton(
                        text = stringResource(R.string.media_studio_task_cancel),
                        onClick = { onCancel(task.id) },
                        variant = IosCapsuleButtonVariant.Text,
                        fillWidth = false,
                    )
                }
            }
        }
    }
}
