package io.zer0.muse.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import compose.icons.TablerIcons
import compose.icons.tablericons.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.Model
import io.zer0.muse.R
import io.zer0.ai.core.ProviderType
import io.zer0.muse.ui.theme.MuseShapes

/**
 * v1.0.8 (7.6): 单个模型健康检查状态。
 *
 * - [Idle]: 未测试(默认),不显示状态 chip
 * - [InProgress]: 测试进行中(显示 CircularProgressIndicator + "测试中")
 * - [Success]: 测试通过(显示 ✓ + "健康",可选携带模型回复摘要)
 * - [Failed]: 测试失败(显示 ✗ + "失败",携带错误信息)
 */
sealed class ModelTestStatus {
    data object Idle : ModelTestStatus()
    data object InProgress : ModelTestStatus()
    data class Success(val message: String? = null) : ModelTestStatus()
    data class Failed(val message: String? = null) : ModelTestStatus()
}

/**
 * Provider 详情页模型行组件。
 *
 * 左侧 Provider 小图标,中间模型名 + 能力标签,右侧测试按钮 + 设置/添加按钮。
 *
 * v1.0.8 (7.6): 新增 [onTest] / [testStatus] 用于触发和展示模型健康检查结果。
 * v1.0.8 (7.7): 能力标签新增上下文窗口显示(如 "128K 上下文")。
 */
