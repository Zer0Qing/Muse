@file:Suppress("FunctionNaming", "LongMethod")

package io.zer0.muse.ui.groupchat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.AgentTeam
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.chipOutline
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseTextField

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
@Composable
fun CreateGroupChatDialog(
    assistants: List<AssistantEntity>,
    teams: List<AgentTeam>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, memberIds: List<String>, teamId: String?, initialMode: String?, initialLength: String?) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var selectedMemberIds by rememberSaveable { mutableStateOf(setOf<String>()) }
    var selectedTeamId by rememberSaveable { mutableStateOf<String?>(null) }
    // v1.77: 输入校验 — 首次提交后才展示错误
    var showErrors by rememberSaveable { mutableStateOf(false) }
    // v2.x: 快速模板 — 点选自动填名称并预置讨论参数(创建后应用)
    var selectedTemplateKey by rememberSaveable { mutableStateOf<String?>(null) }
    var templateMode by rememberSaveable { mutableStateOf<String?>(null) }
    var templateLength by rememberSaveable { mutableStateOf<String?>(null) }
    // v2.3.1: 预置名直接复用模板文案资源(labelRes),不再重复硬编码中文 ——
    // 该值既作 chip 文案也作群聊名预填,两处共用同一份 7 语言译文
    // 只有一个候选成员时，多人模板（辩论 / 脑暴 / 复盘）无法成立：
    // 这些模式预设的是多方轮流发言，单成员下等于自说自话。
    val templatePresets = if (assistants.size <= 1) {
        listOf(
            TemplatePreset("review", R.string.groupchat_template_review, "host", "standard"),
        )
    } else {
        listOf(
            TemplatePreset("review", R.string.groupchat_template_review, "host", "standard"),
            TemplatePreset("debate", R.string.groupchat_template_debate, "debate", "standard"),
            TemplatePreset("brainstorm", R.string.groupchat_template_brainstorm, "auto", "brief"),
            TemplatePreset("retro", R.string.groupchat_template_retro, "round_robin", "detailed"),
        )
    }

    val maxNameLength = 30
    val nameError = showErrors && name.isBlank()
    val memberError = showErrors && selectedMemberIds.isEmpty()

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.groupchat_create_title),
        content = {
            // 群聊名输入框
            MuseTextField(
                value = name,
                onValueChange = { newName ->
                    showErrors = false
                    name = newName.take(maxNameLength)
                },
                label = { Text(stringResource(R.string.groupchat_name_label)) },
                singleLine = true,
                // 显式锁 1 行：singleLine 只影响输入行为，
                // 不限制最小高度，输入框会被拉到接近多行文本域的样子。
                minLines = 1,
                maxLines = 1,
                isError = nameError,
                supportingText = if (nameError) {
                    { Text(stringResource(R.string.groupchat_name_required), color = MaterialTheme.colorScheme.error) }
                } else {
                    { Text("${name.length}/$maxNameLength") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))

            // v2.x: 快速模板行(点选自动填名称与讨论参数)
            Text(
                text = stringResource(R.string.groupchat_template_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 2.dp),
            ) {
                items(templatePresets, key = { it.key }) { preset ->
                    // v2.3.1: 先取译文,再在 onClick(非 Composable)里复用
                    val label = stringResource(preset.labelRes)
                    MuseChip(
                        selected = selectedTemplateKey == preset.key,
                        onClick = {
                            selectedTemplateKey = preset.key
                            name = label
                            templateMode = preset.mode
                            templateLength = preset.length
                        },
                        label = label,
                        // 弹窗底色与未选中胶囊同为白，加描边才看得出可点。
                        modifier = Modifier.chipOutline(selected = selectedTemplateKey == preset.key),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            // 成员选择
            // 只在下方保留一条明确提示；此前标题变红 + 下方红字同时出现，
            // 同一句话说了两遂。
            Text(
                text = stringResource(R.string.groupchat_select_members),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
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
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp),
                ) {
                    items(assistants, key = { it.id }) { assistant ->
                        val selected = assistant.id in selectedMemberIds
                        MuseChip(
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
                            modifier = Modifier.chipOutline(selected = selected),
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
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp),
                ) {
                    item(key = "no_team") {
                        MuseChip(
                            selected = selectedTeamId == null,
                            onClick = { selectedTeamId = null },
                            label = stringResource(R.string.groupchat_no_team),
                        )
                    }
                    items(teams, key = { it.id }) { team ->
                        val selected = selectedTeamId == team.id
                        MuseChip(
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
                onConfirm(trimmedName, selectedMemberIds.toList(), selectedTeamId, templateMode, templateLength)
            } else {
                showErrors = true
            }
        },
        dismissText = stringResource(R.string.groupchat_cancel),
        onDismiss = onDismiss,
    )
}

/** v2.x: 新建群聊快速模板预置(名称 + 讨论模式 + 发言长度)。 */
data class TemplatePreset(
    val key: String,
    /** v2.3.1: 模板名称资源 — 同时用作 chip 文案与群聊名预填。 */
    val labelRes: Int,
    val mode: String,
    val length: String,
)
