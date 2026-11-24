package io.zer0.muse.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AgentDirective
import io.zer0.muse.data.assistant.AgentDirectiveStore
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.assistant.DirectivePolarity
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.MuseAnchoredMenu
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.MuseListItem
import io.zer0.muse.ui.common.surface.actionItem
import io.zer0.muse.ui.common.surface.navigationItem
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseSpacing
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * 助手约定页：按助手分别管理「绝对不要」与「尽量做到」两条长期约定列表。
 */
@Composable
fun AgentDirectivesPage(onBack: () -> Unit, onOpenVoiceSettings: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { AgentDirectiveStore.of(context) }
    val assistantRepository: AssistantRepository = koinInject()

    val assistants by assistantRepository.observeAll.collectAsStateWithLifecycle(initialValue = emptyList())
    val allDirectives by store.all.collectAsStateWithLifecycle()
    var selectedAgent by remember { mutableStateOf<String?>(null) }

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
            item(key = "directives_assistant_picker") {
                Row(
                    modifier = Modifier.padding(top = MusePaddings.contentGap),
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
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
        directiveSection(
            key = "directives_forbidden",
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
            key = "directives_desired",
            copy = DirectiveSectionCopy(
                titleRes = R.string.agent_directives_desired_section,
                addRes = R.string.agent_directives_add_desired,
                emptyRes = R.string.agent_directives_empty_desired,
            ),
            rows = desired,
            onAdd = { drafting = DirectivePolarity.SHOULD },
            onToggle = onToggle,
            onMove = onMove,
            onDelete = onDelete,
        )
        if (omittedCount > 0) {
            item(key = "directives_omitted") {
                DirectiveFootnote(omittedCount = omittedCount)
            }
        }
        item(key = "directives_voice_entry") {
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

private data class DirectiveSectionCopy(
    @StringRes val titleRes: Int,
    @StringRes val addRes: Int,
    @StringRes val emptyRes: Int,
)

private fun LazyListScope.directiveSection(
    key: String,
    copy: DirectiveSectionCopy,
    rows: List<AgentDirective>,
    onAdd: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onMove: (String, Int) -> Unit,
    onDelete: (String) -> Unit,
) {
    item(key = key) {
        val addTitle = stringResource(copy.addRes)
        val emptyTitle = stringResource(copy.emptyRes)
        val switchDesc = stringResource(R.string.agent_directives_switch)
        CardGroup(title = { Text(stringResource(copy.titleRes)) }) {
            if (rows.isEmpty()) {
                item(
                    key = "${key}_empty",
                    headlineContent = {
                        Text(
                            text = emptyTitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            } else {
                rows.forEachIndexed { index, directive ->
                    item(
                        key = directive.id,
                        onClick = { onToggle(directive.id, !directive.enabled) },
                        headlineContent = {
                            Text(
                                text = directive.text,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            var menuExpanded by remember { mutableStateOf(false) }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                            ) {
                                MuseSwitch(
                                    checked = directive.enabled,
                                    onCheckedChange = { enabled -> onToggle(directive.id, enabled) },
                                    contentDescription = switchDesc,
                                )
                                Box {
                                    MuseTactileButton(
                                        icon = MuseIcons.moreHorizontal,
                                        onClick = { menuExpanded = true },
                                        contentDescription = stringResource(R.string.settings_mcp_more),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        compact = true,
                                    )
                                    MuseAnchoredMenu(
                                        expanded = menuExpanded,
                                        onDismissRequest = { menuExpanded = false },
                                        alignEnd = true,
                                    ) {
                                        if (index > 0) {
                                            MuseListItem(
                                                onClick = {
                                                    menuExpanded = false
                                                    onMove(directive.id, -1)
                                                },
                                                leadingContent = {
                                                    Icon(
                                                        imageVector = MuseIcons.arrowUp,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                                                    )
                                                },
                                                headlineContent = {
                                                    Text(stringResource(R.string.agent_directives_move_up))
                                                },
                                            )
                                        }
                                        if (index < rows.lastIndex) {
                                            MuseListItem(
                                                onClick = {
                                                    menuExpanded = false
                                                    onMove(directive.id, 1)
                                                },
                                                leadingContent = {
                                                    Icon(
                                                        imageVector = MuseIcons.arrowDown,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                                                    )
                                                },
                                                headlineContent = {
                                                    Text(stringResource(R.string.agent_directives_move_down))
                                                },
                                            )
                                        }
                                        MuseListItem(
                                            onClick = {
                                                menuExpanded = false
                                                onDelete(directive.id)
                                            },
                                            leadingContent = {
                                                Icon(
                                                    imageVector = MuseIcons.trash,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                                                )
                                            },
                                            headlineContent = {
                                                Text(
                                                    text = stringResource(R.string.common_delete),
                                                    color = MaterialTheme.colorScheme.error,
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
            }
            actionItem(
                key = "${key}_add",
                title = addTitle,
                onClick = onAdd,
            )
        }
    }
}

@Composable
private fun DirectiveFootnote(omittedCount: Int) {
    if (omittedCount <= 0) return
    Text(
        text = stringResource(R.string.agent_directives_omitted, "$omittedCount"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = MuseSpacing.xs),
    )
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
                    placeholder = {
                        Text(
                            stringResource(
                                if (polarity == DirectivePolarity.MUST_NOT) {
                                    R.string.agent_directives_editor_placeholder_forbidden
                                } else {
                                    R.string.agent_directives_editor_placeholder
                                },
                            ),
                        )
                    },
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

@Composable
private fun VoiceTimbreEntry(onOpenTimbre: () -> Unit) {
    CardGroup(title = { Text(stringResource(R.string.agent_directives_voice_section)) }) {
        navigationItem(
            key = "voice_timbre",
            title = stringResource(R.string.voice_timbre_entry),
            leadingContent = {
                Icon(
                    imageVector = MuseIcons.microphone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
            },
            onClick = onOpenTimbre,
        )
    }
}
