@file:Suppress("FunctionNaming")

package io.zer0.muse.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isUnspecified
import io.zer0.muse.R
import io.zer0.muse.data.ChatPreferences
import io.zer0.muse.ui.theme.MuseBubbleStyles
import io.zer0.muse.ui.theme.MusePaddings

/** v2.5.0: 预览正文样式 — 与 MessageBubble 正文排版同源(字号缩放 + 字间距)。 */
@Composable
private fun previewBodyStyle(prefs: ChatPreferences): TextStyle {
    val bodyBase = MaterialTheme.typography.bodyMedium
    val bodyScale = prefs.messageFontScale.coerceIn(0.85f, 1.3f)
    return bodyBase.copy(
        fontSize = bodyBase.fontSize * bodyScale,
        lineHeight = bodyBase.lineHeight.let { if (it.isUnspecified) it else it * bodyScale },
        letterSpacing = prefs.messageLetterSpacingEm.coerceIn(-0.02f, 0.1f).em,
    )
}

/**
 * v2.5.0: 聊天排版预览面板 — 设置页里实时呈现当前聊天外观设置的合成效果。
 *
 * 覆盖：消息字号/字间距、气泡圆角/通栏、MOOD/思考块显示、时间戳、模型名。
 * 静态演示数据；样式计算与 MessageBubble 同源(MuseBubbleStyles + 同一排版缩放)，
 * 保证"所见即所得"。
 */
@Composable
internal fun BubblePreviewPanel(prefs: ChatPreferences, modifier: Modifier = Modifier) {
    val bodyStyle = previewBodyStyle(prefs)
    Column(modifier = modifier.fillMaxWidth().padding(MusePaddings.cardInner)) {
        PreviewUserBubble(prefs, bodyStyle)
        Spacer(Modifier.height(10.dp))
        PreviewAssistantBubble(prefs, bodyStyle)
    }
}

/** 预览：用户消息（右侧,主色浅底,圆角/通栏/时间戳实时反映）。 */
@Composable
private fun PreviewUserBubble(prefs: ChatPreferences, bodyStyle: TextStyle) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        val shape = MuseBubbleStyles.userBubbleShape(prefs.bubbleRadius.toFloat())
        Column(
            modifier = Modifier
                .then(if (prefs.bubbleFullWidth) Modifier.fillMaxWidth() else Modifier.widthIn(max = 260.dp))
                .clip(shape)
                .background(MuseBubbleStyles.userSurfaceColor())
                .border(1.dp, MuseBubbleStyles.userBorderColor(), shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_preview_user_bubble),
                style = bodyStyle,
                color = MuseBubbleStyles.userContentColor(),
            )
            if (prefs.showTimestamp) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.settings_preview_timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 预览：AI 消息（左侧,浅色卡片；MOOD/思考标签行与模型名按开关显示）。 */
@Composable
private fun PreviewAssistantBubble(prefs: ChatPreferences, bodyStyle: TextStyle) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        val shape = MuseBubbleStyles.assistantBubbleShape(prefs.bubbleRadius.toFloat())
        Column(
            modifier = Modifier
                .then(if (prefs.bubbleFullWidth) Modifier.fillMaxWidth() else Modifier.widthIn(max = 260.dp))
                .clip(shape)
                .background(MuseBubbleStyles.assistantSurfaceColor())
                .border(1.dp, MuseBubbleStyles.assistantBorderColor(), shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (prefs.showMoodBlock || prefs.showReasoning) {
                val tags = buildList {
                    if (prefs.showMoodBlock) add("MOOD")
                    if (prefs.showReasoning) add(stringResource(R.string.chat_reasoning_title))
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 4.dp),
                ) {
                    tags.forEach { tag ->
                        Text(
                            text = tag,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.settings_preview_assistant_bubble),
                style = bodyStyle,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (prefs.showModelName) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_preview_model_name),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
