@file:Suppress(
    "FunctionNaming",
    "LongMethod",
    "LongParameterList",
    "CyclomaticComplexMethod",
    "TooManyFunctions",
    "ReturnCount",
    "MaxLineLength",
    "ComplexCondition",
)

package io.zer0.muse.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.OAuthConfig
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.state.MuseErrorStateBox
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseBorderWidth
import io.zer0.muse.ui.theme.MuseButtonSize
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseListItemSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.ui.theme.statusColors

@Composable
internal fun AddModelDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
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
        dismissText = stringResource(R.string.common_cancel),
        onDismiss = onDismiss,
    )
}

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
    openAICustomHeadersText: String,
    onOpenAICustomHeadersTextChange: (String) -> Unit,
    openAICustomBodyText: String,
    onOpenAICustomBodyTextChange: (String) -> Unit,
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
    oauthConfig: OAuthConfig? = null,
    onOAuthLogin: () -> Unit = {},
    isOAuthLoading: Boolean = false,
    hasStoredOAuthToken: Boolean = false,
    onRevokeOAuth: () -> Unit = {},
    requestLimitPerMinuteText: String = "",
    onRequestLimitPerMinuteTextChange: (String) -> Unit = {},
    maxConcurrentRequestsText: String = "",
    onMaxConcurrentRequestsTextChange: (String) -> Unit = {},
    canActivate: Boolean = false,
    onActivate: () -> Unit = {},
    canDelete: Boolean = false,
    onDeleteRequest: () -> Unit = {},
) {
    val cannotConnectPrefix = stringResource(R.string.settings_provider_prefix_cannot_connect)
    val fetchModelsFailedPrefix = stringResource(R.string.settings_provider_prefix_fetch_models_failed)
    val queryFailedPrefix = stringResource(R.string.settings_provider_prefix_query_failed)
    val configureFirstPrefix = stringResource(R.string.settings_provider_prefix_configure)
    val pathNotFoundPrefix = stringResource(R.string.settings_provider_prefix_path_not_found)
    val unableToResolvePrefix = stringResource(R.string.settings_provider_prefix_unable_to_resolve)
    val queryTimeoutPrefix = stringResource(R.string.settings_provider_prefix_query_timeout)
    val stateEnabledText = stringResource(R.string.common_state_enabled)
    val stateDisabledText = stringResource(R.string.common_state_disabled)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = MusePaddings.pageMargin,
            end = MusePaddings.pageMargin,
            top = MuseSpacing.sm,
            bottom = MuseSpacing.xxxl,
        ),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.cardGap),
    ) {
        // 卡片 1：名称、协议、连接凭据与就近测试连接行
        item(key = "provider_core_card") {
            CardGroup {
                item(key = "core_fields") {
                    Column(
                        modifier = Modifier.padding(MusePaddings.cardInner),
                        verticalArrangement = Arrangement.spacedBy(MuseSpacing.md),
                    ) {
                        SettingField(
                            label = stringResource(R.string.settings_provider_display_name),
                            value = displayName,
                            onValueChange = onDisplayNameChange,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(MuseSpacing.smTight)) {
                            Text(
                                text = stringResource(R.string.settings_provider_type),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                            ) {
                                ProviderType.entries.forEach { t ->
                                    MuseChip(
                                        selected = type == t,
                                        onClick = { onTypeChange(t) },
                                        label = providerDisplayTypeName(t),
                                    )
                                }
                            }
                        }
                        SettingField(
                            label = stringResource(R.string.settings_provider_base_url),
                            value = baseUrl,
                            onValueChange = onBaseUrlChange,
                            placeholder = when (type) {
                                ProviderType.OPENAI -> ProviderConfig.DEFAULT_OPENAI_BASE_URL
                                ProviderType.ANTHROPIC -> ProviderConfig.DEFAULT_ANTHROPIC_BASE_URL
                                ProviderType.GEMINI -> ProviderConfig.DEFAULT_GEMINI_BASE_URL
                                ProviderType.OPENAI_RESPONSES -> ProviderConfig.DEFAULT_OPENAI_RESPONSES_BASE_URL
                            },
                        )
                        ApiKeyPoolField(
                            apiKey = apiKey,
                            onApiKeyChange = onApiKeyChange,
                            apiKeyVisible = apiKeyVisible,
                            onApiKeyVisibleChange = onApiKeyVisibleChange,
                        )

                        if (oauthConfig != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                            ) {
                                MuseCapsuleButton(
                                    text = stringResource(R.string.oauth_login),
                                    onClick = onOAuthLogin,
                                    enabled = !isOAuthLoading,
                                    loading = isOAuthLoading,
                                    leadingIcon = MuseIcons.lock,
                                    variant = IosCapsuleButtonVariant.Secondary,
                                    size = MuseButtonSize.Compact,
                                    fillWidth = false,
                                )
                                if (hasStoredOAuthToken) {
                                    MuseCapsuleButton(
                                        text = stringResource(R.string.oauth_revoke),
                                        onClick = onRevokeOAuth,
                                        enabled = !isOAuthLoading,
                                        destructive = true,
                                        size = MuseButtonSize.Compact,
                                        fillWidth = false,
                                    )
                                }
                                if (isOAuthLoading) {
                                    Text(
                                        text = stringResource(R.string.oauth_polling),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
                item(
                    key = "test_connection_action",
                    onClick = if (canTestConnection) onTestConnection else null,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = MuseListItemSizes.compactMinHeight)
                            .padding(horizontal = MusePaddings.screen, vertical = MuseSpacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        if (isTestingConnection) {
                            MuseSpinner(
                                size = MuseIconSizes.iconSmall,
                                strokeWidth = 1.5.dp,
                            )
                            Spacer(Modifier.size(MuseSpacing.sm))
                        }
                        Text(
                            text = stringResource(R.string.settings_provider_test_connection_endpoint),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = if (canTestConnection) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = MuseAlpha.disabled)
                            },
                        )
                    }
                }
            }
        }

        // 测试结果反馈条：独立占满整行宽度，确保长错误原因完整可读。
        if (testConnectionResult != null || testConnectionError != null) {
            item(key = "provider_test_feedback") {
                val isSuccess = testConnectionResult != null
                val message = testConnectionResult ?: testConnectionError.orEmpty()
                val statusColors = MaterialTheme.statusColors
                val tint = if (isSuccess) statusColors.success else statusColors.error
                val bg = if (isSuccess) statusColors.successContainer else statusColors.errorContainer
                Surface(
                    shape = MuseShapes.medium,
                    color = bg,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MusePaddings.cardInner),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                    ) {
                        Icon(
                            imageVector = if (isSuccess) MuseIcons.check else MuseIcons.x,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        MuseTactileButton(
                            icon = MuseIcons.x,
                            onClick = onTestResultDismiss,
                            contentDescription = stringResource(R.string.common_close),
                            tint = tint,
                            compact = true,
                        )
                    }
                }
            }
        }

        if (fetchError != null) {
            item(key = "provider_fetch_error") {
                Column(verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs)) {
                    MuseErrorStateBox(
                        message = fetchError,
                        onRetry = onFetchRetry,
                        onDismiss = onFetchErrorDismiss,
                    )
                    if (fetchError.contains(cannotConnectPrefix) ||
                        fetchError.contains("404") ||
                        fetchError.contains(fetchModelsFailedPrefix)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_provider_manual_add_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = MuseSpacing.xs),
                        )
                    }
                }
            }
        }

        // 卡片 2：启用开关与设为当前默认供应商
        item(key = "provider_status_card") {
            CardGroup {
                item(key = "enabled_row") {
                    ProviderSwitchRow(
                        label = stringResource(R.string.settings_provider_enable_this),
                        checked = enabled,
                        onCheckedChange = onEnabledChange,
                        stateEnabledText = stateEnabledText,
                        stateDisabledText = stateDisabledText,
                        modifier = Modifier.padding(MusePaddings.cardInner),
                    )
                }
                if (canActivate) {
                    item(
                        key = "activate_row",
                        onClick = onActivate,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = MuseListItemSizes.compactMinHeight)
                                .padding(MusePaddings.cardInner),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.settings_provider_action_activate),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }

        // 卡片 3：Vertex AI（仅 GEMINI 类型显示）
        if (type == ProviderType.GEMINI) {
            item(key = "provider_vertex_card") {
                CardGroup {
                    item(key = "vertex_fields") {
                        Column(
                            modifier = Modifier.padding(MusePaddings.cardInner),
                            verticalArrangement = Arrangement.spacedBy(MuseSpacing.md),
                        ) {
                            ProviderSwitchRow(
                                label = stringResource(R.string.settings_provider_enable_vertex),
                                checked = useVertexAi,
                                onCheckedChange = onUseVertexAiChange,
                                stateEnabledText = stateEnabledText,
                                stateDisabledText = stateDisabledText,
                            )
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
                                ProviderSwitchRow(
                                    label = stringResource(R.string.settings_provider_use_service_account),
                                    checked = useServiceAccount,
                                    onCheckedChange = onUseServiceAccountChange,
                                    stateEnabledText = stateEnabledText,
                                    stateDisabledText = stateDisabledText,
                                )
                                if (useServiceAccount) {
                                    MuseCapsuleButton(
                                        text = stringResource(R.string.settings_provider_import_from_json),
                                        onClick = onImportServiceAccountJson,
                                        leadingIcon = MuseIcons.upload,
                                        variant = IosCapsuleButtonVariant.Secondary,
                                        size = MuseButtonSize.Compact,
                                        fillWidth = false,
                                    )
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
                                        visualTransformation = if (privateKeyVisible) {
                                            VisualTransformation.None
                                        } else {
                                            PasswordVisualTransformation()
                                        },
                                        trailingIcon = {
                                            MuseTactileButton(
                                                icon = if (privateKeyVisible) MuseIcons.eyeOff else MuseIcons.eye,
                                                onClick = { onPrivateKeyVisibleChange(!privateKeyVisible) },
                                                contentDescription = stringResource(
                                                    if (privateKeyVisible) {
                                                        R.string.settings_common_hide
                                                    } else {
                                                        R.string.settings_common_show
                                                    },
                                                ),
                                                compact = true,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 卡片 4：高级设置（折叠）
        item(key = "provider_advanced_card") {
            CardGroup {
                item(
                    key = "advanced_toggle",
                    onClick = { onShowAdvancedChange(!showAdvanced) },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = MuseListItemSizes.compactMinHeight)
                            .padding(MusePaddings.cardInner),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.settings_common_advanced),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            imageVector = if (showAdvanced) MuseIcons.chevronUp else MuseIcons.chevronDown,
                            contentDescription = stringResource(
                                if (showAdvanced) R.string.settings_common_collapse else R.string.common_expand,
                            ),
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                    }
                }
                if (showAdvanced) {
                    item(key = "advanced_fields") {
                        Column(
                            modifier = Modifier.padding(MusePaddings.cardInner),
                            verticalArrangement = Arrangement.spacedBy(MuseSpacing.md),
                        ) {
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
                            val balanceEnabled = !isQueryingBalance && balanceApiPath.isNotBlank() && baseUrl.isNotBlank()
                            MuseCapsuleButton(
                                text = stringResource(R.string.settings_provider_query_balance),
                                onClick = onQueryBalance,
                                enabled = balanceEnabled,
                                loading = isQueryingBalance,
                                leadingIcon = MuseIcons.wallet,
                                variant = IosCapsuleButtonVariant.Secondary,
                                size = MuseButtonSize.Compact,
                                fillWidth = false,
                            )
                            balanceResult?.let { result ->
                                val isError = result.startsWith(queryFailedPrefix) ||
                                    result.startsWith(configureFirstPrefix) ||
                                    result.startsWith(pathNotFoundPrefix) ||
                                    result.startsWith(unableToResolvePrefix) ||
                                    result.startsWith(queryTimeoutPrefix)
                                Text(
                                    text = result,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isError) {
                                        MaterialTheme.statusColors.error
                                    } else {
                                        MaterialTheme.statusColors.success
                                    },
                                )
                            }

                            when (type) {
                                ProviderType.OPENAI, ProviderType.OPENAI_RESPONSES -> {
                                    HorizontalDivider(
                                        thickness = MuseBorderWidth.hairline,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = MuseAlpha.divider),
                                    )
                                    if (isCustomSpecific) {
                                        Text(
                                            text = stringResource(R.string.settings_provider_custom_specific),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                            label = { Text(stringResource(R.string.settings_provider_custom_headers)) },
                                            placeholder = { Text("Authorization: Bearer xxx") },
                                            minLines = 2,
                                            maxLines = 6,
                                        )
                                        MuseTextField(
                                            value = customBodyText,
                                            onValueChange = onCustomBodyTextChange,
                                            modifier = Modifier.fillMaxWidth(),
                                            label = { Text(stringResource(R.string.settings_provider_custom_body)) },
                                            placeholder = { Text("{\"seed\": 42}") },
                                            minLines = 2,
                                            maxLines = 6,
                                        )
                                    } else {
                                        Text(
                                            text = stringResource(R.string.settings_provider_openai_specific),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        SettingField(
                                            label = stringResource(R.string.settings_provider_chat_completions_path),
                                            value = openAIChatCompletionsPath,
                                            onValueChange = onOpenAIChatCompletionsPathChange,
                                            placeholder = "/chat/completions",
                                        )
                                        ProviderSwitchRow(
                                            label = stringResource(R.string.settings_provider_use_responses_api),
                                            checked = openAIUseResponseApi,
                                            onCheckedChange = onOpenAIUseResponseApiChange,
                                            stateEnabledText = stateEnabledText,
                                            stateDisabledText = stateDisabledText,
                                        )
                                        ProviderSwitchRow(
                                            label = stringResource(R.string.settings_provider_include_history_reasoning),
                                            checked = openAIIncludeHistoryReasoning,
                                            onCheckedChange = onOpenAIIncludeHistoryReasoningChange,
                                            stateEnabledText = stateEnabledText,
                                            stateDisabledText = stateDisabledText,
                                        )
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
                                        MuseTextField(
                                            value = openAICustomHeadersText,
                                            onValueChange = onOpenAICustomHeadersTextChange,
                                            modifier = Modifier.fillMaxWidth(),
                                            label = { Text(stringResource(R.string.settings_provider_custom_headers)) },
                                            placeholder = { Text("x-opencode-session: my-session") },
                                            minLines = 2,
                                            maxLines = 6,
                                        )
                                        MuseTextField(
                                            value = openAICustomBodyText,
                                            onValueChange = onOpenAICustomBodyTextChange,
                                            modifier = Modifier.fillMaxWidth(),
                                            label = { Text(stringResource(R.string.settings_provider_custom_body)) },
                                            placeholder = { Text("{\"seed\": 42}") },
                                            minLines = 2,
                                            maxLines = 6,
                                        )
                                    }
                                }
                                ProviderType.ANTHROPIC -> {
                                    HorizontalDivider(
                                        thickness = MuseBorderWidth.hairline,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = MuseAlpha.divider),
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_provider_anthropic_specific),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    ProviderSwitchRow(
                                        label = stringResource(R.string.settings_provider_enable_prompt_caching),
                                        checked = anthropicPromptCaching,
                                        onCheckedChange = onAnthropicPromptCachingChange,
                                        stateEnabledText = stateEnabledText,
                                        stateDisabledText = stateDisabledText,
                                    )
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
                                ProviderType.GEMINI -> Unit
                            }
                        }
                    }
                }
            }
        }

        // 卡片 5：破坏性操作独立沉底，避免误触
        if (canDelete) {
            item(key = "provider_delete_card") {
                CardGroup {
                    item(
                        key = "delete_row",
                        onClick = onDeleteRequest,
                    ) {
                        Text(
                            text = stringResource(R.string.settings_provider_delete_title),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(MusePaddings.cardInner),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    stateEnabledText: String,
    stateDisabledText: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.md),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        MuseSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics {
                stateDescription = if (checked) stateEnabledText else stateDisabledText
            },
        )
    }
}

/**
 * API Key 输入组件（兼容单 Key 精简展示与多 Key 轮换池）。
 *
 * 单 Key 场景下保持单行密码输入框；当检测到逗号/换行分隔的多个 Key 时，
 * 自动展开为多行并展示逐条移除入口与数量提示，保持与 KeyRoulette 格式兼容。
 */
@Composable
internal fun ApiKeyPoolField(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    apiKeyVisible: Boolean,
    onApiKeyVisibleChange: (Boolean) -> Unit,
    activeKey: String? = null,
) {
    val keys = remember(apiKey) { parseApiKeys(apiKey) }
    val isMultiKey = keys.size > 1 || apiKey.contains('\n') || apiKey.contains(',')

    Column(verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm)) {
        MuseTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.settings_screen_entry_api_key)) },
            placeholder = {
                Text(
                    text = stringResource(R.string.settings_provider_api_key_pool_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            singleLine = !isMultiKey,
            minLines = if (isMultiKey) 2 else 1,
            maxLines = if (isMultiKey) 5 else 1,
            visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                MuseTactileButton(
                    icon = if (apiKeyVisible) MuseIcons.eyeOff else MuseIcons.eye,
                    onClick = { onApiKeyVisibleChange(!apiKeyVisible) },
                    contentDescription = stringResource(
                        if (apiKeyVisible) R.string.settings_common_hide else R.string.settings_common_show,
                    ),
                    compact = true,
                )
            },
        )

        if (isMultiKey) {
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (keys.isNotEmpty()) {
                    MuseCapsuleButton(
                        text = stringResource(R.string.settings_provider_clear_keys),
                        onClick = { onApiKeyChange("") },
                        variant = IosCapsuleButtonVariant.Text,
                        size = MuseButtonSize.Compact,
                        fillWidth = false,
                    )
                }
            }

            keys.forEachIndexed { index, key ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                ) {
                    Text(
                        text = if (apiKeyVisible) key else maskApiKey(key),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    MuseTactileButton(
                        icon = MuseIcons.x,
                        onClick = {
                            val remaining = keys.filterIndexed { i, _ -> i != index }
                            onApiKeyChange(remaining.joinToString(","))
                        },
                        contentDescription = stringResource(R.string.settings_provider_remove_key),
                        tint = MaterialTheme.colorScheme.error,
                        compact = true,
                    )
                }
            }
        }
    }
}

private fun parseApiKeys(keysString: String): List<String> {
    return keysString.split(",", "\n", "\r\n")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}

private fun maskApiKey(key: String): String {
    return if (key.length <= 6) {
        "*".repeat(key.length)
    } else {
        key.take(3) + "***" + key.takeLast(3)
    }
}
