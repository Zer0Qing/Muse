package io.zer0.muse.ui.settings

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderType
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseListItemSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.statusColors

/**
 * 单个模型健康检查状态。
 *
 * - [Idle]: 未测试，不显示状态标签
 * - [InProgress]: 测试进行中
 * - [Success]: 测试通过，可选携带回复摘要
 * - [Failed]: 测试失败，携带错误原因
 */
sealed class ModelTestStatus {
    data object Idle : ModelTestStatus()
    data object InProgress : ModelTestStatus()
    data class Success(val message: String? = null) : ModelTestStatus()
    data class Failed(val message: String? = null) : ModelTestStatus()
}

/**
 * 供应商模型列表行。
 *
 * 整行点击进入模型能力编辑；同一供应商上下文中默认隐藏重复的品牌 logo，
 * 用单行克制摘要呈现上下文与核心能力，彩色语义标签仅留给健康测试结果。
 */
@Composable
internal fun ProviderModelRow(
    model: Model,
    providerType: ProviderType,
    providerName: String,
    isAdded: Boolean = true,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    onTest: (() -> Unit)? = null,
    testStatus: ModelTestStatus = ModelTestStatus.Idle,
    showLogo: Boolean = false,
) {
    val summary = rememberModelSummaryLine(model)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MuseListItemSizes.minHeight)
            .clickable(onClick = onAction)
            .padding(MusePaddings.cardInner),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.md),
    ) {
        if (showLogo) {
            ProviderLogo(
                type = providerType,
                name = providerName,
                size = 34.dp,
                cornerRadius = 10.dp,
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(MuseSpacing.xxs),
        ) {
            Text(
                text = model.name.takeIf { it.isNotBlank() } ?: model.id,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (testStatus !is ModelTestStatus.Idle) {
                Box(modifier = Modifier.padding(top = MuseSpacing.xxs)) {
                    ModelTestStatusPill(testStatus)
                }
            }
        }

        if (onTest != null) {
            val canTest = testStatus !is ModelTestStatus.InProgress
            Surface(
                onClick = onTest,
                enabled = canTest,
                shape = MuseShapes.pill,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(
                    alpha = if (canTest) 1f else MuseAlpha.disabled,
                ),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (canTest) 1f else MuseAlpha.disabled,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = MuseSpacing.mdTight, vertical = MuseSpacing.smTight),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                ) {
                    if (testStatus is ModelTestStatus.InProgress) {
                        MuseSpinner(size = MuseIconSizes.iconTiny, strokeWidth = 1.5.dp)
                    } else {
                        Icon(
                            imageVector = MuseIcons.gauge,
                            contentDescription = stringResource(R.string.settings_model_action_test_chat),
                            modifier = Modifier.size(MuseIconSizes.iconTiny),
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_model_action_test_chat_short),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
        }

        if (isAdded) {
            ChevronRight()
        } else {
            MuseTactileButton(
                icon = MuseIcons.plus,
                onClick = onAction,
                contentDescription = stringResource(R.string.settings_model_action_add),
                tint = MaterialTheme.colorScheme.primary,
                compact = true,
            )
        }
    }
}

@Composable
private fun rememberModelSummaryLine(model: Model): String {
    val labelTool = stringResource(R.string.settings_model_ability_tool)
    val labelReasoning = stringResource(R.string.settings_model_ability_reasoning)
    val labelImage = stringResource(R.string.settings_model_ability_image)
    val labelMultimodal = stringResource(R.string.settings_model_ability_multimodal)
    val labelVideo = stringResource(R.string.settings_model_ability_video)
    val labelChat = stringResource(R.string.settings_model_ability_chat)
    val labelContextFmt = stringResource(R.string.settings_model_context_label)
    val labelOutputFmt = stringResource(R.string.settings_model_output_label)

    val contextChip = rememberContextChip(model, labelContextFmt)
    val outputChip = rememberOutputChip(model, labelOutputFmt)

    return remember(
        model.id,
        model.abilities,
        model.inputModalities,
        model.outputModalities,
        model.supportsVision,
        model.supportsVideo,
        contextChip,
        outputChip,
    ) {
        buildList {
            contextChip?.let { add(it) }
            outputChip?.let { add(it) }
            if (model.supportsReasoning()) add(labelReasoning)
            if (model.supportsToolCalling()) add(labelTool)
            if (model.supportsVisionInput()) add(labelMultimodal)
            if (model.supportsImageOutput()) add(labelImage)
            if (model.supportsVideoOutput()) add(labelVideo)
        }.ifEmpty { listOf(labelChat) }.joinToString(" · ")
    }
}

/**
 * 精简中性模型能力标签行（供引导页、模型多选等场景复用）。
 *
 * 能力信息统一使用克制的中性浅底胶囊，彩色语义仅用于健康检测状态。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelAbilityChips(model: Model, testStatus: ModelTestStatus = ModelTestStatus.Idle) {
    val labelTool = stringResource(R.string.settings_model_ability_tool)
    val labelReasoning = stringResource(R.string.settings_model_ability_reasoning)
    val labelImage = stringResource(R.string.settings_model_ability_image)
    val labelMultimodal = stringResource(R.string.settings_model_ability_multimodal)
    val labelVideo = stringResource(R.string.settings_model_ability_video)
    val labelChat = stringResource(R.string.settings_model_ability_chat)
    val labelContextFmt = stringResource(R.string.settings_model_context_label)
    val labelOutputFmt = stringResource(R.string.settings_model_output_label)

    val contextChip = rememberContextChip(model, labelContextFmt)
    val outputChip = rememberOutputChip(model, labelOutputFmt)

    val labels = buildList {
        contextChip?.let { add(it) }
        outputChip?.let { add(it) }
        if (model.supportsReasoning()) add(labelReasoning)
        if (model.supportsToolCalling()) add(labelTool)
        if (model.supportsVisionInput()) add(labelMultimodal)
        if (model.supportsImageOutput()) add(labelImage)
        if (model.supportsVideoOutput()) add(labelVideo)
    }.ifEmpty { listOf(labelChat) }

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.smTight),
        verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
    ) {
        labels.forEach { label ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MuseShapes.small,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = MuseSpacing.sm, vertical = MuseSpacing.xxs),
                )
            }
        }
        if (testStatus !is ModelTestStatus.Idle) {
            ModelTestStatusPill(testStatus)
        }
    }
}

@Composable
private fun ModelTestStatusPill(testStatus: ModelTestStatus) {
    val statusColors = MaterialTheme.statusColors
    val labelTestInProgress = stringResource(R.string.settings_model_test_in_progress)
    val labelTestSuccess = stringResource(R.string.settings_model_test_success)
    val labelTestFailed = stringResource(R.string.settings_model_test_failed)
    val labelTestSuccessFmt = stringResource(R.string.settings_model_test_success_with_msg)
    val labelTestFailedFmt = stringResource(R.string.settings_model_test_failed_with_msg)

    when (testStatus) {
        ModelTestStatus.Idle -> Unit
        ModelTestStatus.InProgress -> TestStatusChip(
            text = labelTestInProgress,
            containerColor = statusColors.neutralContainer,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            leading = { MuseSpinner(size = 10.dp, strokeWidth = 1.5.dp) },
        )
        is ModelTestStatus.Success -> {
            val text = testStatus.message?.takeIf { it.isNotBlank() }
                ?.let { labelTestSuccessFmt.format(it) }
                ?: labelTestSuccess
            TestStatusChip(
                text = text,
                containerColor = statusColors.successContainer,
                contentColor = statusColors.success,
                leading = {
                    Icon(
                        imageVector = MuseIcons.check,
                        contentDescription = null,
                        modifier = Modifier.size(MuseIconSizes.iconTiny),
                    )
                },
            )
        }
        is ModelTestStatus.Failed -> {
            val text = testStatus.message?.takeIf { it.isNotBlank() }
                ?.let { labelTestFailedFmt.format(it.take(40)) }
                ?: labelTestFailed
            TestStatusChip(
                text = text,
                containerColor = statusColors.errorContainer,
                contentColor = statusColors.error,
                leading = {
                    Icon(
                        imageVector = MuseIcons.x,
                        contentDescription = null,
                        modifier = Modifier.size(MuseIconSizes.iconTiny),
                    )
                },
            )
        }
    }
}

@Composable
private fun TestStatusChip(
    text: String,
    containerColor: Color,
    contentColor: Color,
    leading: @Composable () -> Unit,
) {
    Surface(
        color = containerColor,
        shape = MuseShapes.small,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = MuseSpacing.sm, vertical = MuseSpacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
        ) {
            leading()
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun rememberContextChip(model: Model, labelFormat: String): String? {
    return remember(model.id, model.contextWindow, labelFormat) {
        val ctx = model.contextWindow?.takeIf { it > 0 } ?: return@remember null
        val formatted = when {
            ctx >= 1_000_000 -> "${ctx / 1_000_000}M"
            ctx >= 1000 -> "${ctx / 1000}K"
            else -> ctx.toString()
        }
        labelFormat.format(formatted)
    }
}

@Composable
private fun rememberOutputChip(model: Model, labelFormat: String): String? {
    return remember(model.id, model.maxOutputTokens, labelFormat) {
        val out = model.maxOutputTokens?.takeIf { it > 0 } ?: return@remember null
        val formatted = when {
            out >= 1_000_000 -> "${out / 1_000_000}M"
            out >= 1000 -> "${out / 1000}K"
            else -> out.toString()
        }
        labelFormat.format(formatted)
    }
}
