package io.zer0.muse.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.ai.registry.ModelRegistry
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.actionItem
import io.zer0.muse.ui.common.surface.switchItem
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.vision.VisionAnalysisException
import io.zer0.muse.vision.VisionBridge
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * 视觉辅助设置页 — 让纯文本模型通过视觉模型"看到"图片。
 *
 * 配置项:
 *  - 启用/禁用视觉辅助
 *  - 选择用于视觉分析的模型(仅显示支持视觉输入的模型)
 *
 * v1.0.1: 中转站支持改造
 *  - P0: 过滤前调用 [ModelRegistry.enhanceModel] 增强模型能力识别,解决中转站拉取的模型列表
 *    不带 supportsVision/inputModalities 字段导致 UI 看不到视觉模型的问题
 *  - P1: 新增"显示全部模型"开关(中转站高级模式),让用户可手动选择未被识别的视觉模型
 */
@Composable
fun VisionSettingsPage(onBack: () -> Unit) {
    val settings: SettingsRepository = koinInject()
    val visionBridge: VisionBridge = koinInject()
    val context = LocalContext.current
    val enabled by settings.visionEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val visionModelId by settings.visionModelIdFlow.collectAsStateWithLifecycle(initialValue = null)
    val visionProviderId by settings.visionProviderIdFlow.collectAsStateWithLifecycle(initialValue = null)
    val providers by settings.providersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    // v1.0.1 (P1): "显示全部模型"开关 — 中转站高级模式
    //  开启后跳过 supportsVisionInput 过滤,显示所有模型,让用户手动选择未被识别的视觉模型
    var showAllModels by rememberSaveable { mutableStateOf(false) }

    // v1.0.5: 探针测试状态 — Idle / Running / Success / Failed
    var probeState by remember { mutableStateOf<ProbeState>(ProbeState.Idle) }

    // v1.0.1 (P0): 过滤前调用 enhanceModel 增强,识别中转站模型
    //  中转站拉取的模型列表通常不带 abilities/inputModalities 字段,直接 supportsVisionInput() 返回 false
    //  enhanceModel 通过 modelId token 匹配预设规则,自动补全 supportsVision=true
    val allModelItems = providers.flatMap { provider ->
        provider.models.map { model ->
            // 增强后的模型(若未匹配预设规则则原样返回)
            val enhanced = ModelRegistry.enhanceModel(model)
            VisionModelItem(
                modelId = model.id,
                modelName = model.name,
                providerId = provider.id,
                providerName = provider.displayName,
                supportsVision = enhanced.supportsVisionInput(),
            )
        }
    }
    // 2026-11-20(P3-13):「显示全部模型」只有在确实存在「未被识别为视觉」的模型时才有意义。
    // 真机上只有一个视觉模型时,这个开关打开前后列表一模一样,用户会以为开关坏了。
    val hiddenModelCount = allModelItems.count { !it.supportsVision }
    val visionModels = allModelItems.filter { showAllModels || it.supportsVision }

    SettingsSubPageScaffold(
        title = stringResource(R.string.settings_vision_title),
        onBack = onBack,
    ) {
        item(key = "vision_enable_group") {
            CardGroup(title = { Text(stringResource(R.string.settings_vision_description)) }) {
                switchItem(
                    key = "vision_enabled",
                    title = stringResource(R.string.settings_vision_enabled),
                    checked = enabled,
                    onCheckedChange = { v -> scope.launch { settings.saveVisionEnabled(v) } },
                    leadingContent = {
                        Icon(
                            imageVector = MuseIcons.eye,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
            }
        }

        if (enabled) {
            item(key = "vision_models_group") {
                val showAllSub = stringResource(
                    if (hiddenModelCount > 0) {
                        R.string.settings_vision_show_all_models_subtitle
                    } else {
                        R.string.settings_vision_show_all_models_subtitle_no_hidden
                    },
                )
                CardGroup(title = { Text(stringResource(R.string.settings_vision_model_section)) }) {
                    switchItem(
                        key = "vision_show_all",
                        title = stringResource(R.string.settings_vision_show_all_models),
                        subtitle = showAllSub,
                        checked = showAllModels,
                        enabled = hiddenModelCount > 0,
                        onCheckedChange = { showAllModels = it },
                        leadingContent = {
                            Icon(
                                imageVector = MuseIcons.eye,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(MuseIconSizes.iconMedium),
                            )
                        },
                    )
                    if (visionModels.isEmpty()) {
                        content(key = "vision_empty") {
                            Text(
                                text = stringResource(R.string.settings_vision_no_models),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(MusePaddings.cardInner),
                            )
                        }
                    } else {
                        visionModels.forEach { item ->
                            val isSelected = item.modelId == visionModelId && item.providerId == visionProviderId
                            val manualSelected = showAllModels && !item.supportsVision
                            val subtitle = if (manualSelected) {
                                "${item.providerName} · ${stringResource(R.string.settings_vision_manual_selected)}"
                            } else {
                                item.providerName
                            }
                            item(
                                key = "${item.providerId}_${item.modelId}",
                                onClick = {
                                    scope.launch {
                                        settings.saveVisionModelId(item.modelId)
                                        settings.saveVisionProviderId(item.providerId)
                                    }
                                },
                                leadingContent = {
                                    Icon(
                                        imageVector = if (isSelected) MuseIcons.circleCheck else MuseIcons.circle,
                                        contentDescription = null,
                                        tint = if (isSelected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                                    )
                                },
                                headlineContent = { Text(item.modelName) },
                                supportingContent = { Text(subtitle) },
                            )
                        }
                    }
                }
            }

            item(key = "vision_probe_group") {
                val isRunning = probeState is ProbeState.Running
                CardGroup(title = { Text(stringResource(R.string.settings_vision_probe_section)) }) {
                    content(key = "vision_probe_desc") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(MusePaddings.cardInner),
                            verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                        ) {
                            Text(
                                text = stringResource(R.string.settings_vision_probe_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            when (val s = probeState) {
                                is ProbeState.Idle -> Unit
                                is ProbeState.Running -> Unit
                                is ProbeState.Success -> {
                                    Text(
                                        text = stringResource(R.string.settings_vision_probe_success, s.chars),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                is ProbeState.Failed -> {
                                    Text(
                                        text = stringResource(R.string.settings_vision_probe_failed, s.message),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                    actionItem(
                        key = "vision_probe_run",
                        title = if (isRunning) {
                            stringResource(R.string.settings_vision_probe_running)
                        } else {
                            stringResource(R.string.settings_vision_probe_button)
                        },
                        loading = isRunning,
                        enabled = !isRunning,
                        onClick = {
                            if (visionModelId.isNullOrBlank() || visionProviderId.isNullOrBlank()) {
                                probeState = ProbeState.Failed(
                                    context.getString(R.string.settings_vision_probe_no_model),
                                )
                                return@actionItem
                            }
                            if (!enabled) {
                                probeState = ProbeState.Failed(
                                    context.getString(R.string.settings_vision_probe_disabled),
                                )
                                return@actionItem
                            }
                            probeState = ProbeState.Running
                            scope.launch {
                                val result = resultOf {
                                    visionBridge.prepare(
                                        text = "探针测试",
                                        images = listOf(VISION_PROBE_IMAGE_PNG_BASE64),
                                        userRequest = "探针测试:这张图是什么颜色?",
                                        sessionId = null,
                                    )
                                }
                                probeState = when {
                                    result.isError -> {
                                        val ex = (result as io.zer0.common.Result.Error).throwable
                                        val msg = (ex as? VisionAnalysisException)?.message
                                            ?: ex?.message
                                            ?: result.message
                                        ProbeState.Failed(msg)
                                    }
                                    result.getOrNull()?.success == true -> ProbeState.Success(
                                        result.getOrNull()?.text?.length ?: 0,
                                    )
                                    else -> ProbeState.Failed(
                                        result.getOrNull()?.text
                                            ?: context.getString(R.string.code_gap_vision_probe_empty),
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

/** 视觉模型选项数据类。 */
private data class VisionModelItem(
    val modelId: String,
    val modelName: String,
    val providerId: String,
    val providerName: String,
    /** v1.0.1 (P0): 是否被 enhanceModel 识别为视觉模型(用于决定是否标记"手动选择") */
    val supportsVision: Boolean = false,
)

/**
 * v1.0.5: 探针测试状态机。
 *
 *  - [Idle]: 初始状态,未触发过测试
 *  - [Running]: 测试进行中(按钮禁用 + 显示 spinner)
 *  - [Success]: 测试成功,携带视觉模型返回的描述字符数
 *  - [Failed]: 测试失败,携带人类可读的错误信息(已脱敏的 VisionAnalysisException.message)
 */
private sealed interface ProbeState {
    data object Idle : ProbeState
    data object Running : ProbeState
    data class Success(val chars: Int) : ProbeState
    data class Failed(val message: String) : ProbeState
}

/**
 * v1.0.5: 探针测试用的内置 1x1 红色 PNG(无 data: 前缀)。
 *
 * 选 1x1 是为了最小化测试成本(单次请求只送几十字节图片),
 * 同时验证视觉模型是否真能接收并返回图片描述。
 */
private const val VISION_PROBE_IMAGE_PNG_BASE64 =
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
