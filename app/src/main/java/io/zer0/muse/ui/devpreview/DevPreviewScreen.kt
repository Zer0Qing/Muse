package io.zer0.muse.ui.devpreview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.ChatPreferences
import io.zer0.muse.ui.MessageBubble
import io.zer0.muse.ui.theme.MusePaddings

/**
 * 开发期预览页：用真实 [MessageBubble] 渲染一份带 MOOD / 思考 / 工具调用的消息。
 *
 * 存在的唯一目的：在没有配置供应商、也没有真实会话的情况下，
 * 也能在模拟器上看到消息气泡的实际排版，用来验收 MOOD 分节、
 * 思考卡摘要行等改动。不参与正式导航，只能由 adb 直接拉起。
 *
 * 此页不属于正式导航，只由本地调试入口使用。
 */
@Composable
fun DevPreviewScreen() {
    var moodExpanded by remember { mutableStateOf(true) }
    var reasoningExpanded by remember { mutableStateOf(true) }

    val prefs = remember {
        ChatPreferences(
            showMoodBlock = true,
            showReasoning = true,
            moodExpandedByDefault = true,
            reasoningExpandedByDefault = true,
            showTokenEstimate = true,
            showModelName = true,
        )
    }

    val sample = remember { sampleMessage() }

    val toolSample = remember { sampleToolMessage() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(vertical = MusePaddings.sectionGap),
    ) {
        Text(
            text = "MOOD / THINKING CARD PREVIEW (DEBUG)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = MusePaddings.screen, vertical = 8.dp),
        )
        MessageBubble(
            msg = sample,
            isStreaming = false,
            isLastAssistant = false,
            isTranslating = false,
            onEdit = {},
            onQuote = {},
            onRegenerate = {},
            onTranslate = {},
            modelName = "muse-preview",
            chatPrefs = prefs,
            isMoodExpanded = moodExpanded,
            isReasoningExpanded = reasoningExpanded,
            onToggleMoodExpanded = { moodExpanded = !moodExpanded },
            onToggleReasoningExpanded = { reasoningExpanded = !reasoningExpanded },
        )
        MessageBubble(
            msg = toolSample,
            isStreaming = false,
            isLastAssistant = false,
            isTranslating = false,
            onEdit = {},
            onQuote = {},
            onRegenerate = {},
            onTranslate = {},
            modelName = "muse-preview",
            chatPrefs = prefs,
            isReasoningExpanded = false,
            onToggleReasoningExpanded = {},
        )
        // 过程动画：与聊天页消息流末尾同一组件
        io.zer0.muse.ui.ThinkingBubble(progressText = null)
    }
}

/** 预览用样本：带 MOOD 四段 + 思考正文。 */
private fun sampleMessage(): UIMessage = UIMessage(
    role = MessageRole.ASSISTANT,
    content = "Settings now follow user intent across 12 categories.\n\n" +
        "Long messages are split into independently rendered sections.",
    reasoning = "Check the real hierarchy and field counts before reorganizing." +
        "Then verify that headers, bodies, and actions can be reused independently.",
    mood = SAMPLE_MOOD,
    modelId = "preview",
)

/** 预览用样本：只有思考 + 一条工具调用，用来展示过程摘要行。 */
private fun sampleToolMessage(): UIMessage = UIMessage(
    role = MessageRole.ASSISTANT,
    content = "",
    reasoning = "Read the current settings groups before merging them.",
    modelId = "preview",
    toolCallInfo = io.zer0.ai.core.ToolCallInfo(
        toolName = "update_settings",
        arguments = "{\"group\":\"look\"}",
        result = "ok",
        isSuccess = true,
        durationMs = 1_200L,
    ),
)

private const val SAMPLE_MOOD = "Vibe: The chat page should feel like a conversation, not a stack of cards\n" +
    "Sparks:\n" +
    "  - Four mood sections leave space for the key ideas\n" +
    "  - Thinking and tool cards should read as one continuous flow\n" +
    "  - A short summary is clearer than a truncated paragraph\n" +
    "Reflections:\n" +
    "  - Collapsed headers expose the operation count at a glance\n" +
    "  - The plan card should remain visible while work is running\n" +
    "Will:\n" +
    "  - Keep this rendering structure stable\n" +
    "  - Avoid adding more card types"
