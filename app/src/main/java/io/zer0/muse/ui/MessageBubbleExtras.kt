@file:Suppress(
    "FunctionNaming",
    "LongMethod",
    "CyclomaticComplexMethod",
    "UnusedPrivateMember",
    "UnusedPrivateProperty",
)

package io.zer0.muse.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.TablerIcons
import compose.icons.tablericons.Check
import compose.icons.tablericons.X
import io.zer0.ai.core.RagCitation
import io.zer0.muse.R
import io.zer0.muse.ui.common.media.AttachmentChip
import io.zer0.muse.ui.theme.MoodSkinColors
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v0.47: 工具调用卡片 — 折叠式展示 tool_call 的入参/出参。
 *
 * 显示工具名 + 状态图标(成功/失败),点击展开看入参 JSON 和出参文本。
 * 用于替代原来"调用工具 xxx: 参数: ... 结果: ..."的纯文本 ASSISTANT 消息。
 *
 * 结果文本中若包含沙盒内文件路径,会渲染为可点击的附件芯片(见 [AttachmentChip])。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ToolCallCard(
    toolName: String,
    arguments: String,
    result: String,
    isSuccess: Boolean,
    modifier: Modifier = Modifier,
) {
    // v1.79 (L-B25): expanded 改用 rememberSaveable,旋屏/后台后保持展开状态
    var expanded by rememberSaveable { mutableStateOf(false) }
    // v1.79 (M-B4): extractFilePaths 含 file.exists() 磁盘 IO,移到 LaunchedEffect + IO 线程
    var attachments by remember(result) { mutableStateOf(emptyList<Pair<String, Long?>>()) }
    LaunchedEffect(result) {
        attachments = withContext(Dispatchers.IO) { extractFilePaths(result) }
    }
    // v1.28: 有附件时默认展开,让用户直接看到可下载的文件芯片
    LaunchedEffect(attachments.isNotEmpty()) {
        if (attachments.isNotEmpty()) expanded = true
    }
    // v1.x: 重构为紧凑可折叠卡片,对齐 mood/reasoning 块的视觉与交互模式
    // (primary.copy(0.08f) 底色 + bubbleInner 内边距 + 整行可点击头部 + 14dp 图标)
    Surface(
        color = if (isSuccess) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
        shape = MuseShapes.medium,
        tonalElevation = MuseElevation.none,
        modifier = modifier.widthIn(max = 360.dp),
    ) {
        Column(modifier = Modifier.padding(MusePaddings.bubbleInner)) {
            // 头部:状态图标 + 工具名(+ 折叠摘要) + 展开箭头,整行可点击
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = MusePaddings.tinyGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (isSuccess) TablerIcons.Check else TablerIcons.X,
                    contentDescription = if (isSuccess) {
                        stringResource(R.string.chat_tool_success_cd)
                    } else {
                        stringResource(R.string.chat_tool_failed_cd)
                    },
                    tint = if (isSuccess) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(MuseIconSizes.iconTiny),
                )
                Spacer(Modifier.width(MusePaddings.tightGap))
                // 折叠时标题显示工具名 + 结果摘要(前 40 字符),展开时只显示工具名
                val titleText = if (expanded) {
                    toolName
                } else {
                    val cleaned = result.replace("\n", " ").trim()
                    when {
                        cleaned.length > 40 -> "$toolName · ${cleaned.take(40)}…"
                        cleaned.isNotEmpty() -> "$toolName · $cleaned"
                        else -> toolName
                    }
                }
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) {
                        stringResource(R.string.action_collapse)
                    } else {
                        stringResource(R.string.action_expand)
                    },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconTiny),
                )
            }
            // 展开内容:入参 + 出参 合并为单段 bodySmall 文本(不再使用两个嵌套 Surface)
            AnimatedVisibility(visible = expanded) {
                val paramsLabel = stringResource(R.string.chat_tool_params)
                val resultLabel = stringResource(R.string.chat_tool_result)
                val isTruncated = result.length > 500
                val displayResult = if (isTruncated) result.take(500) + "…" else result
                val content = "$paramsLabel: ${arguments.ifBlank { "{}" }}\n$resultLabel: $displayResult"
                Column(modifier = Modifier.padding(top = MusePaddings.contentGap)) {
                    Text(
                        text = content,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 结果文本下方:若检测到沙盒内文件路径,渲染可点击附件芯片
                    if (attachments.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier.padding(top = MusePaddings.contentGap),
                            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                        ) {
                            attachments.forEach { (path, size) ->
                                AttachmentChip(
                                    filePath = path,
                                    fileSize = size,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 从工具结果文本中提取沙盒内文件路径。
 *
 * 匹配 /data/.../files/ 或 /data/.../cache/ 等绝对路径下、带扩展名的文件,
 * 只返回磁盘中真实存在的文件,按路径去重。
 *
 * @return 文件路径与对应字节数(读取失败则为 null)的列表
 */
// v1.79 (L-B10): Regex 提为文件级常量,避免每次调用重建
private val PATH_PATTERN = Regex("""(/data/[^\s,)]+\.[a-zA-Z0-9]+)""")

private fun extractFilePaths(text: String): List<Pair<String, Long?>> {
    val results = mutableListOf<Pair<String, Long?>>()
    PATH_PATTERN.findAll(text).forEach { match ->
        val path = match.groupValues[1]
        val file = java.io.File(path)
        if (file.exists()) {
            results.add(path to file.length())
        }
    }
    return results.distinctBy { it.first }
}

/**
 * v1.95: 从文本中提取表情包绝对路径。
 *
 * send_sticker 工具返回格式为 `已发送表情包。路径:/data/.../files/stickers/猫猫/001.png`,
 * 本函数用正则匹配包含 `/stickers/` 的绝对路径(从路径起始的 `/` 到图片扩展名为止),
 * 供 MessageBubble 把这些路径转为 file:// URI 用 Coil AsyncImage 渲染。
 *
 * @return 去重后的绝对路径列表
 */
// v1.95: 表情包路径正则 — 匹配包含 /stickers/ 且以图片扩展名结尾的绝对路径,不区分大小写
// 从路径起始的 / 开始匹配(如 /data/.../files/stickers/猫猫/001.png),保证 file:// URI 可解析
internal val STICKER_PATH_PATTERN =
    Regex("""(/[^\s\]]*?/stickers/[^\s\]]+\.(?:png|jpg|jpeg|gif|webp|bmp))""", RegexOption.IGNORE_CASE)

internal fun extractStickerPaths(text: String): List<String> {
    return STICKER_PATH_PATTERN.findAll(text)
        .map { it.groupValues[1] }
        .distinct()
        .toList()
}

/**
 * v1.133: RAG 引用 chip 列表 — 渲染知识库检索引用,点击展开 snippet 预览。
 *
 * 设计:
 *  - 用 FlowRow 横向排列 chip(自动换行,适配窄屏)
 *  - 当前展开项独占一行显示完整 snippet + 元数据(分数/匹配类型)
 *  - chip 形状用 [MuseShapes.pill](iOS 胶囊形),颜色用 surfaceVariant/primaryContainer
 *
 * @param citations 引用列表(与 system prompt 中的 [N] 一一对应)
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun RagCitationChips(
    citations: List<RagCitation>,
    modifier: Modifier = Modifier,
) {
    // 当前展开的 citation index,-1 表示全部收起
    var expandedIndex by rememberSaveable { mutableStateOf(-1) }
    Column(modifier = modifier.fillMaxWidth()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        ) {
            citations.forEach { citation ->
                RagCitationChip(
                    citation = citation,
                    isExpanded = expandedIndex == citation.index,
                    onClick = {
                        expandedIndex = if (expandedIndex == citation.index) -1 else citation.index
                    },
                )
            }
        }
        // 展开的 snippet 预览(独占一行)
        val expanded = citations.firstOrNull { it.index == expandedIndex }
        if (expanded != null) {
            Surface(
                shape = MuseShapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MusePaddings.tightGap),
            ) {
                Column(modifier = Modifier.padding(MusePaddings.contentGap)) {
                    Text(
                        text = stringResource(
                            R.string.chat_knowledge_chunk_title,
                            expanded.docTitle,
                            expanded.chunkIndex,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(MusePaddings.tightGap))
                    Text(
                        text = expanded.snippet,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(
                            R.string.chat_knowledge_chunk_score,
                            "%.2f".format(expanded.score),
                            expanded.matchType,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = MusePaddings.tightGap),
                    )
                }
            }
        }
    }
}

@Composable
private fun RagCitationChip(
    citation: RagCitation,
    isExpanded: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = MuseShapes.pill,
        color = if (isExpanded) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (isExpanded) MaterialTheme.colorScheme.onPrimaryContainer
                       else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = MusePaddings.contentGap, vertical = MusePaddings.tightGap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        ) {
            Text(
                text = "[${citation.index}]",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = citation.docTitle.take(20),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(MuseIconSizes.iconTiny),
            )
        }
    }
}

// 情绪特效使用固定字号，属装饰性内联样式，不进入正文排版层级
private fun moodSkinEffectStyle(effect: String): SpanStyle = when (effect) {
    "glow" -> SpanStyle(color = MoodSkinColors.glow, fontWeight = FontWeight.SemiBold)
    "big" -> SpanStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold) // mood effect
    "huge" -> SpanStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold) // mood effect
    "whisper" -> SpanStyle(fontSize = 13.sp, color = MoodSkinColors.whisper) // mood effect
    "red" -> SpanStyle(color = MoodSkinColors.red, fontWeight = FontWeight.Bold)
    "shake" -> SpanStyle(color = MoodSkinColors.shake, letterSpacing = 1.sp)
    "blur" -> SpanStyle(color = MoodSkinColors.blur)
    "glitch" -> SpanStyle(color = MoodSkinColors.glitch, letterSpacing = 2.sp)
    else -> SpanStyle()
}
@Composable
private fun buildHighlightedText(text: String, query: String): AnnotatedString {
    val highlightColor = MaterialTheme.colorScheme.primaryContainer
    val onHighlight = MaterialTheme.colorScheme.onPrimaryContainer
    return buildAnnotatedString {
        var start = 0
        val lowerText = text.lowercase()
        val lowerQuery = query.lowercase()
        while (true) {
            val index = lowerText.indexOf(lowerQuery, start)
            if (index < 0) {
                append(text.substring(start))
                break
            }
            append(text.substring(start, index))
            withStyle(SpanStyle(background = highlightColor, color = onHighlight)) {
                append(text.substring(index, index + query.length))
            }
            start = index + query.length
        }
    }
}

/**
 * v1.98 (T5): 任务待办胶囊 — 长任务(≥2 步工具调用)时,显示在工具调用卡片左边。
 *
 * 显示内容:
 * - 执行中:旋转加载图标 + "当前/总数"(primary 色)
 * - 已完成:Build 图标 + "成功/总数"(primary 色)
 * - 有失败:Build 图标 + "成功/总数"(error 色)
 *
 * 点击展开/折叠完整 TaskCard。竖向胶囊,贴合工具调用卡片左侧。
 */
@Composable
private fun TaskProgressBadge(
    phase: io.zer0.muse.ui.taskcard.TaskCardPhase,
    successCount: Int,
    total: Int,
    isExecuting: Boolean,
    modifier: Modifier = Modifier,
) {
    val hasFailed = phase == io.zer0.muse.ui.taskcard.TaskCardPhase.DONE && successCount < total
    val badgeColor = if (hasFailed) MaterialTheme.colorScheme.error
                     else MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier
            .width(36.dp),
        shape = MuseShapes.pill,
        color = badgeColor.copy(alpha = 0.12f),
    ) {
        Column(
            modifier = Modifier.padding(vertical = MusePaddings.contentGap, horizontal = MusePaddings.tinyGap),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap),
        ) {
            if (isExecuting) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(MusePaddings.screen),
                    color = badgeColor,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Build,
                    contentDescription = null,
                    tint = badgeColor,
                    modifier = Modifier.size(MusePaddings.screen),
                )
            }
            Text(
                text = "$successCount/$total",
                style = MaterialTheme.typography.labelSmall,
                color = badgeColor,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

