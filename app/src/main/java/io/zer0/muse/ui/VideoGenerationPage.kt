package io.zer0.muse.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.video.VideoGenRequest
import io.zer0.ai.video.VideoGenerationService
import io.zer0.ai.video.VideoTaskStatus
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.IosTopBar
import io.zer0.muse.ui.common.MuseToast
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.semiLarge
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * VideoTaskStatus 枚举的 Saver(rememberSaveable 不能直接保存枚举,用 name 序列化/反序列化)。
 */
private val VideoTaskStatusSaver = Saver<VideoTaskStatus?, String>(
    save = { it?.name ?: "" },
    restore = { name ->
        if (name.isBlank()) null
        else runCatching { VideoTaskStatus.valueOf(name) }.getOrNull()
    },
)

/**
 * P2-8: 视频生成页 — iOS 风格全屏工具页。
 *
 * 布局:
 *  - IosTopBar:返回 + 标题「视频生成」
 *  - 表单区(可滚动):
 *    - Prompt 输入框(多行)
 *    - 模型选择(可灵 v1 / v2,SegmentedControl 风格)
 *    - 时长选择(5s / 10s)
 *    - 分辨率(720p / 1080p)
 *    - 参考图 URL(可选,留空走文生视频)
 *    - API Key 输入框(密码模式)
 *  - 「生成视频」按钮(Surface + clickable,不用 Material3 Button)
 *  - 任务状态区:进度条 + 状态文本
 *  - 完成后:视频 URL 卡片 + 「打开视频」按钮(Intent.ACTION_VIEW)
 *
 * 设计令牌:MuseShapes / MusePaddings,不使用 Material3 默认 Button。
 */
