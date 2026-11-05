package io.zer0.muse.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AgentDirective
import io.zer0.muse.data.assistant.AgentDirectiveStore
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.assistant.DirectivePolarity
import io.zer0.muse.proactive.AgentDirectivesPrompt
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.navigation.MuseTopBarIconButton
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * 「希望它做的事 / 不希望它做的事」（整改计划 第 4 条 ③，参照图 4 底部那张卡）。
 *
 * 三个刻意的设计：
 *  - **按助手分键**：这是"我对这个人的长期要求"。全局共享会把 A 的规矩套到 B 身上；
 *    按会话分又容易今天设了明天没 —— 跟人设走最符合直觉。
 *  - **不做任何客户端硬拦截**：这里只写进系统提示（`AgentDirectivesPrompt`）。
 *    真去拦输出会变成"我明明要求了却没生效，还不知道为什么"。
 *  - **超量要说出来**：装不进预算的条目在页面上点名，不静默丢。
 *
 * 参数一律传 `@StringRes` 而不是提前 `stringResource(...)`：列表块的构建发生在
 * `LazyListScope`（非组合上下文），提前解析根本编不过 —— 我第一版就踩在这里。
 */
@Composable
fun AgentDirectivesPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { AgentDirectiveStore.of(context) }
    val assistantRepository: AssistantRepository = koinInject()

    val assistants by assistantRepository.observeAll.collectAsStateWithLifecycle(initialValue = emptyList())
    val allDirectives by store.all.collectAsStateWithLifecycle()
    var selectedAgent by remember { mutableStateOf<String?>(null) }
    var drafting by remember { mutableStateOf<DirectivePolarity?>(null) }

    val agentId = selectedAgent ?: assistants.firstOrNull()?.id ?: ""
    val mine = allDirectives.filter { it.agentId == agentId }
    val forbidden = mine.filter { it.polarity == DirectivePolarity.MUST_NOT }.sortedBy { it.position }
    val desired = mine.filter { it.polarity == DirectivePolarity.SHOULD }.sortedBy { it.position }
    // 与真正注入提示词用的是同一个函数：界面说的"没生效"必须和模型那侧一致，
    // 两份口径正是本项目反复出问题的地方。
    val omittedCount = remember(mine) {
        AgentDirectivesPrompt.build(mine, AgentDirectiveStore.MAX_TOTAL_CHARS).omitted.size
    }

    SettingsSubPageScaffold(title = stringResource(R.string.settings_screen_agent_directives), onBack = onBack) {
        if (assistants.size > 1) {
            item {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    SectionLabel(stringResource(R.string.agent_directives_hint))
                    Row(
                        modifier = Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        assistants.forEach { assistant ->
                            MuseChip(
                                label = assistant.name,
                                selected = assistant.id == agentId,
                                onClick = { selectedAgent = assistant.id },
                            )
                        }
                    }
                }
            }
        }
        directiveSection(
            titleRes = R.string.agent_directives_forbidden_section,
            rows = forbidden,
            onAdd = { drafting = DirectivePolarity.MUST_NOT },
            onToggle = { id, enabled -> scope.launch { store.setEnabled(id, enabled) } },
            onMove = { id, delta -> scope.launch { store.move(id, delta) } },
            onDelete = { id -> scope.launch { store.remove(id) } },
        )
        directiveSection(
            titleRes = R.string.agent_directives_desired_section,
            rows = desired,
            onAdd = { drafting = DirectivePolarity.SHOULD },
            onToggle = { id, enabled -> scope.launch { store.setEnabled(id, enabled) } },
            onMove = { id, delta -> scope.launch { store.move(id, delta) } },
            onDelete = { id -> scope.launch { store.remove(id) } },
        )
        item {
            DirectiveFootnote(omittedCount = omittedCount)
        }
    }

    drafting?.let { polarity ->
        DirectiveEditor(
            polarity = polarity,
            onDismiss = { drafting = null },
            onSave = { text ->
                drafting = null
                scope.launch { store.add(agentId, polarity, text) }
            },
        )
    }
}

private fun LazyListScope.directiveSection(
    @StringRes titleRes: Int,
    rows: List<AgentDirective>,
    onAdd: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onMove: (String, Int) -> Unit,
    onDelete: (String) -> Unit,
) {
    item {
        val addRes = if (titleRes == R.string.agent_directives_forbidden_section) {
            R.string.agent_directives_add_forbidden
        } else {
            R.string.agent_directives_add_desired
        }
        SectionLabel(stringResource(titleRes))
        SettingsGroup(modifier = Modifier.padding(top = 8.dp)) {
            if (rows.isEmpty()) {
                SettingsItemRow(
                    icon = MuseIcons.plus,
                    title = stringResource(R.string.agent_directives_empty),
                    subtitle = stringResource(addRes),
                    onClick = onAdd,
                )
            } else {
                rows.forEachIndexed { index, directive ->
                    if (index > 0) SettingsGroupDivider()
                    // 一行给齐：内容、上移、下移、删除、启用开关。
                    // 开关用 MuseSwitch 而不是裸 M3 Switch（业务页禁裸 M3 控件由收敛门禁在管）；
                    // 按钮用 MuseTopBarIconButton，它自带 48dp 触摸区，不用我手调尺寸。
                    SettingsItemRow(
                        title = directive.text,
                        subtitle = stringResource(R.string.agent_directives_text_limit),
                        titleMaxLines = 2,
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MuseTopBarIconButton(
                                    icon = MuseIcons.arrowUp,
                                    contentDescription = stringResource(R.string.agent_directives_move_up),
                                    enabled = index > 0,
                                    onClick = { onMove(directive.id, -1) },
                                )
                                MuseTopBarIconButton(
                                    icon = MuseIcons.arrowDown,
                                    contentDescription = stringResource(R.string.agent_directives_move_down),
                                    enabled = index < rows.lastIndex,
                                    onClick = { onMove(directive.id, 1) },
                                )
                                MuseTopBarIconButton(
                                    icon = MuseIcons.trash,
                                    contentDescription = stringResource(R.string.agent_directives_delete),
                                    onClick = { onDelete(directive.id) },
                                )
                                MuseSwitch(
                                    checked = directive.enabled,
                                    onCheckedChange = { enabled -> onToggle(directive.id, enabled) },
                                    contentDescription = stringResource(R.string.agent_directives_switch),
                                )
                            }
                        },
                    )
                }
            }
            SettingsGroupDivider()
            SettingsItemRow(
                icon = MuseIcons.plus,
                title = stringResource(addRes),
                onClick = onAdd,
            )
        }
    }
}

@Composable
private fun DirectiveFootnote(omittedCount: Int) {
    if (omittedCount <= 0) return
    // 超量必须说得出数量：静默截断等于让用户以为每条都生效了
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Text(
            text = stringResource(R.string.agent_directives_omitted, "$omittedCount"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun DirectiveEditor(
    polarity: DirectivePolarity,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(polarity) { mutableStateOf("") }
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(
            if (polarity == DirectivePolarity.MUST_NOT) R.string.agent_directives_add_forbidden
            else R.string.agent_directives_add_desired,
        ),
        confirmEnabled = text.isNotBlank(),
        onConfirm = { onSave(text.trim()) },
        content = {
            Column {
                MuseTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.agent_directives_editor_placeholder)) },
                )
                Text(
                    text = stringResource(R.string.agent_directives_text_limit),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = MusePaddings.itemGap),
                )
            }
        },
    )
}
