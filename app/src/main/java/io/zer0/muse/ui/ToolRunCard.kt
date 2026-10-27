package io.zer0.muse.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.UIMessage
import io.zer0.muse.R
import io.zer0.muse.ui.chat.processIconFor
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes

/**
 * 操作组卡 —— 把**连续的工具调用**聚合为一张卡。
 *
 * - 折叠态(默认):一行摘要(执行了 N 个操作 + 工具名链);
 * - 展开态:逐个复用 [ToolCallCard],与单条工具调用的细节展示保持一致。
 *
 * 分组由 [io.zer0.muse.ui.chat.ChatDisplayGrouper] 决定;渲染接入见 ChatScreen ——
 * 组首条渲染本卡,组内其余条不渲染(消息索引保持不变,滚动/高亮无需调整)。
 */
@Composable
internal fun ToolRunCard(
    msgs: List<UIMessage>,
    modifier: Modifier = Modifier,
    /**
     * v2.6.7 fix（卡片审计）: 卡片回传处理器。
     *
     * 旧实现不传此参数，导致分组操作卡里的 show_card 没有交互桥：
     * 同一张卡单独出现能点、连续 ≥2 个工具调用被分组后就不能点。
     * 参数为 (messageId, action)，由调用方统一实现。
     */
    onCardAction: ((messageId: String, action: io.zer0.muse.ui.markdown.CardAction) -> Unit)? = null,
    /** 本组的实时状态（是否在跑 / 在跑什么 / 哪一步在思考）。 */
    live: ToolRunLiveState = ToolRunLiveState(),
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val toolInfos = msgs.mapNotNull { it.toolCallInfo }
    // v2.0.1: 纯思考组也要渲染（过程折叠），仅完全空组才跳过
    if (msgs.isEmpty()) return

    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // 折叠态只留一行回看：运行中的实时状态已经移到输入栏上方的扁条，
                    // 这里再撑 12dp 上下内边距纯属浪费屏幕。
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                ToolRunHeaderRow(msgs = msgs, toolInfos = toolInfos, expanded = expanded)
                ToolRunLiveRow(
                    msgs = msgs,
                    isActive = live.isActive,
                    progressMessage = live.progressMessage,
                )
            }
            ToolRunExpandedRows(
                msgs = msgs,
                expanded = expanded,
                live = live,
                onCardAction = onCardAction,
            )
        }
    }
}

/**
 * 过程组的「实时状态」。
 *
 * 打包成一个对象而不是三个参数：卡片构造参数已经贴着静态检查阈值，
 * 而这三项语义上本来就是一回事（这一组现在是不是活的、在干什么）。
 */
internal data class ToolRunLiveState(
    /** 本组是否还在跑（只有当前流式的那一组为 true）。 */
    val isActive: Boolean = false,
    /** 当前工具进度文案（如「正在执行 browser_navigate…」）。 */
    val progressMessage: String? = null,
    /** 当前正在流式的那条消息 id，用于判定哪一步在「思考中」。 */
    val activeMessageId: String? = null,
)

/**
 * 第二排：它现在正在干什么。
 *
 * 这一排是「活的状态」，不是「干完之后的清单」。所以：
 *  - 有工具进度文案 → 原样显示（「正在执行 browser_navigate…」）
 *  - 没进度但在想事 → 显示思考文字的最新一段（单行、取尾部）
 *  - 两者都没有 → 「思考中…」
 *  - 整组跑完了 → 这一排整个不出现
 *
 * 跑完后为什么不留一行：上面已经写了工具数/思考数/总耗时，
 * 再挂一串 `a -> b -> c` 的工具名只是执行日志，既不是状态，也没人读。
 */
