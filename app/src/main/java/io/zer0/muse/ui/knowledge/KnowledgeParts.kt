package io.zer0.muse.ui.knowledge

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.common.FileSizeFormatter
import io.zer0.muse.R
import io.zer0.muse.data.knowledge.KnowledgeDocDao
import io.zer0.muse.data.knowledge.KnowledgeDocEntity
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.bodyLargeStrong
import io.zer0.muse.ui.theme.bodyMediumStrong
import io.zer0.muse.ui.theme.statusColors
import java.util.Date
import java.util.Locale

/**
 * 知识库页的私有组件：文件夹卡、文档卡、详情对话框、PDF 结构化解析。
 *
 * 从 `KnowledgeScreen.kt` 切出——主页面函数超过 1,800 行，这些组件与页面骨架
 * 无耦合，独立成文件后可以单独预览与修改。
 */

@Composable
internal fun KnowledgeFolderCard(
    title: String,
    detail: String,
    onClick: () -> Unit,
    // v2.4.6: 文件夹重命名/删除(仅显式文件夹树里的节点挂动作;KB 卡片不传)
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shadowElevation = MuseElevation.card,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Icon(
                imageVector = MuseIcons.folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLargeStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onRename != null) {
                KbActionIcon(
                    icon = MuseIcons.edit,
                    contentDescription = stringResource(R.string.knowledge_folder_rename),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onRename,
                )
            }
            if (onDelete != null) {
                KbActionIcon(
                    icon = MuseIcons.trash,
                    contentDescription = stringResource(R.string.knowledge_folder_delete),
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onDelete,
                )
            }
            Icon(
                imageVector = MuseIcons.chevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
            )
        }
    }
}

