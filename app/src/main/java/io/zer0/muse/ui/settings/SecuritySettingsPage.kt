package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import compose.icons.TablerIcons
import compose.icons.tablericons.*
import io.zer0.muse.ui.common.form.MuseChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.ShareTemplateConfig
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * 分享设置页(v2.0: 原"安全与分享"收敛为单一分享页,应用锁说明卡片已移除)。
 *
 * 分享:导出对话时包含哪些内容、格式。
 */
@Composable
fun SecuritySettingsPage(
    onBack: () -> Unit,
) {
    val settings: SettingsRepository = koinInject()
    val shareTemplate by settings.shareTemplateFlow.collectAsStateWithLifecycle(initialValue = ShareTemplateConfig())
    val scope = rememberCoroutineScope()

    SettingsSubPageScaffold(title = stringResource(R.string.settings_share_page_title), onBack = onBack) {

        // ── 分享模板(v2.0: 应用锁说明卡片已按用户要求移除,本页只保留分享能力)──
        item { SectionLabel(stringResource(R.string.settings_security_share_template_section)) }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = TablerIcons.Share,
                    title = stringResource(R.string.settings_security_include_timestamp),
                    subtitle = stringResource(R.string.settings_security_include_timestamp_subtitle),
                    checked = shareTemplate.includeTimestamp,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveShareTemplate(shareTemplate.copy(includeTimestamp = v)) }
                    },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = TablerIcons.Share,
                    title = stringResource(R.string.settings_security_include_model),
                    subtitle = stringResource(R.string.settings_security_include_model_subtitle),
                    checked = shareTemplate.includeModelName,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveShareTemplate(shareTemplate.copy(includeModelName = v)) }
                    },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = TablerIcons.Share,
                    title = stringResource(R.string.settings_security_include_tokens),
                    subtitle = stringResource(R.string.settings_security_include_tokens_subtitle),
                    checked = shareTemplate.includeTokenCount,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveShareTemplate(shareTemplate.copy(includeTokenCount = v)) }
                    },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = TablerIcons.Share,
                    title = stringResource(R.string.settings_security_include_mood),
                    subtitle = stringResource(R.string.settings_security_include_mood_subtitle),
                    checked = shareTemplate.includeMoodBlock,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveShareTemplate(shareTemplate.copy(includeMoodBlock = v)) }
                    },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = TablerIcons.Share,
                    title = stringResource(R.string.settings_security_include_reasoning),
                    subtitle = stringResource(R.string.settings_security_include_reasoning_subtitle),
                    checked = shareTemplate.includeReasoning,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveShareTemplate(shareTemplate.copy(includeReasoning = v)) }
                    },
                )
                SettingsGroupDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = TablerIcons.Share,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_security_share_format),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val plainTextLabel = stringResource(R.string.settings_security_format_plain_text)
                            listOf("markdown" to "Markdown", "plain_text" to plainTextLabel, "html" to "HTML").forEach { (value, label) ->
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
}