@Composable
private fun ToolRunLiveRow(
    msgs: List<UIMessage>,
    isActive: Boolean,
    progressMessage: String?,
) {
    if (!isActive) return
    val activity = resolveLiveActivity(
        msgs = msgs,
        progressMessage = progressMessage,
        thinkingPlaceholder = stringResource(R.string.chat_process_thinking_now),
    )
    Spacer(Modifier.size(4.dp))
    Text(
        text = activity,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 实时状态最多展示多少字。一行大约能装这么多，超了取尾部。 */
private const val LIVE_STATUS_CHARS = 90

/**
 * 正在思考的单行预览。
 *
 * 取**尾部**：这是流式输出，最新那句才是它此刻在想的事，
 * 而且标签跟着动才看得出还在跑。
 */
private fun thinkingPreview(raw: String?): String? {
    val flat = raw.orEmpty().replace(Regex("\\s+"), " ").trim()
    if (flat.isEmpty()) return null
    return if (flat.length <= LIVE_STATUS_CHARS) flat else "…" + flat.takeLast(LIVE_STATUS_CHARS)
}

/**
 * 算出当前应显示的活动文本。
 *
 * 思考取**尾部**：这是流式输出，最新那句才是它现在在想的事，
 * 而且标签在动才能看出“还在跑”。
 * 思考文字没落进消息时（刚发请求、首 token 未到）给一个占位词，
 * 否则这排会忽隐忽现，像卡住了。
 */
private fun resolveLiveActivity(
    msgs: List<UIMessage>,
    progressMessage: String?,
    thinkingPlaceholder: String,
): String {
    if (!progressMessage.isNullOrBlank()) return progressMessage
    val raw = msgs.lastOrNull()?.reasoning?.takeIf { it.isNotBlank() }
        ?: msgs.lastOrNull { !it.reasoning.isNullOrBlank() }?.reasoning
    val flat = raw.orEmpty().replace(Regex("\\s+"), " ").trim()
    return when {
        flat.isEmpty() -> thinkingPlaceholder
        flat.length <= LIVE_STATUS_CHARS -> flat
        else -> "…" + flat.takeLast(LIVE_STATUS_CHARS)
    }
}

/**
 * v2.6.7: 分组操作卡的展开区。
 *
 * 从 [ToolRunCard] 抽出 —— 原函数已超 detekt LongMethod 阈值，
 * 且此处是它唯一随时间持续变长的部分（每次给卡片加交互参数都长一截）。
 */
@Composable
private fun ToolRunExpandedRows(
    msgs: List<UIMessage>,
    expanded: Boolean,
    live: ToolRunLiveState,
    onCardAction: ((messageId: String, action: io.zer0.muse.ui.markdown.CardAction) -> Unit)?,
) {
    AnimatedVisibility(
        visible = expanded,
        enter = MuseMotion.expandFadeEnter(),
        exit = MuseMotion.expandFadeExit(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            msgs.forEach { m ->
                val info = m.toolCallInfo
                val reasoningText = m.reasoning
                if (info != null) {
                    // 每个工具各占一张可折叠行，默认收起 —— 与平台侧的
                    // thinkingBlock / toolIndicator 保持同一节奏：
                    // 组是折叠的，组内每一项也各自折叠，避免一次铺开几十行。
                    ProcessStepItem(
                        title = info.toolName,
                        state = ProcessStepState(
                            subtitle = if (info.isSuccess) null else info.result.take(80).ifBlank { null },
                            isFailed = !info.isSuccess && info.result.isNotBlank(),
                            trailing = info.durationMs?.takeIf { it > 0 }?.let { formatToolDuration(it) },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        ToolCallCard(
                            toolName = info.toolName,
                            arguments = info.arguments,
                            result = info.result,
                            isSuccess = info.isSuccess,
                            durationMs = info.durationMs,
                            cardId = m.id.toString(),
                            onCardAction = onCardAction?.let { handler ->
                                { action -> handler(m.id.toString(), action) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else if (reasoningText?.isNotBlank() == true) {
                    // 思考也走同一套可折叠行，但要多做两件事：
                    //  1. 标题按状态说话 —— 还在想就写「思考中…」，别用完成时态
                    //  2. 摘要行下面给一行正文预览，否则一轮四次思考就是四行「思考完成」，
                    //     想看清它想了什么必须逐个点开
                    val thinkingNow = live.isActive && m.id.toString() == live.activeMessageId
                    ProcessStepItem(
                        title = stringResource(
                            if (thinkingNow) {
                                R.string.chat_process_thinking_now
                            } else {
                                R.string.chat_process_thinking_done
                            },
                        ),
                        state = ProcessStepState(
                            // 只在「正在思考」时给预览：历史思考各自挂一行会把列表撑散，
                            // 而且它想出来的东西已经进正文了，再挂一行只是噪音。
                            detailLine = if (thinkingNow) thinkingPreview(reasoningText) else null,
                            // 标题会在「思考中」和「思考完成」之间变，折叠 key 必须用稳定值
                            itemKey = "think_${m.id}",
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Surface(
                            shape = MuseShapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            SelectionContainer {
                                Text(
                                    text = reasoningText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(MusePaddings.iconPadding),
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
 * 一组过程消息的统计口径（对齐平台侧 processFold 的三个计数）。
 *
 * - toolCount：实际调用的工具次数
 * - thinkingCount：带思考内容但有独立段的次数（同一段只算一次）
 * - unsuccessfulCount：已完成但失败的工具次数
 *
 * 第一层摘要只依赖这三个数，展开区才看细节。
 */
internal data class ProcessTally(
    val toolCount: Int,
    val thinkingCount: Int,
    val unsuccessfulCount: Int,
) {
    val isRunning: Boolean get() = toolCount > 0 && unsuccessfulCount < 0

    companion object {
        fun of(msgs: List<UIMessage>): ProcessTally {
            var tools = 0
            var thinking = 0
            var failed = 0
            msgs.forEach { msg ->
                val info = msg.toolCallInfo
                if (info != null) {
                    tools += 1
                    if (!info.isSuccess && info.result.isNotBlank()) failed += 1
                }
                if (!msg.reasoning.isNullOrBlank()) thinking += 1
            }
            return ProcessTally(tools, thinking, failed)
        }
    }
}

/**
 * [ProcessStepItem] 的次要状态。
 *
 * 合成一个对象是为了让单步折叠行的入参保持在静态检查阈值内；
 * 调用方按需填字段，不用的留空。
 */
internal data class ProcessStepState(
    val subtitle: String? = null,
    val isFailed: Boolean = false,
    val trailing: String? = null,
    /**
     * 摘要行下面单独一行正文预览（单行截断）。
     *
     * 与 [subtitle] 的区别：subtitle 与标题同行平分宽度，塞不下长文本；
     * 思考预览需要独立一行，否则标题会被挤没。
     */
    val detailLine: String? = null,
    /** 折叠状态的稳定 key。不传则回退到标题，但标题会变时务必传。 */
    val itemKey: String? = null,
)

/**
 * 过程组内的单步可折叠行。
 *
 * 平台侧的做法是：组本身折叠，组内每一项（一次思考、一次工具调用）也各自折叠，
 * 摘要行常显、正文点击才出。这样一轮跑十几个工具时，展开组不会变成几十行墙。
 *
 * @param title 摘要行主文案（工具名 / 「思考中…」）
 * @param subtitle 可选的次要说明（失败原因片段等）
 * @param isFailed 是否失败态（摘要行用错误色）
 * @param trailing 可选的尾部信息（耗时等）
 * @param content 展开后的正文
 */
@Composable
private fun ProcessStepItem(
    title: String,
    modifier: Modifier = Modifier,
    state: ProcessStepState = ProcessStepState(),
    content: @Composable () -> Unit,
) {
    val subtitle = state.subtitle
    val isFailed = state.isFailed
    val trailing = state.trailing
    val detailLine = state.detailLine
    // key 优先用调用方给的稳定值：思考项标题会在「思考中」和「思考完成」之间变，
    // 拿标题当 key 会让用户刚展开的项在跑完那一刻自己折回去。
    var expanded by rememberSaveable(state.itemKey ?: title) { mutableStateOf(false) }
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                // 失败仍是警告三角（状态优先于类型），正常时按这一步的动作类型给图标。
                imageVector = if (isFailed) MuseIcons.alertTriangle else processIconFor(title),
                contentDescription = null,
                tint = if (isFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = if (isFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (!trailing.isNullOrBlank()) {
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                )
            }
            Icon(
                imageVector = if (expanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(14.dp),
            )
        }
        // 正文预览：不展开也能看到这一步在干什么
        if (!detailLine.isNullOrBlank()) {
            Text(
                text = detailLine,
                style = MaterialTheme.typography.labelSmall,
                color = if (isFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 2.dp, bottom = 2.dp),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = MuseMotion.expandFadeEnter(),
            exit = MuseMotion.expandFadeExit(),
        ) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp)) {
                content()
            }
        }
    }
}

/**
 * v2.6.7: 分组操作卡的摘要头部（图标 + 数量 + 失败标记 + 总耗时 + 折叠箭头）。
 *
 * 从 [ToolRunCard] 抽出：头部本身就是一块独立语义，
 * 也让主函数的 LongMethod 回到阈值内。
 */
@Composable
private fun ToolRunHeaderRow(msgs: List<UIMessage>, toolInfos: List<io.zer0.ai.core.ToolCallInfo>, expanded: Boolean) {
    // 第一层摘要：干了多少事、想了多少次、失败几次，一眼可见。
    // 展开后才看具体每一步 —— 与平台侧的两层结构一致。
    val tally = ProcessTally.of(msgs)
    val counts = buildList {
        if (tally.toolCount > 0) {
            add(stringResource(R.string.chat_process_tool_count, tally.toolCount))
        }
        if (tally.thinkingCount > 0) {
            add(stringResource(R.string.chat_process_thinking_count, tally.thinkingCount))
        }
        if (tally.unsuccessfulCount > 0) {
            add(stringResource(R.string.chat_process_failed_count, tally.unsuccessfulCount))
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = MuseIcons.bolt,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = counts.ifEmpty { listOf(stringResource(R.string.chat_tool_run_summary, msgs.size)) }
                .joinToString(" · "),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // 有失败步骤时给个警告标志,折叠态也能一眼看到
        if (tally.unsuccessfulCount > 0) {
            Icon(
                imageVector = MuseIcons.alertTriangle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(14.dp),
            )
        }
        // 折叠态显示本组工具的总执行耗时：用户能直接判断
        // “慢在工具还是慢在模型”，不用展开就能看到。
        val totalDurationMs = toolInfos.mapNotNull { it.durationMs }.sum()
        if (totalDurationMs > 0) {
            Text(
                text = formatToolDuration(totalDurationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Icon(
            imageVector = if (expanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * 工具执行耗时的可读格式化。
 *
 * 小于 1 秒显示毫秒（如 `320ms`），否则显示秒（一位小数，如 `1.8s`），
 * 超过一分钟显示分秒（如 `1m20s`）。保持短小，不抢工具名与摘要的视觉重心。
 */
internal fun formatToolDuration(durationMs: Long): String = when {
    durationMs < 1000L -> "${durationMs}ms"
    durationMs < 60_000L -> String.format(java.util.Locale.US, "%.1fs", durationMs / 1000.0)
    else -> {
        val totalSec = durationMs / 1000L
        "${totalSec / 60}m${totalSec % 60}s"
    }
}
