package io.zer0.muse.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import compose.icons.TablerIcons
import compose.icons.tablericons.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import io.zer0.common.Result
import io.zer0.common.resultOf
import io.zer0.muse.ui.common.form.MuseTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import io.zer0.muse.ui.common.navigation.MuseTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.zer0.ai.ProviderRegistry
import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelAbility
import io.zer0.ai.core.ModelContextWindowRegistry
import io.zer0.ai.core.ModelListCache
import io.zer0.ai.core.ModelRegistry
import io.zer0.ai.core.OAuthConfig
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderSpecificConfig
import io.zer0.ai.core.ProviderType
import io.zer0.common.AppJson
import io.zer0.muse.R
import io.zer0.muse.auth.OAuthManager
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.huge
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.ui.theme.tiny
import io.zer0.muse.data.ProviderCollisionDetector
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.preset.SiliconFlowFreeModels
import io.zer0.ai.core.FreeModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * v1.133: 供应商批量健康检测状态(列表头部"全部检测"按钮触发)。
 *
 * - [Idle]: 未测试(默认),不显示状态 chip
 * - [Testing]: 检测进行中(显示 CircularProgressIndicator)
 * - [Success]: 检测通过(显示延迟 ms,绿色 ✓)
 * - [Failed]: 检测失败(显示错误简述,红色 ✗)
 *
 * 与 [ModelTestStatus] 区分:本状态针对整个 provider 的 /models 端点连通性,
 * 不发真实 chat 消息;且携带延迟数据用于排序/对比。
 */
sealed class ProviderTestStatus {
    data object Idle : ProviderTestStatus()
    data object Testing : ProviderTestStatus()
    data class Success(val latencyMs: Long, val modelCount: Int) : ProviderTestStatus()
    data class Failed(val error: String) : ProviderTestStatus()
}

/**
 * Provider 列表 section — iOS 风格分组列表。
 *
 * 用 [SettingsGroup] 包裹所有 Provider,每个 Provider 一行。
 * 行内左侧品牌图标、中间名称与类型、右侧启用/禁用状态标签 + 右箭头。
 *
 * v1.133: 新增 [testStatuses] / [isTestingAll] / [onTestAll] 用于"全部检测"批量并发
 * 健康检查(参考 [ProviderEditPage] 的 testConnection 单测逻辑,提升为批量并发)。
 */
