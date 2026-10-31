@file:Suppress("FunctionNaming")

package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.ProviderConfig
import io.zer0.muse.R
import io.zer0.muse.asr.AsrConfig
import io.zer0.muse.asr.AsrProviderType
import io.zer0.muse.data.MediaConfig
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseSelectionSheet
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.surface.MuseListItem
import io.zer0.muse.ui.theme.MusePaddings

/**
 * 语音模型（TTS / ASR）的候选与写入规则。
 *
 * 模型只在供应商里维护，设置页只负责「选一个」：候选来自供应商的模型列表，
 * 写入的字段跟着是否跟随供应商走 —— 跟随写 `modelId`，手动写 `model`。
 * 逻辑抽成纯函数，页面只做渲染，单测不必起 Compose。
 */
internal object SpeechModelPicker {

    /**
     * 候选供应商：配置里指定的那个优先，没指定（或已被删）时取第一个启用中的。
     *
     * 被停用的供应商仍然返回：用户可能正要改回它的模型，直接藏起来会让人以为配置丢了。
     */
    fun candidateProvider(providers: List<ProviderConfig>, providerId: String): ProviderConfig? =
        providers.firstOrNull { it.id == providerId } ?: providers.firstOrNull { it.enabled }

    /** 候选模型：供应商的模型列表，顺序保持不变。 */
    fun options(provider: ProviderConfig?): List<SpeechModelOption> =
        provider?.models.orEmpty().map { SpeechModelOption(id = it.id, name = it.name) }

    /**
     * 当前选中态。
     *
     * 配置里留着手填值、但候选列表里没有它（旧配置或供应商没同步过这个模型）时算
     * [SpeechModelSelection.Custom]：UI 显示「自定义：x」，值原样保留，绝不清空。
     */
    fun selection(currentId: String, options: List<SpeechModelOption>): SpeechModelSelection {
        val current = currentId.trim()
        if (current.isBlank()) return SpeechModelSelection.Empty
        val hit = options.firstOrNull { it.id == current }
        return if (hit != null) SpeechModelSelection.FromProvider(hit) else SpeechModelSelection.Custom(current)
    }

    /** 选中 TTS 模型后要落库的配置：跟随供应商写 [MediaConfig.ttsModelId]，否则写 [MediaConfig.ttsModel]。 */
    fun applyToTts(config: MediaConfig, modelId: String): MediaConfig =
        if (config.ttsFollowProvider) {
            config.copy(ttsModelId = modelId)
        } else {
            config.copy(ttsModel = modelId)
        }

    /**
     * ASR 是否真的在用供应商配置。
     *
     * DashScope 实时识别走内置端点、供应商地址不参与，因此该引擎下即便开关开着也按手动处理
     * （与 [io.zer0.muse.data.SpeechProviderResolver.resolveAsr] 的判定保持一致）。
     */
    fun asrFollowActive(config: AsrConfig): Boolean =
        config.followProvider && config.provider != AsrProviderType.DASHSCOPE

    /** 选中 ASR 模型后要落库的配置：跟随供应商写 [AsrConfig.modelId]，否则写 [AsrConfig.model]。 */
    fun applyToAsr(config: AsrConfig, modelId: String): AsrConfig =
        if (asrFollowActive(config)) {
            config.copy(modelId = modelId)
        } else {
            config.copy(model = modelId)
        }
}

/** 候选模型（只保留选择面板要用的两个字段，避免把整个模型对象带进 UI 状态）。 */
internal data class SpeechModelOption(val id: String, val name: String)

/** 模型选择行的三种显示态。 */
internal sealed interface SpeechModelSelection {

    /** 选中的是候选列表里的模型。 */
    data class FromProvider(val model: SpeechModelOption) : SpeechModelSelection

    /** 配置里的值不在候选里（旧配置 / 供应商没同步），原样显示为「自定义」。 */
    data class Custom(val value: String) : SpeechModelSelection

    /** 没有选模型，用引擎默认。 */
    data object Empty : SpeechModelSelection
}

/** 模型名显示文案：候选里取名字，不在候选里标「自定义」，空值用调用方给的占位。 */
@Composable
internal fun speechModelLabel(selection: SpeechModelSelection, emptyLabel: String): String = when (selection) {
    is SpeechModelSelection.FromProvider -> selection.model.name
    is SpeechModelSelection.Custom -> stringResource(R.string.settings_speech_model_custom_prefix, selection.value)
    SpeechModelSelection.Empty -> emptyLabel
}

/**
 * 模型设置块：模型行 + 说明 + （按需）自定义输入区 + 选择面板。
 *
 * TTS 与 ASR 的唯一差别是「选中后写到哪」，由 [onPick] 决定，页面不重复搭这套控。
 *
 * @param currentModelId 配置里当前生效的模型 id（跟随供应商时是 modelId，手动模式是 model）
 * @param provider 候选供应商（决定候选列表）
 * @param emptyLabel 未选模型时的副标题
 * @param onPick 选中（或保存自定义）后的回调，负责落库
 * @param onOpenProviders 候选为空时的出口；null 表示不提供入口
 */
