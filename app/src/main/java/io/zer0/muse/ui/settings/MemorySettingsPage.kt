package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.memory.ticker.MemoryConfig
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.SettingsSegmentedRow
import io.zer0.muse.ui.common.settings.SettingsSliderRow
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.actionItem
import io.zer0.muse.ui.common.surface.navigationItem
import io.zer0.muse.ui.common.surface.switchItem
import io.zer0.muse.ui.theme.MuseIconSizes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * 记忆系统高级配置页：记忆空间、长期记忆开关、衰减/预算参数与上下文楼层限制。
 */
@Composable
fun MemorySettingsPage(onBack: () -> Unit, onOpenMemorySpace: () -> Unit = {}) {
    val settings: SettingsRepository = koinInject()
    val memoryConfig by settings.memoryConfigFlow.collectAsStateWithLifecycle(initialValue = MemoryConfig())
    val memoryEnabled by settings.memoryEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val scope = rememberCoroutineScope()
    val experienceEnabled by settings.experienceEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val floorLimiterEnabled by settings.floorLimiterEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val floorLimit by settings.floorLimitFlow.collectAsStateWithLifecycle(initialValue = 16)

    var localConfig by remember { mutableStateOf(memoryConfig) }
    var localConfigDirty by remember { mutableStateOf(false) }

    LaunchedEffect(memoryConfig) {
        if (!localConfigDirty) localConfig = memoryConfig
    }

    LaunchedEffect(localConfig, localConfigDirty) {
        if (!localConfigDirty) return@LaunchedEffect
        delay(400)
        val err = MemoryConfig.validate(localConfig)
        if (err != null) {
            MuseToast.show(err)
            localConfig = memoryConfig
            localConfigDirty = false
        } else {
            settings.saveMemoryConfig(localConfig)
            localConfigDirty = false
        }
    }

    SettingsSubPageScaffold(title = stringResource(R.string.settings_memory_page_title), onBack = onBack) {
        item(key = "memory_space_group") {
            CardGroup(title = { Text(stringResource(R.string.memory_space_entry)) }) {
                navigationItem(
                    key = "memory_space_manage",
                    title = stringResource(R.string.memory_space_manage_title),
                    onClick = onOpenMemorySpace,
                )
            }
        }

        item(key = "memory_system_group") {
            CardGroup(title = { Text(stringResource(R.string.settings_memory_system_section)) }) {
                switchItem(
                    key = "memory_enable",
                    title = stringResource(R.string.settings_assistant_memory_enable),
                    checked = memoryEnabled,
                    onCheckedChange = { v -> scope.launch { settings.saveMemoryEnabled(v) } },
                    leadingContent = {
                        Icon(MuseIcons.atom, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(MuseIconSizes.iconMedium))
                    },
                )
                switchItem(
                    key = "memory_conv_recall",
                    title = stringResource(R.string.settings_memory_conversation_recall),
                    checked = localConfig.conversationRecallEnabled,
                    onCheckedChange = { enabled ->
                        localConfigDirty = true
                        localConfig = localConfig.copy(conversationRecallEnabled = enabled)
                    },
                    leadingContent = {
                        Icon(MuseIcons.history, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(MuseIconSizes.iconMedium))
                    },
                )
                switchItem(
                    key = "memory_experience",
                    title = stringResource(R.string.settings_memory_experience_lib),
                    checked = experienceEnabled,
                    onCheckedChange = { v -> scope.launch { settings.saveExperienceEnabled(v) } },
                    leadingContent = {
                        Icon(MuseIcons.server, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(MuseIconSizes.iconMedium))
                    },
                )
                content(key = "memory_token_budget") {
                    SettingsSliderRow(
                        icon = MuseIcons.server,
                        iconContentDescription = stringResource(R.string.settings_memory_token_budget),
                        title = stringResource(R.string.settings_memory_token_budget),
                        value = localConfig.tokenBudget.toFloat(),
                        valueRange = 500f..6000f,
                        steps = 10,
                        valueText = "${localConfig.tokenBudget}",
                        onValueChange = { v ->
                            localConfigDirty = true
                            localConfig = localConfig.copy(tokenBudget = v.toInt())
                        },
                    )
                }
                content(key = "memory_decay_rate") {
                    SettingsSliderRow(
                        icon = MuseIcons.trendingDown,
                        iconContentDescription = stringResource(R.string.settings_memory_decay_rate),
                        title = stringResource(R.string.settings_memory_decay_rate_title),
                        value = localConfig.decayPerDay,
                        valueRange = 0.005f..0.06f,
                        steps = 10,
                        valueText = "%.3f".format(localConfig.decayPerDay),
                        onValueChange = { v ->
                            localConfigDirty = true
                            localConfig = localConfig.copy(decayPerDay = v)
                        },
                    )
                }
                content(key = "memory_hit_bonus") {
                    SettingsSliderRow(
                        icon = MuseIcons.bolt,
                        iconContentDescription = stringResource(R.string.settings_memory_hit_bonus),
                        title = stringResource(R.string.settings_memory_hit_bonus),
                        value = localConfig.hitBonus,
                        valueRange = 0f..15f,
                        steps = 14,
                        valueText = "%.1f".format(localConfig.hitBonus),
                        onValueChange = { v ->
                            localConfigDirty = true
                            localConfig = localConfig.copy(hitBonus = v)
                        },
                    )
                }
                content(key = "memory_compile_threshold") {
                    SettingsSliderRow(
                        icon = MuseIcons.arrowsVertical,
                        iconContentDescription = stringResource(R.string.settings_memory_compile_threshold),
                        title = stringResource(R.string.settings_memory_compile_threshold),
                        subtitle = stringResource(R.string.settings_memory_compile_threshold_subtitle, localConfig.baseImportance),
                        value = localConfig.compileThreshold,
                        valueRange = 1f..(localConfig.baseImportance - 0.5f),
                        steps = 17,
                        valueText = "%.1f".format(localConfig.compileThreshold),
                        onValueChange = { v ->
                            localConfigDirty = true
                            localConfig = localConfig.copy(compileThreshold = v)
                        },
                    )
                }
                content(key = "memory_forget_speed") {
                    SettingsSliderRow(
                        icon = MuseIcons.gauge,
                        iconContentDescription = stringResource(R.string.settings_memory_forget_speed),
                        title = stringResource(R.string.settings_memory_forget_speed),
                        value = localConfig.forgetSpeed,
                        valueRange = 0.5f..3f,
                        steps = 24,
                        valueText = "%.1fx".format(localConfig.forgetSpeed),
                        onValueChange = { v ->
                            localConfigDirty = true
                            localConfig = localConfig.copy(forgetSpeed = v)
                        },
                    )
                }
                actionItem(
                    key = "memory_restore_default",
                    title = stringResource(R.string.settings_memory_restore_default),
                    onClick = {
                        localConfigDirty = true
                        localConfig = MemoryConfig()
                    },
                )
            }
        }

        item(key = "memory_context_window") {
            CardGroup(title = { Text(stringResource(R.string.settings_screen_group_context_window)) }) {
                switchItem(
                    key = "floor_limiter_toggle",
                    title = stringResource(R.string.settings_chat_floor_limiter),
                    checked = floorLimiterEnabled,
                    onCheckedChange = { v -> scope.launch { settings.saveFloorLimiterEnabled(v) } },
                    leadingContent = {
                        Icon(MuseIcons.arrowsVertical, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(MuseIconSizes.iconMedium))
                    },
                )
                if (floorLimiterEnabled) {
                    val floorOptions = listOf(
                        stringResource(R.string.settings_chat_floor_count, 8),
                        stringResource(R.string.settings_chat_floor_count, 16),
                        stringResource(R.string.settings_chat_floor_count, 32),
                    )
                    val floorIndex = when (floorLimit) {
                        8 -> 0
                        32 -> 2
                        else -> 1
                    }
                    content(key = "floor_limiter_count") {
                        SettingsSegmentedRow(
                            icon = MuseIcons.arrowsVertical,
                            title = stringResource(R.string.settings_chat_floor_keep),
                            options = floorOptions,
                            selectedIndex = floorIndex,
                            onSelectedChange = { idx ->
                                val limit = when (idx) {
                                    0 -> 8
                                    2 -> 32
                                    else -> 16
                                }
                                scope.launch { settings.saveFloorLimit(limit) }
                            },
                        )
                    }
                }
            }
        }
    }
}
