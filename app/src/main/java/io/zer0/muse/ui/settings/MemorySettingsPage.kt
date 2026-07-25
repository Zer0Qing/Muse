package io.zer0.muse.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import io.zer0.muse.ui.common.IosChip
import io.zer0.muse.ui.common.IosSlider
import io.zer0.muse.ui.theme.MuseShapes
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.memory.ticker.MemoryConfig
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.SectionLabel
import io.zer0.muse.ui.common.SettingsGroup
import io.zer0.muse.ui.common.SettingsGroupDivider
import io.zer0.muse.ui.common.SettingsSwitchRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/**
 * v0.32: 记忆系统高级配置页。
 *
 * 把 MemoryTicker 的硬编码阈值提升为可配置:
 *  - token 预算(影响注入到 system prompt 的记忆量)
 *  - 衰减系数 λ(控制遗忘速度)
 *  - 命中加成(常提起的记忆不易消失)
 *  - 编译阈值(低于此分的记忆不进入 memory.md)
 *  - 遗忘倍率(1.0 正常 / 2.0 忘得快一倍)
 *
 * + 经验库开关(默认关)
 * + 保持唤醒(默认关)
 * + 通知策略(never / when_unfocused / always)
 */
@Composable
fun MemorySettingsPage(
    onBack: () -> Unit,
) {
    val settings: SettingsRepository = koinInject()
    val memoryConfig by settings.memoryConfigFlow.collectAsStateWithLifecycle(initialValue = MemoryConfig())
    val experienceEnabled by settings.experienceEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val keepAwake by settings.keepAwakeFlow.collectAsStateWithLifecycle(initialValue = false)
    val notificationPolicy by settings.notificationPolicyFlow.collectAsStateWithLifecycle(initialValue = "when_unfocused")
    val scope = rememberCoroutineScope()

    // v1.78 (#19): 滑块防抖 — 拖动时只更新 localConfig,停止 400ms 后才持久化到 DataStore
    var localConfig by remember(memoryConfig) { mutableStateOf(memoryConfig) }
    LaunchedEffect(localConfig) {
        if (localConfig != memoryConfig) {
            delay(400)
            // v1.78 (H7): 保存前校验,拒绝 compileThreshold >= baseImportance 等危险配置
            val err = MemoryConfig.validate(localConfig)
            if (err != null) {
                io.zer0.muse.ui.common.MuseToast.show(err)
                localConfig = memoryConfig // 回退到上次合法值
            } else {
                scope.launch { settings.saveMemoryConfig(localConfig) }
            }
        }
    }

    SettingsSubPageScaffold(title = stringResource(R.string.settings_memory_page_title), onBack = onBack) {
        // ── 1. 记忆系统 ──
        item { SectionLabel(stringResource(R.string.settings_memory_system_section)) }
        item {
            SettingsGroup {
                SliderRow(
                    icon = Icons.Outlined.Memory,
                    iconLabel = stringResource(R.string.settings_memory_token_budget),
                    title = stringResource(R.string.settings_memory_token_budget),
                    subtitle = stringResource(R.string.settings_memory_token_budget_subtitle),
                    value = localConfig.tokenBudget.toFloat(),
                    range = 500f..6000f,
                    steps = 10,
                    valueText = "${localConfig.tokenBudget}",
                    onValueChange = { v ->
                        localConfig = localConfig.copy(tokenBudget = v.toInt())
                    },
                )
                SettingsGroupDivider()
                SliderRow(
                    icon = Icons.AutoMirrored.Outlined.TrendingDown,
                    iconLabel = stringResource(R.string.settings_memory_decay_rate),
                    title = stringResource(R.string.settings_memory_decay_rate_title),
                    subtitle = stringResource(R.string.settings_memory_decay_rate_subtitle),
                    value = localConfig.decayPerDay,
                    range = 0.005f..0.06f,
                    steps = 10,
                    valueText = "%.3f".format(localConfig.decayPerDay),
                    onValueChange = { v ->
                        localConfig = localConfig.copy(decayPerDay = v)
                    },
                )
                SettingsGroupDivider()
                SliderRow(
                    icon = Icons.Outlined.Bolt,
                    iconLabel = stringResource(R.string.settings_memory_hit_bonus),
                    title = stringResource(R.string.settings_memory_hit_bonus),
                    // v7: hitBonus 已接入 factScore / cutoffDays / applyDecay
                    subtitle = stringResource(R.string.settings_memory_hit_bonus_subtitle),
                    value = localConfig.hitBonus,
                    range = 0f..15f,
                    steps = 14,
                    valueText = "%.1f".format(localConfig.hitBonus),
                    onValueChange = { v ->
                        localConfig = localConfig.copy(hitBonus = v)
                    },
                )
                SettingsGroupDivider()
                SliderRow(
                    icon = Icons.Outlined.Compress,
                    iconLabel = stringResource(R.string.settings_memory_compile_threshold),
                    title = stringResource(R.string.settings_memory_compile_threshold),
                    subtitle = stringResource(R.string.settings_memory_compile_threshold_subtitle, localConfig.baseImportance),
                    value = localConfig.compileThreshold,
                    range = 1f..(localConfig.baseImportance - 0.5f),
                    steps = 17,
                    valueText = "%.1f".format(localConfig.compileThreshold),
                    onValueChange = { v ->
                        localConfig = localConfig.copy(compileThreshold = v)
                    },
                )
                SettingsGroupDivider()
                SliderRow(
                    icon = Icons.Outlined.Speed,
                    iconLabel = stringResource(R.string.settings_memory_forget_speed),
                    title = stringResource(R.string.settings_memory_forget_speed),
                    subtitle = stringResource(R.string.settings_memory_forget_speed_subtitle),
                    value = localConfig.forgetSpeed,
                    range = 0.5f..3f,
                    steps = 24,
                    valueText = "%.1fx".format(localConfig.forgetSpeed),
                    onValueChange = { v ->
                        localConfig = localConfig.copy(forgetSpeed = v)
                    },
                )
                SettingsGroupDivider()
                // v1.78 (#21): 恢复默认按钮
                SettingsActionRow(
                    title = stringResource(R.string.settings_memory_restore_default),
                    subtitle = stringResource(R.string.settings_memory_restore_default_subtitle),
                    onClick = { localConfig = MemoryConfig() },
                )
            }
        }

        // ── 2. 高级开关 ──
        item { SectionLabel(stringResource(R.string.settings_memory_advanced_section)) }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = Icons.Outlined.Memory,
                    title = stringResource(R.string.settings_memory_experience_lib),
                    subtitle = stringResource(R.string.settings_memory_experience_lib_subtitle),
                    checked = experienceEnabled,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveExperienceEnabled(v) }
                    },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = Icons.Outlined.Bolt,
                    title = stringResource(R.string.settings_memory_keep_awake),
                    subtitle = stringResource(R.string.settings_memory_keep_awake_subtitle),
                    checked = keepAwake,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveKeepAwake(v) }
                    },
                )
            }
        }

        // ── 3. 通知策略 ──
        item { SectionLabel(stringResource(R.string.settings_memory_notification_section)) }
        item {
            SettingsGroup {
                NotificationPolicyRow(
                    current = notificationPolicy,
                    onChange = { v ->
                        scope.launch { settings.saveNotificationPolicy(v) }
                    },
                )
            }
        }
    }
}

