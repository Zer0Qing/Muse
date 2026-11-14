package io.zer0.muse.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.web.WebSearchConfig
import io.zer0.muse.web.WebSearchProviderCatalog
import org.koin.compose.koinInject

/** 供应商选择页；具体字段只在单家详情页展示。 */
@Composable
fun SettingsWebSearchProviderListPage(
    onBack: () -> Unit,
    onOpenProvider: (String) -> Unit,
) {
    val settings: SettingsRepository = koinInject()
    val config by settings.webSearchConfigFlow.collectAsStateWithLifecycle(
        initialValue = WebSearchConfig(),
    )
    val currentProvider = WebSearchProviderCatalog.canonicalKey(config.providerName)

    SettingsSubPageScaffold(
        title = stringResource(R.string.settings_web_search_provider_list_title),
        onBack = onBack,
    ) {
        item {
            Text(
                text = stringResource(R.string.settings_web_search_provider_list_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = MusePaddings.tightGap),
            )
        }
        item {
            SectionLabel(stringResource(R.string.settings_web_search_provider_selection))
        }
        item {
            SettingsGroup {
                WebSearchProviderCatalog.selectableTypes.forEachIndexed { index, provider ->
                    if (index > 0) SettingsGroupDivider()
                    val state = WebSearchProviderCatalog.setupState(config, provider)
                    val subtitle = when {
                        provider.equals(currentProvider, ignoreCase = true) ->
                            stringResource(R.string.settings_web_search_provider_current)
                        state == WebSearchProviderCatalog.ProviderSetupState.NEEDS_KEY ->
                            stringResource(R.string.settings_web_search_provider_status_needs_key)
                        state == WebSearchProviderCatalog.ProviderSetupState.NEEDS_URL ->
                            stringResource(R.string.settings_web_search_provider_status_needs_url)
                        else -> stringResource(R.string.settings_web_search_provider_status_ready)
                    }
                    SettingsItemRow(
                        icon = if (provider == "Auto") MuseIcons.sparkle else MuseIcons.search,
                        title = provider,
                        subtitle = subtitle,
                        onClick = { onOpenProvider(provider) },
                    ) { ChevronRight() }
                }
            }
        }
    }
}
