package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.terms.SessionTermEntity
import io.zer0.muse.data.terms.SessionTermStore
import io.zer0.muse.data.terms.SessionTerms
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseAnchoredMenu
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.MuseListItem
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseSpacing
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * 助手术语页：列出按助手汇总的候选/已确认术语，用户显式确认或提升为助手级。
 */
@Composable
fun SettingsSessionTermsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store: SessionTermStore = koinInject()
    val settings: SettingsRepository = koinInject()
    var terms by remember { mutableStateOf<List<SessionTermEntity>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<SessionTermEntity?>(null) }
    var assistantId by remember { mutableStateOf("default") }

    LaunchedEffect(Unit) {
        assistantId = runCatching { settings.defaultAssistantIdFlow.first() }.getOrDefault("default")
        terms = runCatching { store.listByAssistantScope(assistantId) }.getOrDefault(emptyList())
        loading = false
    }

    val confirmTerm: (SessionTermEntity) -> Unit = { term ->
        scope.launch {
            runCatching { store.confirm(term.id) }
            terms = runCatching { store.listByAssistantScope(assistantId) }.getOrDefault(terms)
            MuseToast.show(context.getString(R.string.settings_session_terms_toast_confirmed, term.term))
        }
    }

    val promoteTerm: (SessionTermEntity) -> Unit = { term ->
        scope.launch {
            runCatching { store.promoteToAssistant(term.id) }
            terms = runCatching { store.listByAssistantScope(assistantId) }.getOrDefault(terms)
            MuseToast.show(context.getString(R.string.settings_session_terms_toast_promoted, term.term))
        }
    }

    SettingsSubPageScaffold(
        title = stringResource(R.string.settings_screen_session_terms),
        onBack = onBack,
    ) {
        if (loading) {
            item(key = "terms_loading") {
                Text(
                    text = stringResource(R.string.settings_session_terms_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = MuseSpacing.xs, vertical = MusePaddings.contentGap),
                )
            }
        } else if (terms.isEmpty()) {
            item(key = "terms_empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MuseSpacing.xxxl),
                    contentAlignment = Alignment.Center,
                ) {
                    MuseEmptyState(
                        icon = MuseIcons.book,
                        title = stringResource(R.string.settings_session_terms_empty),
                    )
                }
            }
        } else {
            val awaiting = terms.count { SessionTerms.awaitingConfirmation(it.occurrences, it.confirmed) }
            val ordered = terms.sortedWith(termOrder())
            item(key = "terms_hint") {
                Text(
                    text = stringResource(R.string.settings_session_terms_awaiting_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = MuseSpacing.xs),
                )
            }
            item(key = "terms_list") {
                CardGroup(title = { Text(stringResource(R.string.settings_session_terms_awaiting, awaiting)) }) {
                    ordered.forEach { term ->
                        val statusText = stringResource(statusLabel(term)) + " · " +
                            stringResource(R.string.settings_session_terms_occurrences, term.occurrences)
                        val meaningText = term.meaning?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.settings_session_terms_no_meaning)
                        item(
                            key = term.id,
                            onClick = { selected = term },
                            headlineContent = {
                                Text(
                                    text = term.term,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(MuseSpacing.xxs)) {
                                    Text(
                                        text = statusText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = meaningText,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            },
                            trailingContent = {
                                var menuExpanded by remember { mutableStateOf(false) }
                                Box {
                                    MuseTactileButton(
                                        icon = MuseIcons.moreHorizontal,
                                        onClick = { menuExpanded = true },
                                        contentDescription = stringResource(R.string.settings_session_terms_manage),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        compact = true,
                                    )
                                    MuseAnchoredMenu(
                                        expanded = menuExpanded,
                                        onDismissRequest = { menuExpanded = false },
                                        alignEnd = true,
                                    ) {
                                        MuseListItem(
                                            onClick = {
                                                menuExpanded = false
                                                confirmTerm(term)
                                            },
                                            leadingContent = {
                                                Icon(
                                                    imageVector = MuseIcons.check,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                                                )
                                            },
                                            headlineContent = {
                                                Text(stringResource(R.string.settings_session_terms_confirm))
                                            },
                                        )
                                        MuseListItem(
                                            onClick = {
                                                menuExpanded = false
                                                promoteTerm(term)
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
                                                Text(stringResource(R.string.settings_session_terms_promote))
                                            },
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    selected?.let { term ->
        MuseDialog(
            onDismissRequest = { selected = null },
            title = stringResource(R.string.settings_session_terms_action_title, term.term),
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(MuseSpacing.md)) {
                    Text(
                        text = stringResource(
                            R.string.settings_session_terms_action_body,
                            term.occurrences,
                            term.meaning?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.settings_session_terms_no_meaning),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    MuseCapsuleButton(
                        text = stringResource(R.string.settings_session_terms_promote),
                        onClick = {
                            selected = null
                            promoteTerm(term)
                        },
                        variant = IosCapsuleButtonVariant.Secondary,
                    )
                }
            },
            confirmText = stringResource(R.string.settings_session_terms_confirm),
            onConfirm = {
                selected = null
                confirmTerm(term)
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { selected = null },
        )
    }
}

private fun termOrder(): Comparator<SessionTermEntity> = compareByDescending<SessionTermEntity> { entity ->
    SessionTerms.awaitingConfirmation(entity.occurrences, entity.confirmed)
}.thenByDescending { it.scope == SessionTermEntity.SCOPE_ASSISTANT }
    .thenByDescending { it.occurrences }
    .thenByDescending { it.lastSeenAt }

private fun statusLabel(entity: SessionTermEntity): Int = when {
    entity.scope == SessionTermEntity.SCOPE_ASSISTANT -> R.string.settings_session_terms_scope_assistant
    entity.confirmed -> R.string.settings_session_terms_scope_confirmed
    // 「待确认」这个词只能留给真正达到确认阈值、会被计入分组标题的条目。
    // 未达阈值（出现次数还不够）以前也写「待确认」，于是标题显示 0 条、列表里却有 6 条待确认。
    SessionTerms.awaitingConfirmation(entity.occurrences, entity.confirmed) ->
        R.string.settings_session_terms_scope_candidate
    else -> R.string.settings_session_terms_scope_pending
}
