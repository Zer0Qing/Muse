package io.zer0.muse.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.relationship.AssistantRelationshipEntity
import io.zer0.muse.data.relationship.AssistantRelationshipRepository
import io.zer0.muse.data.relationship.ProactiveLevel
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * v2.6.7（P1-1）: 角色关系设置页。
 *
 * 让用户能显式定义"我和这个角色是什么关系"，并调节他主动找你的频率。
 * 此前关系完全不存在——所有角色"同等地位、同样粘人"，主动私信是写死的
 * 40% 概率，与聊了多久毫无关系。
 *
 * 亲密度只读展示（由真实互动自动累加），用户可改的是：关系标签、意愿档位、
 * 是否允许主动私信、每日上限。
 */
@Composable
fun RelationshipSettingsPage(onBack: () -> Unit) {
    val assistantRepository: AssistantRepository = koinInject()
    val relationshipRepository: AssistantRelationshipRepository = koinInject()
    val scope = rememberCoroutineScope()

    val assistants by produceState(initialValue = emptyList<io.zer0.muse.data.assistant.AssistantEntity>()) {
        value = runCatching { assistantRepository.getAll() }.getOrDefault(emptyList())
    }
    val relationships by relationshipRepository.observeAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val byId = remember(relationships) { relationships.associateBy { it.assistantId } }

    var editing by remember { mutableStateOf<AssistantRelationshipEntity?>(null) }

    SettingsSubPageScaffold(
        title = stringResource(R.string.settings_relationship_title),
        onBack = onBack,
    ) {
        item {
            Text(
                text = stringResource(R.string.settings_relationship_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = MusePaddings.screen, vertical = 4.dp),
            )
        }
        assistants.forEach { assistant ->
            item(key = assistant.id) {
                SettingsGroup {
                    SettingsItemRow(
                        icon = MuseIcons.user,
                        title = assistant.name,
                        subtitle = relationshipSubtitle(byId[assistant.id]),
                        onClick = {
                            editing = byId[assistant.id]
                                ?: AssistantRelationshipEntity(assistantId = assistant.id)
                        },
                    ) {
                        ChevronRight()
                    }
                }
            }
        }
    }

    editing?.let { current ->
        RelationshipEditDialog(
            initial = current,
            onDismiss = { editing = null },
            onSave = { updated ->
                scope.launch {
                    relationshipRepository.upsert(updated)
                    editing = null
                }
            },
        )
    }
}

/** 列表副标题：「关系标签 · 亲密度」；未设置时显示提示文案。 */
@Composable
private fun relationshipSubtitle(relationship: AssistantRelationshipEntity?): String {
    val unset = stringResource(R.string.settings_relationship_unset)
    if (relationship == null) return unset
    val label = relationship.relationLabel.takeIf { it.isNotBlank() } ?: unset
    return if (relationship.affinity > 0) {
        "$label · ${affinityLabel(relationship.affinity)}"
    } else {
        label
    }
}

@Suppress("LongMethod")
@Composable
private fun RelationshipEditDialog(
    initial: AssistantRelationshipEntity,
    onDismiss: () -> Unit,
    onSave: (AssistantRelationshipEntity) -> Unit,
) {
    var label by remember { mutableStateOf(initial.relationLabel) }
    var level by remember { mutableStateOf(initial.proactiveLevel) }
    // v2.6.9: 单角色私信开关 + 每日上限（字段早已存在，UI 一直没接）
    var proactiveEnabled by remember { mutableStateOf(initial.proactiveEnabled) }
    var dailyLimitText by remember { mutableStateOf(initial.dailyProactiveLimit.toString()) }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_relationship_edit_title),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_relationship_label_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
                MuseTextField(
                    value = label,
                    onValueChange = { label = it },
                    placeholder = {
                        Text(stringResource(R.string.settings_relationship_label_placeholder))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = stringResource(R.string.settings_relationship_level_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
                ProactiveLevel.ALL.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { level = option }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = proactiveLevelLabel(option),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (level == option) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            fontWeight = if (level == option) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        if (level == option) {
                            Icon(
                                imageVector = MuseIcons.check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                // v2.6.9: 单角色开关 —— 关掉后这个角色不再主动私信，
                // 但手动聊天、朋友圈互动不受影响。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { proactiveEnabled = !proactiveEnabled }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_relationship_proactive_enabled),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                        Text(
                            text = stringResource(R.string.settings_relationship_proactive_enabled_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    MuseSwitch(
                        checked = proactiveEnabled,
                        onCheckedChange = { proactiveEnabled = it },
                        contentDescription = stringResource(
                            R.string.settings_relationship_proactive_enabled,
                        ),
                    )
                }

                // v2.6.9: 每日主动上限（0 = 不限）
                Text(
                    text = stringResource(R.string.settings_relationship_daily_limit),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
                MuseTextField(
                    value = dailyLimitText,
                    onValueChange = { v -> dailyLimitText = v.filter { it.isDigit() }.take(2) },
                    placeholder = { Text(stringResource(R.string.settings_relationship_daily_limit_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmText = stringResource(R.string.action_save),
        onConfirm = {
            onSave(
                initial.copy(
                    relationLabel = label.trim(),
                    proactiveLevel = level,
                    proactiveEnabled = proactiveEnabled,
                    dailyProactiveLimit = dailyLimitText.toIntOrNull()?.coerceIn(0, 99) ?: 0,
                ),
            )
        },
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
    )
}

/** 亲密度可读标签。 */
@Composable
private fun affinityLabel(affinity: Int): String = when {
    affinity >= 80 -> stringResource(R.string.settings_relationship_affinity_close)
    affinity >= 50 -> stringResource(R.string.settings_relationship_affinity_familiar)
    affinity >= 20 -> stringResource(R.string.settings_relationship_affinity_known)
    else -> stringResource(R.string.settings_relationship_affinity_new)
}

@Composable
private fun proactiveLevelLabel(level: String): String = when (level) {
    ProactiveLevel.LOW -> stringResource(R.string.settings_relationship_level_low)
    ProactiveLevel.HIGH -> stringResource(R.string.settings_relationship_level_high)
    else -> stringResource(R.string.settings_relationship_level_normal)
}
