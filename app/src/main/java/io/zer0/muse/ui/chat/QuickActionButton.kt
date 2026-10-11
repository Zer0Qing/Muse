@file:Suppress("LongParameterList", "CyclomaticComplexMethod", "FunctionNaming")

package io.zer0.muse.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.zer0.ai.core.UIMessage
import io.zer0.muse.transformer.MoodSkinParser
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.messageCopyText
import io.zer0.muse.ui.theme.MuseIconSizes

/**
 * v2.6.0: 快捷排单个动作按钮。
 *
 * 把原先散落在 MessageBubble 里的每个按钮渲染与点击行为收拢到一处，
 * 与 [QuickAction] 全量池一一对应。样式与原快捷排完全一致（outline 色、
 * touchTarget 尺寸、iconSmall 图标），行为与原实现逐一对齐：
 * - COPY: 复制正文（含 reasoning 拼接逻辑由调用方 messageCopyText 处理）
 * - REASONING: 仅复制思考过程（清洗后）
 * - DELETE: 触发二次确认（破坏性，红色）
 * - 其余动作与长按「更多」面板同名项行为一致
 */
@Composable
internal fun QuickActionButton(
    action: QuickAction,
    msg: UIMessage,
    isSpeaking: Boolean,
    favorite: Boolean,
    onCopyMessage: (String) -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    onQuote: () -> Unit,
    onAnnotate: () -> Unit,
    onDelegate: () -> Unit,
    onFork: () -> Unit,
    onMultiSelect: () -> Unit,
    onInfo: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleTts: () -> Unit,
    onEdit: () -> Unit,
    onForward: () -> Unit,
    onTranslate: () -> Unit,
    onShare: () -> Unit,
) {
    val description = when (action) {
        QuickAction.REASONING ->
            stringResource(io.zer0.muse.R.string.action_copy) + " " +
                stringResource(QuickAction.REASONING.labelRes)
        QuickAction.TTS ->
            if (isSpeaking) {
                stringResource(io.zer0.muse.R.string.chat_tts_stop)
            } else {
                stringResource(io.zer0.muse.R.string.chat_tts_play)
            }
        QuickAction.FAVORITE ->
            if (favorite) {
                stringResource(io.zer0.muse.R.string.chat_favorite_remove)
            } else {
                stringResource(io.zer0.muse.R.string.chat_favorite_add)
            }
        else -> stringResource(action.labelRes)
    }
    val tint = when (action) {
        QuickAction.DELETE -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    val icon = when (action) {
        QuickAction.FAVORITE -> MuseIcons.star
        QuickAction.TTS -> MuseIcons.play
        else -> action.icon
    }
    MuseTactileButton(
        icon = icon,
        onClick = {
            when (action) {
                QuickAction.COPY -> onCopyMessage(messageCopyText(msg.content, msg.reasoning))
                QuickAction.REASONING -> onCopyMessage(MoodSkinParser.cleanForExport(msg.reasoning.orEmpty()))
                QuickAction.TRANSLATE -> onTranslate()
                QuickAction.SHARE -> onShare()
                QuickAction.REGENERATE -> onRegenerate()
                QuickAction.DELETE -> onDelete()
                QuickAction.QUOTE -> onQuote()
                QuickAction.ANNOTATE -> onAnnotate()
                QuickAction.DELEGATE -> onDelegate()
                QuickAction.FORK -> onFork()
                QuickAction.MULTI_SELECT -> onMultiSelect()
                QuickAction.INFO -> onInfo()
                QuickAction.FAVORITE -> onToggleFavorite()
                QuickAction.TTS -> onToggleTts()
                QuickAction.EDIT -> onEdit()
                QuickAction.FORWARD -> onForward()
            }
        },
        contentDescription = description,
        tint = tint,
        size = MuseIconSizes.touchTarget,
        iconSize = MuseIconSizes.iconSmall,
    )
}
