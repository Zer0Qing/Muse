package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import io.zer0.muse.ui.theme.MusePaddings
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

    SectionLabel(stringResource(R.string.settings_web_search_section))
    SettingsGroup(modifier = Modifier.padding(top = 8.dp)) {
        SettingsSwitchRow(
            // v2.x: 联网搜索图标修正为 globe(原 languages 是翻译语义)
            icon = MuseIcons.globe,
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
    SectionLabel(
        stringResource(R.string.settings_web_search_mode),
        modifier = Modifier.padding(top = MusePaddings.cardGap),
    )
    SettingsGroup {
        Column(Modifier.padding(MusePaddings.cardInner)) {
            Text(
                stringResource(R.string.settings_web_search_mode_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            // 必须用会换行的 FlowRow:四个模式标签在窄屏一行放不下,Row 会把最后一个
            // chip 压成竖排文字甚至挤出屏幕(用户实测截图)。
            // ST-07: FilterChip(M3) → 项目自有 MuseChip(已支持 selected/Role/48dp 触控)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // v2.x: 原「关闭」chip 已删 — 与上方总开关控同一状态,开关是唯一关闭入口。
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
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }

    SectionLabel(
        stringResource(R.string.settings_web_search_default_path),
        modifier = Modifier.padding(top = MusePaddings.cardGap),
    )
    SettingsGroup {
        Text(
            text = stringResource(R.string.settings_web_search_default_path_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(MusePaddings.cardInner),
        )
        // v2.3.1: 路径名走字符串资源(与 Bing HTTP 行一致),不再把中文标签硬编码在 UI 层
        PathRow(MuseIcons.brandBing, "Bing HTTP", stringResource(R.string.settings_web_search_provider_status_bing), true)
        HorizontalDivider(modifier = Modifier.padding(start = 52.dp))
        PathRow(
            MuseIcons.globe,
            stringResource(R.string.settings_web_search_path_baidu),
            stringResource(R.string.settings_web_search_provider_status_jina),
            false,
        )
        HorizontalDivider(modifier = Modifier.padding(start = 52.dp))
        PathRow(
            MuseIcons.search,
            stringResource(R.string.settings_web_search_path_user_api),
            stringResource(R.string.settings_web_search_provider_status_searxng),
            false,
        )
    }

    SectionLabel(
        stringResource(R.string.settings_web_search_strategy),
        modifier = Modifier.padding(top = MusePaddings.cardGap),
    )
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

    SectionLabel(
        stringResource(R.string.settings_web_search_diagnosis),
        modifier = Modifier.padding(top = MusePaddings.cardGap),
    )
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

    SectionLabel(
        stringResource(R.string.settings_web_search_api_services),
        modifier = Modifier.padding(top = MusePaddings.cardGap),
    )
    SettingsGroup {
        SettingsItemRow(
            icon = MuseIcons.plug,
            title = stringResource(R.string.settings_web_search_engine),
            subtitle = currentProviderKey,
            onClick = onOpenProviderList,
        ) { ChevronRight() }
        Text(
            text = stringResource(R.string.settings_web_search_api_services_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(MusePaddings.cardInner),
        )
    }

    // 「搜索顺序」：Auto 回退链的尝试顺序可调，且与链构造共用同一份顺序表
    // （AUTO_CHAIN_PROVIDER_KEYS）—— 界面自己抄一份顺序的话，用户拖完看到的是假顺序。
    WebSearchOrderSection(
        webSearchConfig = webSearchConfig,
        onSave = { order ->
            scope.launch {
                settings.saveWebSearchConfig(webSearchConfig.copy(providerOrder = order))
            }
        },
    )

    // v2.6.10: 自定义搜索引擎 —— 用户自己的检索服务，接进 Auto 链路最前面。
    CustomSearchEnginesSection(
        webSearchConfig = webSearchConfig,
        settings = settings,
    )
}

@Composable
private fun PathRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, primary: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(MusePaddings.cardInner),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
