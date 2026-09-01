@file:Suppress(
    "FunctionNaming",
    "LongMethod",
    "CyclomaticComplexity",
    "UnusedPrivateMember",
    "UnusedPrivateProperty",
)

package io.zer0.muse.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.zer0.ai.core.RagCitation
import io.zer0.muse.R
import io.zer0.muse.ui.chat.ToolCallVisuals
import io.zer0.muse.ui.chat.ToolResultRenderer
import io.zer0.muse.ui.common.media.AttachmentChip
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import compose.icons.TablerIcons
import compose.icons.tablericons.ChevronDown
import compose.icons.tablericons.ChevronUp
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * v1.0.80: 工具调用卡片（现代化重写）。
 *
 * 设计目标：
 *  - 统一的"过程追踪"语言：发丝边 + surface 底，不用色块填充
 *  - 每种工具一个语义图标（[ToolCallVisuals]），折叠态一眼知道 AI 在干嘛
 *  - 折叠态显示一句中文摘要（"搜索了网页 xxx"），不再是裸工具名 + 前 40 字结果
 *  - 展开态：工具标签 + 参数键值对 + 结果分区（等宽字体仅留给代码/JSON）
 *  - 失败态用 error 描边 + 红叉；执行中（result 空且未成功）显示旋转进度
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
    var expanded by rememberSaveable { mutableStateOf(false) }

    // isSuccess=false 且 result 空 → 视为执行中（工具刚下发、尚无结果）
    val isRunning = !isSuccess && result.isBlank()
    val hasFailed = !isSuccess && result.isNotBlank()

    // v1.79: extractFilePaths 含 file.exists() 磁盘 IO,移到 LaunchedEffect + IO 线程
    var attachments by remember(result) { mutableStateOf(emptyList<Pair<String, Long?>>()) }
    LaunchedEffect(result) {
        attachments = withContext(Dispatchers.IO) { extractFilePaths(result) }
    }
    // 有附件时默认展开,让用户直接看到可下载的文件芯片
    LaunchedEffect(attachments.isNotEmpty()) {
        if (attachments.isNotEmpty()) expanded = true
    }

    val icon = remember(toolName) { ToolCallVisuals.iconFor(toolName) }
    val label = remember(toolName) { ToolCallVisuals.labelFor(toolName) }
    val summary = remember(toolName, arguments, result, isSuccess) {
        if (isRunning) {
            "正在执行…"
        } else {
            ToolCallVisuals.summaryFor(toolName, arguments, result, isSuccess)
        }
    }

    val accentColor = when {
        hasFailed -> MaterialTheme.colorScheme.error
        isRunning -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.primary
    }
    // v1.0.80: 卡片底色改用 surfaceVariant(浅灰)/errorContainer(浅红),去掉 1dp 描边。
    //   白底+描边在圆角抗锯齿下四角颜色发淡像断线,与计划卡片(浅灰底无描边)风格也不统一。
    val cardBg = when {
        hasFailed -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }
    val chipBg = when {
        hasFailed -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
        isRunning -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    }

    Surface(
        color = cardBg,
        shape = MuseShapes.medium,
        tonalElevation = MuseElevation.none,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(MusePaddings.bubbleInner)) {
            // ── 头部：图标徽章 + 标签 + 摘要 + 展开箭头 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = MusePaddings.tightGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 图标徽章：圆形浅色底 + 语义图标
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(chipBg),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isRunning) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                            color = accentColor,
                        )
                    } else {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                    }
                }
                Spacer(Modifier.width(MusePaddings.contentGap))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasFailed) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 展开箭头（带旋转动画）
                val rotation by animateFloatAsState(
                    targetValue = if (expanded) 180f else 0f,
                    animationSpec = MuseMotion.tween(MuseAnimation.FAST_NORMAL_MS),
                    label = "toolcard-chevron",
                )
                Icon(
                    imageVector = TablerIcons.ChevronDown,
                    contentDescription = if (expanded) {
                        stringResource(R.string.action_collapse)
                    } else {
                        stringResource(R.string.action_expand)
                    },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(MuseIconSizes.iconSmall)
                        .rotate(rotation),
                )
            }

            // ── 展开内容 ──
            AnimatedVisibility(
                visible = expanded,
                enter = MuseMotion.expandFadeEnter(),
                exit = MuseMotion.expandFadeExit(),
            ) {
                Column(
                    modifier = Modifier.padding(top = MusePaddings.contentGap),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                ) {
                    // 参数：键值对展示（不再裸 {"k":"v"}）
                    val paramRows = remember(arguments) { parseParamRows(arguments) }
                    if (paramRows.isNotEmpty()) {
                        ParamSection(rows = paramRows)
                    }
                    // 结果：带标题的输出框，JSON/diff/表格走专用渲染
                    if (result.isNotBlank()) {
                        ResultSection(result = result, hasFailed = hasFailed)
                    }
                    // 附件芯片
                    if (attachments.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                        ) {
                            attachments.forEach { (path, size) ->
                                AttachmentChip(filePath = path, fileSize = size)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 展开态的参数分区：标题 "参数" + 键值对行。
 *
 * 值过长时截断到一行；布尔/数字用 primary 色强调，字符串用 onSurfaceVariant。
 */
@Composable
private fun ParamSection(rows: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap)) {
        Text(
            text = stringResource(R.string.chat_tool_params),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        rows.forEach { (k, v) ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = k,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.width(84.dp),
                )
                Text(
                    text = v,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 展开态的结果分区：标题 "结果" + 浅色输出框。
 *
 * 截断（>500 字符）时保持纯文本，避免渲染半个 JSON/diff；完整结果走 [ToolResultRenderer]。
 */
@Composable
private fun ResultSection(result: String, hasFailed: Boolean) {
    val resultLabel = stringResource(R.string.chat_tool_result)
    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap)) {
        Text(
            text = resultLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = MuseShapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val isTruncated = result.length > 500
            val displayResult = if (isTruncated) result.take(500) + "…" else result
            Column(modifier = Modifier.padding(MusePaddings.contentGap)) {
                if (isTruncated) {
                    Text(
                        text = displayResult,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = if (hasFailed) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ToolResultRenderer(result = result)
                }
            }
        }
    }
}

// ── 参数解析 ────────────────────────────────────────────────────────────

private data class ParamRow(val key: String, val value: String)

/** 把 arguments JSON 解析成键值对行；非 JSON 或空时返回空列表（UI 不显示参数区）。 */
private fun parseParamRows(arguments: String): List<Pair<String, String>> {
    val t = arguments.trim()
    if (t.isEmpty() || !t.startsWith("{")) return emptyList()
    val obj = runCatching { JSONObject(t) }.getOrNull() ?: return emptyList()
    val rows = mutableListOf<Pair<String, String>>()
    val keys = obj.keys().asSequence().toList()
    for (k in keys) {
        val v = obj.opt(k)
        if (v == null || v == JSONObject.NULL) continue
        val display = when (v) {
            is String -> v.ifBlank { continue; "" }
            is Boolean, is Number -> v.toString()
            is JSONArray -> "[${v.length()} 项]"
            is JSONObject -> "{…}"
            else -> v.toString()
        }
        if (display.isNotBlank()) rows += k to display
    }
    return rows.take(8) // 参数过多时截断展示
}

// ── 文件路径提取（沿用原逻辑）──────────────────────────────────────────

/**
 * 从工具结果文本中提取沙盒内文件路径。
 *
 * 匹配 /data/.../files/ 或 /data/.../cache/ 等绝对路径下、带扩展名的文件,
 * 只返回磁盘中真实存在的文件,按路径去重。
 */
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
 * v1.95: 表情包路径正则 — 匹配包含 /stickers/ 的绝对路径。
 */
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
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RagCitationChips(
    citations: List<RagCitation>,
    modifier: Modifier = Modifier,
) {
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
                imageVector = if (isExpanded) TablerIcons.ChevronUp else TablerIcons.ChevronDown,
                contentDescription = null,
                modifier = Modifier.size(MuseIconSizes.iconTiny),
            )
        }
    }
}

// 情绪特效使用固定字号，属装饰性内联样式，不进入正文排版层级
private fun moodSkinEffectStyle(effect: String): androidx.compose.ui.text.SpanStyle = when (effect) {
    "glow" -> androidx.compose.ui.text.SpanStyle(
        color = io.zer0.muse.ui.theme.MoodSkinColors.glow,
        fontWeight = FontWeight.SemiBold,
    )
    "big" -> androidx.compose.ui.text.SpanStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold) // 情绪特效装饰字号
    "huge" -> androidx.compose.ui.text.SpanStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold) // 情绪特效装饰字号
    "whisper" -> androidx.compose.ui.text.SpanStyle(fontSize = 13.sp, color = io.zer0.muse.ui.theme.MoodSkinColors.whisper) // 情绪特效装饰字号
    "red" -> androidx.compose.ui.text.SpanStyle(color = io.zer0.muse.ui.theme.MoodSkinColors.red, fontWeight = FontWeight.Bold)
    "shake" -> androidx.compose.ui.text.SpanStyle(color = io.zer0.muse.ui.theme.MoodSkinColors.shake, letterSpacing = 1.sp)
    "blur" -> androidx.compose.ui.text.SpanStyle(color = io.zer0.muse.ui.theme.MoodSkinColors.blur)
    "glitch" -> androidx.compose.ui.text.SpanStyle(color = io.zer0.muse.ui.theme.MoodSkinColors.glitch, letterSpacing = 2.sp)
    else -> androidx.compose.ui.text.SpanStyle()
}

@Composable
private fun buildHighlightedText(text: String, query: String): androidx.compose.ui.text.AnnotatedString {
    if (query.isEmpty() || text.isEmpty()) return androidx.compose.ui.text.buildAnnotatedString { append(text) }
    val highlightColor = MaterialTheme.colorScheme.primaryContainer
    val onHighlight = MaterialTheme.colorScheme.onPrimaryContainer
    return androidx.compose.ui.text.buildAnnotatedString {
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
            withStyle(androidx.compose.ui.text.SpanStyle(background = highlightColor, color = onHighlight)) {
                append(text.substring(index, index + query.length))
            }
            start = index + query.length
        }
    }
}

/**
 * v1.98 (T5): 任务待办胶囊 — 长任务(≥2 步工具调用)时,显示在工具调用卡片左边。
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
        modifier = modifier.width(36.dp),
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