internal fun LazyListScope.providerListSection(
    providers: List<ProviderConfig>,
    activeProviderId: String?,
    onActivate: (String) -> Unit,
    onEdit: (ProviderConfig) -> Unit,
    onDelete: (String) -> Unit,
    onAddProvider: () -> Unit = {},
    onScanQr: () -> Unit = {},
    // v1.133: 批量健康检测(列表头部"全部检测"按钮触发)
    testStatuses: Map<String, ProviderTestStatus> = emptyMap(),
    isTestingAll: Boolean = false,
    onTestAll: () -> Unit = {},
) {
    item {
        ProviderListHeader(
            totalCount = providers.size,
            isTestingAll = isTestingAll,
            onTestAll = onTestAll,
        )
    }

    // 按 category 分组渲染(官方 / 中转站 / 自定义)
    val officialProviders = providers.filter { it.category == io.zer0.ai.core.ProviderCategory.OFFICIAL }
    val relayProviders = providers.filter { it.category == io.zer0.ai.core.ProviderCategory.RELAY }
    val customProviders = providers.filter { it.category == io.zer0.ai.core.ProviderCategory.CUSTOM }

    if (providers.isEmpty()) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 64.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                MuseEmptyState(
                    icon = TablerIcons.Atom,
                    title = stringResource(R.string.settings_provider_empty_title),
                    subtitle = stringResource(R.string.settings_provider_empty_subtitle),
                    actionText = stringResource(R.string.settings_provider_empty_action),
                    onAction = onAddProvider,
                )
            }
        }
        return
    }

    // P2-1: Provider 冲突检测 — 在列表顶部展示警告卡片(仅当存在重复配置时)
    item { ProviderCollisionWarning(providers) }

    // 官方厂商分组
    if (officialProviders.isNotEmpty()) {
        item { ProviderCategoryHeader(stringResource(R.string.settings_provider_category_official), officialProviders.size) }
        item {
            SettingsGroup {
                officialProviders.forEachIndexed { index, config ->
                    if (index > 0) SettingsGroupDivider()
                    ProviderRow(
                        config = config,
                        isActive = config.id == activeProviderId,
                        onEdit = { onEdit(config) },
                        testStatus = testStatuses[config.id] ?: ProviderTestStatus.Idle,
                    )
                }
            }
        }
    }

    // 中转站分组
    if (relayProviders.isNotEmpty()) {
        item { ProviderCategoryHeader(stringResource(R.string.settings_provider_category_relay), relayProviders.size) }
        item {
            SettingsGroup {
                relayProviders.forEachIndexed { index, config ->
                    if (index > 0) SettingsGroupDivider()
                    ProviderRow(
                        config = config,
                        isActive = config.id == activeProviderId,
                        onEdit = { onEdit(config) },
                        testStatus = testStatuses[config.id] ?: ProviderTestStatus.Idle,
                    )
                }
            }
        }
    }

    // 自定义分组
    if (customProviders.isNotEmpty()) {
        item { ProviderCategoryHeader(stringResource(R.string.settings_provider_category_custom), customProviders.size) }
        item {
            SettingsGroup {
                customProviders.forEachIndexed { index, config ->
                    if (index > 0) SettingsGroupDivider()
                    ProviderRow(
                        config = config,
                        isActive = config.id == activeProviderId,
                        onEdit = { onEdit(config) },
                        testStatus = testStatuses[config.id] ?: ProviderTestStatus.Idle,
                    )
                }
            }
        }
    }

    // "添加 Provider" 入口(始终在列表末尾)
    item {
        SettingsGroup(
            modifier = Modifier.padding(top = MusePaddings.itemGap),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAddProvider() }
                    .padding(horizontal = MusePaddings.screen, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
            ) {
                Icon(
                    imageVector = TablerIcons.Plus,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
                Text(
                    text = stringResource(R.string.settings_provider_add),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
            }
            // v1.97: 扫描二维码导入 Provider
            SettingsGroupDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onScanQr() }
                    .padding(horizontal = MusePaddings.screen, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
            ) {
                Icon(
                    imageVector = TablerIcons.Qrcode,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
                Text(
                    text = stringResource(R.string.qr_scan_btn),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * P2-1: Provider 冲突(重复配置)警告卡片。
 *
 * 调用 [ProviderCollisionDetector.detect] 检测当前 provider 列表中的重复配置,
 * 仅当检测到冲突时渲染警告卡片。卡片样式:
 *  - 形状:[MuseShapes.medium] 圆角
 *  - 背景:MaterialTheme.colorScheme.errorContainer
 *  - 图标:TablerIcons.AlertTriangle(着色 onErrorContainer)
 *  - 文案:provider_collision_warning(带冲突数量 %1$d)
 *
 * 仅提示,不展开详情,不阻断用户操作。
 */
@Composable
private fun ProviderCollisionWarning(providers: List<ProviderConfig>) {
    val collisions = remember(providers) { ProviderCollisionDetector.detect(providers) }
    if (collisions.isEmpty()) return

    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MusePaddings.itemGap),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.iconPadding),
        ) {
            Icon(
                imageVector = TablerIcons.AlertTriangle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
            Text(
                text = stringResource(R.string.provider_collision_warning, collisions.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun ProviderCategoryHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun ProviderRow(
    config: ProviderConfig,
    isActive: Boolean,
    onEdit: () -> Unit,
    // v1.133: 批量健康检测状态(由列表头部"全部检测"按钮触发,写入后行内显示)
    testStatus: ProviderTestStatus = ProviderTestStatus.Idle,
) {
    val brandColor = providerBrandColor(config.type, config.displayName)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() }
            .padding(MusePaddings.cardInner),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
    ) {
        // 左侧:品牌图标(圆形背景)
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(brandColor.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = providerBrandIcon(config.type, config.displayName),
                contentDescription = null,
                tint = brandColor,
                modifier = Modifier.size(MuseIconSizes.icon),
            )
        }

        // 中间:名称 + 类型
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = config.displayName.ifBlank { config.id },
                style = MaterialTheme.typography.bodyLarge,
                color = if (isActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = providerDisplayTypeName(config.type),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        // v1.133: 批量检测结果 chip(测试中显示转圈,成功显示延迟,失败显示错误简述)
        ProviderTestStatusChip(testStatus)

        // 右侧:启用/禁用状态标签 + 右箭头
        if (config.enabled) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MuseShapes.medium,
            ) {
                Text(
                    text = stringResource(R.string.settings_provider_status_enabled),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MuseShapes.medium,
            ) {
                Text(
                    text = stringResource(R.string.settings_provider_status_disabled),
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        Icon(
            imageVector = TablerIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(MuseIconSizes.iconMedium),
        )
    }
}

/**
 * v1.133: 供应商列表头部 — section 标题 + "全部检测"按钮。
 *
 * 替代原 [SectionLabel] 单文本。检测进行中按钮内显示 CircularProgressIndicator,
 * 点击触发 [onTestAll] 并发测试所有已配置的供应商。
 *
 * 参考 UI:
 * ```
 * Row {
 *     Text("Provider 列表")
 *     Spacer(Modifier.weight(1f))
 *     if (testing) { CircularProgressIndicator(...) }
 *     TextButton(onClick = onTestAll) { Text("全部检测") }
 * }
 * ```
 */
@Composable
private fun ProviderListHeader(
    totalCount: Int,
    isTestingAll: Boolean,
    onTestAll: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionLabel(stringResource(R.string.settings_provider_list_title))
        Spacer(Modifier.weight(1f))
        // v1.133: 检测进行中显示转圈(尺寸 14dp,与按钮文字高度匹配)
        if (isTestingAll) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 1.5.dp,
            )
            Spacer(Modifier.width(8.dp))
        }
        TextButton(
            onClick = onTestAll,
            enabled = !isTestingAll && totalCount > 0,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 8.dp,
                vertical = 0.dp,
            ),
        ) {
            Text(
                text = stringResource(R.string.settings_provider_test_all),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * v1.133: 单个供应商检测结果 chip。
 *
 * - [ProviderTestStatus.Idle]: 不渲染(保持行内整洁)
 * - [ProviderTestStatus.Testing]: 小转圈 + "检测中"
 * - [ProviderTestStatus.Success]: 绿色 ✓ + "{latency} ms"(展示延迟)
 * - [ProviderTestStatus.Failed]: 红色 ✗ + "失败"(短文案,详情点入编辑页查看)
 */
@Composable
private fun ProviderTestStatusChip(status: ProviderTestStatus) {
    when (status) {
        ProviderTestStatus.Idle -> Unit
        ProviderTestStatus.Testing -> {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MuseShapes.small,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(10.dp),
                        strokeWidth = 1.5.dp,
                    )
                    Text(
                        text = stringResource(R.string.settings_provider_test_row_testing),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        is ProviderTestStatus.Success -> {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MuseShapes.small,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = TablerIcons.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = stringResource(R.string.settings_provider_test_result_ok, status.latencyMs.toInt()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
        is ProviderTestStatus.Failed -> {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MuseShapes.small,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = TablerIcons.X,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = stringResource(R.string.settings_provider_test_result_failed),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

/** ProviderType 枚举的 Saver(rememberSaveable 不能直接保存枚举,用 name 序列化/反序列化)。 */
private val ProviderTypeSaver = Saver<ProviderType, String>(
    save = { it.name },
    restore = { runCatching { ProviderType.valueOf(it) }.getOrDefault(ProviderType.DEFAULT) },
)

@Composable
internal fun ProviderEditPage(
    config: ProviderConfig,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (ProviderConfig) -> Unit,
    isFromPreset: Boolean = false,
    onDelete: (() -> Unit)? = null,
) {
    var displayName by rememberSaveable { mutableStateOf(config.displayName) }
    var type by rememberSaveable(stateSaver = ProviderTypeSaver) { mutableStateOf(config.type) }
    var baseUrl by rememberSaveable { mutableStateOf(config.baseUrl) }
    var apiKey by rememberSaveable { mutableStateOf(config.apiKey) }
    var apiKeyVisible by rememberSaveable { mutableStateOf(false) }
    // modelsState 为 Model 列表,需自定义 Saver,且旋转后可从 config 重建,保持 remember
    val modelsState = remember { mutableStateListOf<Model>().apply { addAll(config.models) } }
    var enabled by rememberSaveable { mutableStateOf(config.enabled) }
    var balanceApiPath by rememberSaveable { mutableStateOf(config.balanceApiPath) }
    var balanceResultPath by rememberSaveable { mutableStateOf(config.balanceResultPath) }
    var isQueryingBalance by rememberSaveable { mutableStateOf(false) }
    var balanceResult by rememberSaveable { mutableStateOf<String?>(null) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }

    // Vertex AI 配置(仅 GEMINI 类型显示)
    val geminiSpecific = (config.resolvedSpecific() as? io.zer0.ai.core.ProviderSpecificConfig.Gemini)
        ?: io.zer0.ai.core.ProviderSpecificConfig.Gemini()
    var useVertexAi by rememberSaveable { mutableStateOf(geminiSpecific.useVertexAI) }
    var useServiceAccount by rememberSaveable { mutableStateOf(geminiSpecific.useServiceAccount) }
    var serviceAccountEmail by rememberSaveable { mutableStateOf(geminiSpecific.serviceAccountEmail) }
    var privateKey by rememberSaveable { mutableStateOf(geminiSpecific.privateKey) }
    var privateKeyVisible by rememberSaveable { mutableStateOf(false) }
    var vertexLocation by rememberSaveable { mutableStateOf(geminiSpecific.location) }
    var vertexProjectId by rememberSaveable { mutableStateOf(geminiSpecific.projectId) }

    // OpenAI / Anthropic / Custom specific 字段
    val resolvedSpecific = config.resolvedSpecific()
    var openAIChatCompletionsPath by rememberSaveable {
        mutableStateOf(
            (resolvedSpecific as? ProviderSpecificConfig.OpenAI)?.chatCompletionsPath
                ?: ProviderSpecificConfig.OpenAI().chatCompletionsPath,
        )
    }
    var openAIUseResponseApi by rememberSaveable {
        mutableStateOf(
            (resolvedSpecific as? ProviderSpecificConfig.OpenAI)?.useResponseApi
                ?: ProviderSpecificConfig.OpenAI().useResponseApi,
        )
    }
    var openAIIncludeHistoryReasoning by rememberSaveable {
        mutableStateOf(
            (resolvedSpecific as? ProviderSpecificConfig.OpenAI)?.includeHistoryReasoning
                ?: ProviderSpecificConfig.OpenAI().includeHistoryReasoning,
        )
    }
    var openAIEmbeddingsPath by rememberSaveable {
        mutableStateOf(
            (resolvedSpecific as? ProviderSpecificConfig.OpenAI)?.embeddingsPath
                ?: ProviderSpecificConfig.OpenAI().embeddingsPath,
        )
    }
    var openAIImagesPath by rememberSaveable {
        mutableStateOf(
            (resolvedSpecific as? ProviderSpecificConfig.OpenAI)?.imagesPath
                ?: ProviderSpecificConfig.OpenAI().imagesPath,
        )
    }
    var openAIStripModelPrefix by rememberSaveable {
        mutableStateOf(
            (resolvedSpecific as? ProviderSpecificConfig.OpenAI)?.stripModelPrefix
                ?: ProviderSpecificConfig.OpenAI().stripModelPrefix,
        )
    }
    var anthropicPromptCaching by rememberSaveable {
        mutableStateOf(
            (resolvedSpecific as? ProviderSpecificConfig.Anthropic)?.promptCaching
                ?: ProviderSpecificConfig.Anthropic().promptCaching,
        )
    }
    var anthropicPromptCacheTtl by rememberSaveable {
        mutableStateOf(
            (resolvedSpecific as? ProviderSpecificConfig.Anthropic)?.promptCacheTtl
                ?: ProviderSpecificConfig.Anthropic().promptCacheTtl,
        )
    }
    var anthropicMessagesPath by rememberSaveable {
        mutableStateOf(
            (resolvedSpecific as? ProviderSpecificConfig.Anthropic)?.messagesPath
                ?: ProviderSpecificConfig.Anthropic().messagesPath,
        )
    }
    var anthropicModelsPath by rememberSaveable {
        mutableStateOf(
            (resolvedSpecific as? ProviderSpecificConfig.Anthropic)?.modelsPath
                ?: ProviderSpecificConfig.Anthropic().modelsPath,
        )
    }
    var customChatCompletionsPath by rememberSaveable {
        mutableStateOf(
            (resolvedSpecific as? ProviderSpecificConfig.Custom)?.chatCompletionsPath
                ?: ProviderSpecificConfig.Custom().chatCompletionsPath,
        )
    }
    var customHeadersText by rememberSaveable {
        mutableStateOf(
            formatCustomHeaders(
                (resolvedSpecific as? ProviderSpecificConfig.Custom)?.customHeaders ?: emptyMap(),
            ),
        )
    }
    var customBodyText by rememberSaveable {
        mutableStateOf(
            formatCustomBody(
                (resolvedSpecific as? ProviderSpecificConfig.Custom)?.customBody ?: emptyMap(),
            ),
        )
    }

    // 拉取上游模型状态(fetchedModels 为 List<Model>,需自定义 Saver,保持 remember)
    var isFetchingModels by rememberSaveable { mutableStateOf(false) }
    var fetchError by rememberSaveable { mutableStateOf<String?>(null) }
    var fetchedModels by remember { mutableStateOf<List<Model>>(emptyList()) }
    var showModelsPicker by rememberSaveable { mutableStateOf(false) }
    // P2-2: 上游模型差异 Sheet 显示状态(拉取成功后展示,让用户决定是否合并变更)
    var showModelDiffSheet by remember { mutableStateOf(false) }

    // 底部 Tab 选中项:0=配置,1=模型
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    // 添加新模型对话框
    var showAddModelDialog by rememberSaveable { mutableStateOf(false) }

    // 删除确认
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }

    // 连接测试状态(v2.4: 独立测试,只测不改,不写入 modelsState)
    // - isTestingConnection: 测试进行中(显示 CircularProgressIndicator)
    // - testConnectionResult: 测试成功结果(含模型数量,显示绿色 ✓)
    // - testConnectionError: 测试失败错误信息(分级显示,红色 ✗)
    var isTestingConnection by rememberSaveable { mutableStateOf(false) }
    var testConnectionResult by rememberSaveable { mutableStateOf<String?>(null) }
    var testConnectionError by rememberSaveable { mutableStateOf<String?>(null) }

    // v1.0.8 (7.6): 单个模型健康检查状态(按 model.id 索引)
    //  - 进入页面时为空(Idle),点击测试按钮后变为 InProgress,完成后变 Success/Failed
    //  - 不持久化(remember 而非 rememberSaveable),退出页面后丢失,符合"即时反馈"语义
    var modelTestStatuses by remember { mutableStateOf<Map<String, ModelTestStatus>>(emptyMap()) }

    // 从 Google Service Account JSON 文件导入 Vertex AI 凭证
    val context = LocalContext.current
    val ioScope = rememberCoroutineScope()
    val settingsRepo: SettingsRepository = koinInject()
    // v2.4: 先检查缓存(5分钟TTL,仅对当前配置有效),命中后按成功/失败分发到对应状态
    LaunchedEffect(Unit) {
        val cached = settingsRepo.getCachedConnectionTest(config.id)
        if (cached != null) {
            if (cached.isSuccess) {
                testConnectionResult = cached.result
                testConnectionError = null
            } else {
                testConnectionError = cached.result
                testConnectionResult = null
            }
        }
    }
    val serviceAccountJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        ioScope.launch {
            resultOf {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        // v1.114: 限制读取 10MB,防止超大文件 OOM
                        val MAX_READ_BYTES = 10L * 1024 * 1024
                        val sb = StringBuilder()
                        val buffer = CharArray(8192)
                        var total = 0L
                        BufferedReader(InputStreamReader(stream)).use { reader ->
                            while (true) {
                                val read = reader.read(buffer)
                                if (read <= 0) break
                                total += read
                                if (total > MAX_READ_BYTES) {
                                    error("文件过大,超过 ${MAX_READ_BYTES / 1024 / 1024}MB 限制")
                                }
                                sb.append(buffer, 0, read)
                            }
                        }
                        val text = sb.toString()
                        parseServiceAccountJson(text)
                    }
                }
            }.onSuccess { parsed ->
                if (parsed == null) {
                    fetchError = context.getString(R.string.settings_provider_json_parse_failed)
                    return@onSuccess
                }
                fetchError = null
                serviceAccountEmail = parsed.first
                privateKey = parsed.second
                vertexProjectId = parsed.third
                MuseToast.show(context.getString(R.string.settings_provider_imported_from_json))
            }.onError { msg, t ->
                fetchError = context.getString(R.string.settings_provider_import_failed, msg)
            }
        }
    }

    // 拉取上游模型业务逻辑
    // v1.132 优化(参考 rikkahub/kelivo/openhanako):
    //  - 缓存优先:5 分钟 TTL,命中直接返回,跳过网络(参考 openhanako 的文件级缓存)
    //  - URL 多策略补全:base → base+/v1 → 剥离尾部 /v1 后重试(覆盖用户漏填/多填场景)
    //  - 错误分级:401/403 立即报错不 fallback(凭证问题不掩盖,参考 openhanako)
    //  - 手动刷新按钮可绕过缓存(forceFresh = true)
    val fetchModels: (Boolean) -> Unit = { forceFresh ->
        if (apiKey.isBlank()) {
            fetchError = context.getString(R.string.settings_provider_fill_api_key_first)
        } else {
            val tempSpecific = if (type == ProviderType.GEMINI) {
                io.zer0.ai.core.ProviderSpecificConfig.Gemini(
                    useVertexAI = useVertexAi,
                    useServiceAccount = useServiceAccount,
                    serviceAccountEmail = serviceAccountEmail.trim(),
                    privateKey = privateKey.trim(),
                    location = vertexLocation.trim().ifBlank { "us-central1" },
                    projectId = vertexProjectId.trim(),
                )
            } else null
            val tempConfig = ProviderConfig(
                id = config.id,
                displayName = displayName,
                type = type,
                baseUrl = baseUrl.trim(),
                apiKey = apiKey.trim(),
                specific = tempSpecific,
            )
            isFetchingModels = true
            fetchError = null
            ioScope.launch {
                // v1.132: 缓存优先(5 分钟 TTL)
                if (!forceFresh) {
                    val cached = ModelListCache.get(tempConfig, forceFresh = false)
                    if (cached != null) {
                        isFetchingModels = false
                        if (cached.isEmpty()) {
                            fetchError = context.getString(R.string.settings_provider_no_models_returned)
                        } else {
                            val enriched = cached.map { ModelRegistry.enrich(it) }
                            // P2-2: 不直接覆盖 modelsState,改为透明展示差异让用户决定
                            fetchedModels = enriched
                            showModelDiffSheet = true
                            fetchError = null
                        }
                        return@launch
                    }
                }

                // v1.132: URL 多策略补全 — 覆盖用户漏填/多填 /v1 场景
                val base = tempConfig.baseUrl.trimEnd('/')
                val urlsToTry = mutableListOf<String>()
                if (base.isNotBlank()) {
                    urlsToTry.add(base)
                    if (!base.endsWith("/v1") && !base.endsWith("/v1beta")) {
                        urlsToTry.add("$base/v1")
                    } else if (base.endsWith("/v1")) {
                        // 用户填了 /v1 但可能上游实际不需要,也尝试剥掉
                        urlsToTry.add(base.removeSuffix("/v1"))
                    }
                } else {
                    // Base URL 为空时使用默认端点
                    urlsToTry.add(tempConfig.baseUrl)
                }

                var lastError: Throwable? = null
                var lastHttpCode: Int? = null
                for (url in urlsToTry) {
                    val result = resultOf {
                        withContext(Dispatchers.IO) {
                            ProviderRegistry.create(tempConfig.copy(baseUrl = url))
                                .listModels(tempConfig.copy(baseUrl = url))
                        }
                    }
                    if (result.isSuccess) {
                        isFetchingModels = false
                        result.getOrThrow().let { models ->
                            if (models.isEmpty()) {
                                fetchError = context.getString(R.string.settings_provider_no_models_returned)
                            } else {
                                // v1.97: 对上游返回的模型逐个 enrich(自动推导
                                // abilities/modalities/contextWindow),保留 Provider 已声明的字段
                                val enriched = models.map { ModelRegistry.enrich(it) }
                                // v1.132: 写入缓存(用最终成功的 url 对应的 config)
                                ModelListCache.put(tempConfig.copy(baseUrl = url), models)
                                // P2-2: 不直接覆盖 modelsState,改为透明展示差异让用户决定
                                fetchedModels = enriched
                                showModelDiffSheet = true
                                fetchError = null
                            }
                        }
                        return@launch
                    } else {
                        lastError = (result as Result.Error).throwable
                        // v1.132: 401/403 立即报错,不 fallback(凭证问题)
                        val msg = lastError?.message.orEmpty()
                        if (msg.contains("401") || msg.contains("403")) {
                            lastHttpCode = if (msg.contains("401")) 401 else 403
                            break
                        }
                    }
                }

                // 全部失败
                isFetchingModels = false
                val msg = lastError?.message ?: context.getString(R.string.settings_provider_unknown_error)
                fetchError = when {
                    msg.contains("404") -> context.getString(R.string.settings_provider_404_error)
                    msg.contains("401") || msg.contains("403") -> context.getString(R.string.settings_provider_auth_error)
                    msg.contains("Unable to resolve") || msg.contains("UnknownHost") -> context.getString(R.string.settings_provider_unable_to_resolve)
                    msg.contains("timeout", ignoreCase = true) -> context.getString(R.string.settings_provider_connection_timeout)
                    else -> context.getString(R.string.settings_provider_fetch_failed, msg.take(200))
                }
            }
        }
    }

    // v1.32: 进入编辑页就自动拉取上游模型(不等到切到模型 Tab)
    // v1.132: 改为走缓存(forceFresh=false),5 分钟内重复进入不重复打网络
    LaunchedEffect(Unit) {
        if (modelsState.isEmpty() && apiKey.isNotBlank() && !isFetchingModels) {
            fetchModels(false)
        }
    }

    // v2.4: 独立测试连接业务逻辑(参考 rikkahub/kelivo/openhanako)
    //  - 与 fetchModels 区分:只调用 ProviderRegistry.create().listModels() 做一次轻量测试,
    //    不写入 modelsState,不写 ModelListCache,只显示结果
    //  - 错误分级:401/403 → API Key 无效,404 → URL 不支持,timeout → 连接超时,
    //    UnknownHost → 无法连接服务器(短消息,与 fetchModels 的详细错误区分)
    //  - 成功显示模型数量(连接正常 · N 个模型)
    //  - 用 ioScope.launch 协程执行,避免阻塞 UI
    val testConnection: () -> Unit = {
        val tempSpecific = if (type == ProviderType.GEMINI) {
            io.zer0.ai.core.ProviderSpecificConfig.Gemini(
                useVertexAI = useVertexAi,
                useServiceAccount = useServiceAccount,
                serviceAccountEmail = serviceAccountEmail.trim(),
                privateKey = privateKey.trim(),
                location = vertexLocation.trim().ifBlank { "us-central1" },
                projectId = vertexProjectId.trim(),
            )
        } else null
        val tempConfig = ProviderConfig(
            id = config.id,
            displayName = displayName,
            type = type,
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim(),
            specific = tempSpecific,
        )
        isTestingConnection = true
        testConnectionResult = null
        testConnectionError = null
        ioScope.launch {
            val (result, isSuccess) = withContext(Dispatchers.IO) {
                val r = resultOf {
                    // 轻量测试:仅调用 listModels 验证 baseUrl + apiKey 是否可达
                    val models = ProviderRegistry.create(tempConfig).listModels(tempConfig)
                    context.getString(R.string.settings_provider_test_success_count, models.size) to true
                }
                if (r.isSuccess) {
                    r.getOrThrow()
                } else {
                    // 错误分级(按 task spec 短消息展示)
                    val msg = (r as Result.Error).throwable?.message.orEmpty()
                    val classified = when {
                        msg.contains("401") || msg.contains("403") ->
                            context.getString(R.string.settings_provider_test_error_auth)
                        msg.contains("404") ->
                            context.getString(R.string.settings_provider_test_error_404)
                        msg.contains("Unable to resolve", ignoreCase = true) ||
                            msg.contains("UnknownHost", ignoreCase = true) ->
                            context.getString(R.string.settings_provider_test_error_unknown_host)
                        msg.contains("timeout", ignoreCase = true) ->
                            context.getString(R.string.settings_provider_test_error_timeout)
                        else -> context.getString(
                            R.string.settings_provider_test_error_failed,
                            msg.take(80),
                        )
                    }
                    classified to false
                }
            }
            if (isSuccess) {
                testConnectionResult = result
                testConnectionError = null
            } else {
                testConnectionError = result
                testConnectionResult = null
            }
            isTestingConnection = false
            // v2.4: 缓存测试结果(5 分钟 TTL,下次进入页面直接显示)
            ioScope.launch {
                settingsRepo.saveConnectionTestCache(tempConfig.id, result, isSuccess)
            }
        }
    }

    // 构造当前表单对应的临时 ProviderConfig
    fun buildTempConfig(): ProviderConfig {
        val isCustom = config.resolvedSpecific() is ProviderSpecificConfig.Custom
        val tempSpecific = when (type) {
            ProviderType.GEMINI -> ProviderSpecificConfig.Gemini(
                useVertexAI = useVertexAi,
                useServiceAccount = useServiceAccount,
                serviceAccountEmail = serviceAccountEmail.trim(),
                privateKey = privateKey.trim(),
                location = vertexLocation.trim().ifBlank { "us-central1" },
                projectId = vertexProjectId.trim(),
            )
            ProviderType.OPENAI, ProviderType.OPENAI_RESPONSES -> if (isCustom) {
                ProviderSpecificConfig.Custom(
                    chatCompletionsPath = customChatCompletionsPath.trim(),
                    customHeaders = parseCustomHeaders(customHeadersText),
                    customBody = parseCustomBody(customBodyText),
                )
            } else {
                ProviderSpecificConfig.OpenAI(
                    chatCompletionsPath = openAIChatCompletionsPath.trim(),
                    useResponseApi = openAIUseResponseApi,
                    includeHistoryReasoning = openAIIncludeHistoryReasoning,
                    embeddingsPath = openAIEmbeddingsPath.trim(),
                    imagesPath = openAIImagesPath.trim(),
                    stripModelPrefix = openAIStripModelPrefix.trim(),
                )
            }
            ProviderType.ANTHROPIC -> ProviderSpecificConfig.Anthropic(
                promptCaching = anthropicPromptCaching,
                promptCacheTtl = anthropicPromptCacheTtl.trim().ifBlank { "5m" },
                messagesPath = anthropicMessagesPath.trim(),
                modelsPath = anthropicModelsPath.trim(),
            )
        }
        return ProviderConfig(
            id = config.id,
            displayName = displayName,
            type = type,
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim(),
            specific = tempSpecific,
            balanceApiPath = balanceApiPath.trim(),
            balanceResultPath = balanceResultPath.trim(),
        )
    }

    // v1.0.8 (7.6): 单个模型健康检查 — 用真实 LLM 调用验证 model + apiKey + baseUrl 是否可用
    //  - 复用 buildTempConfig() 构造当前表单对应的临时配置(用户未保存的 apiKey/baseUrl 也能测)
    //  - 调用 Provider.healthCheck(model) 发送 "Reply exactly OK." 测试消息,15s 超时
    //  - 状态写入 modelTestStatuses(按 model.id 索引),ProviderModelRow 据此显示状态 chip
    //  - 与 testConnection 区别:testConnection 只测 /models 端点(轻量),testModel 验证完整 chat 链路
    fun testModel(model: io.zer0.ai.core.Model) {
        if (apiKey.isBlank()) {
            modelTestStatuses = modelTestStatuses + (model.id to ModelTestStatus.Failed("API Key 为空"))
            return
        }
        // 置为 InProgress,禁用测试按钮
        modelTestStatuses = modelTestStatuses + (model.id to ModelTestStatus.InProgress)
        val tempConfig = buildTempConfig()
        ioScope.launch {
            val result = withContext(Dispatchers.IO) {
                val r = resultOf {
                    io.zer0.ai.ProviderRegistry.create(tempConfig).healthCheck(model)
                }
                if (r.isSuccess) {
                    r.getOrThrow()
                } else {
                    io.zer0.ai.core.HealthCheckResult(
                        success = false,
                        message = (r as Result.Error).throwable?.message?.take(120),
                    )
                }
            }
            val status: ModelTestStatus = if (result.success) {
                ModelTestStatus.Success(result.message)
            } else {
                ModelTestStatus.Failed(result.message)
            }
            modelTestStatuses = modelTestStatuses + (model.id to status)
        }
    }

    // 查询余额业务逻辑
    fun queryBalance(tempConfig: ProviderConfig) {
        if (tempConfig.balanceApiPath.isBlank()) {
            balanceResult = context.getString(R.string.settings_provider_configure_balance_first)
            return
        }
        isQueryingBalance = true
        balanceResult = null
        ioScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .build()
                    val url = tempConfig.baseUrl.trimEnd('/') + "/" + tempConfig.balanceApiPath.trimStart('/')
                    val request = Request.Builder()
                        .url(url)
                        .apply {
                            if (tempConfig.apiKey.isNotBlank()) {
                                header("Authorization", "Bearer ${tempConfig.apiKey}")
                            }
                        }
                        .get()
                        .build()
                    client.newCall(request).execute().use { response ->
                        val body = response.body.string()
                        if (!response.isSuccessful) {
                            return@use context.getString(R.string.settings_provider_balance_query_failed_http, response.code, body.take(120))
                        }
                        if (tempConfig.balanceResultPath.isBlank()) {
                            return@use context.getString(R.string.settings_provider_balance_response, body.take(500))
                        }
                        val value = extractJsonPath(body, tempConfig.balanceResultPath)
                        if (value != null) {
                            context.getString(R.string.settings_provider_balance_result, value)
                        } else {
                            context.getString(R.string.settings_provider_balance_path_not_found, tempConfig.balanceResultPath, body.take(300))
                        }
                    }
                }.getOrElse { e ->
                    when {
                        e.message?.contains("Unable to resolve", ignoreCase = true) == true ||
                            e.message?.contains("UnknownHost", ignoreCase = true) == true ->
                            context.getString(R.string.settings_provider_unable_to_resolve_server)
                        e.message?.contains("timeout", ignoreCase = true) == true ->
                            context.getString(R.string.settings_provider_query_timeout)
                        else -> context.getString(R.string.settings_provider_query_failed, e.message?.take(80) ?: "")
                    }
                }
            }
            balanceResult = result
            isQueryingBalance = false
        }
    }

    /**
     * v1.97: 从当前编辑状态构建 ProviderConfig(供保存与二维码分享复用)。
     */
    fun buildCurrentConfig(): ProviderConfig {
        val parsedModels = if (modelsState.isEmpty() && fetchedModels.isNotEmpty()) {
            fetchedModels.toList()
        } else {
            modelsState.toList()
        }
        val isCustom = config.resolvedSpecific() is ProviderSpecificConfig.Custom
        val newSpecific = when (type) {
            ProviderType.GEMINI -> ProviderSpecificConfig.Gemini(
                useVertexAI = useVertexAi,
                useServiceAccount = useServiceAccount,
                serviceAccountEmail = serviceAccountEmail.trim(),
                privateKey = privateKey.trim(),
                location = vertexLocation.trim().ifBlank { "us-central1" },
                projectId = vertexProjectId.trim(),
            )
            ProviderType.OPENAI, ProviderType.OPENAI_RESPONSES -> if (isCustom) {
                ProviderSpecificConfig.Custom(
                    chatCompletionsPath = customChatCompletionsPath.trim(),
                    customHeaders = parseCustomHeaders(customHeadersText),
                    customBody = parseCustomBody(customBodyText),
                )
            } else {
                ProviderSpecificConfig.OpenAI(
                    chatCompletionsPath = openAIChatCompletionsPath.trim(),
                    useResponseApi = openAIUseResponseApi,
                    includeHistoryReasoning = openAIIncludeHistoryReasoning,
                    embeddingsPath = openAIEmbeddingsPath.trim(),
                    imagesPath = openAIImagesPath.trim(),
                    stripModelPrefix = openAIStripModelPrefix.trim(),
                )
            }
            ProviderType.ANTHROPIC -> ProviderSpecificConfig.Anthropic(
                promptCaching = anthropicPromptCaching,
                promptCacheTtl = anthropicPromptCacheTtl.trim().ifBlank { "5m" },
                messagesPath = anthropicMessagesPath.trim(),
                modelsPath = anthropicModelsPath.trim(),
            )
        }
        val savedCategory = if (isNew && !isFromPreset) {
            io.zer0.ai.core.ProviderCategory.CUSTOM
        } else {
            config.category
        }
        return config.copy(
            displayName = displayName.ifBlank { type.name },
            type = type,
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim(),
            models = parsedModels,
            specific = newSpecific,
            enabled = enabled,
            balanceApiPath = balanceApiPath.trim(),
            balanceResultPath = balanceResultPath.trim(),
            category = savedCategory,
        )
    }

    // 保存业务逻辑
    val save: () -> Unit = {
        onSave(buildCurrentConfig())
    }

    // ── P1-6: OAuth 登录 ──────────────────────────────────────────────
    // P2-11: OAuthManager 已在 MuseApp.onCreate 中注入 SecureCredentialStore,
    // 这里直接通过 OAuthManager 间接访问(读取 / 刷新 / 撤销)。
    // 观察 OAuthManager 状态流,驱动弹窗 / 自动填入 apiKey / 错误提示
    val oauthState by OAuthManager.stateFlow.collectAsState()
    // Device Flow 的 user_code 弹窗状态(userCode, verificationUri)
    var showDeviceCodeDialog by remember { mutableStateOf(false) }
    var deviceCodeInfo by remember { mutableStateOf<Pair<String, String>?>(null) }
    // P2-11: 是否已有已存储的 OAuth token(控制「撤销访问」按钮显示)
    var hasStoredOAuthToken by remember { mutableStateOf(false) }
    // P2-11: 「撤销访问」确认弹窗
    var showRevokeConfirm by remember { mutableStateOf(false) }

    // P2-11: 进入页面时优先从 SecureCredentialStore 读取已存储 token,
    // 未过期直接复用,过期则自动 refresh,失败则提示用户重新登录。
    LaunchedEffect(config.id, config.oauthConfig) {
        if (config.oauthConfig == null) return@LaunchedEffect
        val stored = OAuthManager.getStoredToken(config.id) ?: return@LaunchedEffect
        hasStoredOAuthToken = true
        val now = System.currentTimeMillis()
        val bufferMs = 60_000L // 60s 提前刷新窗口,与 OAuthManager.REFRESH_BUFFER_SECONDS 对齐
        if (stored.expiresAt == 0L || stored.expiresAt > now + bufferMs) {
            // 未过期,直接填入 apiKey 输入框
            if (stored.accessToken.isNotBlank() && apiKey.isBlank()) {
                apiKey = stored.accessToken
                MuseToast.show(context.getString(R.string.oauth_stored))
            }
        } else {
            // 过期,尝试自动刷新
            OAuthManager.refreshTokenIfNeeded(config.id)
                .onSuccess { newToken ->
                    apiKey = newToken
                    MuseToast.show(context.getString(R.string.oauth_token_refreshed))
                }
                .onFailure {
                    hasStoredOAuthToken = false
                    MuseToast.show(context.getString(R.string.oauth_token_expired))
                }
        }
    }

    LaunchedEffect(oauthState) {
        when (val s = oauthState) {
            is OAuthManager.State.SUCCESS -> {
                apiKey = s.apiKey
                hasStoredOAuthToken = true
                MuseToast.show(context.getString(R.string.oauth_login_success))
                MuseToast.show(context.getString(R.string.oauth_stored))
                OAuthManager.resetState()
                deviceCodeInfo = null
                showDeviceCodeDialog = false
            }
            is OAuthManager.State.ERROR -> {
                MuseToast.show(context.getString(R.string.oauth_login_failed, s.message))
                OAuthManager.resetState()
                deviceCodeInfo = null
                showDeviceCodeDialog = false
            }
            is OAuthManager.State.AWAITING_USER -> {
                // Device Flow 携带 userCode,弹窗显示;Auth Code Flow 的 userCode 为空,由 ConfigTab 内联显示
                if (s.userCode.isNotBlank()) {
                    deviceCodeInfo = s.userCode to s.verificationUri
                    showDeviceCodeDialog = true
                }
            }
            else -> { /* IDLE / POLLING: 由 ConfigTab 内联显示加载状态 */ }
        }
    }
    // 离开页面时取消进行中的 OAuth 流程,避免状态泄漏到下次进入
    DisposableEffect(Unit) {
        onDispose { OAuthManager.cancel() }
    }
    // OAuth 登录入口:Device Flow 优先,降级 Auth Code Flow
    // P2-11: 透传 providerId = config.id,登录成功后由 OAuthManager 自动持久化 TokenBundle
    val onOAuthLogin: () -> Unit = lambda@{
        val oauthConfig = config.oauthConfig ?: run {
            MuseToast.show(context.getString(R.string.oauth_login_failed, "OAuth 未配置"))
            return@lambda
        }
        if (oauthConfig.deviceCodeUrl.isNullOrBlank()) {
            // Auth Code Flow 需要 Activity 上下文
            val activity = context as? Activity ?: run {
                MuseToast.show(context.getString(R.string.oauth_login_failed, "无法获取 Activity 上下文"))
                return@lambda
            }
            ioScope.launch { OAuthManager.launchAuthorizationCodeFlow(activity, oauthConfig, config.id) }
        } else {
            ioScope.launch { OAuthManager.launchDeviceFlow(oauthConfig, config.id) }
        }
    }
    // P2-11: 撤销访问 — 删除 SecureCredentialStore 中的 token + 清空 apiKey 输入框
    val onRevokeOAuth: () -> Unit = {
        showRevokeConfirm = true
    }
    val isOAuthLoading = oauthState is OAuthManager.State.AWAITING_USER ||
        oauthState is OAuthManager.State.POLLING


    // v1.35: 改用普通全屏 Scaffold(不再用 Dialog),系统导航栏 inset 正确传递
    // v1.48: BackHandler 检查未保存修改,避免误退丢失编辑
    var showDiscardConfirm by remember { mutableStateOf(false) }
    // v1.97: 二维码分享弹窗
    var showQrShareDialog by remember { mutableStateOf(false) }
    // v1.134 P1-2: 用 buildCurrentConfig() == config 全字段比对,
    // 覆盖 specific / Vertex / OpenAI / Anthropic / Custom / balanceApiPath 等高级字段。
    val hasUnsavedChanges by remember {
        derivedStateOf { buildCurrentConfig() != config }
    }
    BackHandler {
        if (hasUnsavedChanges) showDiscardConfirm = true else onDismiss()
    }
    if (showDiscardConfirm) {
        MuseDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = stringResource(R.string.settings_provider_discard_changes_title),
            content = { Text(stringResource(R.string.settings_provider_discard_changes_content)) },
            confirmText = stringResource(R.string.settings_provider_discard),
            onConfirm = {
                showDiscardConfirm = false
                onDismiss()
            },
            destructive = true,
        )
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            MuseTopBar(
                title = displayName.ifBlank {
                    if (isNew) stringResource(R.string.settings_provider_new) else config.displayName.ifBlank { config.id }
                },
                onBack = onDismiss,
                actions = {
                    // v1.97: 分享二维码按钮(仅在已存在的 Provider 显示,新建时不显示)
                    if (!isNew) {
                        MuseTactileButton(
                            icon = TablerIcons.Qrcode,
                            onClick = { showQrShareDialog = true },
                            contentDescription = stringResource(R.string.qr_share_btn),
                        )
                    }
                    // v2.4: 测试连接按钮已移至 ConfigTab(紧邻 baseUrl/apiKey 输入框,
                    // 结果胶囊就近展示,符合 iOS 设置页"操作就近反馈"风格)
                    // v1.134 P0-2: 保存按钮用 Surface+clickable 胶囊(避免 Material3 默认 Button)
                    Surface(
                        shape = MuseShapes.pill,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        onClick = save,
                        modifier = Modifier,
                    ) {
                        Text(
                            text = stringResource(R.string.settings_common_save),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
                        )
                    }
                },
            )
        },
            bottomBar = {
                // 底部悬浮操作栏
                ProviderEditBottomBar(
                    canFetch = !isFetchingModels && apiKey.isNotBlank(),
                    isFetching = isFetchingModels,
                    canDelete = onDelete != null && !isNew && !config.builtIn,
                    onFetch = fetchModels,
                    onAddModel = { showAddModelDialog = true },
                    onDelete = { showDeleteConfirm = true },
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // v1.125: 移除多余的 imePadding(),Scaffold 的 innerPadding 已包含 IME 偏移,
                    // 双重 imePadding 会把整个内容区推得过高,导致输入框被键盘遮挡。
                    .padding(innerPadding),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // 顶部胶囊 Tab 切换器(参考首页风格)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            shape = MuseShapes.extraLarge,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier
                                .widthIn(min = 200.dp)
                                .height(36.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize().padding(3.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val tabs = listOf(stringResource(R.string.settings_provider_tab_config) to 0, stringResource(R.string.settings_provider_tab_models) to 1)
                                tabs.forEach { (label, page) ->
                                    val isSelected = selectedTab == page
                                    val bgColor = if (isSelected) MaterialTheme.colorScheme.surface
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0f)
                                    val textColor = if (isSelected) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                    Surface(
                                        shape = MuseShapes.semiLarge,
                                        color = bgColor,
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .semantics { contentDescription = "$label Tab" }
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                                onClick = { selectedTab = page },
                                            ),
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.fillMaxSize(),
                                        ) {
                                            Text(
                                                text = label,
                                                style = MaterialTheme.typography.labelLarge,
                                                color = textColor,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    when (selectedTab) {
                    0 -> ConfigTab(
                        displayName = displayName,
                        onDisplayNameChange = { displayName = it },
                        type = type,
                        onTypeChange = { type = it },
                        isCustomSpecific = config.resolvedSpecific() is ProviderSpecificConfig.Custom,
                        baseUrl = baseUrl,
                        onBaseUrlChange = { baseUrl = it },
                        apiKey = apiKey,
                        onApiKeyChange = { apiKey = it },
                        apiKeyVisible = apiKeyVisible,
                        onApiKeyVisibleChange = { apiKeyVisible = it },
                        enabled = enabled,
                        onEnabledChange = { enabled = it },
                        balanceApiPath = balanceApiPath,
                        onBalanceApiPathChange = { balanceApiPath = it },
                        balanceResultPath = balanceResultPath,
                        onBalanceResultPathChange = { balanceResultPath = it },
                        showAdvanced = showAdvanced,
                        onShowAdvancedChange = { showAdvanced = it },
                        useVertexAi = useVertexAi,
                        onUseVertexAiChange = { useVertexAi = it },
                        useServiceAccount = useServiceAccount,
                        onUseServiceAccountChange = { useServiceAccount = it },
                        serviceAccountEmail = serviceAccountEmail,
                        onServiceAccountEmailChange = { serviceAccountEmail = it },
                        privateKey = privateKey,
                        onPrivateKeyChange = { privateKey = it },
                        privateKeyVisible = privateKeyVisible,
                        onPrivateKeyVisibleChange = { privateKeyVisible = it },
                        vertexLocation = vertexLocation,
                        onVertexLocationChange = { vertexLocation = it },
                        vertexProjectId = vertexProjectId,
                        onVertexProjectIdChange = { vertexProjectId = it },
                        openAIChatCompletionsPath = openAIChatCompletionsPath,
                        onOpenAIChatCompletionsPathChange = { openAIChatCompletionsPath = it },
                        openAIUseResponseApi = openAIUseResponseApi,
                        onOpenAIUseResponseApiChange = { openAIUseResponseApi = it },
                        openAIIncludeHistoryReasoning = openAIIncludeHistoryReasoning,
                        onOpenAIIncludeHistoryReasoningChange = { openAIIncludeHistoryReasoning = it },
                        openAIEmbeddingsPath = openAIEmbeddingsPath,
                        onOpenAIEmbeddingsPathChange = { openAIEmbeddingsPath = it },
                        openAIImagesPath = openAIImagesPath,
                        onOpenAIImagesPathChange = { openAIImagesPath = it },
                        openAIStripModelPrefix = openAIStripModelPrefix,
                        onOpenAIStripModelPrefixChange = { openAIStripModelPrefix = it },
                        anthropicPromptCaching = anthropicPromptCaching,
                        onAnthropicPromptCachingChange = { anthropicPromptCaching = it },
                        anthropicPromptCacheTtl = anthropicPromptCacheTtl,
                        onAnthropicPromptCacheTtlChange = { anthropicPromptCacheTtl = it },
                        anthropicMessagesPath = anthropicMessagesPath,
                        onAnthropicMessagesPathChange = { anthropicMessagesPath = it },
                        anthropicModelsPath = anthropicModelsPath,
                        onAnthropicModelsPathChange = { anthropicModelsPath = it },
                        customChatCompletionsPath = customChatCompletionsPath,
                        onCustomChatCompletionsPathChange = { customChatCompletionsPath = it },
                        customHeadersText = customHeadersText,
                        onCustomHeadersTextChange = { customHeadersText = it },
                        customBodyText = customBodyText,
                        onCustomBodyTextChange = { customBodyText = it },
                        fetchError = fetchError,
                        onFetchErrorDismiss = { fetchError = null },
                        isTestingConnection = isTestingConnection,
                        testConnectionResult = testConnectionResult,
                        testConnectionError = testConnectionError,
                        canTestConnection = !isTestingConnection && baseUrl.isNotBlank(),
                        onTestConnection = testConnection,
                        onTestResultDismiss = {
                            testConnectionResult = null
                            testConnectionError = null
                        },
                        isQueryingBalance = isQueryingBalance,
                        balanceResult = balanceResult,
                        onQueryBalance = { queryBalance(buildTempConfig()) },
                        oauthConfig = config.oauthConfig,
                        onOAuthLogin = onOAuthLogin,
                        isOAuthLoading = isOAuthLoading,
                        // P2-11: 透传已存储 token 状态 + 撤销回调
                        hasStoredOAuthToken = hasStoredOAuthToken,
                        onRevokeOAuth = onRevokeOAuth,
                        onImportServiceAccountJson = {
                            runCatching {
                                serviceAccountJsonLauncher.launch(arrayOf("application/json"))
                            }.onFailure {
                                fetchError = context.getString(R.string.settings_provider_file_picker_failed, it.message ?: "")
                            }
                        },
                    )

                    1 -> ModelsTab(
                        config = config,
                        modelsState = modelsState,
                        apiKey = apiKey,
                        isFetching = isFetchingModels,
                        onFetch = fetchModels,
                        onAddModel = { showAddModelDialog = true },
                        // P2-5: 透传 baseUrl 给 ModelsTab,用于判定是否展示「一键填入免费模型」按钮
                        baseUrl = baseUrl,
                        // v1.0.8 (7.6): 透传模型健康检查回调 + 状态 Map
                        onTestModel = { testModel(it) },
                        modelTestStatuses = modelTestStatuses,
                    )
                }
                }
            }
        }

    // 添加新模型对话框
    // v1.97: 添加时通过 ModelRegistry 自动推导 abilities/modalities/contextWindow
    if (showAddModelDialog) {
        AddModelDialog(
            onDismiss = { showAddModelDialog = false },
            onConfirm = { modelId ->
                if (modelsState.none { it.id == modelId }) {
                    val raw = Model(id = modelId, name = modelId, providerId = config.id)
                    val enriched = ModelRegistry.enrich(raw)
                    modelsState.add(enriched)
                }
                showAddModelDialog = false
            },
        )
    }

    // 删除确认对话框
    if (showDeleteConfirm && onDelete != null) {
        MuseDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = stringResource(R.string.settings_provider_delete_title),
            content = { Text(stringResource(R.string.settings_provider_delete_content, config.displayName.ifBlank { config.id })) },
            confirmText = stringResource(R.string.settings_common_delete),
            onConfirm = {
                showDeleteConfirm = false
                onDelete()
                onDismiss()
            },
            dismissText = stringResource(R.string.settings_common_cancel),
            onDismiss = { showDeleteConfirm = false },
            destructive = true,
        )
    }

    // v1.97: 二维码分享弹窗(分享当前编辑状态的 ProviderConfig)
    if (showQrShareDialog) {
        val qrContent = io.zer0.muse.ui.qrcode.QrCodeGenerator.encodeProvider(buildCurrentConfig())
        io.zer0.muse.ui.qrcode.QrCodeShareDialog(
            content = qrContent,
            onDismiss = { showQrShareDialog = false },
        )
    }

    // 拉取成功后弹出的模型选择 Sheet
    if (showModelsPicker) {
        val existingIds = modelsState.map { it.id }.toSet()
        FetchedModelsPickerSheet(
            models = fetchedModels,
            existingIds = existingIds,
            providerType = type,
            providerName = displayName.ifBlank { config.id },
            onDismiss = { showModelsPicker = false },
            onConfirm = { selectedModels ->
                selectedModels.forEach { m -> if (modelsState.none { it.id == m.id }) modelsState.add(m) }
                showModelsPicker = false
            },
        )
    }

    // P2-2: 上游模型差异 Sheet(拉取成功后透明展示新增/已删除/一致三类分组)
    //  - onAdd: 把勾选的新增模型合并入 modelsState(按 id 忽略大小写去重)
    //  - onRemove: 从 modelsState 移除勾选的已删除模型(按 id 忽略大小写匹配)
    //  - onDismiss: 关闭 Sheet(点击遮罩 / 返回键 / 应用完成均触发)
    if (showModelDiffSheet) {
        ModelDiffSheet(
            localModels = modelsState.toList(),
            upstreamModels = fetchedModels,
            onAdd = { toAdd ->
                toAdd.forEach { m ->
                    if (modelsState.none { it.id.equals(m.id, ignoreCase = true) }) {
                        modelsState.add(m)
                    }
                }
            },
            onRemove = { toRemove ->
                val removeIds = toRemove.map { it.id.lowercase() }.toHashSet()
                modelsState.removeAll { existing -> existing.id.lowercase() in removeIds }
            },
            onDismiss = { showModelDiffSheet = false },
        )
    }

    // P1-6: Device Flow user_code 弹窗 — 显示 verification_uri + user_code 给用户
    if (showDeviceCodeDialog && deviceCodeInfo != null) {
        val (userCode, verificationUri) = deviceCodeInfo!!
        MuseDialog(
            onDismissRequest = {
                // 用户取消:中止 OAuth 流程
                OAuthManager.cancel()
                showDeviceCodeDialog = false
                deviceCodeInfo = null
            },
            title = stringResource(R.string.oauth_login),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(
                            R.string.oauth_device_code_prompt,
                            verificationUri,
                            userCode,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.size(MusePaddings.contentGap))
                    // user_code 用大字号 + 居中显示,便于用户抄写到授权页
                    Surface(
                        shape = MuseShapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = userCode,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = androidx.compose.ui.unit.TextUnit(
                                    2f,
                                    androidx.compose.ui.unit.TextUnitType.Sp,
                                ),
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = MusePaddings.itemGap),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            },
            confirmText = stringResource(R.string.oauth_open_verification_uri),
            onConfirm = {
                // 用 Intent 打开 verification_uri
                runCatching {
                    val openIntent = Intent(Intent.ACTION_VIEW, Uri.parse(verificationUri))
                    openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(openIntent)
                }.onFailure {
                    MuseToast.show(
                        context.getString(R.string.oauth_login_failed, it.message ?: "无法打开浏览器"),
                    )
                }
            },
            dismissText = stringResource(R.string.settings_common_cancel),
            onDismiss = {
                OAuthManager.cancel()
                showDeviceCodeDialog = false
                deviceCodeInfo = null
            },
        )
    }

    // P2-11: 撤销 OAuth 访问确认弹窗 — 删除 SecureCredentialStore 中的 token
    if (showRevokeConfirm) {
        MuseDialog(
            onDismissRequest = { showRevokeConfirm = false },
            title = stringResource(R.string.oauth_revoke),
            content = { Text(stringResource(R.string.oauth_revoke_confirm)) },
            confirmText = stringResource(R.string.oauth_revoke),
            onConfirm = {
                showRevokeConfirm = false
                ioScope.launch {
                    OAuthManager.revokeStoredToken(config.id)
                    // 清空 apiKey 输入框 + 同步本地状态
                    apiKey = ""
                    hasStoredOAuthToken = false
                    MuseToast.show(context.getString(R.string.oauth_revoke_success))
                }
            },
            dismissText = stringResource(R.string.settings_common_cancel),
            onDismiss = { showRevokeConfirm = false },
            destructive = true,
        )
    }
}

@Composable
private fun ProviderEditBottomBar(
    canFetch: Boolean,
    isFetching: Boolean,
    canDelete: Boolean,
    onFetch: (Boolean) -> Unit,
    onAddModel: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .imePadding() // v1.48: 键盘遮挡修复
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 获取(主操作:黑色实心胶囊按钮);不可获取时半透明灰显
            val fetchContentColor = MaterialTheme.colorScheme.inverseOnSurface.copy(
                alpha = if (canFetch) 1f else 0.5f,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(MuseIconSizes.touchTarget) // 底部操作栏按钮统一 48dp 触摸目标
                    .background(
                        color = if (canFetch) MaterialTheme.colorScheme.inverseSurface
                        else MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.12f),
                        shape = MuseShapes.huge,
                    )
                    .clickable(enabled = canFetch) { onFetch(true) },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isFetching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = fetchContentColor,
                        )
                    } else {
                        Icon(
                            imageVector = TablerIcons.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                            tint = fetchContentColor,
                        )
                    }
                    Spacer(Modifier.size(MusePaddings.contentGap))
                    Text(
                        text = stringResource(R.string.settings_provider_fetch),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = fetchContentColor,
                    )
                }
            }

            // 添加新模型(次操作:描边胶囊按钮)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(MuseIconSizes.touchTarget) // 底部操作栏按钮统一 48dp 触摸目标
                    .background(
                        color = MaterialTheme.colorScheme.surface,
                        shape = MuseShapes.huge,
                    )
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = MuseShapes.huge,
                    )
                    .clickable(onClick = onAddModel),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = TablerIcons.Plus,
                        contentDescription = null,
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.size(MusePaddings.contentGap))
                    Text(
                        text = stringResource(R.string.settings_provider_add_new_model),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            // 删除(图标按钮,仅可删除时显示)
            if (canDelete) {
                Box(
                    modifier = Modifier
                        .size(MuseIconSizes.touchTarget) // 底部操作栏按钮统一 48dp 触摸目标
                        .background(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                            shape = MuseShapes.huge,
                        )
                        .clickable(onClick = onDelete),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = TablerIcons.Trash,
                        contentDescription = stringResource(R.string.settings_common_delete),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                }
            }
        }
    }
}

