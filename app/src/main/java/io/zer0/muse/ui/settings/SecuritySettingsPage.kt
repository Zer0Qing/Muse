package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrowserUpdated
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockReset
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import io.zer0.muse.ui.common.IosChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.ShareTemplateConfig
import io.zer0.muse.ui.common.SectionLabel
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.common.SettingsGroup
import io.zer0.muse.ui.common.SettingsGroupDivider
import io.zer0.muse.ui.common.SettingsSwitchRow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * v0.32: 安全与分享设置页。
 *
 * 合并三个相关功能:
 *  - 安全:应用 PIN 锁(进入应用需要输入 PIN)
 *  - 分享:导出对话时包含哪些内容、格式
 *  - 浏览器:默认搜索引擎选择
 */
@Composable
fun SecuritySettingsPage(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val settings: SettingsRepository = koinInject()
    val appPin by settings.appPinFlow.collectAsStateWithLifecycle(initialValue = "")
    val biometricEnabled by settings.biometricEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val shareTemplate by settings.shareTemplateFlow.collectAsStateWithLifecycle(initialValue = ShareTemplateConfig())
    val searchEngine by settings.defaultSearchEngineFlow.collectAsStateWithLifecycle(initialValue = "auto")
    val scope = rememberCoroutineScope()

    val biometricAvailable = remember {
        val bm = androidx.biometric.BiometricManager.from(context)
        bm.canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
    }

    var pinDraft by rememberSaveable { mutableStateOf("") }

    SettingsSubPageScaffold(title = stringResource(R.string.settings_security_page_title), onBack = onBack) {
        // ── 1. 应用 PIN 锁 ──
        item { SectionLabel(stringResource(R.string.settings_security_app_lock_section)) }
        item {
            SettingsGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = if (appPin.isNotEmpty()) Icons.Outlined.Lock else Icons.Outlined.LockReset,
                        contentDescription = null,
                        tint = if (appPin.isNotEmpty()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (appPin.isNotEmpty()) stringResource(R.string.settings_security_pin_enabled) else stringResource(R.string.settings_security_pin_disabled),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = if (appPin.isNotEmpty()) stringResource(R.string.settings_security_pin_enabled_subtitle) else stringResource(R.string.settings_security_pin_disabled_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                SettingsGroupDivider()
                OutlinedTextField(
                    value = pinDraft,
                    onValueChange = { v -> if (v.length <= 8 && v.all { it.isDigit() }) pinDraft = v },
                    label = { Text(stringResource(R.string.settings_security_set_new_pin)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInnerSpaced),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInnerSpaced),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            if (pinDraft.length in 4..8) {
                                scope.launch { settings.saveAppPin(pinDraft) }
                                pinDraft = ""
                            }
                        },
                        enabled = pinDraft.length in 4..8,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.settings_security_set_pin))
                    }
                    if (appPin.isNotEmpty()) {
                        OutlinedButton2(
                            onClick = {
                                scope.launch { settings.saveAppPin("") }
                                pinDraft = ""
                            },
                            text = stringResource(R.string.settings_security_remove),
                        )
                    }
                }
            }
        }

        // ── 1.5 生物识别解锁 ──
        if (appPin.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.settings_security_biometric_section)) }
            item {
                SettingsGroup {
                    SettingsSwitchRow(
                        icon = Icons.Outlined.Fingerprint,
                        title = stringResource(R.string.settings_security_biometric_title),
                        subtitle = if (biometricAvailable) {
                            stringResource(R.string.settings_security_biometric_available)
                        } else {
                            stringResource(R.string.settings_security_biometric_unavailable)
                        },
                        checked = biometricEnabled && biometricAvailable,
                        onCheckedChange = { v ->
                            if (biometricAvailable) {
                                scope.launch { settings.saveBiometricEnabled(v) }
                            }
                        },
                    )
                }
            }
        }

        // ── 2. 分享模板 ──
        item { SectionLabel(stringResource(R.string.settings_security_share_template_section)) }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = Icons.Outlined.Share,
                    title = stringResource(R.string.settings_security_include_timestamp),
                    subtitle = stringResource(R.string.settings_security_include_timestamp_subtitle),
                    checked = shareTemplate.includeTimestamp,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveShareTemplate(shareTemplate.copy(includeTimestamp = v)) }
                    },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = Icons.Outlined.Share,
                    title = stringResource(R.string.settings_security_include_model),
                    subtitle = stringResource(R.string.settings_security_include_model_subtitle),
                    checked = shareTemplate.includeModelName,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveShareTemplate(shareTemplate.copy(includeModelName = v)) }
                    },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = Icons.Outlined.Share,
                    title = stringResource(R.string.settings_security_include_tokens),
                    subtitle = stringResource(R.string.settings_security_include_tokens_subtitle),
                    checked = shareTemplate.includeTokenCount,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveShareTemplate(shareTemplate.copy(includeTokenCount = v)) }
                    },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = Icons.Outlined.Share,
                    title = stringResource(R.string.settings_security_include_mood),
                    subtitle = stringResource(R.string.settings_security_include_mood_subtitle),
                    checked = shareTemplate.includeMoodBlock,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveShareTemplate(shareTemplate.copy(includeMoodBlock = v)) }
                    },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = Icons.Outlined.Share,
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
                        imageVector = Icons.Outlined.Share,
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
                                IosChip(
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

        // ── 3. 默认搜索引擎 ──
        item { SectionLabel(stringResource(R.string.settings_security_browser_section)) }
        item {
            SettingsGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Public,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_security_default_search_engine),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.settings_security_default_search_engine_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val autoLabel = stringResource(R.string.settings_security_search_auto)
                            val customApiLabel = stringResource(R.string.settings_security_search_custom_api)
                            listOf(
                                "auto" to autoLabel,
                                "bing" to "Bing",
                                "custom_api" to customApiLabel,
                            ).forEach { (value, label) ->
                                IosChip(
                                    selected = searchEngine == value,
                                    onClick = {
                                        scope.launch { settings.saveDefaultSearchEngine(value) }
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

@Composable
private fun OutlinedButton2(
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.TextButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error,
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.error,
        ),
        modifier = modifier,
    ) {
        Text(text)
    }
}
