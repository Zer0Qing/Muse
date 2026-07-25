package io.zer0.muse.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.ChevronRight
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.common.MuseToast
import io.zer0.muse.ui.common.SectionLabel
import io.zer0.muse.ui.common.SettingsGroup
import io.zer0.muse.ui.common.SettingsGroupDivider
import io.zer0.muse.ui.common.SettingsItemRow
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.web.WebSearchConfig
import kotlinx.coroutines.launch

/**
 * 阶段 7: 联网搜索 section — iOS 风格分组列表。
 *
 * 用 [SettingsGroup] 包裹:搜索引擎选择 + API Key + endpoint。
 * v1.134 P0-7: 搜索引擎选择改用 MuseDialog 操作列表(替代 Material3 DropdownMenu)。
 * SearXNG / Tavily / Brave / 自定义 API 等多种 provider。
 */
@Composable
internal fun WebSearchSection(
    webSearchConfig: WebSearchConfig,
    settings: SettingsRepository,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val savedText = stringResource(R.string.settings_saved)

    SectionLabel(stringResource(R.string.settings_web_search_section))
    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        // P3 修复: 全局启用开关(原仅 InputBar chip 临时切换,设置页不可见)
        io.zer0.muse.ui.common.SettingsSwitchRow(
            icon = Icons.Outlined.Language,
            title = stringResource(R.string.settings_web_search_enable),
            subtitle = stringResource(R.string.settings_web_search_enable_subtitle),
            checked = webSearchConfig.enabled,
            onCheckedChange = { enabled ->
                scope.launch {
                    settings.saveWebSearchConfig(webSearchConfig.copy(enabled = enabled))
                }
            },
        )
        SettingsGroupDivider()
        // v1.134 P0-7: 搜索引擎选择行 → 点击弹 MuseDialog 操作列表(替代 DropdownMenu)
        var wsProviderExpanded by remember { mutableStateOf(false) }
        SettingsItemRow(
            icon = Icons.Outlined.Language,
            title = stringResource(R.string.settings_web_search_engine),
            subtitle = webSearchConfig.providerName,
            onClick = { wsProviderExpanded = true },
        ) {
            ChevronRight()
        }
        if (wsProviderExpanded) {
            MuseDialog(
                onDismissRequest = { wsProviderExpanded = false },
                title = stringResource(R.string.settings_web_search_engine),
                content = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp),
                    ) {
                        WebSearchConfig.SUPPORTED_PROVIDERS.forEach { p ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(role = Role.Button) {
                                        wsProviderExpanded = false
                                        scope.launch {
                                            settings.saveWebSearchConfig(webSearchConfig.copy(providerName = p))
                                        }
                                    }
                                    .padding(vertical = MusePaddings.inputPadding, horizontal = MusePaddings.iconPadding),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = p,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (p == webSearchConfig.providerName) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    fontWeight = if (p == webSearchConfig.providerName) FontWeight.SemiBold
                                    else FontWeight.Normal,
                                )
                            }
                        }
                    }
                },
                confirmText = stringResource(R.string.common_close),
                onConfirm = { wsProviderExpanded = false },
                onDismiss = { wsProviderExpanded = false },
            )
        }

        // v1.135: Auto 模式说明
        if (webSearchConfig.providerName == "Auto") {
            SettingsGroupDivider()
            Text(
                text = "Auto 模式会自动尝试多个搜索引擎:优先使用已配置 API Key 的商用引擎,随后依次 fallback 到 Bing、Jina、SearXNG,并自动过滤低质量结果。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(MusePaddings.cardInner),
            )
        }

        // v1.28: 自定义 API 的 API Key + endpoint 配置(Bing 不需要)
        // v1.98: 改为基于 WebSearchConfig.PROVIDERS_NEEDING_API_KEY 集合判断,
        //  覆盖 Brave / Perplexity / Exa / Bocha / Zhipu / Serper / Metaso / Firecrawl /
        //  Tavily / Custom API 等所有商用搜索 API(原先只对 "自定义 API" + "Tavily" 显示输入框,
        //  导致 Brave 等新 provider 选中后无法填 key)
        // v1.135: Auto 模式不显示单 key/endpoint 输入,其通过 apiKeys 映射管理多引擎 key
        val needsApiConfig = webSearchConfig.providerName in WebSearchConfig.PROVIDERS_NEEDING_API_KEY
        if (needsApiConfig) {
            SettingsGroupDivider()
            // v1.135: 优先显示当前 provider 在 apiKeys 中保存的 key,回退到全局 apiKey(兼容旧版)
            var apiKeyText by remember(webSearchConfig.providerName, webSearchConfig.apiKey) {
                mutableStateOf(webSearchConfig.apiKeys[webSearchConfig.providerName] ?: webSearchConfig.apiKey)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MusePaddings.cardInner),
            ) {
                SettingField(
                    label = stringResource(R.string.settings_web_search_api_key_label),
                    value = apiKeyText,
                    onValueChange = { apiKeyText = it },
                )
                // v1.134 P0-2: TextButton → Surface+clickable 胶囊
                SavePillButton(
                    text = stringResource(R.string.settings_web_search_save_api_key),
                    onClick = {
                        scope.launch {
                            val provider = webSearchConfig.providerName
                            val trimmedKey = apiKeyText.trim()
                            val newApiKeys = webSearchConfig.apiKeys.toMutableMap().apply {
                                if (trimmedKey.isNotEmpty()) put(provider, trimmedKey) else remove(provider)
                            }
                            settings.saveWebSearchConfig(
                                webSearchConfig.copy(
                                    apiKey = trimmedKey,
                                    apiKeys = newApiKeys,
                                )
                            )
                            MuseToast.show(savedText)
                        }
                    },
                )
            }
        }

        // 自定义 endpoint(可选,Bing 不需要)
        if (needsApiConfig) {
            SettingsGroupDivider()
            var endpointText by remember(webSearchConfig.endpoint) { mutableStateOf(webSearchConfig.endpoint) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MusePaddings.cardInner),
            ) {
                SettingField(
                    label = stringResource(R.string.settings_web_search_endpoint_label),
                    value = endpointText,
                    onValueChange = { endpointText = it },
                    placeholder = "https://your-search-api.com",
                )
                SavePillButton(
                    text = stringResource(R.string.settings_web_search_save_endpoint),
                    onClick = {
                        scope.launch {
                            settings.saveWebSearchConfig(webSearchConfig.copy(endpoint = endpointText.trim()))
                            MuseToast.show(savedText)
                        }
                    },
                )
            }
        }
    }
}

/**
 * v1.134 P0-2: iOS 风格保存胶囊按钮 — 替代 Material3 TextButton。
 *
 * surfaceVariant 背景 + onSurfaceVariant 文本,圆角 [MuseShapes.pill]。
 */
@Composable
private fun SavePillButton(
    text: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = MuseShapes.pill,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        onClick = onClick,
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(MusePaddings.cardInnerSpaced),
        )
    }
}
