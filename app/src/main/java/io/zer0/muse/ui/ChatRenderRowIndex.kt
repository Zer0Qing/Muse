package io.zer0.muse.ui

internal object ChatRenderRowIndex {
    fun firstRowForMessage(rows: List<ChatMessageRenderRow>, messageIndex: Int, fallback: Int = messageIndex): Int =
        rows.indexOfFirst { it.messageIndex == messageIndex }
            .takeIf { it >= 0 }
            ?: fallback

    fun lastRowForMessage(rows: List<ChatMessageRenderRow>, messageIndex: Int, fallback: Int = messageIndex): Int =
        rows.indexOfLast { it.messageIndex == messageIndex }
            .takeIf { it >= 0 }
            ?: fallback

    fun messageAtRow(rows: List<ChatMessageRenderRow>, rowIndex: Int): Int? = rows.getOrNull(rowIndex)?.messageIndex
}
