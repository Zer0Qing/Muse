package io.zer0.muse.web.a2a

/**
 * Bounded LRU mapping from protocol context ids to handler-owned Agent sessions.
 *
 * A2A is authenticated but a remote client can still request many fresh contexts; bounding this map
 * lets the caller delete the evicted hidden session instead of accumulating unlisted database rows.
 */
internal class A2aContextRegistry(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private val contexts = LinkedHashMap<String, String>(16, 0.75f, true)

    val size: Int
        get() = synchronized(contexts) { contexts.size }

    fun get(contextId: String): String? = synchronized(contexts) { contexts[contextId] }

    /** Inserts/refreshes a context. Returns the evicted session id, if the LRU bound was exceeded. */
    fun put(contextId: String, sessionId: String): String? = synchronized(contexts) {
        contexts[contextId] = sessionId
        if (contexts.size <= maxEntries) {
            null
        } else {
            val eldestKey = contexts.keys.first()
            contexts.remove(eldestKey)
        }
    }

    fun remove(contextId: String, expectedSessionId: String): Boolean = synchronized(contexts) {
        if (contexts[contextId] != expectedSessionId) return@synchronized false
        contexts.remove(contextId)
        true
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 64
    }
}
