package io.zer0.muse.knowledge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.zer0.muse.R
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.MuseListItem
import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.ui.theme.MusePaddings

/**
 * R8 处理中心页面 — 计划 R8「首页只显示目录与任务摘要；处理页列出逐项状态、
 * 部分成功和修复动作，取消有终态」。
 *
 * 无状态 Composable:只渲染 [KnowledgeJobsUiState],用户意图通过回调上抛,
 * 不直接读 DAO、不解析文件(页面职责边界)。
 */
@Composable
fun KnowledgeJobsScreen(
    state: KnowledgeJobsUiState?,
    onBack: () -> Unit,
    onCancel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    MusePageScaffold(
        modifier = modifier,
        topBar = { MuseTopBar(title = stringResource(R.string.kb_jobs_title), onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = MusePaddings.screen),
        ) {
            val summary = state?.summary ?: stringResource(R.string.kb_jobs_empty)
            CardGroup {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap)) {
                        Text(text = summary, style = MaterialTheme.typography.titleMedium)
                        if (state != null && state.total > 0) {
                            Text(
                                text = "${state.succeeded}/${state.total}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (state == null || state.items.isEmpty()) {
                Text(
                    text = stringResource(R.string.kb_jobs_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = MusePaddings.sectionGap),
                )
                return@Column
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MusePaddings.sectionGap),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
            ) {
                items(state.items, key = { it.itemId }) { item ->
                    MuseListItem(
                        headlineContent = { Text(item.docId) },
                        supportingContent = {
                            Text(
                                text = "${item.stateLabel} · ${item.stageLabel} · ${item.availabilityLabel}" +
                                    if (item.hasError && item.errorText.isNotBlank()) " · ${item.errorText}" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (item.hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = if (item.canCancel) {
                            { TextButton(text = stringResource(R.string.kb_jobs_cancel), onClick = { onCancel(item.itemId) }) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TextButton(text: String, onClick: () -> Unit) {
    io.zer0.muse.ui.common.form.MuseCapsuleButton(
        text = text,
        onClick = onClick,
        fillWidth = false,
    )
}