@Composable
fun VideoGenerationPage(
    onBack: () -> Unit,
    videoService: VideoGenerationService = koinInject(),
    settingsRepo: SettingsRepository = koinInject(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // v1.132: 从 SettingsRepository 读取所有启用的供应商,让用户选择
    // (不再硬编码 Kling API Key 输入框,用户在供应商菜单加了供应商后这里就能选)
    val providersState by settingsRepo.providersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val enabledProviders = remember(providersState) {
        providersState.filter { it.enabled && it.apiKey.isNotBlank() }
    }

    // 表单状态(rememberSaveable 保证旋转/配置变更后保留)
    var prompt by rememberSaveable { mutableStateOf("") }
    var selectedModel by rememberSaveable { mutableStateOf("") }
    var duration by rememberSaveable { mutableStateOf(5) }
    var resolution by rememberSaveable { mutableStateOf("720p") }
    var imageUrl by rememberSaveable { mutableStateOf("") }
    // v1.132: 选中的供应商 ID(从用户已配置的供应商中选),替代原来的 apiKey 输入
    var selectedProviderId by rememberSaveable { mutableStateOf("") }
    // 当前选中的供应商(派生状态)
    val selectedProvider: ProviderConfig? = remember(enabledProviders, selectedProviderId) {
        enabledProviders.firstOrNull { it.id == selectedProviderId }
            ?: enabledProviders.firstOrNull()
    }
    // 自动选中第一个供应商(首次进入或所选供应商被删除时)
    LaunchedEffect(enabledProviders) {
        if (selectedProviderId.isBlank() && enabledProviders.isNotEmpty()) {
            selectedProviderId = enabledProviders.first().id
        } else if (selectedProviderId.isNotBlank() &&
            enabledProviders.none { it.id == selectedProviderId }
        ) {
            selectedProviderId = enabledProviders.firstOrNull()?.id ?: ""
        }
    }

    // 当前供应商下支持视频输出的模型列表
    val videoModels = remember(selectedProvider) {
        selectedProvider?.models?.filter { it.supportsVideoOutput() } ?: emptyList()
    }
    // 自动选中第一个视频模型
    LaunchedEffect(videoModels) {
        if (selectedModel.isBlank() && videoModels.isNotEmpty()) {
            selectedModel = videoModels.first().id
        } else if (selectedModel.isNotBlank() && videoModels.none { it.id == selectedModel }) {
            selectedModel = videoModels.firstOrNull()?.id ?: ""
        }
    }

    // 任务状态
    var isGenerating by rememberSaveable { mutableStateOf(false) }
    var taskStatus by rememberSaveable(stateSaver = VideoTaskStatusSaver) { mutableStateOf<VideoTaskStatus?>(null) }
    var statusMessage by rememberSaveable { mutableStateOf("") }
    var videoUrl by rememberSaveable { mutableStateOf("") }
    var errorMessage by rememberSaveable { mutableStateOf("") }

    // 9.5 修复: 进程重建后 rememberSaveable 会恢复 isGenerating=true,但任务上下文已丢失,
    // 此时 loading 永不消失。首次进入页面时检查并重置。
    LaunchedEffect(Unit) {
        if (isGenerating) {
            isGenerating = false
        }
    }

    Scaffold(
        topBar = {
            IosTopBar(
                title = stringResource(R.string.video_gen_title),
                onBack = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            // ── Prompt 输入 ──
            FormSection(label = stringResource(R.string.video_gen_prompt)) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    placeholder = { Text(stringResource(R.string.video_gen_prompt)) },
                    shape = MuseShapes.semiLarge,
                    enabled = !isGenerating,
                    maxLines = 6,
                )
            }

            // ── 模型选择(动态:从当前供应商中筛选支持视频输出的模型)──
            FormSection(label = stringResource(R.string.video_gen_model)) {
                if (videoModels.isEmpty()) {
                    Surface(
                        shape = MuseShapes.semiLarge,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "当前供应商没有支持视频输出的模型。请在「设置→模型与服务」中为模型开启「视频输出」能力。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(MusePaddings.cardInner),
                        )
                    }
                } else {
                    Surface(
                        shape = MuseShapes.semiLarge,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(MusePaddings.tightGap),
                            verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                        ) {
                            videoModels.forEach { model ->
                                val isSelected = model.id == selectedModel
                                Surface(
                                    shape = MuseShapes.medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surface,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !isGenerating) {
                                            selectedModel = model.id
                                        },
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(MusePaddings.cardInner),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = model.name,
                                            style = MaterialTheme.typography.bodyLarge.copy(
                                                fontWeight = FontWeight.SemiBold,
                                            ),
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                            else MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── 时长选择 ──
            FormSection(label = stringResource(R.string.video_gen_duration)) {
                SegmentedOptions(
                    options = listOf(5 to "5s", 10 to "10s"),
                    selected = duration,
                    onSelect = { duration = it },
                    enabled = !isGenerating,
                )
            }

            // ── 分辨率选择 ──
            FormSection(label = stringResource(R.string.video_gen_resolution)) {
                SegmentedOptions(
                    options = listOf("720p" to "720p", "1080p" to "1080p"),
                    selected = resolution,
                    onSelect = { resolution = it },
                    enabled = !isGenerating,
                )
            }

            // ── 参考图 URL(可选)──
            FormSection(label = stringResource(R.string.video_gen_reference_image)) {
                OutlinedTextField(
                    value = imageUrl,
                    onValueChange = { imageUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://...") },
                    shape = MuseShapes.semiLarge,
                    enabled = !isGenerating,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }

            // ── 供应商选择(v1.132: 从 SettingsRepository 读取,不再硬编码 API Key 输入)──
            FormSection(label = stringResource(R.string.video_gen_provider)) {
                if (enabledProviders.isEmpty()) {
                    // 没有可用供应商时提示用户先去供应商菜单添加
                    Surface(
                        shape = MuseShapes.semiLarge,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.video_gen_no_provider),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(MusePaddings.cardInner),
                        )
                    }
                } else {
                    // 供应商列表(iOS 风格 SegmentedOptions,单选)
                    // 用 Surface+clickable 列表呈现,选中高亮 primary
                    Surface(
                        shape = MuseShapes.semiLarge,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(MusePaddings.tightGap),
                            verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                        ) {
                            enabledProviders.forEach { provider ->
                                val isSelected = provider.id == selectedProvider?.id
                                Surface(
                                    shape = MuseShapes.medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surface,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !isGenerating) {
                                            selectedProviderId = provider.id
                                        },
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(MusePaddings.cardInner),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = provider.displayName,
                                                style = MaterialTheme.typography.bodyLarge.copy(
                                                    fontWeight = FontWeight.SemiBold,
                                                ),
                                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                                else MaterialTheme.colorScheme.onSurface,
                                            )
                                            if (provider.baseUrl.isNotBlank()) {
                                                Text(
                                                    text = provider.baseUrl,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                        // API Key 末 4 位预览(让用户知道已配置 key)
                                        Text(
                                            text = "••••${provider.apiKey.takeLast(4)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── 生成视频按钮(Surface + clickable,不用 Material3 Button)──
            Surface(
                shape = MuseShapes.medium,
                color = if (isGenerating || selectedProvider == null || videoModels.isEmpty()) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isGenerating && selectedProvider != null && videoModels.isNotEmpty()) {
                        if (prompt.isBlank()) {
                            MuseToast.show(context.getString(R.string.video_gen_prompt))
                            return@clickable
                        }
                        val provider = selectedProvider ?: return@clickable
                        scope.launch {
                            isGenerating = true
                            taskStatus = VideoTaskStatus.PENDING
                            statusMessage = context.getString(R.string.video_gen_pending)
                            errorMessage = ""
                            videoUrl = ""

                            val request = VideoGenRequest(
                                prompt = prompt,
                                model = selectedModel,
                                referenceImages = imageUrl.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList(),
                                duration = duration,
                                resolution = resolution,
                            )
                            // v1.137: 通过 VideoProviderRegistry 按 specId/host 路由,
                            // 不再按 providerId 硬匹配(修复 preset_kling ≠ kling 的路由 bug)
                            val result = videoService.generateVideo(provider, request)
                            isGenerating = false
                            result.onSuccess { url ->
                                taskStatus = VideoTaskStatus.SUCCESS
                                statusMessage = context.getString(R.string.video_gen_success)
                                videoUrl = url
                                MuseToast.show(context.getString(R.string.video_gen_success))
                            }.onFailure { e ->
                                taskStatus = VideoTaskStatus.FAILED
                                val msg = e.message ?: "unknown error"
                                errorMessage = msg
                                statusMessage = context.getString(R.string.video_gen_failed, msg)
                                MuseToast.show(statusMessage)
                            }
                        }
                    },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MusePaddings.cardInner.calculateTopPadding()),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isGenerating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(MusePaddings.iconPadding),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.size(MusePaddings.iconPadding))
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Movie,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(MusePaddings.iconPadding * 2),
                        )
                        Spacer(Modifier.size(MusePaddings.iconPadding))
                    }
                    Text(
                        text = stringResource(R.string.video_gen_submit),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isGenerating) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onPrimary
                        },
                    )
                }
            }

            // ── 任务状态区 ──
            if (isGenerating || taskStatus != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                StatusSection(
                    status = taskStatus,
                    statusMessage = statusMessage,
                    isGenerating = isGenerating,
                )
            }

            // ── 完成后视频预览 ──
            if (videoUrl.isNotBlank() && taskStatus == VideoTaskStatus.SUCCESS) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                VideoResultCard(
                    videoUrl = videoUrl,
                    onOpen = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(videoUrl)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { context.startActivity(intent) }
                            .onFailure {
                                MuseToast.show(context.getString(R.string.video_gen_no_app))
                            }
                    },
                )
            }

            // 底部留白
            Spacer(Modifier.height(MusePaddings.sectionGap))
        }
    }
}

