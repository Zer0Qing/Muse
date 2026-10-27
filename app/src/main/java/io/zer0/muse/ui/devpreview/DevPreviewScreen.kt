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
 * 上线前应连同路由一起删除。
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
            text = "MOOD / 思考卡 预览（开发用）",
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
    content = "我把设置页按用户意图重排成 11 组了，一级入口从 34 个增到 48 个。\n\n" +
        "顺手修了两个以前根本进不去的设置页。",
    reasoning = "先确认现有层级和真实字段数量对不对得上。" +
        "发现一级只有 7 组、可见入口 34 个，但落库字段有 275 个，" +
        "说明界面层级是代码模块结构的投影，不是用户意图的投影。",
    mood = SAMPLE_MOOD,
    modelId = "preview",
)

/** 预览用样本：只有思考 + 一条工具调用，用来展示过程摘要行。 */
private fun sampleToolMessage(): UIMessage = UIMessage(
    role = MessageRole.ASSISTANT,
    content = "",
    reasoning = "先读一遍现有设置页的分组，再决定怎么并。",
    modelId = "preview",
    toolCallInfo = io.zer0.ai.core.ToolCallInfo(
        toolName = "update_settings",
        arguments = "{\"group\":\"look\"}",
        result = "ok",
        isSuccess = true,
        durationMs = 1_200L,
    ),
)

private const val SAMPLE_MOOD = "Vibe: 这一版改完，聊天页终于不像一堆卡片堆着了\n" +
    "Sparks:\n" +
    "  - 四段 mood 拆开之后，Sparks 那三条才有地方站\n" +
    "  - 思考卡和工具卡应该像一条流水，而不是两块积木\n" +
    "  - 摘要行用「思考了一阵」比截取前 40 字更像人话\n" +
    "Reflections:\n" +
    "  - 折叠标题带操作数，用户能一眼看出这一步干了多少事\n" +
    "  - 计划卡保持独立是对的，它是干活时最该盯的东西\n" +
    "Will:\n" +
    "  - 想把这套排版稳定下来\n" +
    "  - 不想再加新卡片了"
