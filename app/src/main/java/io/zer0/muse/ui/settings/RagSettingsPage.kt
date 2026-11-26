package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.ai.core.ProviderConfig
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.rag.RagConfig
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseSecretTextField
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.SettingsSliderRow
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.CardGroupScope
import io.zer0.muse.ui.common.surface.actionItem
import io.zer0.muse.ui.common.surface.navigationItem
import io.zer0.muse.ui.common.surface.switchItem
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.ui.theme.statusColors
import io.zer0.muse.util.launchSafely
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/**
 * RAG 知识库检索配置页。
 *
 * 合并为 6 张语义卡片：
 *  1. 知识库与检索开关（自动注入、会话附件检索、多知识库管理）
 *  2. Embedding 后端与连通性测试（云端 / 本地 ONNX、生效状态、模型与验证）
 *  3. 文档解析器（本地 / 云端 / MinerU 及对应端点配置）
 *  4. 检索与重排参数（topK、相似度阈值、MMR、混合检索、Rerank、Token 预算）
 *  5. 分块参数（分块大小、重叠、Markdown 感知、按 Token 分块）
 *  6. 说明
 */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
fun RagSettingsPage(onBack: () -> Unit, onManageKbs: () -> Unit = {}) {
    val context = LocalContext.current
    val settings: SettingsRepository = koinInject()
    val embeddingService: io.zer0.muse.rag.EmbeddingService = koinInject()
    val config by settings.ragConfigFlow.collectAsStateWithLifecycle(initialValue = RagConfig())
    val providers by settings.providersFlow.collectAsStateWithLifecycle(initialValue = emptyList<ProviderConfig>())
    val activeBackend by embeddingService.activeBackend.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var embeddingTestRunning by remember { mutableStateOf(false) }
    var embeddingTestResult by remember { mutableStateOf<TestResult?>(null) }

    // ONNX 模型文件选择（SAF），复制到应用私有目录避免 URI 授权过期
    val onnxPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        val dir = java.io.File(context.filesDir, "muse_onnx").apply { mkdirs() }
                        val target = java.io.File(dir, "embedding.onnx")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        } ?: error("selected file stream is null: unable to read the picked document")
                        target.absolutePath
                    }
                }
                saved.onSuccess { path ->
                    // 模型指纹:同一路径可能被覆盖成另一个模型,只看路径无法发现「模型换了」。
                    // 本入口是当前唯一写本地模型文件的路径,因此指纹也只能在这里算。
                    val digest = withContext(Dispatchers.IO) {
                        runCatching { io.zer0.common.Hashing.sha256Hex(java.io.File(path)) }
                            .getOrNull()
                            .orEmpty()
                    }
                    if (digest.isBlank()) {
                        Logger.w("RagSettingsPage", "本地模型文件指纹计算失败，按无指纹处理: $path")
                    }
                    settings.saveRagConfig(
                        config.copy(localModelPath = path, localModelDigest = digest),
                    )
                    MuseToast.show(context.getString(R.string.settings_rag_onnx_picked))
                }.onFailure { e ->
                    MuseToast.show(context.getString(R.string.settings_rag_onnx_pick_failed, e.message ?: ""))
                }
            }
        }
    }

    LaunchedEffect(
        config.embeddingSource,
        config.cloudProviderId,
        config.cloudModel,
        config.localModelPath,
    ) {
        resolveActiveBackendQuietly(embeddingService, config)
    }

    // 临时状态：避免拖动滑杆或打字时频繁写 DataStore
    var cloudModelTemp by remember(config.cloudModel) { mutableStateOf(config.cloudModel) }
    var topKTemp by remember(config.topK) { mutableStateOf(config.topK) }
    var thresholdTemp by remember(config.threshold) { mutableStateOf(config.threshold) }
    var chunkSizeTemp by remember(config.chunkSize) { mutableStateOf(config.chunkSize) }
    var chunkOverlapTemp by remember(config.chunkOverlap) { mutableStateOf(config.chunkOverlap) }
    var mmrLambdaTemp by remember(config.mmrLambda) { mutableStateOf(config.mmrLambda) }
    var tokenBudgetTemp by remember(config.tokenBudget) { mutableStateOf(config.tokenBudget) }
    var hybridBm25WeightTemp by remember(config.hybridBm25Weight) { mutableStateOf(config.hybridBm25Weight) }
    var hybridVectorWeightTemp by remember(config.hybridVectorWeight) { mutableStateOf(config.hybridVectorWeight) }

    // 静默防抖保存，仅在失败时弹 Toast，避免每停顿 300ms 刷屏提示
    LaunchedEffect(cloudModelTemp) {
        delay(300)
        if (cloudModelTemp != config.cloudModel) {
            runCatching {
                settings.saveRagConfig(config.copy(cloudModel = cloudModelTemp))
            }.onFailure {
                MuseToast.show(context.getString(R.string.rag_save_failed))
            }
        }
    }

    SettingsSubPageScaffold(title = stringResource(R.string.settings_rag_page_title), onBack = onBack) {
        // ── 1. 总开关与知识库管理 ──
        item(key = "rag_general_group") {
            CardGroup(title = { Text(stringResource(R.string.settings_rag_injection_section)) }) {
                switchItem(
                    key = "rag_auto_inject",
                    title = stringResource(R.string.settings_rag_auto_inject),
                    checked = config.enabled,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveRagConfig(config.copy(enabled = v)) }
                    },
                    leadingContent = {
                        Icon(
                            imageVector = MuseIcons.sparkle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
                switchItem(
                    key = "rag_session_attachment",
                    title = stringResource(R.string.settings_experiments_session_attachment_rag),
                    checked = config.sessionAttachmentEnabled,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveRagConfig(config.copy(sessionAttachmentEnabled = v)) }
                    },
                    leadingContent = {
                        Icon(
                            imageVector = MuseIcons.file,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
                navigationItem(
                    key = "rag_manage_kbs",
                    title = stringResource(R.string.settings_rag_manage_kbs),
                    leadingContent = {
                        Icon(
                            imageVector = MuseIcons.folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                    onClick = onManageKbs,
                )
            }
        }

        // ── 2. Embedding 来源、生效后端与模型配置 ──
        item(key = "rag_embedding_group") {
            val activeSummary = resolveActiveBackendSummary(
                state = activeBackend,
                degradedFallback = stringResource(R.string.settings_rag_active_backend_degraded_unknown),
            )
            val baseUrlNotSetText = stringResource(R.string.settings_rag_baseurl_not_set)
            CardGroup(title = { Text(stringResource(R.string.settings_rag_embedding_source_section)) }) {
                selectionOptionItem(
                    key = "rag_source_cloud",
                    icon = MuseIcons.cloud,
                    title = stringResource(R.string.settings_rag_cloud_api),
                    selected = config.embeddingSource == RagConfig.EmbeddingSource.CLOUD,
                    onClick = {
                        scope.launch {
                            settings.saveRagConfig(config.copy(embeddingSource = RagConfig.EmbeddingSource.CLOUD))
                        }
                    },
                )
                selectionOptionItem(
                    key = "rag_source_local",
                    icon = MuseIcons.cloudOff,
                    title = stringResource(R.string.settings_rag_local_keyword),
                    selected = config.embeddingSource != RagConfig.EmbeddingSource.CLOUD,
                    onClick = {
                        scope.launch {
                            settings.saveRagConfig(
                                config.copy(
                                    embeddingSource = RagConfig.EmbeddingSource.LOCAL_KEYWORD,
                                    cloudProviderId = "",
                                    cloudModel = "",
                                ),
                            )
                        }
                    },
                )

                if (activeSummary != null) {
                    content(key = "rag_active_backend") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(MusePaddings.cardInner),
                            verticalArrangement = Arrangement.spacedBy(MuseSpacing.xxs),
                        ) {
                            Text(
                                text = stringResource(R.string.settings_rag_active_backend_label),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = activeSummary.primary,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            val warning = activeSummary.warning
                            if (warning != null) {
                                Text(
                                    text = warning,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }

                if (config.embeddingSource != RagConfig.EmbeddingSource.CLOUD) {
                    content(key = "rag_onnx_picker") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(MusePaddings.cardInner),
                        ) {
                            Text(
                                text = stringResource(R.string.settings_rag_onnx_model_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = if (config.localModelPath.isBlank()) {
                                    stringResource(R.string.settings_rag_onnx_model_empty)
                                } else {
                                    config.localModelPath
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                modifier = Modifier.padding(top = MuseSpacing.xs),
                            )
                            Spacer(Modifier.height(MuseSpacing.sm))
                            Row(horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm)) {
                                MuseCapsuleButton(
                                    text = stringResource(R.string.settings_rag_onnx_pick),
                                    onClick = { onnxPicker.launchSafely(arrayOf("*/*"), context) },
                                    variant = IosCapsuleButtonVariant.Secondary,
                                    fillWidth = false,
                                )
                                if (config.localModelPath.isNotBlank()) {
                                    MuseCapsuleButton(
                                        text = stringResource(R.string.settings_rag_onnx_clear),
                                        onClick = {
                                            scope.launch {
                                                // 清路径必须同时清指纹：残留指纹会让「模型已换」判定漏报。
                                                settings.saveRagConfig(
                                                    config.copy(localModelPath = "", localModelDigest = ""),
                                                )
                                            }
                                        },
                                        variant = IosCapsuleButtonVariant.Secondary,
                                        fillWidth = false,
                                    )
                                }
                            }
                            Text(
                                text = stringResource(R.string.settings_rag_onnx_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(top = MuseSpacing.xs),
                            )
                        }
                    }
                } else {
                    if (providers.isEmpty()) {
                        content(key = "rag_no_provider") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(MusePaddings.cardInner),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(MuseSpacing.md),
                            ) {
                                Icon(
                                    imageVector = MuseIcons.flask,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                                )
                                Text(
                                    text = stringResource(R.string.settings_rag_no_provider_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                    } else {
                        providers.forEach { provider ->
                            selectionOptionItem(
                                key = "rag_provider_${provider.id}",
                                icon = MuseIcons.cloud,
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
                            content(key = "rag_deselect_hint") {
                                Text(
                                    text = stringResource(R.string.settings_rag_deselect_hint),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(MusePaddings.cardInnerSpaced),
                                )
                            }
                        }
                    }

                    content(key = "rag_cloud_model") {
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
                            MuseTextField(
                                value = cloudModelTemp,
                                onValueChange = { cloudModelTemp = it },
                                placeholder = {
                                    Text(
                                        when {
                                            providers.isEmpty() -> "text-embedding-3-small"
                                            config.cloudProviderId.isNotBlank() -> {
                                                val p = providers.find { it.id == config.cloudProviderId }
                                                if (p?.displayName?.contains("openai", true) == true) {
                                                    "text-embedding-3-small"
                                                } else {
                                                    "embedding-2"
                                                }
                                            }
                                            else -> "text-embedding-3-small"
                                        },
                                    )
                                },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = MuseSpacing.sm),
                            )
                            Text(
                                text = stringResource(R.string.settings_rag_common_models),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = MuseSpacing.sm),
                            )
                            Text(
                                text = stringResource(R.string.settings_rag_embedding_provider_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(top = MuseSpacing.xs),
                            )
                            embeddingTestResult?.let { r ->
                                Spacer(Modifier.height(MuseSpacing.sm))
                                Text(
                                    text = r.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (r.ok) MaterialTheme.statusColors.success else MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }

                    actionItem(
                        key = "rag_embedding_test",
                        title = if (embeddingTestRunning) {
                            stringResource(R.string.settings_rag_embedding_testing)
                        } else {
                            stringResource(R.string.settings_rag_embedding_test)
                        },
                        loading = embeddingTestRunning,
                        enabled = !embeddingTestRunning,
                        onClick = {
                            embeddingTestRunning = true
                            embeddingTestResult = null
                            scope.launch {
                                val cfg = runCatching { settings.getRagConfig() }.getOrNull()
                                if (cfg == null) {
                                    embeddingTestResult = TestResult(
                                        false,
                                        context.getString(
                                            R.string.settings_rag_embedding_test_failed,
                                            context.getString(R.string.settings_rag_config_read_failed),
                                        ),
                                    )
                                    embeddingTestRunning = false
                                    return@launch
                                }
                                val outcome = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val provider = embeddingService.getProvider(cfg)
                                        val started = System.currentTimeMillis()
                                        val vec = provider.embed("ping")
                                        vec.size to (System.currentTimeMillis() - started)
                                    }
                                }
                                embeddingTestResult = outcome.fold(
                                    onSuccess = { (dim, ms) ->
                                        TestResult(
                                            true,
                                            context.getString(R.string.settings_rag_embedding_test_ok, dim, ms),
                                        )
                                    },
                                    onFailure = { e ->
                                        TestResult(
                                            false,
                                            context.getString(
                                                R.string.settings_rag_embedding_test_failed,
                                                e.message?.take(160) ?: e::class.simpleName ?: "",
                                            ),
                                        )
                                    },
                                )
                                embeddingTestRunning = false
                            }
                        },
                    )
                }
            }
        }

        // ── 3. 文档解析器 ──
        item(key = "rag_parser_group") {
            CardGroup(title = { Text(stringResource(R.string.settings_rag_parser_section)) }) {
                selectionOptionItem(
                    key = "rag_parser_local",
                    icon = MuseIcons.deviceMobile,
                    title = stringResource(R.string.settings_rag_parser_local),
                    selected = config.documentParserType == RagConfig.ParserType.LOCAL,
                    onClick = {
                        scope.launch {
                            settings.saveRagConfig(config.copy(documentParserType = RagConfig.ParserType.LOCAL))
                        }
                    },
                )
                selectionOptionItem(
                    key = "rag_parser_cloud",
                    icon = MuseIcons.cloud,
                    title = stringResource(R.string.settings_rag_parser_cloud),
                    selected = config.documentParserType == RagConfig.ParserType.CLOUD,
                    onClick = {
                        scope.launch {
                            settings.saveRagConfig(config.copy(documentParserType = RagConfig.ParserType.CLOUD))
                        }
                    },
                )
                selectionOptionItem(
                    key = "rag_parser_mineru",
                    icon = MuseIcons.fileText,
                    title = stringResource(R.string.settings_rag_parser_mineru),
                    selected = config.documentParserType == RagConfig.ParserType.MINERU,
                    onClick = {
                        scope.launch {
                            settings.saveRagConfig(config.copy(documentParserType = RagConfig.ParserType.MINERU))
                        }
                    },
                )
                if (config.documentParserType == RagConfig.ParserType.CLOUD) {
                    content(key = "rag_cloud_parser_endpoint") {
                        EndpointInputRow(
                            title = stringResource(R.string.settings_rag_cloud_parser_endpoint),
                            value = config.cloudParserEndpoint,
                            onValueChange = { v ->
                                scope.launch { settings.saveRagConfig(config.copy(cloudParserEndpoint = v)) }
                            },
                        )
                    }
                }
                if (config.documentParserType == RagConfig.ParserType.MINERU) {
                    content(key = "rag_mineru_endpoint") {
                        EndpointInputRow(
                            title = stringResource(R.string.settings_rag_mineru_endpoint),
                            value = config.mineruEndpoint,
                            onValueChange = { v ->
                                scope.launch { settings.saveRagConfig(config.copy(mineruEndpoint = v)) }
                            },
                        )
                    }
                    content(key = "rag_mineru_token") {
                        EndpointInputRow(
                            title = stringResource(R.string.settings_rag_mineru_token),
                            value = config.mineruToken,
                            placeholder = "sk-...",
                            secret = true,
                            onValueChange = { v ->
                                scope.launch { settings.saveRagConfig(config.copy(mineruToken = v)) }
                            },
                        )
                    }
                }
            }
        }

        // ── 4. 检索与重排参数 ──
        item(key = "rag_retrieval_group") {
            CardGroup(title = { Text(stringResource(R.string.settings_rag_search_params_section)) }) {
                content(key = "rag_top_k") {
                    SettingsSliderRow(
                        icon = MuseIcons.stack,
                        title = stringResource(R.string.settings_rag_top_k),
                        value = topKTemp.toFloat(),
                        valueRange = 1f..10f,
                        steps = 8,
                        valueText = "$topKTemp",
                        onValueChange = { v -> topKTemp = v.roundToInt() },
                        onValueChangeFinished = {
                            if (topKTemp != config.topK) {
                                scope.launch { settings.saveRagConfig(config.copy(topK = topKTemp)) }
                            }
                        },
                    )
                }
                content(key = "rag_threshold") {
                    SettingsSliderRow(
                        icon = MuseIcons.gauge,
                        title = stringResource(R.string.settings_rag_similarity_threshold),
                        value = thresholdTemp,
                        valueRange = 0f..1f,
                        steps = 19,
                        valueText = "%.2f".format(thresholdTemp),
                        onValueChange = { v -> thresholdTemp = v },
                        onValueChangeFinished = {
                            if (thresholdTemp != config.threshold) {
                                scope.launch { settings.saveRagConfig(config.copy(threshold = thresholdTemp)) }
                            }
                        },
                    )
                }
                content(key = "rag_mmr") {
                    SettingsSliderRow(
                        icon = MuseIcons.affiliate,
                        title = stringResource(R.string.settings_rag_mmr_lambda),
                        value = mmrLambdaTemp,
                        valueRange = 0f..1f,
                        steps = 19,
                        valueText = "%.2f".format(mmrLambdaTemp),
                        onValueChange = { v -> mmrLambdaTemp = v },
                        onValueChangeFinished = {
                            if (mmrLambdaTemp != config.mmrLambda) {
                                scope.launch { settings.saveRagConfig(config.copy(mmrLambda = mmrLambdaTemp)) }
                            }
                        },
                    )
                }
                switchItem(
                    key = "rag_hybrid",
                    title = stringResource(R.string.settings_rag_hybrid),
                    checked = config.hybridEnabled,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveRagConfig(config.copy(hybridEnabled = v)) }
                    },
                    leadingContent = {
                        Icon(
                            imageVector = MuseIcons.swapHorizontal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
                content(key = "rag_hybrid_bm25") {
                    SettingsSliderRow(
                        icon = MuseIcons.typography,
                        title = stringResource(R.string.settings_rag_hybrid_bm25_weight),
                        value = hybridBm25WeightTemp,
                        valueRange = 0f..2f,
                        steps = 19,
                        valueText = "%.2f".format(hybridBm25WeightTemp),
                        onValueChange = { v -> hybridBm25WeightTemp = v },
                        onValueChangeFinished = {
                            if (hybridBm25WeightTemp != config.hybridBm25Weight) {
                                scope.launch {
                                    settings.saveRagConfig(config.copy(hybridBm25Weight = hybridBm25WeightTemp))
                                }
                            }
                        },
                    )
                }
                content(key = "rag_hybrid_vector") {
                    SettingsSliderRow(
                        icon = MuseIcons.atom,
                        title = stringResource(R.string.settings_rag_hybrid_vector_weight),
                        value = hybridVectorWeightTemp,
                        valueRange = 0f..2f,
                        steps = 19,
                        valueText = "%.2f".format(hybridVectorWeightTemp),
                        onValueChange = { v -> hybridVectorWeightTemp = v },
                        onValueChangeFinished = {
                            if (hybridVectorWeightTemp != config.hybridVectorWeight) {
                                scope.launch {
                                    settings.saveRagConfig(config.copy(hybridVectorWeight = hybridVectorWeightTemp))
                                }
                            }
                        },
                    )
                }
                switchItem(
                    key = "rag_rerank",
                    title = stringResource(R.string.settings_rag_rerank),
                    checked = config.rerankEnabled,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveRagConfig(config.copy(rerankEnabled = v)) }
                    },
                    leadingContent = {
                        Icon(
                            imageVector = MuseIcons.sort,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
                content(key = "rag_token_budget") {
                    SettingsSliderRow(
                        icon = MuseIcons.wallet,
                        title = stringResource(R.string.settings_rag_token_budget),
                        value = tokenBudgetTemp.toFloat(),
                        valueRange = 0f..8000f,
                        steps = 79,
                        valueText = if (tokenBudgetTemp == 0) "∞" else "$tokenBudgetTemp",
                        onValueChange = { v -> tokenBudgetTemp = v.roundToInt() },
                        onValueChangeFinished = {
                            if (tokenBudgetTemp != config.tokenBudget) {
                                scope.launch { settings.saveRagConfig(config.copy(tokenBudget = tokenBudgetTemp)) }
                            }
                        },
                    )
                }
            }
        }

        // ── 5. 分块参数 ──
        item(key = "rag_chunk_group") {
            CardGroup(title = { Text(stringResource(R.string.settings_rag_chunk_params_section)) }) {
                content(key = "rag_chunk_size") {
                    SettingsSliderRow(
                        icon = MuseIcons.layoutColumns,
                        title = stringResource(R.string.settings_rag_chunk_size),
                        value = chunkSizeTemp.toFloat(),
                        valueRange = 100f..2000f,
                        steps = 18,
                        valueText = "$chunkSizeTemp",
                        onValueChange = { v -> chunkSizeTemp = v.roundToInt() },
                        onValueChangeFinished = {
                            if (chunkSizeTemp != config.chunkSize) {
                                scope.launch { settings.saveRagConfig(config.copy(chunkSize = chunkSizeTemp)) }
                            }
                        },
                    )
                }
                content(key = "rag_chunk_overlap") {
                    SettingsSliderRow(
                        icon = MuseIcons.layoutColumns,
                        title = stringResource(R.string.settings_rag_chunk_overlap),
                        value = chunkOverlapTemp.toFloat(),
                        valueRange = 0f..200f,
                        steps = 19,
                        valueText = "$chunkOverlapTemp",
                        onValueChange = { v -> chunkOverlapTemp = v.roundToInt() },
                        onValueChangeFinished = {
                            if (chunkOverlapTemp != config.chunkOverlap) {
                                scope.launch { settings.saveRagConfig(config.copy(chunkOverlap = chunkOverlapTemp)) }
                            }
                        },
                    )
                }
                switchItem(
                    key = "rag_markdown_aware",
                    title = stringResource(R.string.settings_rag_markdown_aware),
                    checked = config.markdownAware,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveRagConfig(config.copy(markdownAware = v)) }
                    },
                    leadingContent = {
                        Icon(
                            imageVector = MuseIcons.layoutColumns,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
                switchItem(
                    key = "rag_chunk_by_token",
                    title = stringResource(R.string.settings_rag_chunk_by_token),
                    checked = config.chunkByToken,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveRagConfig(config.copy(chunkByToken = v)) }
                    },
                    leadingContent = {
                        Icon(
                            imageVector = MuseIcons.layoutColumns,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
            }
        }

        // ── 6. 说明 ──
        item(key = "rag_notes_group") {
            CardGroup(title = { Text(stringResource(R.string.settings_rag_notes_section)) }) {
                content(key = "rag_notes") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MusePaddings.cardInner),
                        verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
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
}

private fun CardGroupScope.selectionOptionItem(
    key: String,
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val cleanSubtitle = subtitle?.takeIf { it.isNotBlank() }
    item(
        key = key,
        onClick = onClick,
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
        },
        headlineContent = { Text(title) },
        supportingContent = cleanSubtitle?.let { { Text(it) } },
        trailingContent = if (selected) {
            {
                Icon(
                    imageVector = MuseIcons.check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
            }
        } else {
            null
        },
    )
}

/**
 * 主动解析一次当前实际生效的 embedding 后端，让状态卡不必等第一次检索。
 */
@Suppress("TooGenericExceptionCaught")
private suspend fun resolveActiveBackendQuietly(
    embeddingService: io.zer0.muse.rag.EmbeddingService,
    config: RagConfig,
) {
    if (embeddingService.activeBackend.value != null) return
    try {
        embeddingService.getProvider(config)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(
            "RagSettingsPage",
            "解析 embedding 后端失败(source=${config.embeddingSource}): ${e.message ?: e::class.java.simpleName}",
        )
    }
}

/**
 * 解析器端点/密钥输入行：300ms 防抖保存，避免逐字写 DataStore。
 */
@Composable
private fun EndpointInputRow(
    title: String,
    value: String,
    placeholder: String = "https://example.com/api/parse",
    secret: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    var temp by remember(value) { mutableStateOf(value) }
    LaunchedEffect(temp) {
        delay(300)
        if (temp != value) onValueChange(temp)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
    ) {
        if (secret) {
            MuseSecretTextField(
                value = temp,
                onValueChange = { temp = it },
                label = title,
                placeholder = placeholder,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            MuseTextField(
                value = temp,
                onValueChange = { temp = it },
                label = { Text(title) },
                placeholder = { Text(placeholder) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Embedding 测试结果（成功/失败 + 可读消息）。 */
private data class TestResult(val ok: Boolean, val message: String)
