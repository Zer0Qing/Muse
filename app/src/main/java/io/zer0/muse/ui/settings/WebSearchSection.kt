package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.navigationItem
import io.zer0.muse.ui.common.surface.switchItem
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.web.WebSearchConfig
import io.zer0.muse.web.WebSearchCoordinator
import io.zer0.muse.web.WebSearchMode
import io.zer0.muse.web.WebSearchProviderCatalog
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WebSearchSection(webSearchConfig: WebSearchConfig, settings: SettingsRepository, onOpenProviderList: () -> Unit) {
    val scope = rememberCoroutineScope()
    val coordinator: WebSearchCoordinator = org.koin.compose.koinInject()
    val lastSearch by coordinator.lastResponse.collectAsStateWithLifecycle()
    val currentProviderKey = WebSearchProviderCatalog.canonicalKey(webSearchConfig.providerName)

    CardGroup(title = { Text(stringResource(R.string.settings_web_search_section)) }) {
        switchItem(
            key = "web_search_enable",
            title = stringResource(R.string.settings_web_search_enable),
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
            leadingContent = {
                Icon(
                    imageVector = MuseIcons.globe,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
            },
        )
    }

    CardGroup(
        title = { Text(stringResource(R.string.settings_web_search_mode)) },
        modifier = Modifier.padding(top = MusePaddings.cardGap),
    ) {
        content(key = "web_search_mode") {
            Column(Modifier.padding(MusePaddings.cardInner)) {
                Text(
                    stringResource(R.string.settings_web_search_mode_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(MuseSpacing.sm))
                // 必须用会换行的 FlowRow：四个模式标签在窄屏一行放不下。
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                ) {
                    MuseChip(
                        selected = webSearchConfig.mode == WebSearchMode.AUTO && webSearchConfig.enabled,
                        onClick = {
                            scope.launch {
                                settings.saveWebSearchConfig(
                                    webSearchConfig.copy(mode = WebSearchMode.AUTO, enabled = true),
                                )
                            }
                        },
                        label = stringResource(R.string.settings_web_search_mode_auto),
                    )
                    MuseChip(
                        selected = webSearchConfig.mode == WebSearchMode.LOCAL && webSearchConfig.enabled,
                        onClick = {
                            scope.launch {
                                settings.saveWebSearchConfig(
                                    webSearchConfig.copy(mode = WebSearchMode.LOCAL, enabled = true),
                                )
                            }
                        },
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
                    Text(
                        stringResource(R.string.settings_web_search_native_unavailable),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = MuseSpacing.sm),
                    )
                }
            }
        }
    }

    CardGroup(
        title = { Text(stringResource(R.string.settings_web_search_default_path)) },
        modifier = Modifier.padding(top = MusePaddings.cardGap),
    ) {
        content(key = "web_search_path_desc") {
            Text(
                text = stringResource(R.string.settings_web_search_default_path_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(MusePaddings.cardInner),
            )
        }
        item(
            key = "web_search_path_bing",
            leadingContent = {
                Icon(
                    MuseIcons.brandBing,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
            },
            headlineContent = { Text("Bing HTTP") },
            supportingContent = { Text(stringResource(R.string.settings_web_search_provider_status_bing)) },
        )
        item(
            key = "web_search_path_baidu",
            leadingContent = {
                Icon(
                    MuseIcons.globe,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
            },
            headlineContent = { Text(stringResource(R.string.settings_web_search_path_baidu)) },
            supportingContent = { Text(stringResource(R.string.settings_web_search_provider_status_jina)) },
        )
        item(
            key = "web_search_path_user_api",
            leadingContent = {
                Icon(
                    MuseIcons.search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
            },
            headlineContent = { Text(stringResource(R.string.settings_web_search_path_user_api)) },
            supportingContent = { Text(stringResource(R.string.settings_web_search_provider_status_searxng)) },
        )
    }

    CardGroup(
        title = { Text(stringResource(R.string.settings_web_search_strategy)) },
        modifier = Modifier.padding(top = MusePaddings.cardGap),
    ) {
        content(key = "web_search_budget") {
            Column(Modifier.padding(MusePaddings.cardInner)) {
                Text(stringResource(R.string.settings_web_search_budget), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(MuseSpacing.sm))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
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
        }
        content(key = "web_search_max_results") {
            Column(Modifier.padding(MusePaddings.cardInner)) {
                Text(stringResource(R.string.settings_web_search_max_results), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(MuseSpacing.sm))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
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
        }
        switchItem(
            key = "web_search_fallback",
            title = stringResource(R.string.settings_web_search_fallback),
            checked = webSearchConfig.fallbackEnabled,
            onCheckedChange = { enabled ->
                scope.launch { settings.saveWebSearchConfig(webSearchConfig.copy(fallbackEnabled = enabled)) }
            },
            leadingContent = {
                Icon(
                    MuseIcons.refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
            },
        )
    }

    CardGroup(
        title = { Text(stringResource(R.string.settings_web_search_diagnosis)) },
        modifier = Modifier.padding(top = MusePaddings.cardGap),
    ) {
        content(key = "web_search_diagnosis") {
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
                    Text(
                        text = stringResource(R.string.settings_web_search_diagnostic_query, response.normalizedQuery),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = stringResource(
                            R.string.settings_web_search_diagnostic_status,
                            response.status,
                            response.provider ?: stringResource(R.string.settings_web_search_diagnostic_unknown_provider),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.settings_web_search_diagnostic_results, response.results.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    response.attempts.forEach { attempt ->
                        Text(
                            "${attempt.provider} · ${attempt.status} · ${attempt.elapsedMs}ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    CardGroup(
        title = { Text(stringResource(R.string.settings_web_search_api_services)) },
        modifier = Modifier.padding(top = MusePaddings.cardGap),
    ) {
        navigationItem(
            key = "web_search_engine",
            title = stringResource(R.string.settings_web_search_engine),
            subtitle = currentProviderKey,
            leadingContent = {
                Icon(
                    MuseIcons.plug,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
            },
            onClick = onOpenProviderList,
        )
        content(key = "web_search_api_desc") {
            Text(
                text = stringResource(R.string.settings_web_search_api_services_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(MusePaddings.cardInner),
            )
        }
    }

    // 「搜索顺序」：Auto 回退链的尝试顺序可调，且与链构造共用同一份顺序表。
    WebSearchOrderSection(
        webSearchConfig = webSearchConfig,
        onSave = { order ->
            scope.launch {
                settings.saveWebSearchConfig(webSearchConfig.copy(providerOrder = order))
            }
        },
    )

    // 自定义搜索引擎 —— 用户自己的检索服务，接进 Auto 链路最前面。
    CustomSearchEnginesSection(
        webSearchConfig = webSearchConfig,
        settings = settings,
    )
}