@Composable
internal fun SpeechModelSection(
    title: String,
    currentModelId: String,
    provider: ProviderConfig?,
    emptyLabel: String,
    onPick: (String) -> Unit,
    onOpenProviders: (() -> Unit)? = null,
) {
    val options = remember(provider) { SpeechModelPicker.options(provider) }
    val selection = remember(currentModelId, options) { SpeechModelPicker.selection(currentModelId, options) }
    var pickerOpen by remember { mutableStateOf(false) }
    var customOpen by remember { mutableStateOf(false) }
    var customValue by remember(currentModelId) { mutableStateOf(currentModelId) }

    SpeechModelRow(
        title = title,
        selection = selection,
        emptyLabel = emptyLabel,
        onOpenPicker = { pickerOpen = true },
    )
    if (customOpen) {
        SpeechModelCustomField(
            value = customValue,
            fieldLabel = title,
            onValueChange = { customValue = it },
            onSave = {
                onPick(customValue.trim())
                customOpen = false
            },
            onCancel = { customOpen = false },
        )
    }
    if (pickerOpen) {
        SpeechModelSheet(
            title = title,
            options = options,
            selection = selection,
            onSelect = { modelId ->
                onPick(modelId)
                pickerOpen = false
            },
            onUseCustom = {
                customValue = currentModelId
                pickerOpen = false
                customOpen = true
            },
            onOpenProviders = onOpenProviders,
            onDismiss = { pickerOpen = false },
        )
    }
}

/**
 * 模型行：点开选择面板，下面跟一句「模型由供应商统一管理」的说明。
 *
 * @param title 行标题（TTS 与 ASR 各自的「模型」文案）
 * @param selection 当前选中态
 * @param emptyLabel 未选模型时的副标题
 */
@Composable
internal fun SpeechModelRow(
    title: String,
    selection: SpeechModelSelection,
    emptyLabel: String,
    onOpenPicker: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsItemRow(
            icon = MuseIcons.robot,
            title = title,
            subtitle = speechModelLabel(selection, emptyLabel),
            onClick = onOpenPicker,
        ) {
            ChevronRight()
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
        ) {
            Text(
                text = stringResource(R.string.settings_speech_model_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 模型选择面板。
 *
 * 候选来自供应商；列表为空时给引导文案与「前往供应商设置」入口，
 * 末尾固定留「改为自定义…」，让供应商没同步到的手填值仍有出路。
 */
@Composable
internal fun SpeechModelSheet(
    title: String,
    options: List<SpeechModelOption>,
    selection: SpeechModelSelection,
    onSelect: (String) -> Unit,
    onUseCustom: () -> Unit,
    onOpenProviders: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    MuseSelectionSheet(
        title = title,
        subtitle = stringResource(R.string.settings_speech_model_hint),
        onDismissRequest = onDismiss,
    ) {
        if (options.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_speech_model_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onOpenProviders != null) {
                MuseListItem(
                    onClick = {
                        onDismiss()
                        onOpenProviders()
                    },
                    leadingContent = { Icon(MuseIcons.server, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.settings_speech_model_open_providers)) },
                    trailingContent = { ChevronRight() },
                )
            }
        } else {
            val selectedId = (selection as? SpeechModelSelection.FromProvider)?.model?.id
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
            ) {
                items(options, key = { it.id }) { option ->
                    MuseListItem(
                        onClick = { onSelect(option.id) },
                        headlineContent = {
                            Text(
                                text = option.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = if (option.id == option.name) {
                            null
                        } else {
                            { Text(option.id) }
                        },
                        trailingContent = if (option.id == selectedId) {
                            {
                                Icon(
                                    imageVector = MuseIcons.check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        MuseListItem(
            onClick = { onUseCustom() },
            leadingContent = { Icon(MuseIcons.edit, contentDescription = null) },
            headlineContent = { Text(stringResource(R.string.settings_speech_model_custom_entry)) },
        )
    }
}

/**
 * 自定义模型名输入区（只有从面板里点了「改为自定义…」才展开）。
 *
 * 旧版直接摆在页面上的文本框由它接手：能力不删，只是藏到一个明确的入口后面。
 */
@Composable
internal fun SpeechModelCustomField(
    value: String,
    fieldLabel: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
    ) {
        MuseTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(fieldLabel) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
        ) {
            MuseCapsuleButton(
                text = stringResource(R.string.action_save),
                onClick = onSave,
                variant = IosCapsuleButtonVariant.Text,
                fillWidth = false,
            )
            MuseCapsuleButton(
                text = stringResource(R.string.common_cancel),
                onClick = onCancel,
                variant = IosCapsuleButtonVariant.Text,
                fillWidth = false,
            )
        }
    }
}