/** v2.4.6: 「新建文件夹」行 — 虚线感入口(用 surfaceVariant 底色区分)。 */
@Composable
internal fun NewFolderRow(onClick: () -> Unit) {
    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Icon(
                imageVector = MuseIcons.plus,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = stringResource(R.string.knowledge_folder_new),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

internal data class FileTypeStyle(
    val icon: ImageVector,
    val iconColor: Color,
    val containerColor: Color,
)

@Composable
internal fun fileTypeStyle(fileType: String): FileTypeStyle {
    // v1.0.52: 文档类型色接入语义状态色,深色模式自动切亮档保证图标可读
    val statusColors = MaterialTheme.statusColors
    return when (fileType.lowercase(Locale.getDefault())) {
        "pdf" -> FileTypeStyle(
            icon = MuseIcons.fileText,
            iconColor = statusColors.error,
            containerColor = statusColors.error.copy(alpha = 0.10f),
        )
        "md", "markdown" -> FileTypeStyle(
            icon = MuseIcons.fileText,
            iconColor = statusColors.info,
            containerColor = statusColors.info.copy(alpha = 0.10f),
        )
        "txt" -> FileTypeStyle(
            icon = MuseIcons.bookOpen,
            iconColor = statusColors.success,
            containerColor = statusColors.success.copy(alpha = 0.10f),
        )
        else -> FileTypeStyle(
            icon = MuseIcons.fileText,
            iconColor = statusColors.neutral,
            containerColor = statusColors.neutral.copy(alpha = 0.10f),
        )
    }
}

@Composable
internal fun DocCard(doc: KnowledgeDocEntity, highlight: String?, onClick: () -> Unit, onDelete: () -> Unit) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val style = fileTypeStyle(doc.fileType)
    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = MuseElevation.card,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(color = style.containerColor, shape = MuseShapes.small),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = style.icon,
                    contentDescription = null,
                    tint = style.iconColor,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    doc.title,
                    style = MaterialTheme.typography.bodyMediumStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // P0-3（审计报告）: 索引替换中途失败会把旧块删掉、新块不完整，
                // 文档仍在列表里但检索永远召不到。此前零提示，用户只会觉得“这个库不灵了”。
                // 清理时已在 embedding_model 写入哨兵值，这里识别并给出可操作提示。
                if (doc.embeddingModel == io.zer0.muse.data.knowledge.KnowledgeDocDao.INDEX_BROKEN_SENTINEL) {
                    Text(
                        text = stringResource(R.string.knowledge_doc_index_broken),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                    )
                }
                // v1.71: 用 remember 缓存 SimpleDateFormat,避免列表项重组都新建对象
                val dateFmt = remember { MuseDateFormats.dateTimeShort() }
                Text(
                    stringResource(
                        R.string.knowledge_doc_meta_with_date,
                        doc.fileType,
                        doc.content.length,
                        dateFmt.format(Date(doc.createdAt)),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MuseTactileButton(
                icon = MuseIcons.trash,
                onClick = { showDeleteConfirm = true },
                contentDescription = stringResource(R.string.common_delete),
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                size = 48.dp,
                iconSize = 18.dp,
            )
        }
    }
    if (showDeleteConfirm) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.knowledge_delete_doc),
            itemName = doc.title,
            onConfirm = {
                showDeleteConfirm = false
                onDelete()
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

@Composable
internal fun DocDetailDialog(
    doc: KnowledgeDocEntity,
    onDismiss: () -> Unit,
    onReindex: () -> Unit,
    // v1.0.53: 封面能力(打开封面库 / 生成封面 / 生成中状态)
    onOpenCoverManager: () -> Unit = {},
    onGenerateCover: () -> Unit = {},
    generatingCover: Boolean = false,
    // F-32: 文档重命名 / 正文编辑入口(仅文本型文档提供正文编辑)
    onRename: () -> Unit = {},
    onEditContent: () -> Unit = {},
    onMoveToFolder: () -> Unit = {},
) {
    // v1.0.53: 封面区解析需要 context(filesDir/covers)
    val context = LocalContext.current
    // v1.0.4 P2-2:从 doc.content 中分离 PDF 元数据块 / 目录块 / 正文,
    // 让用户在详情弹窗里看到结构化的文档信息卡片(而不是混在等宽正文中)。
    // DocumentParser 输出格式:[文档信息]\n字段: 值\n...\n\n[目录]\n• 条目\n...\n\n<正文>
    val parsed by remember(doc.id, doc.content) {
        mutableStateOf(parsePdfStructuredContent(doc.content, doc.fileType))
    }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = doc.title,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                // v1.0.53: 封面区(frontmatter cover 字段 → filesDir/covers/)
                val coverPath = io.zer0.muse.common.markdown.FrontmatterParser.parse(doc.content)?.cover
                val coverFile = coverPath?.let { path ->
                    if (path.startsWith("covers/")) {
                        java.io.File(java.io.File(context.filesDir, "covers"), path.removePrefix("covers/"))
                    } else {
                        null
                    }
                }
                if (coverFile != null && coverFile.exists()) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 5f)
                            .clip(MuseShapes.large),
                    ) {
                        coil.compose.AsyncImage(
                            model = coverFile,
                            contentDescription = doc.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }

                // v1.0.53: 封面操作行(封面库 + 生成封面)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MuseCapsuleButton(
                        text = stringResource(R.string.cover_import),
                        onClick = onOpenCoverManager,
                        variant = IosCapsuleButtonVariant.Secondary,
                        modifier = Modifier.weight(1f),
                    )
                    MuseCapsuleButton(
                        text = if (generatingCover) stringResource(R.string.cover_generating) else stringResource(R.string.cover_generate),
                        onClick = onGenerateCover,
                        enabled = !generatingCover,
                        loading = generatingCover,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(10.dp))

                // F-32: 操作行(重命名 / 编辑正文)— 文本型文档提供正文编辑,其余仅重命名
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MuseCapsuleButton(
                        text = stringResource(R.string.knowledge_rename_doc),
                        onClick = onRename,
                        variant = IosCapsuleButtonVariant.Secondary,
                        modifier = Modifier.weight(1f),
                    )
                    if (isTextDoc(doc.fileType)) {
                        MuseCapsuleButton(
                            text = stringResource(R.string.knowledge_edit_content),
                            onClick = onEditContent,
                            variant = IosCapsuleButtonVariant.Secondary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                MuseCapsuleButton(
                    text = stringResource(R.string.knowledge_move_to_folder),
                    onClick = onMoveToFolder,
                    variant = IosCapsuleButtonVariant.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))

                Text(
                    // v1.67-A: 显示分块数和 embedding 模型,让用户判断是否需要重新索引
                    text = buildString {
                        append(stringResource(R.string.knowledge_doc_chars, doc.fileType, doc.content.length))
                        append(
                            if (doc.chunkCount > 0) {
                                stringResource(
                                    R.string.knowledge_doc_chunks,
                                    doc.chunkCount,
                                )
                            } else {
                                stringResource(R.string.knowledge_doc_not_indexed)
                            },
                        )
                        if (doc.embeddingModel.isNotBlank()) append(" · ${doc.embeddingModel}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(8.dp))

                // v1.0.4 P2-2:PDF 元数据卡片(仅在有元数据时展示)
                if (parsed.metadata.isNotEmpty()) {
                    PdfMetadataCard(metadata = parsed.metadata)
                    Spacer(Modifier.height(8.dp))
                }
                // v1.0.4 P2-2:PDF 目录卡片(可折叠,仅在有目录时展示)
                if (parsed.outline.isNotEmpty()) {
                    PdfOutlineCard(outline = parsed.outline)
                    Spacer(Modifier.height(8.dp))
                }

                // 正文(无元数据/目录时退化为原始 content 展示,保持兼容)
                val bodyText = parsed.body
                Text(
                    bodyText,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = MuseMonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        confirmText = stringResource(R.string.common_close),
        onConfirm = onDismiss,
        // v1.67-A: 重新索引入口(更换 embedding 模型后或首次索引失败时可用)
        dismissText = stringResource(R.string.knowledge_reindex),
        onDismiss = {
            onReindex()
            onDismiss()
        },
    )
}

/**
 * v1.0.4 P2-2:从 DocumentParser 输出的拼接文本中分离 PDF 元数据 / 目录 / 正文。
 *
 * DocumentParser.readPdf 的输出格式(见 doc/DocumentParser.kt:242-258):
 * ```
 * [文档信息]
 * 标题: xxx
 * 作者: xxx
 *
 * [目录]
 * • 第一章
 *   • 1.1 节
 *
 * <正文>
 * ```
 * 本函数把三块拆开,供 UI 分别结构化渲染。非 PDF 文档(fileType != "pdf")
 * 直接把整个 content 当作 body 返回,保持原有展示行为。
 */
internal fun parsePdfStructuredContent(content: String, fileType: String): ParsedPdfContent {
    if (fileType.lowercase(Locale.getDefault()) != "pdf" || content.isBlank()) {
        return ParsedPdfContent(body = content)
    }
    // 仅在 content 以 [文档信息] 或 [目录] 开头时才解析(避免误吞普通正文)
    if (!content.startsWith("[文档信息]") && !content.startsWith("[目录]")) {
        return ParsedPdfContent(body = content)
    }

    val metadata = LinkedHashMap<String, String>()
    val outline = mutableListOf<String>()
    val bodyBuilder = StringBuilder()

    var section = "meta" // meta / outline / body
    content.lineSequence().forEach { line ->
        when {
            line == "[文档信息]" -> section = "meta"
            line == "[目录]" -> section = "outline"
            // 空行作为 section 切换的隐式分隔(DocumentParser 在块之间会输出空行)
            line.isBlank() && section != "body" -> {
                // 不立即切换:等遇到非 [xxx] 且非空行时再决定
            }
            section == "meta" && line.contains(": ") -> {
                val idx = line.indexOf(": ")
                if (idx > 0) {
                    metadata[line.substring(0, idx).trim()] = line.substring(idx + 2).trim()
                }
            }
            section == "outline" && line.startsWith("•") -> {
                outline.add(line.trimStart(' ', '•').trim())
            }
            section == "outline" && line.startsWith("  •") -> {
                outline.add("  " + line.trimStart(' ', '•').trim())
            }
            else -> {
                // 遇到非元数据/非目录条目,切换到 body 模式
                if (section != "body") section = "body"
                bodyBuilder.appendLine(line)
            }
        }
    }

    return ParsedPdfContent(
        metadata = metadata,
        outline = outline,
        body = bodyBuilder.toString().trim(),
    )
}

/** [parsePdfStructuredContent] 的解析结果。 */
internal data class ParsedPdfContent(
    val metadata: Map<String, String> = emptyMap(),
    val outline: List<String> = emptyList(),
    val body: String = "",
)

/**
 * PDF 元数据卡片 — 字段名 + 值成对展示,limit 8 行避免过长。
 */
@Composable
internal fun PdfMetadataCard(metadata: Map<String, String>) {
    Surface(
        shape = MuseShapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = MuseIcons.info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = stringResource(R.string.knowledge_doc_info),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(6.dp))
            metadata.forEach { (k, v) ->
                Text(
                    text = "$k: $v",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * PDF 目录卡片 — 默认折叠(避免占用过多弹窗空间),点击标题切换展开/折叠。
 */
@Composable
internal fun PdfOutlineCard(outline: List<String>) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = MuseShapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 标题行可点击,切换展开/折叠
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = MuseIcons.bookOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = stringResource(R.string.knowledge_doc_outline, outline.size),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (expanded) {
                        stringResource(
                            R.string.action_collapse,
                        )
                    } else {
                        stringResource(R.string.common_expand)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                outline.forEach { item ->
                    val indent = if (item.startsWith("  ")) 16.dp else 0.dp
                    Text(
                        text = "• " + item.trimStart(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = indent),
                    )
                }
            }
        }
    }
}

/**
 * v1.66: 知识库排序模式。
 * DAO 仅支持 updated_at DESC,其余维度在 UI 层用 comparator 排序。
 */
enum class KnowledgeSortMode(
    val labelRes: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val comparator: Comparator<KnowledgeDocEntity>,
) {
    UPDATED(R.string.knowledge_sort_updated, MuseIcons.clock, compareByDescending { it.updatedAt }),
    CREATED(R.string.knowledge_sort_created, MuseIcons.calendar, compareByDescending { it.createdAt }),
    TITLE(R.string.knowledge_sort_title, MuseIcons.sort, compareBy { it.title.lowercase(Locale.getDefault()) }),
    SIZE(R.string.knowledge_sort_size, MuseIcons.database, compareByDescending { it.content.length }),
}

/**
 * v1.0.47 P7-3: 格式化文件大小为人类可读字符串。
 */
internal fun formatFileSize(bytes: Long): String = FileSizeFormatter.format(bytes)

/**
 * F-32: 判断文档是否为可编辑正文的文本型(md/markdown/txt/csv/json)。
 * PDF/docx/epub/ocr 等由解析器生成的拼接文本不属于"文本型",不提供正文直改功能,
 * 避免误改解析产物导致结构化信息损坏。
 */
internal fun isTextDoc(fileType: String): Boolean = when (fileType.lowercase(Locale.getDefault())) {
    "md", "markdown", "txt", "csv", "json" -> true
    else -> false
}
