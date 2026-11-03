package io.zer0.muse.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isUnspecified
import io.zer0.muse.data.ChatPreferences
import io.zer0.muse.ui.chat.StickerAwareMarkdownBody
import io.zer0.muse.ui.chat.buildHighlightedText
import io.zer0.muse.ui.theme.MusePaddings

/**
 * 已由首条交互外壳接管操作能力的后续长文块。这里只承担正文组合，
 * 让每个块可以被 LazyColumn 独立测量。
 */
@Composable
internal fun ChatRenderContinuationRow(
    row: ChatMessageRenderRow,
    chatPreferences: ChatPreferences,
    highlightText: String?,
    onHtmlPreview: (String) -> Unit,
    onCardAction: ((io.zer0.muse.ui.markdown.CardAction) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    require(row.kind == ChatMessageRenderRowKind.Body) { "continuation row must be a body row" }
    val chunk = requireNotNull(row.chunk) { "continuation row requires a chunk" }
    require(chunk.text.isNotBlank()) { "continuation chunk must not be blank" }
    val message = requireNotNull(row.message) { "continuation row must retain its source message" }
    require(!row.ownsInteraction) { "continuation rows must not own message interactions" }
    val bodyBase = MaterialTheme.typography.bodyMedium
    val bodyScale = chatPreferences.messageFontScale.coerceIn(0.85f, 1.3f)
    val bodyStyle = bodyBase.copy(
        fontSize = bodyBase.fontSize * bodyScale,
        lineHeight = bodyBase.lineHeight.let { if (it.isUnspecified) it else it * bodyScale },
        letterSpacing = chatPreferences.messageLetterSpacingEm.coerceIn(-0.02f, 0.1f).em,
    )
    val rowModifier =
        modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen)
            .padding(bottom = MusePaddings.messageGap / 2)
    val highlightQuery = highlightText?.takeIf { it.isNotBlank() }
    if (highlightQuery != null && chunk.text.contains(highlightQuery, ignoreCase = true)) {
        Text(
            text = buildHighlightedText(chunk.text, highlightQuery),
            style = bodyStyle,
            color = MaterialTheme.colorScheme.onSurface,
            overflow = TextOverflow.Clip,
            modifier = rowModifier.semantics { stateDescription = "search-highlighted" },
        )
    } else {
        StickerAwareMarkdownBody(
            text = chunk.text,
            stickerSeed = message.id.hashCode().toLong(),
            style = bodyStyle,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = rowModifier,
            isStreaming = false,
            onHtmlPreview = onHtmlPreview,
            onCardAction = onCardAction,
            cardId = message.id.toString(),
        )
    }
}
