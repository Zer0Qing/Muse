@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "CyclomaticComplexMethod", "TooManyFunctions", "ReturnCount", "TooGenericExceptionCaught", "SwallowedException", "MaxLineLength", "ComplexCondition", "UseCheckOrError")

package io.zer0.muse.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import compose.icons.TablerIcons
import compose.icons.tablericons.Qrcode
import androidx.compose.material3.MaterialTheme
import io.zer0.common.Result
import io.zer0.common.resultOf
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import io.zer0.muse.ui.common.navigation.MuseTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.ai.ProviderRegistry
import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelListCache
import io.zer0.ai.core.ModelRegistry
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderSpecificConfig
import io.zer0.ai.core.ProviderType
import io.zer0.muse.R
import io.zer0.muse.auth.OAuthManager
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

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
    // P1-3: 限流参数(RPM + 最大并发)。用 String 状态绑定文本框,保存时解析为 Int(空/非法 → 0=不限)
    var requestLimitPerMinuteText by rememberSaveable { mutableStateOf(config.requestLimitPerMinute.takeIf { it > 0 }?.toString() ?: "") }
    var maxConcurrentRequestsText by rememberSaveable { mutableStateOf(config.maxConcurrentRequests.takeIf { it > 0 }?.toString() ?: "") }

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
    // v1.132 优化(既有实现/既有实现):
    //  - 缓存优先:5 分钟 TTL,命中直接返回,跳过网络(按 既有实现 的文件级缓存)
    //  - URL 多策略补全:base → base+/v1 → 剥离尾部 /v1 后重试(覆盖用户漏填/多填场景)
    //  - 错误分级:401/403 立即报错不 fallback(凭证问题不掩盖,按 既有实现)
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

    // v2.4: 独立测试连接业务逻辑(既有实现/既有实现)
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
            // P1-3: 限流参数(健康检查/拉取模型时也带上,保持配置一致)
            requestLimitPerMinute = requestLimitPerMinuteText.trim().toIntOrNull() ?: 0,
            maxConcurrentRequests = maxConcurrentRequestsText.trim().toIntOrNull() ?: 0,
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
            // P1-3: 限流参数(0 表示不限,RateLimitDecorator 在 ProviderRegistry.create 时按需叠加)
            requestLimitPerMinute = requestLimitPerMinuteText.trim().toIntOrNull() ?: 0,
            maxConcurrentRequests = maxConcurrentRequestsText.trim().toIntOrNull() ?: 0,
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
    val oauthState by OAuthManager.stateFlow.collectAsStateWithLifecycle()
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
            // B-24: 把持久化的 config.oauthConfig 传入刷新路径 — 内存 providerConfigs 只在
            // launch* 流程填充,App 重启后为空,必须显式注入 config 才能保证跨重启自动刷新可用。
            OAuthManager.refreshTokenIfNeeded(config.id, config.oauthConfig)
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
                        onFetchRetry = { fetchModels(true) },
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
                        // P1-3: 透传限流参数
                        requestLimitPerMinuteText = requestLimitPerMinuteText,
                        onRequestLimitPerMinuteTextChange = { requestLimitPerMinuteText = it.filter { ch -> ch.isDigit() } },
                        maxConcurrentRequestsText = maxConcurrentRequestsText,
                        onMaxConcurrentRequestsTextChange = { maxConcurrentRequestsText = it.filter { ch -> ch.isDigit() } },
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
                        isFetching = isFetchingModels,
                        onFetch = fetchModels,
                        onAddModel = { showAddModelDialog = true },
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

