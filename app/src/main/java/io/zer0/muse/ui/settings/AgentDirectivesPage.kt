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
 *  - **不做任何客户端硬拦截**：这里只写进系统提示（`AgentDirectivesUiState`（内部再调装配层同名逻辑））。
 *    真去拦输出会变成"我明明要求了却没生效，还不知道为什么"。
 *  - **超量要说出来**：装不进预算的条目在页面上点名，不静默丢。
 *
 * 参数一律传 `@StringRes` 而不是提前 `stringResource(...)`：列表块的构建发生在
 * `LazyListScope`（非组合上下文），提前解析根本编不过 —— 我第一版就踩在这里。
 */
@Composable
fun AgentDirectivesPage(
    onBack: () -> Unit,
    /** 跳到既有的「设置 → 多媒体」页配置音色；这里**不再存一份**。 */
    onOpenVoiceSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { AgentDirectiveStore.of(context) }
    val assistantRepository: AssistantRepository = koinInject()

    val assistants by assistantRepository.observeAll.collectAsStateWithLifecycle(initialValue = emptyList())
    val allDirectives by store.all.collectAsStateWithLifecycle()
    var selectedAgent by remember { mutableStateOf<String?>(null) }
    var drafting by remember { mutableStateOf<DirectivePolarity?>(null) }

    // 作用域解析、分组、未生效计数都在这里（纯函数），页面只负责渲染与派发：
    // 这样这三条判断能被单测钉住，而不是必须起一整套 Koin + Room 才能验证。
    val agentId = AgentDirectivesUiState.resolveAgentId(
        selected = selectedAgent,
        assistantIds = assistants.map { it.id },
    )
    val sections = remember(allDirectives, agentId) {
        AgentDirectivesUiState.sectionsFor(allDirectives, agentId)
    }
    val forbidden = sections.forbidden
    val desired = sections.desired
    val omittedCount = remember(forbidden, desired) {
        AgentDirectivesUiState.omittedCount(forbidden + desired)
    }

    AgentDirectivesContent(
        assistants = assistants,
        agentId = agentId,
        forbidden = forbidden,
        desired = desired,
        omittedCount = omittedCount,
        onBack = onBack,
        onSelectAgent = { selectedAgent = it },
        onSave = { polarity, text -> scope.launch { store.add(agentId, polarity, text) } },
        onToggle = { id, enabled -> scope.launch { store.setEnabled(id, enabled) } },
        onMove = { id, delta -> scope.launch { store.move(id, delta) } },
        onDelete = { id -> scope.launch { store.remove(id) } },
        onOpenTimbre = onOpenVoiceSettings,
    )
}

/**
 * 无状态的渲染层：数据与动作全部由外壳传进来。
 *
 * 为什么要拆：外壳 `koinInject(AssistantRepository)`，要整体测渲染就得连 Room 一起起；
 * 拆开之后这一层可以用假数据直接渲染出来测（分段顺序、空态、超量提示、语音档位哪颗被选中），
 * 而外壳薄到只剩"取数据 + 派发"，不值得为它搭脚手架 —— 与 `WebSearchOrderSection` 同形状。
 */
