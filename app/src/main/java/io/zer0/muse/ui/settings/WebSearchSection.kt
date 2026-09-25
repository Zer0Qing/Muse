package io.zer0.muse.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.web.CompositeWebSearchService
import io.zer0.muse.web.WebSearchConfig
import io.zer0.muse.web.WebSearchCoordinator
import io.zer0.muse.web.WebSearchMode
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WebSearchSection(
    webSearchConfig: WebSearchConfig,
    settings: SettingsRepository,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val savedText = stringResource(R.string.settings_saved)
    val coordinator: WebSearchCoordinator = org.koin.compose.koinInject()
    val lastSearch by coordinator.lastResponse.collectAsStateWithLifecycle()
    var wsProviderExpanded by remember { mutableStateOf(false) }
    var apiKeyText by remember(webSearchConfig.providerName) {
        mutableStateOf(webSearchConfig.apiKeys[webSearchConfig.providerName] ?: webSearchConfig.apiKey)
    }
    var endpointText by remember(webSearchConfig.endpoint) { mutableStateOf(webSearchConfig.endpoint) }
    var testQuery by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    SectionLabel(stringResource(R.string.settings_web_search_section))
    SettingsGroup(modifier = Modifier.padding(top = 8.dp)) {
        SettingsSwitchRow(
            icon = MuseIcons.languages,
            title = stringResource(R.string.settings_web_search_enable),
            subtitle = stringResource(R.string.settings_web_search_enable_subtitle),
            checked = webSearchConfig.enabled,
            onCheckedChange = { enabled ->
                scope.launch {
                    settings.saveWebSearchConfig(
                        webSearchConfig.copy(
                            enabled = enabled,
                            mode = if (enabled) {
                                webSearchConfig.mode.takeUnless { it == WebSearchMode.OFF } ?: WebSearchMode.AUTO
                            } else {
                                WebSearchMode.OFF
                            },
                        ),
                    )
                }
            },
        )
    }

    // ST-10 (A11Y-03): 分区间距由固定高度改为 heightIn(min = …) — 纯间距不裁剪文本,
    // 但保证字号放大 1.3x 时不会被写死的高度限制。
    Spacer(Modifier.heightIn(min = 12.dp))
    SectionLabel(stringResource(R.string.settings_web_search_mode))
    SettingsGroup {
        Column(Modifier.padding(MusePaddings.cardInner)) {
            Text(stringResource(R.string.settings_web_search_mode_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            // 必须用会换行的 FlowRow:四个模式标签在窄屏一行放不下,Row 会把最后一个
            // chip 压成竖排文字甚至挤出屏幕(用户实测截图)。
            // ST-07: FilterChip(M3) → 项目自有 MuseChip(已支持 selected/Role/48dp 触控)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MuseChip(
                    selected = webSearchConfig.mode == WebSearchMode.OFF || !webSearchConfig.enabled,
                    onClick = { scope.launch { settings.saveWebSearchConfig(webSearchConfig.copy(mode = WebSearchMode.OFF, enabled = false)) } },
                    label = stringResource(R.string.settings_web_search_mode_off),
                )
                MuseChip(
                    selected = webSearchConfig.mode == WebSearchMode.AUTO && webSearchConfig.enabled,
                    onClick = { scope.launch { settings.saveWebSearchConfig(webSearchConfig.copy(mode = WebSearchMode.AUTO, enabled = true)) } },
                    label = stringResource(R.string.settings_web_search_mode_auto),
                )
                MuseChip(
                    selected = webSearchConfig.mode == WebSearchMode.LOCAL && webSearchConfig.enabled,
                    onClick = { scope.launch { settings.saveWebSearchConfig(webSearchConfig.copy(mode = WebSearchMode.LOCAL, enabled = true)) } },
                    label = stringResource(R.string.settings_web_search_mode_local),
                )
                MuseChip(
                    selected = webSearchConfig.mode == WebSearchMode.NATIVE && webSearchConfig.enabled,
                    onClick = {
                        scope.launch {
                            settings.saveWebSearchConfig(
                                webSearchConfig.copy(mode = WebSearchMode.NATIVE, enabled = true),
                            )
                        }
                    },
                    label = stringResource(R.string.settings_web_search_mode_native),
                )
            }
            if (webSearchConfig.mode == WebSearchMode.NATIVE) {
                Text(stringResource(R.string.settings_web_search_native_unavailable), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }

    Spacer(Modifier.heightIn(min = 12.dp))
    SectionLabel(stringResource(R.string.settings_web_search_default_path))
    SettingsGroup {
        Text(
            text = stringResource(R.string.settings_web_search_default_path_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(MusePaddings.cardInner),
        )
        PathRow(MuseIcons.brandBing, "Bing HTTP", stringResource(R.string.settings_web_search_provider_status_bing), true)
        HorizontalDivider(modifier = Modifier.padding(start = 52.dp))
        PathRow(MuseIcons.globe, "百度 HTTP", stringResource(R.string.settings_web_search_provider_status_jina), false)
        HorizontalDivider(modifier = Modifier.padding(start = 52.dp))
        PathRow(MuseIcons.search, "用户 API", stringResource(R.string.settings_web_search_provider_status_searxng), false)
    }

    Spacer(Modifier.heightIn(min = 12.dp))
    SectionLabel(stringResource(R.string.settings_web_search_strategy))
    SettingsGroup {
        Column(Modifier.padding(MusePaddings.cardInner)) {
            Text(stringResource(R.string.settings_web_search_budget), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.heightIn(min = 8.dp))
            // ST-07: 数值 chip 也用 FlowRow — MuseChip 触控目标 48dp、内边距更大,
            // 窄屏 + 大字号下 4 个 chip 一行放不下,Row 会挤压(MuseChip 最小宽度不可压)。
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(5, 10, 20, 50).forEach { n ->
                    MuseChip(
                        selected = webSearchConfig.maxSearchesPerTurn == n,
                        onClick = { scope.launch { settings.saveWebSearchConfig(webSearchConfig.copy(maxSearchesPerTurn = n)) } },
                        label = n.toString(),
                    )
                }
            }
        }
        SettingsGroupDivider()
        Column(Modifier.padding(MusePaddings.cardInner)) {
            Text(stringResource(R.string.settings_web_search_max_results), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.heightIn(min = 8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(3, 5, 8, 10).forEach { n ->
                    MuseChip(
                        selected = webSearchConfig.maxResults == n,
                        onClick = { scope.launch { settings.saveWebSearchConfig(webSearchConfig.copy(maxResults = n)) } },
                        label = n.toString(),
                    )
                }
            }
        }
        SettingsGroupDivider()
        SettingsSwitchRow(
            icon = MuseIcons.refresh,
            title = stringResource(R.string.settings_web_search_fallback),
            subtitle = null,
            checked = webSearchConfig.fallbackEnabled,
            onCheckedChange = { enabled ->
                scope.launch { settings.saveWebSearchConfig(webSearchConfig.copy(fallbackEnabled = enabled)) }
            },
        )
    }

    Spacer(Modifier.height(12.dp))
    SectionLabel(stringResource(R.string.settings_web_search_diagnosis))
    SettingsGroup {
        val response = lastSearch
        if (response == null) {
            Text(
                text = stringResource(R.string.settings_web_search_no_diagnosis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(MusePaddings.cardInner),
            )
        } else {
            Column(Modifier.padding(MusePaddings.cardInner)) {
                Text("查询：${response.normalizedQuery}", style = MaterialTheme.typography.bodySmall)
                Text("状态：${response.status}   来源：${response.provider ?: "未知"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("结果：${response.results.size} 条", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                response.attempts.forEach { attempt ->
                    Text("${attempt.provider} · ${attempt.status} · ${attempt.elapsedMs}ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    SectionLabel(stringResource(R.string.settings_web_search_api_services))
    SettingsGroup {
        SettingsItemRow(
            icon = MuseIcons.plug,
            title = stringResource(R.string.settings_web_search_engine),
            subtitle = webSearchConfig.providerName,
            onClick = { wsProviderExpanded = true },
        ) { ChevronRight() }
        Text(
            text = stringResource(R.string.settings_web_search_api_services_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(MusePaddings.cardInner),
        )
    }

    if (wsProviderExpanded) {
        MuseDialog(
            onDismissRequest = { wsProviderExpanded = false },
            title = stringResource(R.string.settings_web_search_engine),
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    WebSearchConfig.SUPPORTED_PROVIDERS.forEach { p ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) {
                                wsProviderExpanded = false
                                scope.launch { settings.saveWebSearchConfig(webSearchConfig.copy(providerName = p)) }
                            }.padding(vertical = 12.dp, horizontal = MusePaddings.iconPadding),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(p, color = if (p == webSearchConfig.providerName) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = if (p == webSearchConfig.providerName) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
            },
            confirmText = stringResource(R.string.common_close),
            onConfirm = { wsProviderExpanded = false },
            onDismiss = { wsProviderExpanded = false },
        )
    }

    // 多引擎 API Key:所有需要 key 的搜索引擎各配各的(底层写入 apiKeys 映射;
    // Auto 模式按顺序回退)。此前 UI 只显示“当前选中引擎”的输入框,让人误以为只能配一个。
    SettingsGroup(modifier = Modifier.padding(top = 8.dp)) {
        Column(Modifier.padding(MusePaddings.cardInner)) {
            Text(
                text = stringResource(R.string.settings_web_search_multi_key_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.settings_web_search_multi_key_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 2.dp),
            )
            WebSearchConfig.PROVIDERS_NEEDING_API_KEY
                .filter { it != "Custom API" }
                .forEach { provider ->
                    var keyDraft by remember(provider, webSearchConfig.apiKeys[provider]) {
                        mutableStateOf(webSearchConfig.apiKeys[provider].orEmpty())
                    }
                    Spacer(Modifier.height(10.dp))
                    SettingField(
                        label = provider,
                        value = keyDraft,
                        onValueChange = { keyDraft = it },
                    )
                    SavePill(stringResource(R.string.settings_web_search_save_api_key)) {
                        scope.launch {
                            val key = keyDraft.trim()
                            val keys = webSearchConfig.apiKeys.toMutableMap().apply {
                                if (key.isNotEmpty()) put(provider, key) else remove(provider)
                            }
                            settings.saveWebSearchConfig(webSearchConfig.copy(apiKeys = keys))
                            MuseToast.show(savedText)
                        }
                    }
                }
        }
    }

    val needsApiConfig = webSearchConfig.providerName in WebSearchConfig.PROVIDERS_NEEDING_API_KEY
    if (needsApiConfig) {
        SettingsGroup(modifier = Modifier.padding(top = 8.dp)) {
            Column(Modifier.padding(MusePaddings.cardInner)) {
                SettingField(
                    label = stringResource(R.string.settings_web_search_api_key_label),
                    value = apiKeyText,
                    onValueChange = { apiKeyText = it },
                )
                SavePill(stringResource(R.string.settings_web_search_save_api_key)) {
                    scope.launch {
                        val provider = webSearchConfig.providerName
                        val key = apiKeyText.trim()
                        val keys = webSearchConfig.apiKeys.toMutableMap().apply {
                            if (key.isNotEmpty()) put(provider, key) else remove(provider)
                        }
                        settings.saveWebSearchConfig(webSearchConfig.copy(apiKey = key, apiKeys = keys, endpoint = endpointText.trim()))
                        MuseToast.show(savedText)
                    }
                }
                Spacer(Modifier.height(12.dp))
                SettingField(
                    label = stringResource(R.string.settings_web_search_endpoint_optional),
                    value = endpointText,
                    onValueChange = { endpointText = it },
                    placeholder = "https://api.example.com",
                )
                Spacer(Modifier.height(12.dp))
                SettingField(
                    label = stringResource(R.string.settings_web_search_test_query_label),
                    value = testQuery,
                    onValueChange = { testQuery = it },
                    placeholder = stringResource(R.string.settings_web_search_test_query_placeholder),
                )
                Spacer(Modifier.height(8.dp))
                MuseCapsuleButton(
                    text = if (testing) stringResource(R.string.settings_web_search_testing)
                    else stringResource(R.string.settings_web_search_test),
                    onClick = {
                        val q = testQuery.trim()
                        if (q.isEmpty() || testing) return@MuseCapsuleButton
                        testing = true
                        testResult = null
                        scope.launch {
                            try {
                                val client = GlobalContext.get().get<okhttp3.OkHttpClient>(org.koin.core.qualifier.named("webSearch"))
                                val provider = CompositeWebSearchService.buildDelegate(client, webSearchConfig)
                                val results = provider.search(q, maxResults = webSearchConfig.maxResults)
                                testResult = if (results.isNotEmpty()) {
                                    context.getString(R.string.settings_web_search_test_success, results.size, results.take(3).joinToString("\n") { "  • ${'$'}{it.title}" })
                                } else context.getString(R.string.settings_web_search_test_empty)
                            } catch (e: Exception) {
                                testResult = context.getString(R.string.settings_web_search_test_failed, e.message ?: context.getString(R.string.settings_web_search_test_failed_unknown))
                            } finally {
                                testing = false
                            }
                        }
                    },
                    enabled = testQuery.isNotBlank() && !testing,
                    loading = testing,
                    variant = IosCapsuleButtonVariant.Secondary,
                    fillWidth = false,
                )
                testResult?.let { Text(it, modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun PathRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, primary: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(MusePaddings.cardInner),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SavePill(text: String, onClick: () -> Unit) {
    Surface(
        shape = MuseShapes.pill,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier
            .padding(top = 8.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, modifier = Modifier.padding(MusePaddings.cardInnerSpaced))
    }
}