@Composable
private fun NotificationPolicyRow(
    current: String,
    onChange: (String) -> Unit,
) {
    val replyNotificationCd = stringResource(R.string.settings_memory_reply_notification_cd)
    val replyNotificationTitle = stringResource(R.string.settings_memory_reply_notification)
    val replyNotificationSubtitle = stringResource(R.string.settings_memory_reply_notification_subtitle)
    // 通知策略选项(值 -> 资源 ID),R.string.xxx 为编译期常量,无需 remember
    val policies = listOf(
        "never" to R.string.settings_memory_policy_never,
        "when_unfocused" to R.string.settings_memory_policy_when_unfocused,
        "always" to R.string.settings_memory_policy_always,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                imageVector = Icons.Outlined.CloudOff,
                contentDescription = replyNotificationCd,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = replyNotificationTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = replyNotificationSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            policies.forEach { (value, labelRes) ->
                IosChip(
                    selected = current == value,
                    onClick = { onChange(value) },
                    label = stringResource(labelRes),
                )
            }
        }
    }
}

@Composable
private fun SliderRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconLabel: String,
    title: String,
    subtitle: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueText: String,
    onValueChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            // v1.78 (#22): 无障碍 — 图标语义标签
            contentDescription = iconLabel,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            IosSlider(
                value = value,
                onValueChange = { v -> onValueChange(v) },
                valueRange = range,
                steps = steps,
                modifier = Modifier.padding(top = 4.dp),
                showValueLabel = false,
            )
        }
    }
}

/**
 * v1.78 (#21): 可点击的操作行(如"恢复默认")。
 */
@Composable
private fun SettingsActionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
