package io.zer0.muse.ui.groupchat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import io.zer0.muse.ui.common.IosChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.AgentTeam
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.semiLarge

/**
 * 新建群聊对话框。
 *
 * 包含:
 *  - 群聊名输入框
 *  - 成员多选 chips(从助手列表加载)
 *  - 可选关联团队(从 SettingsRepository.multiAgentConfigCache.teams 加载)
 *
 * 不使用 ModalBottomSheet(已知卡死 bug),改用 MuseDialog。
 *
 * @param assistants 全部助手列表(成员候选)
 * @param teams 全部协作团队(关联团队候选)
 * @param onDismiss 关闭对话框
 * @param onConfirm 确认创建回调(name, memberIds, teamId)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateGroupChatDialog(
    assistants: List<AssistantEntity>,
    teams: List<AgentTeam>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, memberIds: List<String>, teamId: String?) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var selectedMemberIds by rememberSaveable { mutableStateOf(setOf<String>()) }
    var selectedTeamId by rememberSaveable { mutableStateOf<String?>(null) }
    // v1.77: 输入校验 — 首次提交后才展示错误
    var showErrors by rememberSaveable { mutableStateOf(false) }

    val maxNameLength = 30
    val nameError = showErrors && name.isBlank()
    val memberError = showErrors && selectedMemberIds.isEmpty()

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.groupchat_create_title),
        content = {
            // 群聊名输入框
            OutlinedTextField(
                value = name,
                onValueChange = { newName ->
                    showErrors = false
                    name = newName.take(maxNameLength)
                },
                label = { Text(stringResource(R.string.groupchat_name_label)) },
                singleLine = true,
                isError = nameError,
                supportingText = if (nameError) {
                    { Text(stringResource(R.string.groupchat_name_required), color = MaterialTheme.colorScheme.error) }
                } else {
                    { Text("${name.length}/$maxNameLength") }
                },
                shape = MuseShapes.semiLarge,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))

            // 成员选择
            Text(
                text = stringResource(R.string.groupchat_select_members),
                style = MaterialTheme.typography.labelMedium,
                color = if (memberError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.outline
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            if (assistants.isEmpty()) {
                Text(
                    text = stringResource(R.string.groupchat_no_assistants),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    assistants.forEach { assistant ->
                        val selected = assistant.id in selectedMemberIds
                        IosChip(
                            selected = selected,
                            onClick = {
                                showErrors = false
                                selectedMemberIds = if (selected) {
                                    selectedMemberIds - assistant.id
                                } else {
                                    selectedMemberIds + assistant.id
                                }
                            },
                            label = assistant.name,
                        )
                    }
                }
            }
            if (memberError) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.groupchat_member_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // 关联团队(可选)
            if (teams.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.groupchat_team_optional),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    IosChip(
                        selected = selectedTeamId == null,
                        onClick = { selectedTeamId = null },
                        label = stringResource(R.string.groupchat_no_team),
                    )
                    teams.forEach { team ->
                        val selected = selectedTeamId == team.id
                        IosChip(
                            selected = selected,
                            onClick = { selectedTeamId = team.id },
                            label = team.name,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.groupchat_selected_members, selectedMemberIds.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmText = stringResource(R.string.groupchat_create_btn),
        onConfirm = {
            val trimmedName = name.trim()
            if (trimmedName.isNotBlank() && selectedMemberIds.isNotEmpty()) {
                onConfirm(trimmedName, selectedMemberIds.toList(), selectedTeamId)
            } else {
                showErrors = true
            }
        },
        dismissText = stringResource(R.string.groupchat_cancel),
        onDismiss = onDismiss,
    )
}
