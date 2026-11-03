package io.zer0.muse.perf

import io.zer0.ai.core.UIMessage

data class ChatMessageWindowResult(
    val visible: List<UIMessage>,
    val allRenderableCount: Int,
    val projectedRenderableCount: Int,
    val byId: Map<String, UIMessage>,
) {
    val hasHiddenRenderableMessages: Boolean
        get() = visible.size < projectedRenderableCount
}

/**
 * Produces one bounded render window from a conversation snapshot. Filtering,
 * boundary pruning and tail paging happen in one linear pass over the snapshot,
 * and the resulting identity index is shared by the list renderers.
 */
object ChatMessageWindow {
    fun project(
        messages: List<UIMessage>,
        pageSize: Int,
        isRenderable: (UIMessage) -> Boolean,
        boundaryMessageId: String? = null,
        includeBeforeBoundary: Boolean = true,
    ): ChatMessageWindowResult {
        require(pageSize > 0) { "page size must be positive" }
        val renderable = messages.filter(isRenderable)
        val boundaryIndex =
            boundaryMessageId?.let { id -> renderable.indexOfFirst { it.id.toString() == id } } ?: -1
        val pruned =
            if (boundaryIndex > 0 && !includeBeforeBoundary) {
                renderable.subList(boundaryIndex, renderable.size)
            } else {
                renderable
            }
        val visible =
            if (pruned.size <= pageSize) {
                pruned
            } else {
                pruned.subList(pruned.size - pageSize, pruned.size)
            }
        val referencedIds = visible.mapNotNullTo(HashSet()) { it.translationSourceId }
        val byId = LinkedHashMap<String, UIMessage>(visible.size + referencedIds.size)
        visible.forEach { message -> byId[message.id.toString()] = message }
        if (referencedIds.isNotEmpty()) {
            messages.forEach { message ->
                val id = message.id.toString()
                if (id in referencedIds) byId[id] = message
            }
        }
        return ChatMessageWindowResult(
            visible = visible,
            allRenderableCount = renderable.size,
            projectedRenderableCount = pruned.size,
            byId = byId,
        )
    }
}