/**
 * 表单分组(标题 + 内容)。
 */
@Composable
private fun FormSection(
    label: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
        content()
    }
}

/**
 * 分段选择器(用 Surface + clickable 实现 iOS 风格 SegmentedControl)。
 */
@Composable
private fun <T> SegmentedOptions(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
) {
    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.tightGap),
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        ) {
            options.forEach { (value, label) ->
                val isSelected = value == selected
                Surface(
                    shape = MuseShapes.small,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = enabled) { onSelect(value) },
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(vertical = MusePaddings.contentGap),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * 任务状态区(进度条 + 状态文本)。
 */
@Composable
private fun StatusSection(
    status: VideoTaskStatus?,
    statusMessage: String,
    isGenerating: Boolean,
) {
    // 预提取 stringResource(ifBlank lambda 非 @Composable,不能直接调用)
    val pendingText = stringResource(R.string.video_gen_pending)
    val processingText = stringResource(R.string.video_gen_processing)
    val successText = stringResource(R.string.video_gen_success)
    val failedText = stringResource(R.string.video_gen_failed, "")
    val fallbackText = when (status) {
        VideoTaskStatus.PENDING -> pendingText
        VideoTaskStatus.PROCESSING -> processingText
        VideoTaskStatus.SUCCESS -> successText
        VideoTaskStatus.FAILED -> failedText
        null -> ""
    }

    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isGenerating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(MusePaddings.iconPadding * 2),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(MusePaddings.iconPadding))
            }
            Text(
                text = statusMessage.ifBlank { fallbackText },
                style = MaterialTheme.typography.bodyMedium,
                color = when (status) {
                    VideoTaskStatus.FAILED -> MaterialTheme.colorScheme.error
                    VideoTaskStatus.SUCCESS -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }

        if (isGenerating) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

/**
 * 视频结果卡片 — 展示视频 URL + 「打开视频」按钮。
 */
@Composable
private fun VideoResultCard(
    videoUrl: String,
    onOpen: () -> Unit,
) {
    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.screen),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Text(
                text = stringResource(R.string.video_gen_success),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = videoUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Surface(
                shape = MuseShapes.medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen() },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MusePaddings.contentGap),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(MusePaddings.iconPadding * 2),
                    )
                    Spacer(Modifier.size(MusePaddings.iconPadding))
                    Text(
                        text = stringResource(R.string.video_gen_open_video),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}
