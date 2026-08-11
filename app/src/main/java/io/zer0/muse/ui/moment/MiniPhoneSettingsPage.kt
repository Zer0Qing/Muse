package io.zer0.muse.ui.moment

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import compose.icons.TablerIcons
import compose.icons.tablericons.DeviceMobile
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import io.zer0.muse.ui.settings.SettingsSubPageScaffold
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * v1.0.74: 小手机设置页。
 * 第一个: 总开关(首页右上角小手机图标显隐)。
 */
@Composable
fun MiniPhoneSettingsPage(
    onBack: () -> Unit,
) {
    val settings: SettingsRepository = koinInject()
    val enabled by settings.miniPhoneEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val scope = rememberCoroutineScope()

    SettingsSubPageScaffold(
        title = stringResource(R.string.settings_miniphone_title),
        onBack = onBack,
    ) {
        // ── 总开关 ──
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = TablerIcons.DeviceMobile,
                    title = stringResource(R.string.settings_miniphone_enable_title),
                    subtitle = stringResource(R.string.settings_miniphone_enable_subtitle),
                    checked = enabled,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveMiniPhoneEnabled(v) }
                    },
                )
            }
        }
        item {
            Surface(
                shape = MuseShapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_miniphone_desc_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.settings_miniphone_desc_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
