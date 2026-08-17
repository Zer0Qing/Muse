// v1.205 D2: 工具结果专用渲染 — 递归树/视图均为 PascalCase Composable,
// 文件含 13 个函数(容器+叶子+视图),项目惯例用文件级豁免(MessageBubbleTail 先例)。
@file:Suppress("TooManyFunctions")

package io.zer0.muse.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MusePaddings
import org.json.JSONArray
import org.json.JSONObject

/**
 * v1.205 D2: 工具结果专用渲染 — 自动识别结果类型并选择视图:
 * - JSON(以 { / [ 开头且可解析)→ 可折叠 JSON 树
 * - unified diff(含 diff 头 / @@ hunk / +/- 行)→ 行级 diff 视图
 * - 表格(| 分隔多行)→ 结构化表格
 * - 其他 → 纯文本(与旧行为一致)
 *
 * 识别是启发式的:任何解析失败都会安全回退到纯文本,不抛异常拖垮消息气泡。
 */
@Composable
internal fun ToolResultRenderer(
    result: String,
    modifier: Modifier = Modifier,
) {
    val kind = remember(result) { detectResultKind(result) }
    when (kind) {
        ResultKind.JSON -> JsonTreeView(result, modifier)
        ResultKind.DIFF -> DiffView(result, modifier)
        ResultKind.TABLE -> TableView(result, modifier)
        ResultKind.PLAIN -> Text(
            text = result,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
    }
}

private enum class ResultKind { PLAIN, DIFF, JSON, TABLE }

/** 启发式类型识别:JSON > diff > table > plain(纯文本永不误判为表格)。 */
private fun detectResultKind(text: String): ResultKind {
    val trimmed = text.trim()
    val isJson = trimmed.isNotEmpty() &&
        (trimmed.startsWith("{") || trimmed.startsWith("[")) &&
        parseJsonOrNull(trimmed) != null
    return when {
        isJson -> ResultKind.JSON
        isDiffLike(text) -> ResultKind.DIFF
        isTableLike(text) -> ResultKind.TABLE
        else -> ResultKind.PLAIN
    }
}

/** unified diff 特征:有 diff 头/hunk 头,或同时存在多条 + 与 - 行。 */
private fun isDiffLike(text: String): Boolean {
    val lines = text.lines()
    if (lines.size < 3) return false
    val hasHeader = lines.any { it.startsWith("diff --git") || it.startsWith("@@") }
    val hasAdds = lines.count { it.startsWith("+") } >= 2
    val hasDels = lines.count { it.startsWith("-") } >= 2
    return hasHeader || hasAdds && hasDels
}

/** 表格特征:至少 2 行含 |,且含 | 行占比 ≥60%。 */
private fun isTableLike(text: String): Boolean {
    val lines = text.lines()
    val pipeLines = lines.filter { it.contains("|") }
    return pipeLines.size >= 2 && pipeLines.size.toFloat() / lines.size >= 0.6f
}

private fun parseJsonOrNull(text: String): Any? {
    val compact = text.trim()
    return try {
        JSONObject(compact)
    } catch (_: Exception) {
        try {
            JSONArray(compact)
        } catch (_: Exception) {
            null
        }
    }
}

// ── JSON 树 ──────────────────────────────────────────────────────────────

private const val MAX_JSON_DEPTH = 6

@Composable
private fun JsonTreeView(jsonText: String, modifier: Modifier) {
    val root = remember(jsonText) { parseJsonOrNull(jsonText) }
    if (root == null) {
        Text(
            text = jsonText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }
    Column(modifier = modifier) {
        JsonNode(key = null, value = root, depth = 0)
    }
}

@Composable
private fun JsonNode(key: String?, value: Any, depth: Int) {
    when (value) {
        is JSONObject -> JsonContainerNode(key, value, depth)
        is JSONArray -> JsonArrayNode(key, value, depth)
        else -> JsonLeafRow(key, value)
    }
}

@Composable
private fun JsonContainerNode(key: String?, obj: JSONObject, depth: Int) {
    var expanded by remember { mutableStateOf(depth < MAX_JSON_DEPTH) }
    val keys = obj.keys().asSequence().toList()
    Column {
        JsonToggleRow(key, "{${keys.size}}", expanded, depth) { expanded = !expanded }
        if (expanded) {
            keys.forEach { childKey ->
                val child = obj.opt(childKey)
                JsonNode(
                    key = childKey,
                    value = if (child == JSONObject.NULL) "null" else child,
                    depth = depth + 1,
                )
            }
        }
    }
}

@Composable
private fun JsonArrayNode(key: String?, arr: JSONArray, depth: Int) {
    var expanded by remember { mutableStateOf(depth < MAX_JSON_DEPTH) }
    val size = arr.length()
    Column {
        JsonToggleRow(key, "[$size]", expanded, depth) { expanded = !expanded }
        if (expanded) {
            repeat(size) { idx ->
                val child = arr.opt(idx)
                JsonNode(
                    key = null,
                    value = if (child == JSONObject.NULL) "null" else child,
                    depth = depth + 1,
                )
            }
        }
    }
}

@Composable
private fun JsonToggleRow(
    key: String?,
    preview: String,
    expanded: Boolean,
    depth: Int,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 12).dp)
            .clickable(onClick = onToggle),
    ) {
        Text(
            text = if (expanded) "▾ " else "▸ ",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.primary,
        )
        if (key != null) {
            Text(
                text = "\"$key\": ",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = preview,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun JsonLeafRow(key: String?, value: Any) {
    Row(modifier = Modifier.fillMaxWidth()) {
        if (key != null) {
            Text(
                text = "\"$key\": ",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = formatJsonLeaf(value),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = when (value) {
                is Boolean, is Int, is Long, is Double -> MaterialTheme.colorScheme.primary
                is String -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun formatJsonLeaf(value: Any): String = when (value) {
    is String -> "\"$value\""
    else -> value.toString()
}

// ── diff 视图 ────────────────────────────────────────────────────────────

private const val MAX_RENDER_LINES = 300

@Composable
private fun DiffView(diffText: String, modifier: Modifier) {
    val lines = remember(diffText) { diffText.lines().take(MAX_RENDER_LINES) }
    val addColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    val delColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
    val hunkColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
    Column(modifier = modifier) {
        lines.forEach { line ->
            val bg = when {
                line.startsWith("+") && !line.startsWith("+++") -> addColor
                line.startsWith("-") && !line.startsWith("---") -> delColor
                line.startsWith("@@") -> hunkColor
                else -> null
            }
            Row(
                modifier = if (bg != null) {
                    Modifier.fillMaxWidth().background(bg)
                } else {
                    // 普通 diff 行无背景色,按默认样式渲染
                    Modifier.fillMaxWidth()
                },
            ) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (diffText.lines().size > MAX_RENDER_LINES) {
            Text(
                text = "…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── 表格视图 ─────────────────────────────────────────────────────────────

@Composable
private fun TableView(tableText: String, modifier: Modifier) {
    val rows = remember(tableText) { parseTableRows(tableText) }
    Column(modifier = modifier) {
        rows.forEachIndexed { index, cells ->
            Row(modifier = Modifier.fillMaxWidth()) {
                cells.forEachIndexed { cellIndex, cell ->
                    Text(
                        text = cell,
                        style = if (index == 0) {
                            MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                        } else {
                            MaterialTheme.typography.bodySmall
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = MusePaddings.contentGap)
                            .widthIn(min = 40.dp),
                    )
                }
            }
        }
    }
}

/** 表格分隔行正则(--- / |---|---| 等),文件级常量避免每次解析重建。 */
private val TABLE_SEPARATOR_REGEX = Regex("^[|:\\-\\s]+$")

/** 解析 | 分隔表格:去空行/纯分隔行,单元格 trim,行数与列数均设上限。 */
private fun parseTableRows(tableText: String): List<List<String>> {
    val rows = tableText.lines()
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.matches(TABLE_SEPARATOR_REGEX) }
        .take(MAX_RENDER_LINES)
        .map { line ->
            line.split("|")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
        .filter { it.size >= 2 }
        .toList()
    val maxCols = rows.maxOfOrNull { it.size } ?: return emptyList()
    return rows.map { row ->
        if (row.size < maxCols) row + List(maxCols - row.size) { "" } else row
    }
}
