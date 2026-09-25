@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "CyclomaticComplexMethod", "TooManyFunctions", "ReturnCount", "TooGenericExceptionCaught", "SwallowedException", "MaxLineLength", "ComplexCondition", "UseCheckOrError", "UnusedPrivateProperty")

package io.zer0.muse.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseTextField
import androidx.compose.material3.Surface
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseSwitch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.OAuthConfig
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.muse.R
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.state.MuseErrorStateBox
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.museBottomBarInsets
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.huge
import io.zer0.muse.ui.theme.pill

@Composable
internal fun ProviderEditBottomBar(
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
            .museBottomBarInsets(),
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
                        MuseSpinner(
                            size = 16.dp,
                            color = fetchContentColor,
                        )
                    } else {
                        Icon(
                            imageVector = MuseIcons.refresh,
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
                        imageVector = MuseIcons.plus,
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
                        imageVector = MuseIcons.trash,
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
internal fun AddModelDialog(
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
internal fun ConfigTab(
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
    onFetchRetry: () -> Unit = {},
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
    // P1-3: 限流参数(RPM + 最大并发,空串表示不限)
    requestLimitPerMinuteText: String = "",
    onRequestLimitPerMinuteTextChange: (String) -> Unit = {},
    maxConcurrentRequestsText: String = "",
    onMaxConcurrentRequestsTextChange: (String) -> Unit = {},
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
                    // F-11: 多 Key 池输入(按行/逗号分隔,支持多 key 的 429/401 轮换)
                    ApiKeyPoolField(
                        apiKey = apiKey,
                        onApiKeyChange = onApiKeyChange,
                        apiKeyVisible = apiKeyVisible,
                        onApiKeyVisibleChange = onApiKeyVisibleChange,
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
                                        MuseSpinner(
                                            size = MuseIconSizes.iconTiny,
                                            color = oauthBtnContentColor,
                                        )
                                    } else {
                                        Icon(
                                            imageVector = MuseIcons.lock,
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
                                            imageVector = MuseIcons.x,
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


                    // v2.4: 独立测试连接按钮 + 结果胶囊(既有实现/既有实现)
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
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isTestingConnection) {
                                        MuseSpinner(
                                            size = MuseIconSizes.iconTiny,
                                            color = testBtnContentColor,
                                        )
                                    } else {
                                        Icon(
                                            imageVector = MuseIcons.globe,
                                            contentDescription = null,
                                            tint = testBtnContentColor,
                                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                                        )
                                    }
                                    Spacer(Modifier.size(6.dp))
                                    // F-12: 端点测试(仅调 /models)与模型测试(完整链路)语义区分
                                    Text(
                                        text = stringResource(R.string.settings_provider_test_connection_endpoint),
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                        color = testBtnContentColor,
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.settings_provider_test_connection_endpoint_sub),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = testBtnContentColor.copy(alpha = 0.8f),
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
                                        imageVector = if (isSuccess) MuseIcons.check else MuseIcons.x,
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
                                        icon = MuseIcons.x,
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
                MuseErrorStateBox(
                    message = fetchError,
                    onRetry = onFetchRetry,
                    onDismiss = onFetchErrorDismiss,
                )
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
                                        MuseIcons.upload,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                                    )
                                    Spacer(Modifier.size(MusePaddings.contentGap))
                                    MuseCapsuleButton(
                                        text = stringResource(R.string.settings_provider_import_from_json),
                                        onClick = onImportServiceAccountJson,
                                        variant = IosCapsuleButtonVariant.Text,
                                        fillWidth = false,
                                    )
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
                                            icon = if (privateKeyVisible) MuseIcons.eyeOff else MuseIcons.eye,
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
                            imageVector = if (showAdvanced) MuseIcons.chevronUp else MuseIcons.chevronDown,
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
                        // P1-3: 限流参数 — RPM(每分钟最大请求数)+ 最大并发请求数
                        // 空 = 不限;> 0 时 ProviderRegistry.create 叠加 RateLimitDecorator
                        SettingField(
                            label = stringResource(R.string.settings_provider_rate_limit_rpm),
                            value = requestLimitPerMinuteText,
                            onValueChange = onRequestLimitPerMinuteTextChange,
                            placeholder = stringResource(R.string.settings_provider_rate_limit_rpm_hint),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        SettingField(
                            label = stringResource(R.string.settings_provider_max_concurrent),
                            value = maxConcurrentRequestsText,
                            onValueChange = onMaxConcurrentRequestsTextChange,
                            placeholder = stringResource(R.string.settings_provider_max_concurrent_hint),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
                                    MuseSpinner(
                                        size = 16.dp,
                                    )
                                } else {
                                    Icon(
                                        MuseIcons.wallet,
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

/**
 * F-11: 多 Key 池输入组件。
 *
 * 将 provider 的 API Key 字符串(逗号/换行分隔,与 [io.zer0.ai.util.KeyRoulette] 解析格式一致)
 * 拆分为多行展示,支持:
 *  - 顶部多行 TextArea 直接编辑/粘贴多个 key
 *  - 指示行:有活动 key 时显示其脱敏尾号(如 sk-***aB3),否则显示"已配置 N 个 Key"
 *  - 每行末尾删除按钮(逐条删除)
 *  - "全部清空"按钮
 *
 * 持久化仍为逗号分隔字符串,与现有 KeyRoulette 轮换逻辑兼容。
 *
 * @param apiKey 原始 key 字符串(逗号或换行分隔)
 * @param onApiKeyChange 保存时回传更新后的字符串
 * @param apiKeyVisible 是否明文显示
 * @param onApiKeyVisibleChange 明文/掩码切换回调
 * @param activeKey 当前正在使用的 key 脱敏尾号;为 null 时显示配置个数
 */
@Composable
internal fun ApiKeyPoolField(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    apiKeyVisible: Boolean,
    onApiKeyVisibleChange: (Boolean) -> Unit,
    activeKey: String? = null,
) {
    // 按 KeyRoulette 解析语法拆分(逗号/换行),记住便于稳定渲染删除行
    val keys = remember(apiKey) { parseApiKeys(apiKey) }

    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
        // 多行输入区:支持直接粘贴多个 key(逗号或换行分隔)
        MuseTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.settings_provider_api_key_pool)) },
            minLines = 3,
            maxLines = 8,
            visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                MuseTactileButton(
                    icon = if (apiKeyVisible) MuseIcons.eyeOff else MuseIcons.eye,
                    onClick = { onApiKeyVisibleChange(!apiKeyVisible) },
                    contentDescription = if (apiKeyVisible) stringResource(R.string.settings_common_hide) else stringResource(R.string.settings_common_show),
                )
            },
        )

        // 指示行:活动 key 尾号(有信息时)或已配置个数 + "全部清空"
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val indicator = activeKey
                ?.takeIf { it.isNotBlank() }
                ?.let { stringResource(R.string.settings_provider_active_key, it) }
                ?: stringResource(R.string.settings_provider_keys_count, keys.size)
            Text(
                text = indicator,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f),
            )
            if (keys.isNotEmpty()) {
                MuseCapsuleButton(
                    text = stringResource(R.string.settings_provider_clear_keys),
                    onClick = { onApiKeyChange("") },
                    variant = IosCapsuleButtonVariant.Text,
                    fillWidth = false,
                )
            }
        }

        // 逐条 Key 展示 + 每行删除按钮
        keys.forEachIndexed { index, key ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (apiKeyVisible) key else maskApiKey(key),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                MuseTactileButton(
                    icon = MuseIcons.trash,
                    onClick = {
                        val remaining = keys.filterIndexed { i, _ -> i != index }
                        onApiKeyChange(remaining.joinToString(","))
                    },
                    contentDescription = stringResource(R.string.settings_provider_remove_key),
                    size = 32.dp,
                    iconSize = MuseIconSizes.iconSmall,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * 按 KeyRoulette 的解析语法拆分 key 字符串(与 [io.zer0.ai.util.KeyRoulette] 内部
 * parseKeys 保持一致:以逗号/换行分隔,去首尾空白并过滤空项)。
 */
private fun parseApiKeys(keysString: String): List<String> {
    return keysString.split(",", "\n", "\r\n")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}

/**
 * 生成 key 的脱敏尾号,格式如 "sk-***aB3"(保留头部 3 位 + 尾部 3 位)。
 * 对过短(<=6 位)的 key 全部掩码,避免泄露信息。
 */
private fun maskApiKey(key: String): String {
    return if (key.length <= 6) {
        "*".repeat(key.length)
    } else {
        key.take(3) + "***" + key.takeLast(3)
    }
}

