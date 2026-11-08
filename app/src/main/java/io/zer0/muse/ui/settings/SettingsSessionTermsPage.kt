package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
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
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * v2.7.x(E2): 助手术语页。
 *
 * 这页存在的唯一理由是**把决定权交回用户**:候选是机器从对话里抽的,可能对也可能错;
 * 只有用户确认过的词才配被当成既定说法注入。
 *
 * 为什么按助手列而不是按"当前会话"列:设置页里没有当前会话这个概念,
 * 硬套一个会话 id 只会让页面列不出东西(第一版就是这么空的)。
 * 每条术语本来就带着 `assistant_id`,按助手列能同时看到会话级与助手级,
 * 也和注入侧的取数口径一致(同一助手的会话级 + 助手级)。
 *
 * "提升为助手级"必须是显式动作:系统绝不因为"在三个会话里都出现过"就自己搬上去 ——
 * 那些词往往只在原语境里成立。
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

    // 助手 id 在 LaunchedEffect 里取(不能在这里 runBlocking:那会把一次磁盘读放到首帧上)。
    // 取一次留着用 —— 每次动作后重读会让"确认"与"提升"落到与列表不同的助手上。
    var assistantId by remember { mutableStateOf("default") }

    LaunchedEffect(Unit) {
        assistantId = runCatching { settings.defaultAssistantIdFlow.first() }.getOrDefault("default")
        terms = runCatching { store.listByAssistantScope(assistantId) }.getOrDefault(emptyList())
        loading = false
    }

    SettingsSubPageScaffold(
        title = stringResource(R.string.settings_screen_session_terms),
        onBack = onBack,
    ) {
        if (loading) {
            item {
                Text(
                    text = stringResource(R.string.settings_session_terms_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
                )
            }
        } else if (terms.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.settings_session_terms_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
                )
            }
        } else {
            val awaiting = terms.count { SessionTerms.awaitingConfirmation(it.occurrences, it.confirmed) }
            item {
                CardGroup(title = { Text(stringResource(R.string.settings_session_terms_awaiting, awaiting)) }) {
                    item {
                        Text(
                            text = stringResource(R.string.settings_session_terms_awaiting_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = MusePaddings.screen, vertical = MusePaddings.tightGap),
                        )
                    }
                }
            }
            items(items = terms.sortedWith(termOrder()), key = { it.id }) { term ->
                TermCard(term = term, onManage = { selected = term })
            }
        }
    }

    selected?.let { term ->
        MuseDialog(
            onDismissRequest = { selected = null },
            title = stringResource(R.string.settings_session_terms_action_title, term.term),
            content = {
                Text(
                    text = stringResource(
                        R.string.settings_session_terms_action_body,
                        term.occurrences,
                        term.meaning?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.settings_session_terms_no_meaning),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmText = stringResource(R.string.settings_session_terms_confirm),
            onConfirm = {
                selected = null
                scope.launch {
                    runCatching { store.confirm(term.id) }
                    terms = runCatching { store.listByAssistantScope(assistantId) }.getOrDefault(terms)
                    MuseToast.show(context.getString(R.string.settings_session_terms_toast_confirmed, term.term))
                }
            },
            dismissText = stringResource(R.string.settings_session_terms_promote),
            onDismiss = {
                selected = null
                scope.launch {
                    runCatching { store.promoteToAssistant(term.id) }
                    terms = runCatching { store.listByAssistantScope(assistantId) }.getOrDefault(terms)
                    MuseToast.show(context.getString(R.string.settings_session_terms_toast_promoted, term.term))
                }
            },
        )
    }
}

/**
 * 一条术语的卡片:词、状态与次数、解释、处理入口。
 *
 * 单独成 composable 不只是因为页面本身超长 —— 一屏里只有这一个地方需要
 * "把状态、次数、解释三件事一起看清",拆出来后调排版不会牵动页面状态机。
 */
@Composable
private fun TermCard(term: SessionTermEntity, onManage: () -> Unit) {
    CardGroup {
        item {
            Column(
                modifier = Modifier.padding(horizontal = MusePaddings.screen, vertical = MusePaddings.tightGap),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                Text(
                    text = term.term,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(statusLabel(term)) + " · " +
                        stringResource(R.string.settings_session_terms_occurrences, term.occurrences),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = term.meaning?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.settings_session_terms_no_meaning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                MuseCapsuleButton(
                    text = stringResource(R.string.settings_session_terms_manage),
                    onClick = onManage,
                    variant = IosCapsuleButtonVariant.Secondary,
                )
            }
        }
    }
}

/** 排序:待确认优先,其次助手级,再按次数与最近出现 —— 用户不该翻列表找该处理的那条。 */
private fun termOrder(): Comparator<SessionTermEntity> = compareByDescending<SessionTermEntity> { entity ->
    SessionTerms.awaitingConfirmation(entity.occurrences, entity.confirmed)
}.thenByDescending { it.scope == SessionTermEntity.SCOPE_ASSISTANT }
    .thenByDescending { it.occurrences }
    .thenByDescending { it.lastSeenAt }

/** 状态标签走资源,不写死英文:这页会被截图求助。 */
private fun statusLabel(entity: SessionTermEntity): Int = when {
    entity.scope == SessionTermEntity.SCOPE_ASSISTANT -> R.string.settings_session_terms_scope_assistant
    entity.confirmed -> R.string.settings_session_terms_scope_confirmed
    else -> R.string.settings_session_terms_scope_candidate
}