@Composable
internal fun AgentDirectivesContent(
    assistants: List<io.zer0.muse.data.assistant.AssistantEntity>,
    agentId: String,
    forbidden: List<AgentDirective>,
    desired: List<AgentDirective>,
    omittedCount: Int,
    onBack: () -> Unit,
    onSelectAgent: (String) -> Unit,
    onSave: (DirectivePolarity, String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onMove: (String, Int) -> Unit,
    onDelete: (String) -> Unit,
    onOpenTimbre: () -> Unit,
) {
    var drafting by remember { mutableStateOf<DirectivePolarity?>(null) }

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
                                onClick = { onSelectAgent(assistant.id) },
                            )
                        }
                    }
                }
            }
        }
        directiveSection(
            copy = DirectiveSectionCopy(
                titleRes = R.string.agent_directives_forbidden_section,
                addRes = R.string.agent_directives_add_forbidden,
                emptyRes = R.string.agent_directives_empty,
            ),
            rows = forbidden,
            onAdd = { drafting = DirectivePolarity.MUST_NOT },
            onToggle = onToggle,
            onMove = onMove,
            onDelete = onDelete,
        )
        directiveSection(
            copy = DirectiveSectionCopy(
                titleRes = R.string.agent_directives_desired_section,
                addRes = R.string.agent_directives_add_desired,
                // 「尽量做到」必须有属于自己的示例：以前两段共用 agent_directives_empty，
                // 这一段落举的却是「别叫我宝贝」这种「绝对不要」的例子。
                emptyRes = R.string.agent_directives_empty_desired,
            ),
            rows = desired,
            onAdd = { drafting = DirectivePolarity.SHOULD },
            onToggle = onToggle,
            onMove = onMove,
            onDelete = onDelete,
        )
        item {
            DirectiveFootnote(omittedCount = omittedCount)
        }
        item {
            VoiceTimbreEntry(onOpenTimbre = onOpenTimbre)
        }
    }

    drafting?.let { polarity ->
        DirectiveEditor(
            polarity = polarity,
            onDismiss = { drafting = null },
            onSave = { text ->
                drafting = null
                onSave(polarity, text)
            },
        )
    }
}

/**
 * 一段约定分节的文案：段标题、添加入口、空态示例。
 *
 * 三者总是同时出现、也必须同时换（漏一个就是刚才那个「两段共用同一句示例」的 bug），
 * 所以打包成一个类型而不是三个并列参数。
 */
private data class DirectiveSectionCopy(
    @StringRes val titleRes: Int,
    @StringRes val addRes: Int,
    @StringRes val emptyRes: Int,
)

private fun LazyListScope.directiveSection(
    copy: DirectiveSectionCopy,
    rows: List<AgentDirective>,
    onAdd: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onMove: (String, Int) -> Unit,
    onDelete: (String) -> Unit,
) {
    item {
        SectionLabel(stringResource(copy.titleRes))
        SettingsGroup(modifier = Modifier.padding(top = 8.dp)) {
            if (rows.isEmpty()) {
                // 空态只说"还没有"，不再重复一遍"添加"：段尾本来就有一行添加入口，
                // 两处写同一个标签会让用户以为是两个不同的动作（这条是渲染测试实测出来的）。
                // 示例文案也必须分段：以前两段共用 agent_directives_empty，
                // 「尽量做到」那段举的是「绝对不要」的例子，而且界面会同时出现两句一模一样的空态提示。
                SettingsItemRow(
                    title = stringResource(copy.emptyRes),
                    subtitle = stringResource(R.string.agent_directives_text_limit),
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
                title = stringResource(copy.addRes),
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
private fun DirectiveEditor(polarity: DirectivePolarity, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(polarity) { mutableStateOf("") }
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(
            if (polarity == DirectivePolarity.MUST_NOT) {
                R.string.agent_directives_add_forbidden
            } else {
                R.string.agent_directives_add_desired
            },
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

/**
 * 「音色」入口：只跳到既有的语音播报设置页。
 *
 * 2.6.24: 语音回复频率已统一到语音播报设置，本页不再放第二份（两处能改、值却可能不一致）。
 */
@Composable
private fun VoiceTimbreEntry(onOpenTimbre: () -> Unit) {
    SectionLabel(stringResource(R.string.agent_directives_voice_section))
    SettingsGroup(modifier = Modifier.padding(top = 8.dp)) {
        SettingsItemRow(
            icon = MuseIcons.microphone,
            title = stringResource(R.string.voice_timbre_entry),
            subtitle = stringResource(R.string.voice_timbre_entry_desc),
            onClick = onOpenTimbre,
        )
    }
}
