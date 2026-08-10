@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "CyclomaticComplexMethod", "TooManyFunctions")

package io.zer0.muse.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import io.zer0.muse.ui.common.surface.MuseDivider
import androidx.compose.foundation.shape.CircleShape
import compose.icons.TablerIcons
import compose.icons.tablericons.Photo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.common.media.AssistantAvatar
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.ui.theme.MusePaddings

/**
 * v0.29 P0-1: 空聊天引导 — 轻量居中提示 + 建议 prompt 胶囊。
 *
 * 设计(iOS 风格空状态):
 *  - 不覆盖全屏(Box + CenterAlignment,只占居中区域,不拦截 InputBar)
 *  - 居中品牌图标 + 一句引导语
 *  - 下方 FlowRow 排列建议 prompt 胶囊,点击即填入输入框
 *  - 胶囊用 surfaceVariant 背景,轻量不抢眼
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun EmptyChatGuide(
    onPickPrompt: (String) -> Unit,
    modifier: Modifier = Modifier,
    assistant: io.zer0.muse.data.assistant.AssistantEntity? = null,
    // v1.0.72: 本会话不参考记忆开关
    ignoreMemory: Boolean = false,
    onToggleIgnoreMemory: (Boolean) -> Unit = {},
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MusePaddings.screen),
    ) {
        // 头像:助手有自定义头像(图片或 Emoji)用助手头像,否则用项目图标
        val currentAssistant = assistant
        val avatarCd = if (currentAssistant != null) stringResource(R.string.chat_avatar_assistant_cd)
        else stringResource(R.string.chat_avatar_muse_cd)
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                    shape = androidx.compose.foundation.shape.CircleShape,
                )
                .clearAndSetSemantics {
                    contentDescription = avatarCd
                },
            contentAlignment = Alignment.Center,
        ) {
            if (currentAssistant != null &&
                (currentAssistant.hasImageAvatar() || currentAssistant.avatarEmoji.isNotBlank())
            ) {
                io.zer0.muse.ui.common.media.AssistantAvatar(
                    assistant = currentAssistant,
                    avatarSize = 48.dp,
                )
            } else {
                Image(
                    painter = painterResource(R.drawable.ic_muse_logo),
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape),
                )
            }
        }
        // 引导语
        Text(
            text = stringResource(R.string.chat_empty_guide_title),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 建议 prompt 胶囊(FlowRow 自动换行)
        val prompts = listOf(
            stringResource(R.string.chat_suggested_prompt_report),
            stringResource(R.string.chat_suggested_prompt_summary),
            stringResource(R.string.chat_suggested_prompt_explain),
            stringResource(R.string.chat_suggested_prompt_ideas),
        )
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            prompts.forEach { prompt ->
                Surface(
                    onClick = { onPickPrompt(prompt) },
                    shape = MuseShapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                ) {
                    Text(
                        text = prompt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = MusePaddings.contentGap),
                    )
                }
            }
        }

        // v1.0.72: 此条对话不参考记忆 — 开关式胶囊选项
        // 开启后本会话不注入任何记忆(用户画像/置顶/长期记忆/群聊记忆/经验库),从零开始
        Surface(
            onClick = { onToggleIgnoreMemory(!ignoreMemory) },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(percent = 50),
            color = if (ignoreMemory) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = MusePaddings.contentGap),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 开关状态圆点(开启 = 实心主色,关闭 = 空心)
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            color = if (ignoreMemory) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                            shape = CircleShape,
                        ),
                )
                Text(
                    text = stringResource(R.string.chat_ignore_memory_option),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (ignoreMemory) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/**
 * 任务 2B: shimmer 骨架屏气泡占位。
 *
 * v1.0.3 改进:
 *  - 顶部加三个跳动圆点 + "思考中"文字,给用户明确的语义反馈(原纯 shimmer 缺少文字提示)
 *  - 三行圆角条配合从左到右的扫光渐变,营造"AI 正在写"的呼吸感
 *  - "思考中"文字带脉冲呼吸动画(alpha 0.5↔1.0),比静态文字更有活力
 *  - 整体布局: [三点动画] / "思考中" / [三行 shimmer 条]
 * v1.0.4 改进:
 *  - 新增 [progressText] 参数,视觉分析阶段显示"正在分析图片 2/4…"等进度文字(替代"思考中")
 */
