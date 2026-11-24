package io.zer0.muse.knowledge.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.zer0.muse.R
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.settings.SettingsSubPageScaffold

/**
 * 知识库高级策略只读展示页：摄取、编码与查询三组配置及其继承来源与修改影响。
 */
@Composable
fun KnowledgeSettingsScreen(
    state: KnowledgeSettingsUiState,
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") modifier: Modifier = Modifier,
) {
    SettingsSubPageScaffold(
        title = stringResource(R.string.kb_settings_title),
        onBack = onBack,
    ) {
        item(key = "kb_ingest") {
            SettingsGroupCard(title = stringResource(R.string.kb_settings_ingest), fields = state.ingestFields)
        }
        item(key = "kb_embedding") {
            SettingsGroupCard(title = stringResource(R.string.kb_settings_embedding), fields = state.embeddingFields)
        }
        item(key = "kb_retrieval") {
            SettingsGroupCard(title = stringResource(R.string.kb_settings_retrieval), fields = state.retrievalFields)
        }
    }
}

@Composable
private fun SettingsGroupCard(title: String, fields: List<KnowledgePolicyFieldUi>) {
    CardGroup(title = { Text(title) }) {
        fields.forEach { field ->
            item(
                key = field.label,
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
