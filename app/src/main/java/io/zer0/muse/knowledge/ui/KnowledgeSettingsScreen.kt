package io.zer0.muse.knowledge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.zer0.muse.R
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.ui.theme.MusePaddings

/**
 * R8 设置页 — 计划 R8「设置拆成摄取、编码与查询高级项；每字段标明继承来源和修改影响」。
 *
 * 只渲染 [KnowledgeSettingsUiState](由 `KnowledgeSettingsUiMapper` 从 `ResolvedPolicy` 生成),
 * 不直接读写配置;标签与影响面文案来自状态模型,页面不自行发明同义说明。
 */
@Composable
fun KnowledgeSettingsScreen(
    state: KnowledgeSettingsUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MusePageScaffold(
        modifier = modifier,
        topBar = { MuseTopBar(title = stringResource(R.string.kb_settings_title), onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MusePaddings.screen),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.sectionGap),
        ) {
            SettingsGroupCard(title = stringResource(R.string.kb_settings_ingest), fields = state.ingestFields)
            SettingsGroupCard(title = stringResource(R.string.kb_settings_embedding), fields = state.embeddingFields)
            SettingsGroupCard(title = stringResource(R.string.kb_settings_retrieval), fields = state.retrievalFields)
        }
    }
}

@Composable
private fun SettingsGroupCard(title: String, fields: List<KnowledgePolicyFieldUi>) {
    CardGroup(title = { Text(title, style = MaterialTheme.typography.titleSmall) }) {
        fields.forEach { field ->
            item(
                headlineContent = { Text(field.label) },
                supportingContent = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "${field.value} · ${field.sourceLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = field.impact,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }
    }
}