@Composable
internal fun ProviderModelRow(
    model: Model,
    providerType: ProviderType,
    providerName: String,
    isAdded: Boolean = true,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    // v1.0.8 (7.6): 模型健康检查回调,null 表示不显示测试按钮(如未保存的新增模型)
    onTest: (() -> Unit)? = null,
    testStatus: ModelTestStatus = ModelTestStatus.Idle,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 左侧 Provider 品牌图标(圆形背景)
        val brandColor = providerBrandColor(providerType, providerName)
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(brandColor.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = providerBrandIcon(providerType, providerName),
                contentDescription = null,
                tint = brandColor,
                modifier = Modifier.size(22.dp),
            )
        }

        // 中间:模型名 + 能力标签
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.name.takeIf { it.isNotBlank() } ?: model.id,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(4.dp))
            ModelAbilityChips(model = model, testStatus = testStatus)
        }

        // v1.0.8 (7.6): 测试按钮(可选,仅当 onTest != null 时显示)
        if (onTest != null) {
            val canTest = testStatus !is ModelTestStatus.InProgress
            IconButton(onClick = onTest, enabled = canTest) {
                if (testStatus is ModelTestStatus.InProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = TablerIcons.Gauge,
                        contentDescription = stringResource(R.string.settings_model_action_test),
                        tint = if (canTest) MaterialTheme.colorScheme.outline
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    )
                }
            }
        }

        // 右侧操作按钮
        IconButton(onClick = onAction) {
            Icon(
                imageVector = if (isAdded) TablerIcons.Settings else TablerIcons.Plus,
                contentDescription = if (isAdded) stringResource(R.string.settings_model_action_settings) else stringResource(R.string.settings_model_action_add),
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/**
 * 模型能力标签行。
 *
 * 按顺序显示:工具 / 推理 / 流式 / 画图 / 多模态 / 视频 / 上下文窗口,没有任何能力时默认显示"聊天"。
 *
 * v1.0.8 (7.6): 新增 [testStatus] — 在能力行末尾追加健康检查状态 chip。
 * v1.0.8 (7.7): 新增上下文窗口 chip(如 "128K 上下文"),仅当 contextWindow > 0 时显示。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelAbilityChips(
    model: Model,
    testStatus: ModelTestStatus = ModelTestStatus.Idle,
) {
    val labelTool = stringResource(R.string.settings_model_ability_tool)
    val labelReasoning = stringResource(R.string.settings_model_ability_reasoning)
    val labelStreaming = stringResource(R.string.settings_model_ability_streaming)
    val labelImage = stringResource(R.string.settings_model_ability_image)
    val labelMultimodal = stringResource(R.string.settings_model_ability_multimodal)
    val labelVideo = stringResource(R.string.settings_model_ability_video)
    val labelChat = stringResource(R.string.settings_model_ability_chat)
    val labelContextFmt = stringResource(R.string.settings_model_context_label)
    val labelTestInProgress = stringResource(R.string.settings_model_test_in_progress)
    val labelTestSuccess = stringResource(R.string.settings_model_test_success)
    val labelTestFailed = stringResource(R.string.settings_model_test_failed)
    val labelTestSuccessFmt = stringResource(R.string.settings_model_test_success_with_msg)
    val labelTestFailedFmt = stringResource(R.string.settings_model_test_failed_with_msg)

    // v1.0.8 (7.7): 上下文窗口 chip 文本(格式化为 K/M 后缀,null/0 不显示)
    val contextChip = rememberContextChip(model, labelContextFmt)

    val labels = buildList {
        if (model.supportsToolCalling()) add(labelTool)
        if (model.supportsReasoning()) add(labelReasoning)
        if (model.supportsStreaming) add(labelStreaming)
        if (model.supportsImageOutput()) add(labelImage)
        if (model.supportsVisionInput()) add(labelMultimodal)
        if (model.supportsVideoOutput()) add(labelVideo)
        contextChip?.let { add(it) }
    }.ifEmpty { listOf(labelChat) }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        labels.forEach { label ->
            val (containerColor, contentColor) = when (label) {
                labelTool -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                labelReasoning -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
                labelStreaming -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
                labelImage -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
                labelMultimodal -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
                labelVideo -> MaterialTheme.colorScheme.inverseSurface to MaterialTheme.colorScheme.inverseOnSurface
                labelChat -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
                contextChip -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
            }
            Surface(
                color = containerColor,
                shape = MuseShapes.small,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }

        // v1.0.8 (7.6): 健康检查状态 chip(追加在能力标签末尾)
        when (testStatus) {
            is ModelTestStatus.InProgress -> TestStatusChip(
                text = labelTestInProgress,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                leading = {
                    CircularProgressIndicator(
                        modifier = Modifier.size(10.dp),
                        strokeWidth = 1.5.dp,
                    )
                },
            )
            is ModelTestStatus.Success -> {
                val text = testStatus.message?.takeIf { it.isNotBlank() }
                    ?.let { labelTestSuccessFmt.format(it) }
                    ?: labelTestSuccess
                TestStatusChip(
                    text = text,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    leading = {
                        Icon(
                            imageVector = TablerIcons.Check,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
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
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    leading = {
                        Icon(
                            imageVector = TablerIcons.X,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                        )
                    },
                )
            }
            ModelTestStatus.Idle -> Unit
        }
    }
}

/**
 * v1.0.8 (7.6): 健康检查状态 chip(图标 + 文本)。
 */
@Composable
private fun TestStatusChip(
    text: String,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    leading: @Composable () -> Unit,
) {
    Surface(
        color = containerColor,
        shape = MuseShapes.small,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            leading()
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
            )
        }
    }
}

/**
 * v1.0.8 (7.7): 计算上下文窗口 chip 文本。
 *
 * - 0 或 null → null(不显示)
 * - >= 1M → "1M 上下文"
 * - >= 1K → "128K 上下文"
 * - 其他 → "8192 上下文"
 *
 * 用 [remember] + [derivedStateOf] 避免每次重组都重新格式化。
 */
@Composable
private fun rememberContextChip(model: Model, labelFormat: String): String? {
    return androidx.compose.runtime.remember(model.id, model.contextWindow, labelFormat) {
        val ctx = model.contextWindow?.takeIf { it > 0 } ?: return@remember null
        val formatted = when {
            ctx >= 1_000_000 -> "${ctx / 1_000_000}M"
            ctx >= 1000 -> "${ctx / 1000}K"
            else -> ctx.toString()
        }
        labelFormat.format(formatted)
    }
}