@Composable
private fun AddModelDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var modelId by remember { mutableStateOf("") }
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_provider_add_new_model),
        content = {
            MuseTextField(
                value = modelId,
                onValueChange = { modelId = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_provider_model_id)) },
                singleLine = true,
            )
        },
        confirmText = stringResource(R.string.settings_common_add),
        onConfirm = {
            val id = modelId.trim()
            if (id.isNotBlank()) onConfirm(id)
            onDismiss()
        },
        dismissText = stringResource(R.string.settings_common_cancel),
        onDismiss = onDismiss,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigTab(
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    type: ProviderType,
    onTypeChange: (ProviderType) -> Unit,
    isCustomSpecific: Boolean,
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    apiKeyVisible: Boolean,
    onApiKeyVisibleChange: (Boolean) -> Unit,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    balanceApiPath: String,
    onBalanceApiPathChange: (String) -> Unit,
    balanceResultPath: String,
    onBalanceResultPathChange: (String) -> Unit,
    showAdvanced: Boolean,
    onShowAdvancedChange: (Boolean) -> Unit,
    useVertexAi: Boolean,
    onUseVertexAiChange: (Boolean) -> Unit,
    useServiceAccount: Boolean,
    onUseServiceAccountChange: (Boolean) -> Unit,
    serviceAccountEmail: String,
    onServiceAccountEmailChange: (String) -> Unit,
    privateKey: String,
    onPrivateKeyChange: (String) -> Unit,
    privateKeyVisible: Boolean,
    onPrivateKeyVisibleChange: (Boolean) -> Unit,
    vertexLocation: String,
    onVertexLocationChange: (String) -> Unit,
    vertexProjectId: String,
    onVertexProjectIdChange: (String) -> Unit,
    openAIChatCompletionsPath: String,
    onOpenAIChatCompletionsPathChange: (String) -> Unit,
    openAIUseResponseApi: Boolean,
    onOpenAIUseResponseApiChange: (Boolean) -> Unit,
    openAIIncludeHistoryReasoning: Boolean,
    onOpenAIIncludeHistoryReasoningChange: (Boolean) -> Unit,
    openAIEmbeddingsPath: String,
    onOpenAIEmbeddingsPathChange: (String) -> Unit,
    openAIImagesPath: String,
    onOpenAIImagesPathChange: (String) -> Unit,
    openAIStripModelPrefix: String,
    onOpenAIStripModelPrefixChange: (String) -> Unit,
    anthropicPromptCaching: Boolean,
    onAnthropicPromptCachingChange: (Boolean) -> Unit,
    anthropicPromptCacheTtl: String,
    onAnthropicPromptCacheTtlChange: (String) -> Unit,
    anthropicMessagesPath: String,
    onAnthropicMessagesPathChange: (String) -> Unit,
    anthropicModelsPath: String,
    onAnthropicModelsPathChange: (String) -> Unit,
    customChatCompletionsPath: String,
    onCustomChatCompletionsPathChange: (String) -> Unit,
    customHeadersText: String,
    onCustomHeadersTextChange: (String) -> Unit,
    customBodyText: String,
    onCustomBodyTextChange: (String) -> Unit,
    fetchError: String?,
    onFetchErrorDismiss: () -> Unit,
    // v2.4: 独立测试连接相关参数(只测不改,不写入 modelsState)
    isTestingConnection: Boolean,
    testConnectionResult: String?,
    testConnectionError: String?,
    canTestConnection: Boolean,
    onTestConnection: () -> Unit,
    onTestResultDismiss: () -> Unit,
    isQueryingBalance: Boolean,
    balanceResult: String?,
    onQueryBalance: () -> Unit,
    onImportServiceAccountJson: () -> Unit,
    // P1-6: OAuth 登录(仅当 oauthConfig != null 时显示)
    oauthConfig: OAuthConfig? = null,
    onOAuthLogin: () -> Unit = {},
    isOAuthLoading: Boolean = false,
    // P2-11: 已存储 OAuth token 状态 + 撤销回调
    hasStoredOAuthToken: Boolean = false,
    onRevokeOAuth: () -> Unit = {},
) {
    // 前缀匹配字符串(用于 startsWith/contains 判断)
    val connectionSuccessPrefix = stringResource(R.string.settings_provider_prefix_connection_success)
    val cannotConnectPrefix = stringResource(R.string.settings_provider_prefix_cannot_connect)
    val fetchModelsFailedPrefix = stringResource(R.string.settings_provider_prefix_fetch_models_failed)
    val queryFailedPrefix = stringResource(R.string.settings_provider_prefix_query_failed)
    val configureFirstPrefix = stringResource(R.string.settings_provider_prefix_configure)
    val pathNotFoundPrefix = stringResource(R.string.settings_provider_prefix_path_not_found)
    val unableToResolvePrefix = stringResource(R.string.settings_provider_prefix_unable_to_resolve)
    val queryTimeoutPrefix = stringResource(R.string.settings_provider_prefix_query_timeout)
    // 状态描述字符串(需在 @Composable 上下文中提取,不能在 semantics{} lambda 内调用 stringResource)
    val stateEnabledText = stringResource(R.string.settings_state_enabled)
    val stateDisabledText = stringResource(R.string.settings_state_disabled)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = MusePaddings.screen, vertical = MusePaddings.sectionGap),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.sectionGap),
    ) {
        item {
            SettingsGroup {
                Column(
                    modifier = Modifier.padding(MusePaddings.cardInner),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                ) {
                    // 类型选择
                    Text(stringResource(R.string.settings_provider_type), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
                        ProviderType.entries.forEach { t ->
                            // v1.134 P0-8: FilterChip → MuseChip 胶囊
                            MuseChip(
                                selected = type == t,
                                onClick = { onTypeChange(t) },
                                label = providerDisplayTypeName(t),
                            )
                        }
                    }
                    SettingField(
                        label = stringResource(R.string.settings_provider_display_name),
                        value = displayName,
                        onValueChange = onDisplayNameChange,
                    )
                    SettingField(
                        label = stringResource(R.string.settings_provider_base_url),
                        value = baseUrl,
                        onValueChange = onBaseUrlChange,
                        placeholder = when (type) {
                            ProviderType.OPENAI -> ProviderConfig.DEFAULT_OPENAI_BASE_URL
                            ProviderType.ANTHROPIC -> ProviderConfig.DEFAULT_ANTHROPIC_BASE_URL
                            ProviderType.GEMINI -> ProviderConfig.DEFAULT_GEMINI_BASE_URL
                            // v1.0.6: OPENAI_RESPONSES 复用 OpenAI 同款 UI 配置(/v1/responses 切换由 useResponseApi 开关控制)
                            ProviderType.OPENAI_RESPONSES -> ProviderConfig.DEFAULT_OPENAI_RESPONSES_BASE_URL
                        },
                    )
                    MuseTextField(
                        value = apiKey,
                        onValueChange = onApiKeyChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.settings_provider_api_key)) },
                        singleLine = true,
                        visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            MuseTactileButton(
                                icon = if (apiKeyVisible) TablerIcons.EyeOff else TablerIcons.Eye,
                                onClick = { onApiKeyVisibleChange(!apiKeyVisible) },
                                contentDescription = if (apiKeyVisible) stringResource(R.string.settings_common_hide) else stringResource(R.string.settings_common_show),
                            )
                        },
                    )

                    // P1-6: OAuth 登录按钮 + 状态行(仅当 oauthConfig != null 时显示)
                    // - 用 Surface + MuseShapes.pill 包裹,非 Material3 Button
                    // - 加载中(AWAITING_USER / POLLING):按钮禁用 + 圆形进度 + "等待授权完成..."
                    // - 点击:调用 onOAuthLogin,由 ProviderEditPage 启动 Device Flow / Auth Code Flow
                    // P2-11: 已存储 token 时额外显示「撤销访问」按钮(红色 errorContainer 配色,
                    //   与登录按钮风格统一但语义对立)
                    if (oauthConfig != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = MusePaddings.contentGap),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                        ) {
                            // OAuth 登录胶囊按钮(非 Material3 Button,用 Surface + clickable 包裹)
                            val oauthBtnBg = if (isOAuthLoading)
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            else
                                MaterialTheme.colorScheme.primaryContainer
                            val oauthBtnContentColor = if (isOAuthLoading)
                                MaterialTheme.colorScheme.outline
                            else
                                MaterialTheme.colorScheme.onPrimaryContainer
                            Surface(
                                shape = MuseShapes.pill,
                                color = oauthBtnBg,
                                modifier = Modifier.clickable(enabled = !isOAuthLoading) { onOAuthLogin() },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    if (isOAuthLoading) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(MuseIconSizes.iconTiny),
                                            strokeWidth = 2.dp,
                                            color = oauthBtnContentColor,
                                        )
                                    } else {
                                        Icon(
                                            imageVector = TablerIcons.Lock,
                                            contentDescription = null,
                                            tint = oauthBtnContentColor,
                                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                                        )
                                    }
                                    Text(
                                        text = stringResource(R.string.oauth_login),
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                        color = oauthBtnContentColor,
                                    )
                                }
                            }
                            // P2-11: 撤销访问胶囊按钮(仅当已有已存储 token 时显示)
                            // - 用 Surface + MuseShapes.pill,与登录按钮风格一致
                            // - errorContainer 配色暗示「破坏性操作」
                            // - 已在登录流程中(isOAuthLoading)禁用,避免状态冲突
                            if (hasStoredOAuthToken) {
                                val revokeBtnBg = if (isOAuthLoading)
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                else
                                    MaterialTheme.colorScheme.errorContainer
                                val revokeBtnContentColor = if (isOAuthLoading)
                                    MaterialTheme.colorScheme.outline
                                else
                                    MaterialTheme.colorScheme.onErrorContainer
                                Surface(
                                    shape = MuseShapes.pill,
                                    color = revokeBtnBg,
                                    modifier = Modifier.clickable(enabled = !isOAuthLoading) { onRevokeOAuth() },
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Icon(
                                            imageVector = TablerIcons.X,
                                            contentDescription = null,
                                            tint = revokeBtnContentColor,
                                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                                        )
                                        Text(
                                            text = stringResource(R.string.oauth_revoke),
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                            color = revokeBtnContentColor,
                                        )
                                    }
                                }
                            }
                            // 加载状态提示
                            if (isOAuthLoading) {
                                Text(
                                    text = stringResource(R.string.oauth_polling),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }


                    // v2.4: 独立测试连接按钮 + 结果胶囊(参考 rikkahub/kelivo/openhanako)
                    // - 测试中: 按钮内 CircularProgressIndicator
                    // - 成功: 绿色 ✓ 胶囊 + "连接正常 · N 个模型"
                    // - 失败: 红色 ✗ 胶囊 + 分级错误信息(API Key 无效 / URL 不支持 / 连接超时 / 无法连接服务器)
                    // - 用 MuseShapes.pill 胶囊形,与 iOS 风格一致
                    // - 关闭按钮用 MuseTactileButton 而非 Material3 IconButton
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = MusePaddings.contentGap),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                    ) {
                        // 测试连接按钮(iOS 风格胶囊,非 Material3 IconButton)
                        val testBtnBg = if (canTestConnection)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        val testBtnContentColor = if (canTestConnection)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.outline
                        Box(
                            modifier = Modifier
                                .background(color = testBtnBg, shape = MuseShapes.pill)
                                .clickable(enabled = canTestConnection) { onTestConnection() }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isTestingConnection) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(MuseIconSizes.iconTiny),
                                        strokeWidth = 2.dp,
                                        color = testBtnContentColor,
                                    )
                                } else {
                                    Icon(
                                        imageVector = TablerIcons.World,
                                        contentDescription = null,
                                        tint = testBtnContentColor,
                                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                                    )
                                }
                                Spacer(Modifier.size(6.dp))
                                Text(
                                    text = stringResource(R.string.settings_provider_test_connection),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                    color = testBtnContentColor,
                                )
                            }
                        }

                        // 结果胶囊(成功/失败态,MuseShapes.pill 形状)
                        if (testConnectionResult != null || testConnectionError != null) {
                            val isSuccess = testConnectionResult != null
                            val message = testConnectionResult ?: testConnectionError ?: ""
                            val capsuleColor = if (isSuccess)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error
                            val capsuleBg = if (isSuccess)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                            else
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                            Surface(
                                shape = MuseShapes.pill,
                                color = capsuleBg,
                                modifier = Modifier.weight(1f),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        imageVector = if (isSuccess) TablerIcons.Check else TablerIcons.X,
                                        contentDescription = null,
                                        tint = capsuleColor,
                                        modifier = Modifier.size(MuseIconSizes.iconTiny),
                                    )
                                    Text(
                                        text = message,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = capsuleColor,
                                        modifier = Modifier.weight(1f),
                                    )
                                    // 关闭按钮:用 MuseTactileButton 而非 Material3 IconButton
                                    MuseTactileButton(
                                        icon = TablerIcons.X,
                                        onClick = onTestResultDismiss,
                                        contentDescription = stringResource(R.string.settings_common_close),
                                        size = 20.dp,
                                        iconSize = MuseIconSizes.iconTiny,
                                        tint = capsuleColor,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 拉取错误提示
        if (fetchError != null) {
            item {
                Surface(
                    shape = MuseShapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                    ) {
                        Icon(
                            TablerIcons.InfoCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = fetchError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        MuseTactileButton(
                            icon = TablerIcons.X,
                            onClick = { onFetchErrorDismiss() },
                            contentDescription = stringResource(R.string.settings_common_close),
                            tint = MaterialTheme.colorScheme.error,
                            iconSize = 16.dp,
                        )
                    }
                }
            }
        }

        // 自动获取失败后提示手动添加
        if (fetchError != null &&
            (fetchError.contains(cannotConnectPrefix) ||
                fetchError.contains("404") ||
                fetchError.contains(fetchModelsFailedPrefix))
        ) {
            item {
                Text(
                    text = stringResource(R.string.settings_provider_manual_add_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 12.dp, top = 4.dp),
                )
            }
        }

        // Vertex AI 配置(仅 GEMINI)
        if (type == ProviderType.GEMINI) {
            item {
                SettingsGroup {
                    Column(
                        modifier = Modifier.padding(MusePaddings.cardInner),
                        verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                    ) {
                        Text(stringResource(R.string.settings_provider_vertex_ai), style = MaterialTheme.typography.labelMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MuseSwitch(
                                checked = useVertexAi,
                                onCheckedChange = onUseVertexAiChange,
                                modifier = Modifier.semantics {
                                    stateDescription = if (useVertexAi) stateEnabledText else stateDisabledText
                                },
                            )
                            Text(
                                text = stringResource(R.string.settings_provider_enable_vertex),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = MusePaddings.iconPadding),
                            )
                        }
                        if (useVertexAi) {
                            SettingField(
                                label = stringResource(R.string.settings_provider_location),
                                value = vertexLocation,
                                onValueChange = onVertexLocationChange,
                                placeholder = "us-central1",
                            )
                            SettingField(
                                label = stringResource(R.string.settings_provider_project_id),
                                value = vertexProjectId,
                                onValueChange = onVertexProjectIdChange,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                MuseSwitch(
                                    checked = useServiceAccount,
                                    onCheckedChange = onUseServiceAccountChange,
                                    modifier = Modifier.semantics {
                                        stateDescription = if (useServiceAccount) stateEnabledText else stateDisabledText
                                    },
                                )
                                Text(
                                    text = stringResource(R.string.settings_provider_use_service_account),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(start = MusePaddings.iconPadding),
                                )
                            }
                            if (useServiceAccount) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        TablerIcons.FileUpload,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                                    )
                                    Spacer(Modifier.size(MusePaddings.contentGap))
                                    TextButton(onClick = onImportServiceAccountJson) {
                                        Text(stringResource(R.string.settings_provider_import_from_json))
                                    }
                                }
                                SettingField(
                                    label = stringResource(R.string.settings_provider_service_account_email),
                                    value = serviceAccountEmail,
                                    onValueChange = onServiceAccountEmailChange,
                                )
                                MuseTextField(
                                    value = privateKey,
                                    onValueChange = onPrivateKeyChange,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(stringResource(R.string.settings_provider_private_key)) },
                                    placeholder = { Text("-----BEGIN PRIVATE KEY-----\n...") },
                                    minLines = 3,
                                    maxLines = 8,
                                    visualTransformation = if (privateKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        MuseTactileButton(
                                            icon = if (privateKeyVisible) TablerIcons.EyeOff else TablerIcons.Eye,
                                            onClick = { onPrivateKeyVisibleChange(!privateKeyVisible) },
                                            contentDescription = if (privateKeyVisible) stringResource(R.string.settings_common_hide) else stringResource(R.string.settings_common_show),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        // 高级字段(可折叠)
        item {
            SettingsGroup {
                Column(
                    modifier = Modifier.padding(MusePaddings.cardInner),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onShowAdvancedChange(!showAdvanced) }
                            .padding(vertical = MusePaddings.tightGap),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.settings_common_advanced),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Icon(
                            imageVector = if (showAdvanced) TablerIcons.ChevronUp else TablerIcons.ChevronDown,
                            contentDescription = if (showAdvanced) stringResource(R.string.settings_common_collapse) else stringResource(R.string.settings_common_expand),
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    }
                    if (showAdvanced) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MuseSwitch(
                                checked = enabled,
                                onCheckedChange = onEnabledChange,
                                modifier = Modifier.semantics {
                                    stateDescription = if (enabled) stateEnabledText else stateDisabledText
                                },
                            )
                            Text(
                                text = stringResource(R.string.settings_provider_enable_this),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = MusePaddings.iconPadding),
                            )
                        }
                        SettingField(
                            label = stringResource(R.string.settings_provider_balance_api_path),
                            value = balanceApiPath,
                            onValueChange = onBalanceApiPathChange,
                            placeholder = "/dashboard/billing/usage",
                        )
                        SettingField(
                            label = stringResource(R.string.settings_provider_balance_result_path),
                            value = balanceResultPath,
                            onValueChange = onBalanceResultPathChange,
                            placeholder = "\$.data.total_usage",
                        )
                        // v1.134 P0-3: 余额查询按钮用 Surface+clickable 胶囊
                        val balanceEnabled = !isQueryingBalance && balanceApiPath.isNotBlank() && baseUrl.isNotBlank()
                        Surface(
                            shape = MuseShapes.pill,
                            color = if (balanceEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (balanceEnabled) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = if (balanceEnabled) onQueryBalance else ({}),
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (isQueryingBalance) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Icon(
                                        TablerIcons.Wallet,
                                        contentDescription = null,
                                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                                    )
                                }
                                Text(stringResource(R.string.settings_provider_query_balance))
                            }
                        }
                        balanceResult?.let { result ->
                            val isError = result.startsWith(queryFailedPrefix) || result.startsWith(configureFirstPrefix) || result.startsWith(pathNotFoundPrefix) || result.startsWith(unableToResolvePrefix) || result.startsWith(queryTimeoutPrefix)
                            Text(
                                text = result,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = MusePaddings.contentGap),
                            )
                        }

                        // Provider 特定高级字段
                        when (type) {
                            // v1.0.6: OPENAI_RESPONSES 复用 OpenAI 同款 UI 配置(/v1/responses 切换由 useResponseApi 开关控制)
                            ProviderType.OPENAI, ProviderType.OPENAI_RESPONSES -> if (isCustomSpecific) {
                                Text(
                                    text = stringResource(R.string.settings_provider_custom_specific),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                SettingField(
                                    label = stringResource(R.string.settings_provider_chat_completions_path),
                                    value = customChatCompletionsPath,
                                    onValueChange = onCustomChatCompletionsPathChange,
                                    placeholder = "/chat/completions",
                                )
                                MuseTextField(
                                    value = customHeadersText,
                                    onValueChange = onCustomHeadersTextChange,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = {
                                        Text(
                                            stringResource(R.string.settings_provider_custom_headers),
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    },
                                    placeholder = { Text("Authorization: Bearer xxx") },
                                    minLines = 3,
                                    maxLines = 8,
                                )
                                MuseTextField(
                                    value = customBodyText,
                                    onValueChange = onCustomBodyTextChange,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = {
                                        Text(
                                            stringResource(R.string.settings_provider_custom_body),
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    },
                                    placeholder = { Text("{\"seed\": 42}") },
                                    minLines = 3,
                                    maxLines = 8,
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.settings_provider_openai_specific),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                SettingField(
                                    label = stringResource(R.string.settings_provider_chat_completions_path),
                                    value = openAIChatCompletionsPath,
                                    onValueChange = onOpenAIChatCompletionsPathChange,
                                    placeholder = "/chat/completions",
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    MuseSwitch(
                                        checked = openAIUseResponseApi,
                                        onCheckedChange = onOpenAIUseResponseApiChange,
                                        modifier = Modifier.semantics {
                                            stateDescription = if (openAIUseResponseApi) stateEnabledText else stateDisabledText
                                        },
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_provider_use_responses_api),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(start = MusePaddings.iconPadding),
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    MuseSwitch(
                                        checked = openAIIncludeHistoryReasoning,
                                        onCheckedChange = onOpenAIIncludeHistoryReasoningChange,
                                        modifier = Modifier.semantics {
                                            stateDescription = if (openAIIncludeHistoryReasoning) stateEnabledText else stateDisabledText
                                        },
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_provider_include_history_reasoning),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(start = MusePaddings.iconPadding),
                                    )
                                }
                                SettingField(
                                    label = stringResource(R.string.settings_provider_embeddings_path),
                                    value = openAIEmbeddingsPath,
                                    onValueChange = onOpenAIEmbeddingsPathChange,
                                    placeholder = "/embeddings",
                                )
                                SettingField(
                                    label = stringResource(R.string.settings_provider_images_path),
                                    value = openAIImagesPath,
                                    onValueChange = onOpenAIImagesPathChange,
                                    placeholder = "/images/generations",
                                )
                                SettingField(
                                    label = stringResource(R.string.settings_provider_strip_model_prefix),
                                    value = openAIStripModelPrefix,
                                    onValueChange = onOpenAIStripModelPrefixChange,
                                    placeholder = stringResource(R.string.settings_provider_strip_model_prefix_placeholder),
                                )
                            }

                            ProviderType.ANTHROPIC -> {
                                Text(
                                    text = stringResource(R.string.settings_provider_anthropic_specific),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    MuseSwitch(
                                        checked = anthropicPromptCaching,
                                        onCheckedChange = onAnthropicPromptCachingChange,
                                        modifier = Modifier.semantics {
                                            stateDescription = if (anthropicPromptCaching) stateEnabledText else stateDisabledText
                                        },
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_provider_enable_prompt_caching),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(start = MusePaddings.iconPadding),
                                    )
                                }
                                SettingField(
                                    label = stringResource(R.string.settings_provider_prompt_cache_ttl),
                                    value = anthropicPromptCacheTtl,
                                    onValueChange = onAnthropicPromptCacheTtlChange,
                                    placeholder = "5m",
                                )
                                SettingField(
                                    label = stringResource(R.string.settings_provider_messages_path),
                                    value = anthropicMessagesPath,
                                    onValueChange = onAnthropicMessagesPathChange,
                                    placeholder = "/messages",
                                )
                                SettingField(
                                    label = stringResource(R.string.settings_provider_models_path),
                                    value = anthropicModelsPath,
                                    onValueChange = onAnthropicModelsPathChange,
                                    placeholder = "/models",
                                )
                            }

                            ProviderType.GEMINI -> { /* Vertex AI 已在独立卡片配置 */ }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelsTab(
    config: ProviderConfig,
    modelsState: SnapshotStateList<Model>,
    apiKey: String,
    isFetching: Boolean,
    onFetch: (Boolean) -> Unit,
    onAddModel: () -> Unit,
    // P2-5: 透传 baseUrl,用于判定是否展示「一键填入免费模型」按钮
    baseUrl: String = "",
    // v1.0.8 (7.6): 模型健康检查回调 + 状态透传(从 ProviderEditPage 传入)
    onTestModel: (Model) -> Unit = {},
    modelTestStatuses: Map<String, ModelTestStatus> = emptyMap(),
) {
    var editingModel by remember { mutableStateOf<Model?>(null) }

    // P2-5: SiliconFlow 免费模型一键填入
    //  - 仅当 baseUrl 命中 siliconflow.cn 时展示入口(SiliconFlow 官方 / 自部署兼容域名均可)
    //  - 点击弹出二次确认(替换会清空当前 modelsState,不可撤销)
    val isSiliconFlow = baseUrl.contains("siliconflow.cn", ignoreCase = true)
    var showFillFreeModelsConfirm by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // P2-5: 顶部「一键填入免费模型」按钮(仅 SiliconFlow 显示)
        //  - 用 Surface + clickable + MuseShapes.tiny,不使用 Material3 Button
        //  - 与 OAuth 登录按钮、测试连接按钮风格保持一致(胶囊 / 圆角小标签)
        if (isSiliconFlow) {
            SiliconFlowFreeModelsButton(
                onClick = { showFillFreeModelsConfirm = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
            )
            // v1.0.18: Kelivo 式免费模型 fallback 提示 — 用户未填 apiKey 时告知可用免费模型,
            // 填写后可解锁 SiliconFlow 全部模型(由 listModels 调远程 /models 拉取)
            if (apiKey.isBlank() && FreeModelConfig.isFreeProvider(baseUrl, apiKey)) {
                Text(
                    text = FreeModelConfig.FREE_PROVIDER_HINT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.tightGap),
                )
            }
        }

        if (modelsState.isEmpty()) {
            // P2-5: 用 weight(1f) 让空状态占满按钮下方剩余空间
            //  (Column 中 fillMaxSize 会拿到父级完整高度,而非剩余高度,导致溢出)
            EmptyModelsState(
                isFetching = isFetching,
                onFetch = onFetch,
                onAddModel = onAddModel,
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = MusePaddings.contentGap),
            ) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { modelsState.clear() },
                            enabled = modelsState.isNotEmpty(),
                            shape = MuseShapes.pill,
                        ) {
                            Icon(compose.icons.TablerIcons.CircleMinus, contentDescription = null, modifier = Modifier.size(MuseIconSizes.iconSmall))
                            Spacer(Modifier.size(MusePaddings.contentGap))
                            Text(stringResource(io.zer0.muse.R.string.settings_provider_deselect_all), style = MaterialTheme.typography.labelMedium)
                        }
                        TextButton(
                            onClick = { onFetch(true) },
                            enabled = !isFetching,
                            shape = MuseShapes.pill,
                        ) {
                            if (isFetching) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(TablerIcons.Refresh, contentDescription = null, modifier = Modifier.size(MuseIconSizes.iconSmall))
                            }
                            Spacer(Modifier.size(MusePaddings.contentGap))
                            Text(stringResource(R.string.settings_provider_refresh_models))
                        }
                    }
                }
                items(modelsState.toList(), key = { it.id }) { model ->
                    ProviderModelRow(
                        model = model,
                        providerType = config.type,
                        providerName = config.displayName,
                        isAdded = true,
                        onAction = { editingModel = model },
                        // v1.0.8 (7.6): 传入健康检查回调 + 当前状态(按 model.id 查询)
                        onTest = { onTestModel(model) },
                        testStatus = modelTestStatuses[model.id] ?: ModelTestStatus.Idle,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 68.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        thickness = 0.5.dp,
                    )
                }
            }
        }
    }

    editingModel?.let { model ->
        ModelAbilityEditorDialog(
            model = model,
            onDismiss = { editingModel = null },
            onSave = { updated ->
                val idx = modelsState.indexOfFirst { it.id == updated.id }
                if (idx >= 0) modelsState[idx] = updated
                editingModel = null
            },
            onDelete = {
                modelsState.removeAll { it.id == model.id }
                editingModel = null
            },
        )
    }

    // P2-5: 一键填入免费模型 — 二次确认弹窗
    //  - 替换会清空当前 modelsState 后填入 SiliconFlowFreeModels.MODELS,不可撤销
    //  - 默认 common_confirm / common_cancel 文案,与现有 MuseDialog 一致
    if (showFillFreeModelsConfirm) {
        MuseDialog(
            onDismissRequest = { showFillFreeModelsConfirm = false },
            title = stringResource(R.string.siliconflow_fill_free_models),
            content = {
                Text(
                    text = stringResource(R.string.siliconflow_fill_free_models_confirm),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            confirmText = stringResource(R.string.common_confirm),
            onConfirm = {
                modelsState.clear()
                modelsState.addAll(SiliconFlowFreeModels.MODELS)
                showFillFreeModelsConfirm = false
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { showFillFreeModelsConfirm = false },
        )
    }
}

/**
 * P2-5: SiliconFlow「一键填入免费模型」按钮。
 *
 * 设计:
 *  - 用 [Surface] + [Modifier.clickable] + [MuseShapes.tiny],不使用 Material3 Button
 *    (与 OAuth 登录 / 测试连接等行内胶囊按钮风格一致)
 *  - 背景用 primaryContainer,文字/图标用 onPrimaryContainer,确保深浅色主题对比度
 *  - 图标用 [TablerIcons.Wand](魔法棒),呼应"一键填入"的语义
 */
@Composable
private fun SiliconFlowFreeModelsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MuseShapes.tiny,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = MusePaddings.iconPadding,
                vertical = MusePaddings.contentGap,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        ) {
            Icon(
                imageVector = TablerIcons.Wand,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
            )
            Text(
                text = stringResource(R.string.siliconflow_fill_free_models),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun ModelAbilityEditorDialog(
    model: Model,
    onDismiss: () -> Unit,
    onSave: (Model) -> Unit,
    onDelete: () -> Unit,
) {
    var supportsTools by remember { mutableStateOf(model.abilities.contains(ModelAbility.TOOL) || model.abilities.isEmpty()) }
    var supportsReasoning by remember { mutableStateOf(model.abilities.contains(ModelAbility.REASONING) || model.abilities.isEmpty()) }
    var supportsStreaming by remember { mutableStateOf(model.supportsStreaming) }
    var supportsImageOutput by remember { mutableStateOf(model.supportsImageOutput()) }
    var supportsVision by remember { mutableStateOf(model.supportsVisionInput()) }
    var supportsVideo by remember { mutableStateOf(model.supportsVideo) }
    var contextWindow by remember { mutableStateOf(model.contextWindow?.toString() ?: "") }
    var maxOutputTokens by remember { mutableStateOf(model.maxOutputTokens?.toString() ?: "") }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_provider_edit_model_abilities),
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = model.id,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    // v1.97: 一键自动检测能力(基于 ModelRegistry token 匹配)
                    TextButton(
                        onClick = {
                            val abilities = ModelRegistry.lookupAbilities(model.id)
                            val inputMods = ModelRegistry.lookupInputModalities(model.id)
                            val outputMods = ModelRegistry.lookupOutputModalities(model.id)
                            supportsTools = ModelAbility.TOOL in abilities
                            supportsReasoning = ModelAbility.REASONING in abilities
                            supportsVision = "image" in inputMods
                            supportsImageOutput = "image" in outputMods
                            // 未显式设置 contextWindow 时,顺带回填注册表兜底值
                            if (contextWindow.isBlank()) {
                                ModelContextWindowRegistry.lookup(model.id)?.let {
                                    contextWindow = it.toString()
                                }
                            }
                        },
                    ) {
                        Icon(
                            imageVector = TablerIcons.Wand,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.size(MusePaddings.tightGap))
                        Text(
                            text = stringResource(R.string.settings_provider_auto_detect),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                Spacer(Modifier.size(MusePaddings.contentGap))
                AbilitySwitchRow(stringResource(R.string.settings_provider_ability_tools), supportsTools) { supportsTools = it }
                AbilitySwitchRow(stringResource(R.string.settings_provider_ability_reasoning), supportsReasoning) { supportsReasoning = it }
                AbilitySwitchRow(stringResource(R.string.settings_provider_ability_streaming), supportsStreaming) { supportsStreaming = it }
                AbilitySwitchRow(stringResource(R.string.settings_provider_ability_image_output), supportsImageOutput) { supportsImageOutput = it }
                AbilitySwitchRow(stringResource(R.string.settings_provider_ability_vision), supportsVision) { supportsVision = it }
                AbilitySwitchRow(stringResource(R.string.settings_provider_ability_video), supportsVideo) { supportsVideo = it }
                Spacer(Modifier.size(MusePaddings.itemGap))
                MuseTextField(
                    value = contextWindow,
                    onValueChange = { contextWindow = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.settings_provider_context_window)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.size(MusePaddings.contentGap))
                MuseTextField(
                    value = maxOutputTokens,
                    onValueChange = { maxOutputTokens = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.settings_provider_max_output_tokens)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.size(MusePaddings.itemGap))
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(
                        text = stringResource(R.string.settings_provider_remove_model),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        },
        confirmText = stringResource(R.string.settings_common_save),
        onConfirm = {
            val newAbilities = buildSet {
                if (supportsTools) add(ModelAbility.TOOL)
                if (supportsReasoning) add(ModelAbility.REASONING)
            }
            val newOutput = buildSet {
                add("text")
                if (supportsImageOutput) add("image")
                if (supportsVideo) add("video")
            }
            val newInput = buildSet {
                add("text")
                if (supportsVision) add("image")
            }
            onSave(
                model.copy(
                    abilities = newAbilities,
                    supportsStreaming = supportsStreaming,
                    supportsVision = supportsVision,
                    supportsVideo = supportsVideo,
                    outputModalities = newOutput,
                    inputModalities = newInput,
                    contextWindow = contextWindow.toIntOrNull(),
                    maxOutputTokens = maxOutputTokens.toIntOrNull(),
                )
            )
        },
        dismissText = stringResource(R.string.settings_common_cancel),
        onDismiss = onDismiss,
    )
}

@Composable
private fun AbilitySwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MusePaddings.tightGap),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        MuseSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun EmptyModelsState(
    isFetching: Boolean,
    onFetch: (Boolean) -> Unit,
    onAddModel: () -> Unit,
    // P2-5: 外部传入 modifier,允许调用方在 Column 中用 weight(1f) 让其占满剩余空间
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.settings_provider_no_models),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(MusePaddings.contentGap))
        Text(
            text = stringResource(R.string.settings_provider_no_models_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.size(MuseIconSizes.icon))
        Row(
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
        ) {
            TextButton(
                onClick = { onFetch(true) },
                enabled = !isFetching,
                shape = MuseShapes.pill,
            ) {
                if (isFetching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(TablerIcons.Refresh, contentDescription = null, modifier = Modifier.size(MuseIconSizes.iconSmall))
                }
                Spacer(Modifier.size(MusePaddings.contentGap))
                Text(stringResource(R.string.settings_provider_refresh_models))
            }
            TextButton(
                onClick = onAddModel,
                shape = MuseShapes.pill,
            ) {
                Icon(TablerIcons.Plus, contentDescription = null, modifier = Modifier.size(MuseIconSizes.iconSmall))
                Spacer(Modifier.size(MusePaddings.contentGap))
                Text(stringResource(R.string.settings_provider_add_new_model))
            }
        }
    }
}

