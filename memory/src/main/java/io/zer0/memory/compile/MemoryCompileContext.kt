package io.zer0.memory.compile

/** A stable per-assistant compilation target. Keep it immutable and pass it per call. */
data class MemoryCompileTarget(
    val assistantId: String? = null,
    val scope: String = "main",
    val spaceId: String = "default",
) {
    val normalizedScope: String get() = scope.ifBlank { "main" }
    val normalizedSpaceId: String get() = spaceId.ifBlank { "default" }
}

/** Legacy host lookup retained for callers that do not yet provide an explicit target. */
data class MemoryCompileContext(
    val getScope: suspend () -> String = { "main" },
    val getSpaceId: suspend () -> String = { "default" },
)
