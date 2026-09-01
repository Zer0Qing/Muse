package io.zer0.muse.ui

import io.zer0.muse.ui.theme.MuseMotion
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import compose.icons.TablerIcons
import compose.icons.tablericons.AlertTriangle
import compose.icons.tablericons.Lock
import compose.icons.tablericons.MessageCircle
import io.zer0.muse.R
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes

/**
 * v1.0.47 P6: Agent Mode 增强提示卡片。
 *
 * 三种状态(可叠加):
 *  - 会话锁定:Agent 模式开启后会话锁定,仅显示状态,不可关闭
 *  - 弱工具降级:当前模型工具调用能力弱,已自动降级为串行,可关闭
 *  - Agent Mode 提示:其他 Agent Mode 相关提示,可关闭
 *
 * 视觉风格沿用 ChatScreen 顶部 Banner(tertiaryContainer + MuseShapes.medium),
 * 由 [ChatScreen] 在消息列表上方根据 [ChatUiState] 字段条件渲染。
 */
@Composable
fun AgentModeHintCard(
    isSessionLocked: Boolean,
    weakToolHint: String?,
    agentModeHint: String?,
    onDismissWeakToolHint: () -> Unit,
    onDismissAgentModeHint: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        // 1) 会话锁定状态(常驻,不可关闭)
        AnimatedVisibility(
            visible = isSessionLocked,
            enter = MuseMotion.expandFadeEnter(),
            exit = MuseMotion.expandFadeExit(),
        ) {
            HintRow(
                icon = TablerIcons.Lock,
                title = stringResource(R.string.chat_agent_session_locked),
                titleColor = MaterialTheme.colorScheme.onSecondaryContainer,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            )
        }

        // 2) 弱工具降级提示(可关闭)
        AnimatedVisibility(
            visible = !weakToolHint.isNullOrEmpty(),
            enter = MuseMotion.expandFadeEnter(),
            exit = MuseMotion.expandFadeExit(),
        ) {
            HintRow(
                icon = TablerIcons.AlertTriangle,
                title = stringResource(R.string.chat_agent_weak_tool_title),
                subtitle = weakToolHint,
                titleColor = MaterialTheme.colorScheme.onErrorContainer,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                action = {
                    TextButton(onClick = onDismissWeakToolHint) {
                        Text(stringResource(R.string.chat_agent_hint_dismiss))
                    }
                },
            )
        }

        // 3) Agent Mode 一般提示(可关闭)
        AnimatedVisibility(
            visible = !agentModeHint.isNullOrEmpty(),
            enter = MuseMotion.expandFadeEnter(),
            exit = MuseMotion.expandFadeExit(),
        ) {
            HintRow(
                icon = TablerIcons.MessageCircle,
                title = stringResource(R.string.chat_agent_weak_tool_title),
                subtitle = agentModeHint,
                titleColor = MaterialTheme.colorScheme.onTertiaryContainer,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                action = {
                    TextButton(onClick = onDismissAgentModeHint) {
                        Text(stringResource(R.string.chat_agent_hint_dismiss))
                    }
                },
            )
        }
    }
}

@Composable
private fun HintRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    titleColor: androidx.compose.ui.graphics.Color,
    containerColor: androidx.compose.ui.graphics.Color,
    action: (@Composable () -> Unit)? = null,
) {
    Surface(
        color = containerColor,
        shape = MuseShapes.medium,
        tonalElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MusePaddings.itemGap),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.itemGap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = titleColor,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = titleColor,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        color = titleColor,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            action?.invoke()
        }
    }
}
