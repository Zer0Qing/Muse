// 知识库(KB)与文件夹的生命周期组件 — 供合并后的 KnowledgeScreen 复用。
// 从原 KnowledgeBaseManagePage 抽出(KbRow / KbEditDialog),并新增文件夹操作。
@file:Suppress("TooManyFunctions", "FunctionNaming", "LongParameterList")

package io.zer0.muse.ui.knowledge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.knowledge.KnowledgeBaseEntity
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.settings.SettingField
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.bodyLargeStrong

/**
 * v2.4.6: KB 行操作集合 — 聚合成一个参数,避免 LongParameterList。
 */
data class KbRowActions(
    val onOpen: () -> Unit,
    val onEdit: () -> Unit,
    val onDelete: () -> Unit,
    val onReindex: () -> Unit,
    val onAddDocument: () -> Unit,
)

/**
 * KB 列表项 — 名称 + 描述 + 文档数;右侧:进库 / 添加文档 / 重索引 / 编辑 / 删除。
 *
 * 与 v1.133 管理页的 KbRow 相比:点击整行 = 进入该知识库浏览文件夹与文档,
 * 右侧动作按钮保持不变。
 */
@Composable
fun KbRow(kb: KnowledgeBaseEntity, actions: KbRowActions) {
    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = actions.onOpen)
                .padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                MuseIcons.folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.size(MusePaddings.contentGap))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    kb.name,
                    style = MaterialTheme.typography.bodyLargeStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (kb.description.isNotBlank()) {
                    Text(
                        kb.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    stringResource(R.string.kb_manage_doc_count, kb.docCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            KbActionIcon(
                icon = MuseIcons.refresh,
                contentDescription = stringResource(R.string.kb_reindex_all),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = actions.onReindex,
            )
            KbActionIcon(
                icon = MuseIcons.plus,
                contentDescription = stringResource(R.string.kb_manage_add_doc),
                tint = MaterialTheme.colorScheme.primary,
                onClick = actions.onAddDocument,
            )
            KbActionIcon(
                icon = MuseIcons.edit,
                contentDescription = stringResource(R.string.kb_manage_edit),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = actions.onEdit,
            )
            if (kb.id != "default") {
                KbActionIcon(
                    icon = MuseIcons.trash,
                    contentDescription = stringResource(R.string.kb_manage_delete),
                    tint = MaterialTheme.colorScheme.error,
                    onClick = actions.onDelete,
                )
            }
        }
    }
}

/** KB/文件夹 行内图标按钮(40dp 触摸目标 + 20dp 图标)。 */
@Composable
fun KbActionIcon(icon: ImageVector, contentDescription: String, tint: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/**
 * v2.4.6: 编辑弹窗形态选项 — 聚合展示相关字段,避免 LongParameterList。
 */
data class KbEditOptions(
    val initialDesc: String = "",
    val showDescription: Boolean = true,
    val nameLabel: String? = null,
)

/**
 * 名称 + 描述 两字段编辑弹窗(KB 新建/重命名、文件夹新建/重命名通用)。
 */
@Composable
fun KbEditDialog(
    title: String,
    initialName: String,
    onConfirm: (name: String, desc: String) -> Unit,
    onDismiss: () -> Unit,
    options: KbEditOptions = KbEditOptions(),
) {
    var name by remember { mutableStateOf(initialName) }
    var desc by remember { mutableStateOf(options.initialDesc) }
    MuseDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
                SettingField(
                    label = options.nameLabel ?: stringResource(R.string.kb_manage_name_label),
                    value = name,
                    onValueChange = { name = it },
                )
                if (options.showDescription) {
                    SettingField(
                        label = stringResource(R.string.kb_manage_desc_label),
                        value = desc,
                        onValueChange = { desc = it },
                    )
                }
            }
        },
        confirmText = stringResource(R.string.action_save),
        onConfirm = { onConfirm(name.trim(), desc.trim()) },
        dismissText = stringResource(R.string.common_cancel),
        onDismiss = onDismiss,
    )
}
