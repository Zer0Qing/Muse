package io.zer0.muse.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.fact.ProfileSection
import io.zer0.memory.fact.ProfileSnapshotEntity
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.surface.CardGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * v2.6.23(M3): 「系统提炼的画像」区 —— 展示自动画像快照、提供总开关与逐段人工覆盖。
 *
 * 与上方手填字段的关系必须让用户看得见:
 *  - 手填字段永远优先,这里只展示"系统从对话里学到的";
 *  - 每段可「覆盖」(写 manual_content,注入时替换自动版)或「恢复自动」;
 *  - 关掉开关 = 注入立即回到只用手填画像,快照数据保留不删。
 */
@Composable
internal fun ProfileSnapshotSection() {
    val settings: SettingsRepository = koinInject()
    val factDbProvider: FactDbProvider = koinInject()
    val scope = rememberCoroutineScope()

    val chatPrefs by settings.chatPreferencesFlow.collectAsStateWithLifecycle(initialValue = null)
    val enabled = chatPrefs?.profileSnapshotEnabled ?: true

    var snapshots by remember { mutableStateOf<List<ProfileSnapshotEntity>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) {
        val rows = resultOf {
            factDbProvider.getFactDb("default").profileSnapshotDao()
                .getByScopeAndSpace("main", "default")
        }.onError { msg, t -> Logger.w("ProfileSnapshotSection", "读画像快照失败: $msg", t) }
            .getOrNull().orEmpty()
        snapshots = rows.sortedBy { sectionOrderIndex(it.section) }
    }

    var overrideTarget by remember { mutableStateOf<ProfileSnapshotEntity?>(null) }

    CardGroup(title = { Text(stringResource(R.string.settings_user_profile_auto_section)) }) {
        item(
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_user_profile_auto_switch))
                        Text(
                            text = stringResource(R.string.settings_user_profile_auto_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { on ->
                            scope.launch { settings.updateChatPreferences { it.copy(profileSnapshotEnabled = on) } }
                        },
                    )
                }
            },
        )
        if (snapshots.isEmpty()) {
            item(
                headlineContent = {
                    Text(
                        text = stringResource(R.string.settings_user_profile_auto_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                },
            )
        }
        snapshots.forEach { row ->
            val body = row.manualContent?.takeIf { it.isNotBlank() } ?: row.autoContent
            if (body.isBlank() && row.manualContent.isNullOrBlank()) return@forEach
            item(
                headlineContent = {
                    ProfileSnapshotRow(
                        row = row,
                        body = body,
                        onOverride = { overrideTarget = row },
                        onRevert = {
                            scope.launch(Dispatchers.IO) {
                                resultOf {
                                    factDbProvider.getFactDb("default").profileSnapshotDao()
                                        .setManualContent(row.scope, row.spaceId, row.section, null)
                                }.onError { msg, t -> Logger.w("ProfileSnapshotSection", "恢复自动画像失败: $msg", t) }
                                reload++
                            }
                        },
                    )
                },
            )
        }
    }

    overrideTarget?.let { target ->
        ProfileSnapshotOverrideDialog(
            target = target,
            factDbProvider = factDbProvider,
            onDone = { overrideTarget = null },
            onSaved = { reload++ },
        )
    }
}

/** 单段快照行:标题 + 正文 + 「覆盖/恢复自动」动作。 */
@Composable
private fun ProfileSnapshotRow(
    row: ProfileSnapshotEntity,
    body: String,
    onOverride: () -> Unit,
    onRevert: () -> Unit,
) {
    val overridden = !row.manualContent.isNullOrBlank()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(profileSectionTitle(row.section)),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(
                if (overridden) {
                    R.string.settings_user_profile_auto_revert
                } else {
                    R.string.settings_user_profile_auto_override
                },
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable { if (overridden) onRevert() else onOverride() }
                .padding(vertical = 4.dp),
        )
    }
}

/** 覆盖弹层:初值取自动版(让用户在原文上改,而不是面对空框)。 */
@Composable
private fun ProfileSnapshotOverrideDialog(
    target: ProfileSnapshotEntity,
    factDbProvider: FactDbProvider,
    onDone: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var draft by remember(target.id) { mutableStateOf(target.autoContent) }
    MuseDialog(
        onDismissRequest = onDone,
        title = stringResource(R.string.settings_user_profile_auto_override),
        content = {
            MuseTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text(stringResource(profileSectionTitle(target.section))) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmText = stringResource(R.string.action_save),
        onConfirm = {
            val value = draft.trim()
            scope.launch(Dispatchers.IO) {
                resultOf {
                    factDbProvider.getFactDb("default").profileSnapshotDao()
                        .setManualContent(target.scope, target.spaceId, target.section, value.ifBlank { null })
                }.onError { msg, t -> Logger.w("ProfileSnapshotSection", "覆盖画像段失败: $msg", t) }
                onSaved()
            }
            onDone()
        },
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDone,
    )
}

/** 六段按注入顺序排列;未知段名放最后(前向兼容新增段)。 */
private fun sectionOrderIndex(section: String): Int =
    ProfileSection.ORDER.indexOf(section).takeIf { it >= 0 } ?: Int.MAX_VALUE

/** 六段的本地化标题。 */
@Composable
private fun profileSectionTitle(section: String): Int = when (section) {
    ProfileSection.IDENTITY_SETTINGS -> R.string.profile_section_identity
    ProfileSection.RELATIONSHIP_SETTINGS -> R.string.profile_section_relationship
    ProfileSection.STABLE_FACTS -> R.string.profile_section_stable
    ProfileSection.INTERACTION_PREFERENCES -> R.string.profile_section_interaction
    ProfileSection.RECENT_INTERACTIONS -> R.string.profile_section_recent
    ProfileSection.UNCERTAIN_NOTES -> R.string.profile_section_uncertain
    else -> R.string.profile_section_stable
}
