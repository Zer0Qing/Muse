package io.zer0.muse.ui.settings

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
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.switchItem
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.web.WebSearchConfig
import io.zer0.muse.web.WebSearchProviderCatalog
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.koin.compose.koinInject
import org.koin.core.qualifier.named

/**
 * 单个联网搜索供应商的详情页。
 *
 * 供应商列表只负责导航；Key、默认地址、连接测试和保存都集中在这里，
 * 避免用户在一张长页面里来回切换不同供应商的字段。
 */
@Composable
fun SettingsWebSearchProviderDetailPage(providerKey: String, onBack: () -> Unit) {
    val settings: SettingsRepository = koinInject()
    val webSearchClient: OkHttpClient = koinInject(qualifier = named("webSearch"))
    val scope = rememberCoroutineScope()
    val config by settings.webSearchConfigFlow.collectAsStateWithLifecycle(
        initialValue = WebSearchConfig(),
    )
    val selectedProviderKey = WebSearchProviderCatalog.canonicalKey(providerKey)
    val isAuto = selectedProviderKey == "Auto"
    val isCurrent = WebSearchProviderCatalog.canonicalKey(config.providerName)
        .equals(selectedProviderKey, ignoreCase = true)

    SettingsSubPageScaffold(
        title = selectedProviderKey,
        onBack = onBack,
    ) {
        item(key = "provider_current_group") {
            CardGroup(title = { Text(stringResource(R.string.settings_web_search_provider_selection)) }) {
                switchItem(
                    key = "provider_set_current",
                    title = stringResource(R.string.settings_web_search_provider_set_current),
                    subtitle = stringResource(
                        R.string.settings_web_search_provider_set_current_subtitle,
                        selectedProviderKey,
                    ),
                    checked = isCurrent,
                    onCheckedChange = { checked ->
                        if (checked || isCurrent) {
                            scope.launch {
                                settings.saveWebSearchConfig(
                                    config.copy(providerName = if (checked) selectedProviderKey else "Auto"),
                                )
                            }
                        }
                    },
                    leadingContent = {
                        Icon(
                            imageVector = MuseIcons.search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
            }
        }
        if (isAuto) {
            item(key = "provider_auto_desc") {
                CardGroup {
                    item(
                        key = "provider_auto_desc_row",
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.settings_web_search_provider_auto_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
        } else if (WebSearchProviderCatalog.selectableTypes.contains(selectedProviderKey)) {
            item(key = "provider_type_section") {
                WebSearchProviderTypeSection(
                    providerKey = selectedProviderKey,
                    config = config,
                    settings = settings,
                    scope = scope,
                    webSearchClient = webSearchClient,
                )
            }
        }
    }
}
