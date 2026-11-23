package io.zer0.muse.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.ProviderCategory
import io.zer0.ai.core.ProviderConfig
import io.zer0.muse.R
import io.zer0.muse.data.preset.PresetProviders
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.ui.common.surface.museBottomBarInsets
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseListItemSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.ui.theme.pill
import org.koin.compose.koinInject

/**
 * 预置供应商选择器（全屏页面，保留原函数名以兼容现有调用方）。
 *
 * 使用圆角分组列表展示海外官方 / 国产官方 / 中转站预设，并在顶部提供搜索与自定义入口。
 */
@Composable
fun PresetProviderPickerDialog(onDismiss: () -> Unit, onPickPreset: (ProviderConfig) -> Unit, onPickCustom: () -> Unit) {
    PresetProviderPickerPage(
        onDismiss = onDismiss,
        onPickPreset = onPickPreset,
        onPickCustom = onPickCustom,
    )
}

@Composable
private fun PresetProviderPickerPage(onDismiss: () -> Unit, onPickPreset: (ProviderConfig) -> Unit, onPickCustom: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val presetProviders = koinInject<PresetProviders>()

    BackHandler(onBack = onDismiss)

    val allPresets = presetProviders.all
    val filtered = remember(query, allPresets) {
        if (query.isBlank()) {
            allPresets
        } else {
            val q = query.lowercase().trim()
            allPresets.filter { p ->
                p.displayName.lowercase().contains(q) ||
                    p.baseUrl.lowercase().contains(q) ||
                    p.id.lowercase().contains(q)
            }
        }
    }

    val overseasIds = remember(presetProviders) { presetProviders.overseas.mapTo(HashSet()) { it.id } }
    val domesticIds = remember(presetProviders) { presetProviders.domestic.mapTo(HashSet()) { it.id } }

    val groups = remember(filtered, overseasIds, domesticIds) {
        listOf(
            PresetGroupSpec(
                key = "overseas",
                titleRes = R.string.settings_preset_group_overseas,
                items = filtered.filter { it.category == ProviderCategory.OFFICIAL && it.id in overseasIds },
            ),
            PresetGroupSpec(
                key = "domestic",
                titleRes = R.string.settings_preset_group_domestic,
                items = filtered.filter { it.category == ProviderCategory.OFFICIAL && it.id in domesticIds },
            ),
            PresetGroupSpec(
                key = "relay",
                titleRes = R.string.settings_preset_group_relay,
                items = filtered.filter { it.category == ProviderCategory.RELAY },
            ),
        ).filter { it.items.isNotEmpty() }
    }

    MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.settings_preset_select_provider),
                onBack = onDismiss,
            )
        },
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .museBottomBarInsets()
                .padding(horizontal = MusePaddings.pageMargin),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + MuseSpacing.sm,
                bottom = innerPadding.calculateBottomPadding() + MuseSpacing.lg,
            ),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.cardGap),
        ) {
            item(key = "preset_search") {
                PresetSearchField(
                    query = query,
                    onQueryChange = { query = it },
                )
            }

            item(key = "preset_custom_entry") {
                CardGroup {
                    item(
                        key = "custom_row",
                        onClick = onPickCustom,
                        leadingContent = {
                            Icon(
                                imageVector = MuseIcons.plus,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(MuseIconSizes.iconMedium),
                            )
                        },
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.settings_preset_custom),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                        trailingContent = { ChevronRight() },
                    )
                }
            }

            groups.forEach { group ->
                item(key = "preset_group_${group.key}") {
                    Column(verticalArrangement = Arrangement.spacedBy(MuseSpacing.smTight)) {
                        Text(
                            text = stringResource(group.titleRes),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = MuseSpacing.xs),
                        )
                        CardGroup {
                            group.items.forEach { preset ->
                                item(key = preset.id) {
                                    PresetRow(
                                        preset = preset,
                                        onClick = { onPickPreset(preset) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (filtered.isEmpty()) {
                item(key = "preset_empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = MuseSpacing.xxxl),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.settings_preset_no_match),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private data class PresetGroupSpec(
    val key: String,
    val titleRes: Int,
    val items: List<ProviderConfig>,
)

@Composable
private fun PresetSearchField(query: String, onQueryChange: (String) -> Unit) {
    Surface(
        shape = MuseShapes.pill,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .height(MusePaddings.searchBarHeight),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = MuseSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
        ) {
            Icon(
                imageVector = MuseIcons.search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
            )
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    decorationBox = { innerTextField ->
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(R.string.settings_preset_search_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    },
                )
            }
            if (query.isNotEmpty()) {
                MuseTactileButton(
                    icon = MuseIcons.x,
                    onClick = { onQueryChange("") },
                    contentDescription = stringResource(R.string.settings_preset_clear),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    compact = true,
                )
            }
        }
    }
}

@Composable
private fun PresetRow(preset: ProviderConfig, onClick: () -> Unit) {
    val needBaseUrlText = stringResource(R.string.settings_preset_need_baseurl)
    val domain = remember(preset.baseUrl, needBaseUrlText) {
        extractDomain(preset.baseUrl).ifBlank { needBaseUrlText }
    }
    val modelCountLabel = stringResource(R.string.settings_preset_model_count, preset.models.size)
    val subtitle = if (preset.oauthConfig != null) {
        stringResource(R.string.settings_preset_oauth_quick_login)
    } else if (preset.models.isNotEmpty()) {
        "$domain · $modelCountLabel"
    } else {
        domain
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MuseListItemSizes.minHeight)
            .clickable(onClick = onClick)
            .padding(MusePaddings.cardInner),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.md),
    ) {
        ProviderLogo(
            type = preset.type,
            name = preset.displayName,
            size = 34.dp,
            cornerRadius = 10.dp,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(MuseSpacing.xxs),
        ) {
            Text(
                text = preset.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (preset.oauthConfig != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ChevronRight()
    }
}

private fun extractDomain(baseUrl: String): String {
    if (baseUrl.isBlank()) return ""
    return runCatching {
        val uri = java.net.URI(baseUrl)
        uri.host ?: baseUrl
    }.getOrElse { baseUrl }
}