/**
 * 拉取上游模型成功后弹出的 iOS 风格底部 Sheet。
 *
 * 改进点:
 *  - 不再手动输入上下文(K),统一用 [ModelContextWindowRegistry] 自动推断
 *  - 复选框改为 iOS 式行点击 + 右侧对勾
 *  - 搜索栏、分组标题、底部按钮均使用暖色/iOS 风格
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FetchedModelsPickerSheet(
    models: List<Model>,
    existingIds: Set<String>,
    providerType: ProviderType,
    providerName: String,
    onDismiss: () -> Unit,
    onConfirm: (List<Model>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    var groupExpanded by remember { mutableStateOf(true) }

    val filtered = remember(query, models) {
        if (query.isBlank()) models else models.filter { it.id.contains(query, ignoreCase = true) }
    }

    // 选中数量用 derivedStateOf 缓存,避免 confirmText 每次重组都重新 count
    val selectedCount by remember {
        derivedStateOf { filtered.count { selected[it.id] == true } }
    }

    // MuseDialog 替代原 ModalBottomSheet,避免真机 scrim 卡死
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_provider_select_models),
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.settings_provider_models_count, models.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )

                Spacer(Modifier.size(MusePaddings.itemGap))

                // iOS 风格搜索栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = MuseShapes.small,
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    Icon(
                        imageVector = TablerIcons.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        if (query.isBlank()) {
                            Text(
                                text = stringResource(R.string.settings_provider_search_models),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        // 隐藏式 TextField,保持 iOS 搜索栏的视觉纯净
                        androidx.compose.foundation.text.BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            singleLine = true,
                        )
                    }
                    if (query.isNotBlank()) {
                        MuseTactileButton(
                            icon = TablerIcons.X,
                            onClick = { query = "" },
                            contentDescription = stringResource(R.string.settings_provider_clear),
                            tint = MaterialTheme.colorScheme.outline,
                            iconSize = MuseIconSizes.iconSmall,
                        )
                    }
                }

                // 全选/清空
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = MusePaddings.contentGap),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(
                        text = stringResource(R.string.settings_provider_select_all),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { filtered.forEach { selected[it.id] = true } },
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = stringResource(R.string.settings_provider_clear),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { filtered.forEach { selected[it.id] = false } },
                    )
                }

                // 分组标题(可折叠)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { groupExpanded = !groupExpanded }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val brandColor = providerBrandColor(providerType, providerName)
                    Box(
                        modifier = Modifier
                            .size(MuseIconSizes.iconLarge)
                            .background(brandColor.copy(alpha = 0.12f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = providerBrandIcon(providerType, providerName),
                            contentDescription = null,
                            tint = brandColor,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                    }
                    Text(
                        text = providerName.ifBlank { stringResource(R.string.settings_provider_model_list) },
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = if (groupExpanded) TablerIcons.ChevronUp else TablerIcons.ChevronDown,
                        contentDescription = if (groupExpanded) stringResource(R.string.settings_common_collapse) else stringResource(R.string.settings_common_expand),
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // 模型列表(改为 Column,MuseDialog 内容区自带滚动)
                if (groupExpanded) {
                    filtered.forEach { m ->
                        val isSelected = selected[m.id] ?: false
                        val effectiveTokens = m.contextWindow ?: ModelContextWindowRegistry.lookup(m.id)
                        val contextText = formatContextWindow(effectiveTokens, stringResource(R.string.settings_provider_unknown))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected[m.id] = !isSelected }
                                .padding(vertical = MusePaddings.itemGap),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                        ) {
                            // 左侧选中态:iOS 风格对勾圆圈
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .background(
                                        color = if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                                        shape = CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = TablerIcons.Check,
                                        contentDescription = stringResource(R.string.settings_provider_selected),
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(MuseIconSizes.iconTiny),
                                    )
                                }
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = m.id,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                    if (m.id in existingIds) {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            stringResource(R.string.settings_provider_already_exists),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.tertiary,
                                        )
                                    }
                                }
                                if (m.name != m.id) {
                                    Text(
                                        text = m.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                                ModelAbilityChips(model = m)
                            }

                            // 上下文窗口标签
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = MuseShapes.small,
                            ) {
                                Text(
                                    text = contextText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
        },
        confirmText = stringResource(R.string.settings_provider_add_with_count, selectedCount),
        onConfirm = {
            onConfirm(
                filtered.filter { selected[it.id] == true }.map { m ->
                    m.copy(
                        contextWindow = m.contextWindow ?: ModelContextWindowRegistry.lookup(m.id)
                    )
                }
            )
        },
        dismissText = stringResource(R.string.settings_common_cancel),
        onDismiss = onDismiss,
    )
}

/** 将 token 数格式化为 K/M 显示(如 128000 -> "128K", 1000000 -> "1M")。null 返回 [unknownLabel]。 */
private fun formatContextWindow(tokens: Int?, unknownLabel: String): String {
    if (tokens == null) return unknownLabel
    return when {
        tokens >= 1_000_000 -> "${tokens / 1_000_000}M"
        tokens >= 1000 -> "${tokens / 1000}K"
        else -> tokens.toString()
    }
}

