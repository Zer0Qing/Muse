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
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.surface.MuseListItem
import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.ui.theme.MusePaddings

/**
 * R8 检索验证页 — 计划 R8「检索验证页共用真实检索 API」。
 *
 * 本页只把用户查询交给统一检索用例(与真实助手同一范围/策略解析),
 * 不另写"看起来效果更好"的调试搜索;错误与零命中分开显示。
 */
@Composable
fun KnowledgeSearchScreen(
    state: KnowledgeSearchUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MusePageScaffold(
        modifier = modifier,
        topBar = { MuseTopBar(title = stringResource(R.string.kb_search_title), onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = MusePaddings.screen),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.sectionGap),
        ) {
            MuseTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.kb_search_hint)) },
                singleLine = true,
            )
            MuseCapsuleButton(
                text = stringResource(R.string.kb_search_run),
                onClick = onSearch,
                loading = state.running,
                fillWidth = false,
            )
            when {
                state.emptyReason != null -> Text(
                    text = state.emptyReason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.results.isEmpty() -> Text(
                    text = stringResource(R.string.kb_search_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    Text(
                        text = stringResource(R.string.kb_search_results, state.results.size),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap)) {
                        items(state.results, key = { it.chunkId }) { result ->
                            MuseListItem(
                                headlineContent = { Text(result.docId) },
                                supportingContent = {
                                    Text(
                                        text = "${result.locator} · ${result.snippet.take(160)}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