@Composable
internal fun ShimmerBubble(progressText: String? = null) {
    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
    )
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer",
    )
    // v1.0.3: "思考中"文字的脉冲呼吸动画
    val textAlpha by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "text_alpha",
    )
    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnim - 200f, 0f),
        end = Offset(translateAnim + 200f, 0f),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInnerSpaced),
    ) {
        Spacer(Modifier.width(32.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // v1.0.3: 顶部 — 三个跳动圆点 + "思考中"文字
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                // 三个圆点共享一个 transition,依次缩放/淡入淡出
                repeat(3) { index ->
                    val dotScale by transition.animateFloat(
                        initialValue = 0.6f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(600, delayMillis = index * 120, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "dot_scale_$index",
                    )
                    val dotAlpha by transition.animateFloat(
                        initialValue = 0.4f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(600, delayMillis = index * 120, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "dot_alpha_$index",
                    )
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .then(
                                Modifier.graphicsLayer {
                                    scaleX = dotScale
                                    scaleY = dotScale
                                    alpha = dotAlpha
                                },
                            )
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
                Spacer(Modifier.width(MusePaddings.tinyGap))
                // v1.0.4: 视觉分析阶段优先显示进度文字(如"正在分析图片 2/4…"),否则回退"思考中"
                val defaultThinking = stringResource(R.string.chat_loading_thinking)
                Text(
                    text = progressText ?: defaultThinking,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = textAlpha),
                )
            }
            // 三行 shimmer 骨架条
            repeat(3) { index ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth(if (index == 2) 0.6f else 1f)
                        .height(MusePaddings.itemGap)
                        .clip(MuseShapes.extraSmall)
                        .background(brush),
                )
            }
        }
    }
}

/**
 * P5-G: 图片生成中占位卡片。
 *
 * 用圆角矩形 shimmer + 图片图标 + "生成图片中…" 文案,
 * 让用户明确知道正在绘图而不是卡死。
 * v1.0.4 (P2): 顶部加三个跳动圆点,与 ShimmerBubble 视觉一致,强化"正在进行"语义。
 */