/**
 * 解析 Google Service Account JSON 文件内容。
 *
 * Google Service Account JSON 标准字段(仅取所需三项):
 *  - `private_key`: PEM PKCS#8 字符串
 *  - `client_email`: 服务账号邮箱
 *  - `project_id`: GCP 项目 ID
 *
 * @return Triple(email, privateKey, projectId);任一关键字段缺失返回 null
 */
internal fun parseServiceAccountJson(jsonText: String): Triple<String, String, String>? {
    return runCatching {
        val obj = AppJson.decodeFromString(JsonObject.serializer(), jsonText)
        val email = obj["client_email"]?.jsonPrimitive?.contentOrNull ?: return null
        val key = obj["private_key"]?.jsonPrimitive?.contentOrNull ?: return null
        val projectId = obj["project_id"]?.jsonPrimitive?.contentOrNull ?: ""
        Triple(email, key, projectId)
    }.getOrNull()
}

/**
 * 按简单的点分 JSON Path 从 JSON 字符串中提取值。
 *
 * 支持对象字段与数组下标(如 $.data.total_usage 或 $.data.items.0.value)。
 * 解析失败或路径不存在返回 null。
 */
private fun extractJsonPath(json: String, path: String): String? {
    val keys = path.trim()
        .removePrefix("$")
        .removePrefix(".")
        .split(".")
        .filter { it.isNotBlank() }
    val root = runCatching {
        Json.parseToJsonElement(json)
    }.getOrNull() ?: return null
    var current: JsonElement = root
    for (key in keys) {
        current = when (current) {
            is JsonObject -> current[key] ?: return null
            is JsonArray -> {
                val index = key.toIntOrNull() ?: return null
                current.getOrNull(index) ?: return null
            }
            else -> return current.toString()
        }
    }
    return when (current) {
        is JsonPrimitive -> current.contentOrNull ?: current.toString()
        else -> current.toString()
    }
}

/** 把 Map 格式化为每行 "Key: Value" 的多行文本。 */
private fun formatCustomHeaders(map: Map<String, String>): String {
    return map.entries.joinToString("\n") { "${it.key}: ${it.value}" }
}

/** 把每行 "Key: Value" 解析为 Map,格式不合法的行被忽略。 */
private fun parseCustomHeaders(text: String): Map<String, String> {
    return text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx == -1) return@mapNotNull null
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            if (key.isEmpty()) return@mapNotNull null
            key to value
        }
        .toMap()
}

/** 把 Map 格式化为 JSON 字符串,失败返回空字符串。 */
private fun formatCustomBody(map: Map<String, JsonElement>): String {
    if (map.isEmpty()) return ""
    return runCatching {
        AppJson.encodeToString(JsonObject.serializer(), JsonObject(map))
    }.getOrDefault("")
}

/** 把 JSON 字符串解析为 Map,失败返回空 Map。 */
private fun parseCustomBody(text: String): Map<String, JsonElement> {
    val trimmed = text.trim()
    if (trimmed.isBlank()) return emptyMap()
    return runCatching {
        AppJson.parseToJsonElement(trimmed).jsonObject.toMap()
    }.getOrDefault(emptyMap())
}
