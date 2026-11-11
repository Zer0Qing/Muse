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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.ui.theme.MusePaddings

/**
 * R8 文档详情页 — 计划 R8「文档页读取完整 revision，显示 coverage/来源/能力/活动版本；
 * 文本编辑保尾，二进制资料提供替换/重解析，不用预览编辑假装全文」。
 *
 * 展示的是完整 revision 正文(不是 preview);编辑提交带 expectedRevisionId,
 * 冲突由 ViewModel 报告,不静默覆盖。
 */
@Composable
fun KnowledgeDocumentScreen(
    state: KnowledgeDocumentUiState?,
    onBack: () -> Unit,
    onSave: (docId: String, expectedRevisionId: String, newContent: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf(false) }
    MusePageScaffold(
        modifier = modifier,
        topBar = { MuseTopBar(title = stringResource(R.string.kb_doc_title), onBack = onBack) },
    ) { padding ->
        if (state == null) {
            Text(
                text = stringResource(R.string.kb_jobs_empty),
                modifier = Modifier.padding(padding).padding(horizontal = MusePaddings.screen),
                style = MaterialTheme.typography.bodyMedium,
            )
            return@MusePageScaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MusePaddings.screen),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.sectionGap),
        ) {
            CardGroup {
                item(headlineContent = { Text(state.docId) }, supportingContent = {
                    Text(
                        text = "${stringResource(R.string.kb_doc_version, state.revisionNo)} · ${state.availability}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                })
                item(headlineContent = { Text(stringResource(R.string.kb_doc_source)) }, supportingContent = { Text(state.sourceLabel) })
            }

            MuseCapsuleButton(
                text = stringResource(R.string.kb_doc_edit),
                onClick = { editing = true },
                fillWidth = false,
            )

            Text(
                text = state.fullContent,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (editing) {
            var draft by remember(state.revisionId) { mutableStateOf(state.fullContent) }
            MuseDialog(
                onDismissRequest = { editing = false },
                title = stringResource(R.string.kb_doc_edit),
                content = {
                    MuseTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 8,
                    )
                },
                confirmText = stringResource(R.string.common_confirm),
                onConfirm = {
                    onSave(state.docId, state.revisionId, draft)
                    editing = false
                },
                onDismiss = { editing = false },
            )
        }
    }
}