@Composable
internal fun ImageGenerationPlaceholder() {
    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    )
    val transition = rememberInfiniteTransition(label = "image_shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "image_shimmer",
    )
    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnim - 200f, 0f),
        end = Offset(translateAnim + 200f, 0f),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInnerSpaced),
    ) {
        Spacer(Modifier.width(32.dp))
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // v1.0.4 (P2): 顶部三圆点跳动(复用 ShimmerBubble 同款动画,改 label 避免冲突)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(3) { index ->
                    val dotScale by transition.animateFloat(
                        initialValue = 0.6f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(600, delayMillis = index * 120, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "img_dot_scale_$index",
                    )
                    val dotAlpha by transition.animateFloat(
                        initialValue = 0.4f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(600, delayMillis = index * 120, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "img_dot_alpha_$index",
                    )
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .then(
                                Modifier.graphicsLayer {
                                    scaleX = dotScale
                                    scaleY = dotScale
                                    alpha = dotAlpha
                                },
                            )
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
            Spacer(Modifier.height(MusePaddings.contentGap))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(MuseShapes.semiLarge)
                    .background(brush),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    Icon(
                        imageVector = TablerIcons.Photo,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(40.dp),
                    )
                    Text(
                        text = stringResource(R.string.chat_image_generating),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

/**
 * v1.0.4 (P1): 视频生成中占位卡片。
 *
 * 与 [ImageGenerationPlaceholder] 对称:圆角矩形 shimmer + 播放图标 + "正在生成视频,可能需要几十秒…"文案,
 * 让用户在聊天主流程内明确感知 LLM 调用 generate_video 工具时的长任务进度。
 * (execGenerateVideo 内部还会通过 updateAssistant 在助手消息气泡里同步"已等待 N 秒…",本占位是补充反馈)
 */
@Composable
internal fun VideoGenerationPlaceholder() {
    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    )
    val transition = rememberInfiniteTransition(label = "video_shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "video_shimmer",
    )
    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnim - 200f, 0f),
        end = Offset(translateAnim + 200f, 0f),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInnerSpaced),
    ) {
        Spacer(Modifier.width(32.dp))
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(MuseShapes.semiLarge)
                    .background(brush),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(40.dp),
                    )
                    Text(
                        text = stringResource(R.string.chat_video_generating),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

/**
 * v1.0.4 (P1): 历史加载更多顶部占位条。
 *
 * 上滑触发 loadMoreHistory 后,LazyColumn 顶部插入此占位,让用户看到"正在加载更多历史…"反馈。
 * 与 [ShimmerBubble] 风格一致(三行圆角 shimmer 条 + 文案),但去掉圆点和气泡,做成顶部细条。
 * 占位在 isLoadingMore=true 时显示,加载完成(lastHistoryLoadCount > 0)后由 scrollToItem 移除。
 */
@Composable
internal fun HistoryLoadMorePlaceholder() {
    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
    )
    val transition = rememberInfiniteTransition(label = "load_more_shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "load_more_shimmer",
    )
    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnim - 200f, 0f),
        end = Offset(translateAnim + 200f, 0f),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInnerSpaced),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            strokeWidth = 2.dp,
            modifier = Modifier.size(MusePaddings.screen),
        )
        Spacer(Modifier.width(MusePaddings.contentGap))
        Text(
            text = stringResource(R.string.chat_loading_more_history),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(MusePaddings.contentGap))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(MuseShapes.extraSmall)
                .background(brush),
        )
    }
}

/**
 * 日期分隔线 — 细线 + 居中日期文字,用于消息列表跨天分组。
 * 当天消息显示“今天 HH:mm”,其他日期显示“MM-dd HH:mm”,颜色更淡。
 */
@Composable
internal fun DateSeparator(timestamp: Long) {
    val now = System.currentTimeMillis()
    val isToday = remember(timestamp) { isSameDay(timestamp, now) }
    val dateText = remember(timestamp, isToday) {
        val timeSdf = java.text.SimpleDateFormat(MuseDateFormats.TIME_SHORT, java.util.Locale.getDefault())
        val timeText = timeSdf.format(java.util.Date(timestamp))
        if (isToday) {
            "今天 $timeText"
        } else {
            val dateSdf = java.text.SimpleDateFormat(MuseDateFormats.DATE_TIME_SHORT, java.util.Locale.getDefault())
            dateSdf.format(java.util.Date(timestamp))
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MusePaddings.itemGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
    ) {
        MuseDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
        )
        Text(
            text = dateText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
        )
        MuseDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
        )
    }
}

/**
 * 判断两个时间戳是否在同一天。
 */
internal fun isSameDay(ts1: Long, ts2: Long): Boolean {
    val cal1 = java.util.Calendar.getInstance().apply { timeInMillis = ts1 }
    val cal2 = java.util.Calendar.getInstance().apply { timeInMillis = ts2 }
    return cal1.get(java.util.Calendar.YEAR) == cal2.get(java.util.Calendar.YEAR) &&
        cal1.get(java.util.Calendar.DAY_OF_YEAR) == cal2.get(java.util.Calendar.DAY_OF_YEAR)
}

/** B7-01: 消息多选操作条。 */
@Composable
internal fun ChatSelectionBar(
    count: Int,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shadowElevation = 4.dp,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = MusePaddings.contentGap,
                vertical = MusePaddings.tightGap,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.groupchat_selected_members, count),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSelectAll) {
                Text(stringResource(R.string.settings_provider_select_all))
            }
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.chat_delete_message))
            }
            TextButton(onClick = onExport) {
                Text(stringResource(R.string.action_share))
            }
            TextButton(onClick = onExit) {
                Text(stringResource(R.string.action_close))
            }
        }
    }
}
