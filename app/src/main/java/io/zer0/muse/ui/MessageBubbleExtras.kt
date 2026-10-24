@file:Suppress(
    "LongMethod",
    "CyclomaticComplexMethod",
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.zer0.ai.core.RagCitation
import io.zer0.muse.R
import io.zer0.muse.ui.chat.ToolCallVisuals
import io.zer0.muse.ui.chat.ToolResultRenderer
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.AttachmentChip
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.markdown.RichContentCard
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
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
@Suppress("LongParameterList")
@Composable
internal fun ToolCallCard(
    toolName: String,
    arguments: String,
    result: String,
    isSuccess: Boolean,
    modifier: Modifier = Modifier,
    /** 本地执行耗时（毫秒）；null 表示未知或尚在执行。 */
    durationMs: Long? = null,
    /** v2.6.0: 卡片回传(show_card 渲染后转发「保存」等动作);null 时不渲染卡桥。 */
    onCardAction: ((io.zer0.muse.ui.markdown.CardAction) -> Unit)? = null,
    /** v2.6.7: 卡片身份(聊天场景传消息 id),注入为 window.muse.cardId。 */
    cardId: String? = null,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    // v2.6.0: show_card 专用渲染 —— 工具参数(title/code/data)直接渲染为内联卡片,
    // 不再展示原始参数键值对。解析失败(参数残缺)时回落普通工具卡展示。
    val showCardSpec = remember(toolName, arguments) {
        if (toolName == SHOW_CARD_TOOL_NAME) parseShowCardArgs(arguments) else null
    }

    // v2.0: 插件工具卡 — 插件为该工具声明自定义 HTML 卡片时提供查看入口
    var showPluginCard by rememberSaveable { mutableStateOf(false) }
    val pluginManager: io.zer0.muse.data.plugin.PluginManager = org.koin.compose.koinInject()
    val pluginCardHtml = remember(toolName) {
        runCatching { pluginManager.loadToolCardHtml(toolName) }.getOrNull()
    }

    // isSuccess=false 且 result 空 → 视为执行中（工具刚下发、尚无结果）
    val isRunning = !isSuccess && result.isBlank()
    val hasFailed = !isSuccess && result.isNotBlank()

    // 产物直出与普通工具卡都要用到的标签，提前解出来。
    val labelResources = LocalContext.current.resources
    val label = remember(toolName) { ToolCallVisuals.labelFor(toolName, labelResources) }

    // ── 产物直出 ──────────────────────────────────────────────────────────
    //
    // 用户反馈: 生成出来的卡片/图片本身不该被折叠在一个「工具调用」里。
    //
    // 工具卡的心智是「过程」（调用了什么、参数、耗时），
    // 而图片/卡片/下载文件是「结果」——结果是给用户看的内容，不是执行日志。
    // 产物塞进折叠卡后，用户必须先展开「展示卡片」才能看到东西，多一步无意义操作。
    //
    // 规则：执行成功且有可展示产物时，直接渲染产物（带一行极淡的来源标识），
    // 不再套工具卡外壳；失败/执行中/无可展示产物时，保持原有工具卡（便于排查）。
    if (!isRunning && !hasFailed) {
        val directOutput = remember(toolName, arguments, result) {
            resolveDirectOutput(toolName, arguments, result)
        }
        if (directOutput != null) {
            DirectToolOutput(
                output = directOutput,
                label = label,
                onCardAction = onCardAction,
                cardId = cardId,
                modifier = modifier,
            )
            return
        }
    }

    // v1.79: extractFilePaths 含 file.exists() 磁盘 IO,移到 LaunchedEffect + IO 线程
    var attachments by remember(result) { mutableStateOf(emptyList<Pair<String, Long?>>()) }
    LaunchedEffect(result) {
        attachments = withContext(Dispatchers.IO) { extractFilePaths(result) }
    }
    // 有附件时默认展开,让用户直接看到可下载的文件芯片
    LaunchedEffect(attachments.isNotEmpty()) {
        if (attachments.isNotEmpty()) expanded = true
    }

    // I18N-01: 工具摘要/标签经资源解析(zh+en 已入 strings_tools)
    val resources = LocalContext.current.resources
    val icon = remember(toolName) { ToolCallVisuals.iconFor(toolName) }
    // label 已在函数开头解出（产物直出分支需要提前使用）
    // F-19: 生成图片/视频/二维码类工具，若结果含图片源则渲染 AsyncImage 缩略图
    val imageSources = remember(toolName, result) {
        if (toolName in IMAGE_PREVIEW_TOOL_NAMES) extractImageSources(result) else emptyList()
    }
    val summary = remember(toolName, arguments, result, isSuccess) {
        when {
            isRunning -> "正在执行…"
            else -> ToolCallVisuals.terminalSummaryFor(result, resources)
                ?: ToolCallVisuals.summaryFor(toolName, arguments, result, isSuccess, resources)
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
                        MuseSpinner(
                            size = 16.dp,
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
                        color = if (hasFailed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 执行耗时：让用户能区分“慢在模型”与“慢在工具”。
                // 执行中不展示（durationMs 为 null），完成后显示。
                if (durationMs != null && durationMs > 0) {
                    Text(
                        text = formatToolDuration(durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                // 展开箭头（带旋转动画）
                val rotation by animateFloatAsState(
                    targetValue = if (expanded) 180f else 0f,
                    animationSpec = MuseMotion.tween(MuseAnimation.FAST_NORMAL_MS),
                    label = "toolcard-chevron",
                )
                Icon(
                    imageVector = MuseIcons.chevronDown,
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
                    // v2.6.0: show_card 卡片本体 —— 直接渲染 HTML/SVG 卡片,
                    // 不再展示原始参数键值对与文本结果。
                    if (showCardSpec != null) {
                        io.zer0.muse.ui.markdown.RichContentCard(
                            language = if (showCardSpec.isSvg) "svg" else "html",
                            content = showCardSpec.code,
                            onCardAction = onCardAction,
                            inlineCardData = showCardSpec.data,
                            cardId = cardId,
                            showPreviewButton = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        // 参数：键值对展示（不再裸 {"k":"v"}）
                        val paramRows = remember(arguments) { parseParamRows(arguments) }
                        if (paramRows.isNotEmpty()) {
                            ParamSection(rows = paramRows)
                        }
                        // 结果：带标题的输出框，JSON/diff/表格走专用渲染
                        if (result.isNotBlank()) {
                            ResultSection(result = result, hasFailed = hasFailed)
                        }
                    }
                    // 附件芯片
                    if (attachments.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                            contentPadding = PaddingValues(horizontal = 2.dp),
                        ) {
                            items(attachments, key = { it.first }) { (path, size) ->
                                AttachmentChip(filePath = path, fileSize = size)
                            }
                        }
                    }
                    // F-19: 生成图片/视频/二维码的内嵌预览产物卡（AsyncImage 缩略图，点击放大从简未做）
                    if (imageSources.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                            contentPadding = PaddingValues(horizontal = 2.dp),
                        ) {
                            items(imageSources.take(6)) { src ->
                                AsyncImage(
                                    model = src,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(120.dp)
                                        .clip(MuseShapes.small),
                                )
                            }
                        }
                    }
                    // v2.0: 插件自定义工具卡入口(声明 toolCards 的插件工具)
                    if (pluginCardHtml != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showPluginCard = true }
                                .padding(vertical = MusePaddings.tightGap),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.chat_tool_card_open),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }

    // v2.0: 插件工具卡弹窗 — 只读渲染插件 HTML,数据经 window.__TOOL_CARD__ 注入
    if (showPluginCard && pluginCardHtml != null) {
        MuseDialog(
            onDismissRequest = { showPluginCard = false },
            title = label,
            content = {
                RichContentCard(
                    language = "html",
                    content = buildToolCardHtml(pluginCardHtml, toolName, arguments, result, isSuccess),
                    showPreviewButton = false,
                )
            },
            confirmText = stringResource(R.string.action_close),
            onConfirm = { showPluginCard = false },
        )
    }
}

/** v2.0: 把工具调用数据注入插件卡片 HTML(前置脚本,只读)。 */
private fun buildToolCardHtml(html: String, toolName: String, arguments: String, result: String, isSuccess: Boolean): String {
    val argsElement = runCatching {
        io.zer0.common.AppJson.parseToJsonElement(arguments)
    }.getOrNull() ?: kotlinx.serialization.json.JsonPrimitive(arguments)
    val json = kotlinx.serialization.json.buildJsonObject {
        put("toolName", kotlinx.serialization.json.JsonPrimitive(toolName))
        put("arguments", argsElement)
        put("result", kotlinx.serialization.json.JsonPrimitive(result.take(8000)))
        put("success", kotlinx.serialization.json.JsonPrimitive(isSuccess))
    }.toString()
    return "<script>window.__TOOL_CARD__ = $json;</script>\n$html"
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
                    // LAYOUT-01: 参数名固定 84.dp 列宽，补限行以免长键名在窄列里逐字换行
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
    val context = LocalContext.current
    val resultLabel = stringResource(R.string.chat_tool_result)
    // Long results keep a bounded preview so智能渲染 stays cheap, but the user can
    // still expand the full text and copy it without leaving the message.
    var showFullResult by rememberSaveable(result) { mutableStateOf(false) }
    val isTruncated = result.length > RESULT_PREVIEW_CHARS
    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = resultLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
                // LAYOUT-01: 已取宽的结果标签补限行，剩余宽度极小时不再逐字换行
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // LAYOUT-01: 行尾动作标签只限行、不取宽（左侧"结果"已 weight(1f)，取宽会把它挤到中间）
            Text(
                text = stringResource(R.string.chat_tool_copy),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(MuseShapes.small)
                    .clickable {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("Muse Tool Result", result),
                        )
                        MuseToast.show(context.getString(R.string.chat_copied_toast))
                    }
                    .padding(MusePaddings.tinyGap),
            )
        }
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = MuseShapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(MusePaddings.contentGap)) {
                when {
                    !isTruncated -> ToolResultRenderer(result = result)
                    showFullResult -> Text(
                        text = result,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = if (hasFailed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    else -> Text(
                        text = result.take(RESULT_PREVIEW_CHARS) + "…",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = if (hasFailed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (isTruncated) {
                    Text(
                        text = stringResource(
                            if (showFullResult) {
                                R.string.chat_tool_result_collapse
                            } else {
                                R.string.chat_tool_result_expand
                            },
                            result.length,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = MusePaddings.tinyGap)
                            .clip(MuseShapes.small)
                            .clickable { showFullResult = !showFullResult }
                            .padding(MusePaddings.tinyGap),
                    )
                }
            }
        }
    }
}

/** Bounded preview shown before the user expands a long tool result. */
private const val RESULT_PREVIEW_CHARS = 500

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
        val display: String = when (v) {
            is String -> v
            is Boolean, is Number -> v.toString()
            is JSONArray -> "[${v.length()} 项]"
            is JSONObject -> "{…}"
            else -> v.toString()
        }
        if (display.isNotBlank()) rows += k to display
    }
    return rows.take(8) // 参数过多时截断展示
}

// ── v2.6.0: show_card 参数解析与内联卡片渲染 ───────────────────────────

/** show_card 工具名。 */
internal const val SHOW_CARD_TOOL_NAME = "show_card"

/**
 * 从 show_card 的工具参数中提取的内联卡片内容。
 *
 * @param code HTML/SVG 片段(渲染主体)
 * @param data 可选绑定数据(内联给卡片脚本;null 表示无)
 * @param isSvg 片段是否为 SVG(决定渲染路径)
 */
internal data class ShowCardSpec(
    val code: String,
    val data: String?,
    val isSvg: Boolean,
)

/**
 * 解析 show_card 的工具参数 JSON。
 *
 * 返回 null 表示参数残缺(code 缺失或非法 JSON),此时工具卡回落普通展示。
 * 参数值可能是嵌套对象(模型把 data/code 按 string 或 object 传入),这里统一
 * 用容错取字符:字符串基元取去转义后的文本,其余取 JSON 文本。
 *
 * v2.6.7: 支持**流式半成品**——工具参数是一段段拼出来的，完整 JSON 要到
 * 结束时才成立。旧实现在流式期间永远返回 null，于是先显示成工具卡（参数
 * 键值对），退出重进后才从数据库拿到完整参数变成卡片，观感上就是
 * “刚生成不显示、重进才出现”。
 * 现在严格解析失败时，会补一个「渐进抽取」：尽量把 code 字段已有的内容取出来，
 * 让卡片边流边长。
 */
internal fun parseShowCardArgs(arguments: String): ShowCardSpec? {
    val t = arguments.trim()
    if (t.isEmpty()) return null
    // 完整 JSON 优先；拿不到 code 时再退回渐进抽取。
    return parseComplete(t) ?: parsePartial(t)
}

/** 完整 JSON 参数解析；无有效 code 时返回 null。 */
private fun parseComplete(t: String): ShowCardSpec? {
    val obj = t.takeIf { it.startsWith("{") }
        ?.let { runCatching { JSONObject(it) }.getOrNull() }
    val code = obj?.let { jsonArgText(it.opt("code")) }?.takeIf { it.isNotBlank() }
    val data = obj?.let { jsonArgText(it.opt("data")) }?.takeIf { d -> d.isNotBlank() }
    return code?.let { buildSpec(it, data) }
}

/** 流式半成品：从残缺 JSON 里尽力抽出 code 当前值。 */
private fun parsePartial(t: String): ShowCardSpec? {
    val code = partialJsonStringField(t, "code")?.takeIf { it.isNotBlank() } ?: return null
    return buildSpec(code, partialJsonStringField(t, "data")?.takeIf { it.isNotBlank() })
}

private fun buildSpec(code: String, data: String?): ShowCardSpec {
    val isSvg = code.trimStart().let { s -> s.startsWith("<svg", ignoreCase = true) || s.startsWith("<?xml") }
    return ShowCardSpec(code = code, data = data, isSvg = isSvg)
}

/**
 * v2.6.7: 从残缺 JSON 文本中抽取某个字符串字段的当前值。
 *
 * 不追求完整正确：找到 `"key"` 后的 `"` 起点，然后往后扫到结束引号
 * 或文本末尾（流式未写完时就是末尾），再把转义字符还原成真实字符。
 * 只支持字符串值 —— show_card 的 code/data 本来就是字符串。
 */
internal fun partialJsonStringField(raw: String, key: String): String? {
    val start = findStringValueStart(raw, "\"$key\"") ?: return null
    return readJsonStringFrom(raw, start)
}

/** 找到 `"key"` 后紧跟的字符串值起始引号位置（已跳过该引号）。 */
private fun findStringValueStart(raw: String, keyToken: String): Int? {
    var result: Int? = null
    var searchFrom = 0
    var searching = true
    while (searching) {
        val keyAt = raw.indexOf(keyToken, searchFrom)
        if (keyAt < 0) {
            searching = false
        } else {
            searchFrom = keyAt + keyToken.length
            val valueStart = stringValueStartAfter(raw, searchFrom)
            if (valueStart != VALUE_NOT_FOUND) {
                result = valueStart.takeIf { it >= 0 }
                searching = false
            }
        }
    }
    return result
}

/** 返回值表示“这个 key 不是字符串字段，继续找”。 */
private const val VALUE_NOT_FOUND = Int.MIN_VALUE

/** 从 [from] 起跳过空白与冒号，返回字符串体起点；不是字符串字段时返回 [VALUE_NOT_FOUND]。 */
private fun stringValueStartAfter(raw: String, from: Int): Int {
    var i = from
    while (i < raw.length && raw[i].isWhitespace()) i++
    if (i >= raw.length || raw[i] != ':') return VALUE_NOT_FOUND
    i++
    while (i < raw.length && raw[i].isWhitespace()) i++
    return if (i < raw.length && raw[i] == '"') i + 1 else -1
}

/** 从 [from] 开始读一个 JSON 字符串体（容忍未闭合，流式时就是读到末尾）。 */
private fun readJsonStringFrom(raw: String, from: Int): String {
    val sb = StringBuilder()
    var i = from
    while (i < raw.length) {
        val c = raw[i]
        if (c == '"') return sb.toString()
        if (c != '\\') {
            sb.append(c)
            i++
            continue
        }
        val consumed = appendEscape(raw, i, sb)
        i += consumed
    }
    return sb.toString()
}

/** 处理一个转义序列，返回消耗的字符数。 */
private fun appendEscape(raw: String, at: Int, sb: StringBuilder): Int {
    val esc = if (at + 1 < raw.length) raw[at + 1] else null
    val simple = when (esc) {
        'n' -> '\n'
        't' -> '\t'
        'r' -> '\r'
        '"' -> '"'
        '\\' -> '\\'
        '/' -> '/'
        else -> null
    }
    val unicode = esc
        ?.takeIf { it == 'u' && at + 6 <= raw.length }
        ?.let { raw.substring(at + 2, at + 6).toIntOrNull(16) }

    val consumed = when {
        simple != null -> 2
        unicode != null -> 6
        esc == null -> 1
        else -> 2
    }
    when {
        simple != null -> sb.append(simple)
        unicode != null -> sb.append(unicode.toChar())
        esc != null -> sb.append(esc)
    }
    return consumed
}

/** 取一个 JSON 值的文本:字符串直接用,GSON 的嵌套对象/数组用 JSON 文本。 */
private fun jsonArgText(value: Any?): String? = when (value) {
    null, JSONObject.NULL -> null
    is String -> value
    else -> value.toString()
}

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

// ── F-19: 生成图片/视频/二维码预览 ──────────────────────────────────────

/** F-19: 需要渲染图片预览产物的工具名。 */
private val IMAGE_PREVIEW_TOOL_NAMES: Set<String> = setOf("generate_image", "generate_video", "generate_qrcode")

/** F-19: 匹配工具结果中的 http(s) 图片 URL。 */
private val IMAGE_URL_PATTERN = Regex("""https?://[^\s)\]},，。；;]+""")

/** F-19: 匹配沙盒内绝对路径的本地图片文件（generate_qrcode 等返回 cacheDir 文件路径）。 */
private val IMAGE_LOCAL_PATH_PATTERN = Regex("""(/data/[^\s,)]+\.(?:png|jpe?g|gif|webp|bmp))""", RegexOption.IGNORE_CASE)

/**
 * F-19: 从工具结果文本中提取内嵌图片源（网络 URL 或本地图片文件路径）。
 *
 * 返回 List<Any>：元素为 String(URL) 或 java.io.File(本地图)，供 Coil AsyncImage 直接加载。
 */
private fun extractImageSources(text: String): List<Any> {
    val sources = mutableListOf<Any>()
    IMAGE_URL_PATTERN.findAll(text).forEach { match ->
        sources.add(match.value.trimEnd('.', '，', '。', '；', ';', ',', ')', ']', '}'))
    }
    IMAGE_LOCAL_PATH_PATTERN.findAll(text).forEach { match ->
        sources.add(java.io.File(match.value))
    }
    return sources.distinct()
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
 * v2.x: 长文消息判定 — 结构型内容或超长正文时,AI 消息"不进卡"
 * (长内容直接落在呼吸边上全宽排版,不被气泡容器包住)。
 *
 * 判定信号(任一命中):
 *  - 代码块围栏 ```
 *  - Markdown 标题行(行首 #~###### + 空格)
 *  - Markdown 表格分隔行 |---
 *  - 正文超长(> 500 字符)
 */
internal fun isLongFormContent(content: String): Boolean {
    if (content.length > 500) return true
    if (content.contains("```")) return true
    if (content.contains("|---")) return true
    return LONG_FORM_HEADING_REGEX.containsMatchIn(content)
}

/** v2.x: Markdown 标题行(行首 1-6 个 # + 空格)。 */
private val LONG_FORM_HEADING_REGEX = Regex("(?m)^#{1,6}\\s")

/**
 * v1.133: RAG 引用 chip 列表 — 渲染知识库检索引用,点击展开 snippet 预览。
 *
 * Phase 2 补齐:
 *  - index 作为 LazyRow key 先按 index 去重,异常重复数据不再触发 key 冲突崩溃;
 *  - chip 增加 contentDescription(引用编号 + 文档名 + 展开/收起提示);
 *  - 展开区增加「打开文档」(由调用方透传 [onOpenDocument],未提供时不显示)与「复制摘要」动作。
 */
@Composable
internal fun RagCitationChips(
    citations: List<RagCitation>,
    modifier: Modifier = Modifier,
    onOpenDocument: ((RagCitation) -> Unit)? = null,
) {
    // 同一消息内若出现重复 index(数据异常/重复注入),distinctBy 兜底避免 LazyRow key 冲突
    val uniqueCitations = remember(citations) { citations.distinctBy { it.index } }
    var expandedIndex by rememberSaveable { mutableStateOf(-1) }
    Column(modifier = modifier.fillMaxWidth()) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            contentPadding = PaddingValues(horizontal = 2.dp),
        ) {
            items(uniqueCitations, key = { it.index }) { citation ->
                RagCitationChip(
                    citation = citation,
                    isExpanded = expandedIndex == citation.index,
                    onClick = {
                        expandedIndex = if (expandedIndex == citation.index) -1 else citation.index
                    },
                )
            }
        }
        val expanded = uniqueCitations.firstOrNull { it.index == expandedIndex }
        if (expanded != null) {
            RagCitationDetail(
                citation = expanded,
                onOpenDocument = onOpenDocument,
            )
        }
    }
}

/** 展开的引用详情:片段标题 + 摘要 + 分数 + 动作行(打开文档 / 复制摘要)。 */
@Composable
private fun RagCitationDetail(citation: RagCitation, onOpenDocument: ((RagCitation) -> Unit)?) {
    val context = LocalContext.current
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
                    citation.docTitle,
                    citation.chunkIndex,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(MusePaddings.tightGap))
            Text(
                text = citation.snippet,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(
                    R.string.chat_knowledge_chunk_score,
                    "%.2f".format(citation.score),
                    citation.matchType,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = MusePaddings.tightGap),
            )
            // Phase 2: 引用动作 — 打开文档(可选回调)/ 复制摘要(剪贴板)
            Row(
                modifier = Modifier.padding(top = MusePaddings.tightGap),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onOpenDocument != null) {
                    RagCitationAction(
                        text = stringResource(R.string.chat_citation_open_doc),
                        onClick = { onOpenDocument(citation) },
                    )
                }
                RagCitationAction(
                    text = stringResource(R.string.chat_citation_copy_snippet),
                    onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("Muse RAG Citation", citation.snippet),
                        )
                        MuseToast.show(context.getString(R.string.chat_copied_toast))
                    },
                )
            }
        }
    }
}

