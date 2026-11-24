package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import io.zer0.muse.data.ShareTemplateConfig
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.switchItem
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseSpacing
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * 分享设置页。
 *
 * 分享：导出对话时包含哪些内容、格式。
 */
@Composable
fun SecuritySettingsPage(onBack: () -> Unit) {
    val settings: SettingsRepository = koinInject()
    val shareTemplate by settings.shareTemplateFlow.collectAsStateWithLifecycle(initialValue = ShareTemplateConfig())
    val scope = rememberCoroutineScope()

    SettingsSubPageScaffold(title = stringResource(R.string.common_share), onBack = onBack) {
        item(key = "share_template") {
            CardGroup(title = { Text(stringResource(R.string.settings_security_share_template_section)) }) {
                switchItem(
                    key = "share_include_timestamp",
                    title = stringResource(R.string.settings_security_include_timestamp),
                    checked = shareTemplate.includeTimestamp,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveShareTemplate(shareTemplate.copy(includeTimestamp = v)) }
                    },
                    leadingContent = { ShareIcon() },
                )
                switchItem(
                    key = "share_include_model",
                    title = stringResource(R.string.settings_security_include_model),
                    checked = shareTemplate.includeModelName,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveShareTemplate(shareTemplate.copy(includeModelName = v)) }
                    },
                    leadingContent = { ShareIcon() },
                )
                switchItem(
                    key = "share_include_tokens",
                    title = stringResource(R.string.settings_security_include_tokens),
                    checked = shareTemplate.includeTokenCount,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveShareTemplate(shareTemplate.copy(includeTokenCount = v)) }
                    },
                    leadingContent = { ShareIcon() },
                )
                switchItem(
                    key = "share_include_mood",
                    title = stringResource(R.string.settings_security_include_mood),
                    checked = shareTemplate.includeMoodBlock,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveShareTemplate(shareTemplate.copy(includeMoodBlock = v)) }
                    },
                    leadingContent = { ShareIcon() },
                )
                switchItem(
                    key = "share_include_reasoning",
                    title = stringResource(R.string.settings_security_include_reasoning),
                    checked = shareTemplate.includeReasoning,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveShareTemplate(shareTemplate.copy(includeReasoning = v)) }
                    },
                    leadingContent = { ShareIcon() },
                )
                content(key = "share_format") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MusePaddings.cardInner),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.md),
                    ) {
                        Icon(
                            imageVector = MuseIcons.share,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                        Text(
                            text = stringResource(R.string.settings_security_share_format),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        val plainTextLabel = stringResource(R.string.settings_security_format_plain_text)
                        listOf(
                            "markdown" to "Markdown",
                            "plain_text" to plainTextLabel,
                            "html" to "HTML",
                        ).forEach { (value, label) ->
                            MuseChip(
                                selected = shareTemplate.format == value,
                                onClick = {
                                    scope.launch { settings.saveShareTemplate(shareTemplate.copy(format = value)) }
                                },
                                label = label,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareIcon() {
    Icon(
        imageVector = MuseIcons.share,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(MuseIconSizes.iconMedium),
    )
}
