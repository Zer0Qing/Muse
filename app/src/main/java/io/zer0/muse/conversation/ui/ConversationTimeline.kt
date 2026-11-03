package io.zer0.muse.conversation.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.zer0.muse.conversation.core.RenderBlock
import io.zer0.muse.conversation.core.SlotKind
import io.zer0.muse.conversation.render.LegacyConversationProjection
import io.zer0.muse.conversation.render.LegacyRenderSlotKind
import io.zer0.muse.ui.theme.MusePaddings

sealed interface ConversationTimelineRow {
    val rowId: String
    val messageId: String

    data class Markdown(
        override val rowId: String,
        override val messageId: String,
        val block: RenderBlock,
    ) : ConversationTimelineRow

    data class Rich(
        override val rowId: String,
        override val messageId: String,
        val kind: LegacyRenderSlotKind,
        val text: String,
        val payload: LegacyConversationProjection,
    ) : ConversationTimelineRow
}

object ConversationTimelineRows {
    fun project(
        slotId: String,
        kind: SlotKind,
        projection: LegacyConversationProjection,
    ): List<ConversationTimelineRow> {
        require(slotId.isNotBlank()) { "slot id must not be blank" }
        return buildList {
            projection.slots.forEachIndexed { index, slot ->
                if (slot.kind == LegacyRenderSlotKind.Body) {
                    projection.markdown.forEachIndexed { markdownIndex, block ->
                        add(
                            ConversationTimelineRow.Markdown(
                                rowId = "$slotId:${kind.name.lowercase()}:md:$markdownIndex:${block.blockId}",
                                messageId = projection.messageId,
                                block = block,
                            ),
                        )
                    }
                } else {
                    val text = when (slot.kind) {
                        LegacyRenderSlotKind.Attachments ->
                            projection.attachments.joinToString(", ") { it.name }
                        LegacyRenderSlotKind.Artifacts ->
                            projection.artifactIds.joinToString(", ")
                        LegacyRenderSlotKind.Translations ->
                            projection.translations.values.joinToString("\n")
                        else -> slot.text
                    }
                    if (
                        text.isNotBlank()
                    ) {
                        add(
                            ConversationTimelineRow.Rich(
                                rowId = "$slotId:${kind.name.lowercase()}:rich:$index:${slot.kind.name}",
                                messageId = projection.messageId,
                                kind = slot.kind,
                                text = text,
                                payload = projection,
                            ),
                        )
                    }
                }
            }
            if (projection.markdown.isNotEmpty() && projection.slots.none { it.kind == LegacyRenderSlotKind.Body }) {
                projection.markdown.forEachIndexed { index, block ->
                    add(
                        ConversationTimelineRow.Markdown(
                            rowId = "$slotId:${kind.name.lowercase()}:md:$index:${block.blockId}",
                            messageId = projection.messageId,
                            block = block,
                        ),
                    )
                }
            }
        }.also { rows ->
            val duplicateIds = rows.groupingBy { it.rowId }.eachCount().filterValues { it > 1 }
            check(duplicateIds.isEmpty()) { "duplicate timeline row ids: ${duplicateIds.keys}" }
        }
    }

}

/**
 * One vertical lazy list over render blocks. Message boundaries are represented
 * by rows rather than nested scroll containers, so a long message cannot force
 * every paragraph or table row to compose at once.
 */
@Composable
fun ConversationTimeline(
    rows: List<RenderBlock>,
    modifier: Modifier = Modifier,
) {
    val timelineRows =
        rows.map { block ->
            ConversationTimelineRow.Markdown(
                rowId = block.blockId,
                messageId = block.blockId,
                block = block,
            )
        }
    ConversationRichTimeline(rows = timelineRows, modifier = modifier)
}

@Composable
fun ConversationRichTimeline(
    rows: List<ConversationTimelineRow>,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(MusePaddings.messageGap),
    ) {
        items(
            items = rows,
            key = { row -> row.rowId },
            contentType = { row -> row::class },
        ) { row ->
            when (row) {
                is ConversationTimelineRow.Rich ->
                    RichTimelineRow(row)
                is ConversationTimelineRow.Markdown ->
                    when (val block = row.block) {
                        is RenderBlock.Paragraph ->
                            Text(
                                text = block.text,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxSize().paddingVertical(4.dp),
                            )
                        is RenderBlock.Code ->
                            Text(
                                text = block.text,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                ),
                                modifier = Modifier.fillMaxSize().paddingVertical(4.dp),
                            )
                    }
            }
        }
    }
}

@Composable
private fun RichTimelineRow(row: ConversationTimelineRow.Rich) {
    if (row.text.isBlank()) return
    Text(
        text = row.text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxSize().paddingVertical(4.dp),
    )
}

private fun Modifier.paddingVertical(value: androidx.compose.ui.unit.Dp): Modifier = padding(vertical = value)