/** 引用详情内的文本动作按钮。 */
@Composable
private fun RagCitationAction(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(MuseShapes.small)
            .clickable(onClick = onClick)
            .padding(MusePaddings.tinyGap),
    )
}

@Composable
private fun RagCitationChip(citation: RagCitation, isExpanded: Boolean, onClick: () -> Unit) {
    val chipDescription = stringResource(
        R.string.chat_citation_chip_a11y,
        citation.index,
        citation.docTitle,
        stringResource(if (isExpanded) R.string.action_collapse else R.string.action_expand),
    )
    Surface(
        shape = MuseShapes.pill,
        color = if (isExpanded) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (isExpanded) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .clickable(onClick = onClick)
            .semantics { contentDescription = chipDescription },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = MusePaddings.contentGap, vertical = MusePaddings.tightGap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        ) {
            // LAYOUT-01: 序号标记是行首定量，只限行、不取宽（正文 docTitle 已是取宽项）
            Text(
                text = "[${citation.index}]",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            Text(
                text = citation.docTitle.take(20),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = if (isExpanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
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
    val badgeColor = if (hasFailed) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
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
                MuseSpinner(
                    size = MusePaddings.screen,
                    color = badgeColor,
                )
            } else {
                Icon(
                    imageVector = MuseIcons.wrench,
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

// ─────────────────────────────────────────────────────────────────────────────
// 产物直出
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 工具产出的可直出内容。
 *
 * 概念上区分「过程」与「结果」：工具卡记录执行过程，
 * 而这里承载的是用户真正想看的东西（卡片、图片、文件），不该被折叠隐藏。
 */
internal sealed interface DirectOutput {
    /** 交互式卡片（HTML/SVG）。 */
    data class Card(val code: String, val isSvg: Boolean, val data: String?) : DirectOutput

    /** 生成的媒体（图片 / 视频 / 二维码预览源）。 */
    data class Media(val sources: List<Any>) : DirectOutput

    /** 已落盘的文件（下载/导出）。 */
    data class File(val paths: List<String>) : DirectOutput
}

/** 直出型工具 → 产物类型。执行成功时跳过工具卡外壳，直接把产物呈现给用户。 */
private val CARD_OUTPUT_TOOLS: Set<String> = setOf(SHOW_CARD_TOOL_NAME, "render_data")

/**
 * v2.6.8: 该工具的产物是「交付物」，而不是「过程」。
 *
 * show_card 生成的那张卡是用户要的东西本身（可以点、可以提交、可以保存），
 * 不是「AI 调了个工具」的日志。过程可以折叠进任务卡的步骤里，交付物不行 ——
 * 用户不该为了看见自己刚生成的卡片去展开一张执行计划。
 *
 * 这条判定同时被三处使用，保证三处口径一致：
 *  - 任务卡步骤：只留一行引用，不再把卡片压在步骤里；
 *  - 消息流去重：不把 show_card 的工具消息当作「已由任务卡渲染」而隐藏；
 *  - 过程分组：不把 show_card 并进「连续工具调用」组卡（否则又得展开才能看）。
 *
 * 纯函数，便于单测。
 */
internal fun isInlineDeliverableTool(toolName: String?): Boolean = toolName == SHOW_CARD_TOOL_NAME

/**
 * v2.6.8: 消息列表里属于「交付物」的工具消息 id（目前即 show_card）。
 *
 * 调用方用它把这些消息从「产物已由任务卡渲染」的去重集合里摘出去，
 * 让卡片以一等内联卡的形式留在消息流里。
 */
internal fun inlineDeliverableMessageIds(messages: List<io.zer0.ai.core.UIMessage>): Set<String> = messages.asSequence()
    .filter { isInlineDeliverableTool(it.toolCallInfo?.toolName) }
    .map { it.id.toString() }
    .toHashSet()

private val MEDIA_OUTPUT_TOOLS: Set<String> = setOf("generate_image", "generate_video", "generate_qr_code")

private val FILE_OUTPUT_TOOLS: Set<String> = setOf("create_download")

/**
 * 判断一次成功的工具调用是否有可直出的产物。
 *
 * 返回 null 表示「这是过程，不是结果」——调用方应回落到普通工具卡。
 * 纯函数，便于单测；不做磁盘 IO（路径提取由渲染侧处理）。
 */
internal fun resolveDirectOutput(toolName: String, arguments: String, result: String): DirectOutput? {
    if (result.isBlank()) return null
    return when {
        toolName in CARD_OUTPUT_TOOLS -> {
            val spec = runCatching { parseShowCardArgs(arguments) }.getOrNull()
            if (spec != null && spec.code.isNotBlank()) {
                DirectOutput.Card(code = spec.code, isSvg = spec.isSvg, data = spec.data)
            } else {
                null
            }
        }
        toolName in MEDIA_OUTPUT_TOOLS -> {
            val sources = extractImageSources(result)
            if (sources.isEmpty()) null else DirectOutput.Media(sources)
        }
        toolName in FILE_OUTPUT_TOOLS -> {
            val paths = extractFilePathsSync(result)
            if (paths.isEmpty()) null else DirectOutput.File(paths)
        }
        else -> null
    }
}

/** 同步版路径提取（只做正则匹配，不查文件系统），用于纯函数判定与渲染。 */
private fun extractFilePathsSync(result: String): List<String> =
    runCatching { FILE_PATH_REGEX.findAll(result).map { it.value }.distinct().toList() }
        .getOrDefault(emptyList())

private val FILE_PATH_REGEX = Regex("""/[\w\-./]+\.(md|txt|json|csv|pdf|zip|png|jpg|jpeg|webp|mp4)\b""")

/**
 * 渲染直出产物：极淡的来源标识 + 产物本体。
 *
 * 保留标识是为了让用户知道「这是 AI 刚生成的东西」，
 * 但不使用可折叠外壳——产物本身就在首屏可见。
 */
/**
 * v2.6.7: 产物渲染入口 —— 计划卡/任务卡的步骤结果也走这里。
 *
 * 拆出来的原因：原来产物只能从孤立工具卡渲染，一旦工具调用被收进计划卡的
 * “步骤”里，产物就退化成一段被截断的文本，用户看不到。
 *
 * v2.6.8: 补上卡片回传桥。旧实现在这里硬编码 `onCardAction = null`，于是
 * 同一张 show_card 卡：单独出现时能点，一旦被收进任务卡的步骤里，桥就断了 ——
 * 渲染出来的 HTML 能看，但所有控件点了没反应（真机反馈的直接来源）。
 * 桥必须和产物一起传进来，渲染位置不该改变卡片的能力。
 *
 * @param label 来源标识（传工具名）；空串时不显示标识行
 * @param onCardAction 卡片回传桥；null 时卡片只渲染不交互（非聊天场景）
 */
@Composable
internal fun DirectToolOutputInline(
    output: DirectOutput,
    cardId: String? = null,
    label: String = "",
    modifier: Modifier = Modifier,
    onCardAction: ((io.zer0.muse.ui.markdown.CardAction) -> Unit)? = null,
) {
    DirectToolOutput(
        output = output,
        label = label,
        onCardAction = onCardAction,
        cardId = cardId,
        modifier = modifier,
        showLabel = label.isNotBlank(),
    )
}

@Composable
private fun DirectToolOutput(
    output: DirectOutput,
    label: String,
    onCardAction: ((io.zer0.muse.ui.markdown.CardAction) -> Unit)?,
    cardId: String? = null,
    modifier: Modifier = Modifier,
    /** v2.6.7: 计划卡内的产物不需要“来源标识”行，空间宝贵。 */
    showLabel: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // 极淡来源标识
        if (showLabel) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(bottom = MusePaddings.tightGap),
            ) {
                Icon(
                    imageVector = MuseIcons.sparkle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when (output) {
            is DirectOutput.Card -> {
                io.zer0.muse.ui.markdown.RichContentCard(
                    language = if (output.isSvg) "svg" else "html",
                    content = output.code,
                    onCardAction = onCardAction,
                    inlineCardData = output.data,
                    cardId = cardId,
                    showPreviewButton = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is DirectOutput.Media -> {
                // 直接展示生成的媒体（不再套工具卡），用户首屏即可看到成品。
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    items(output.sources.take(6)) { src ->
                        AsyncImage(
                            model = src,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(140.dp)
                                .clip(MuseShapes.medium),
                        )
                    }
                }
            }
            is DirectOutput.File -> {
                Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap)) {
                    output.paths.forEach { path ->
                        AttachmentChip(filePath = path, fileSize = null)
                    }
                }
            }
        }
    }
}
