package io.zer0.muse.runtime

enum class RuntimeFailureKind {
    NETWORK,
    STALLED,
    CHECKSUM,
    EXTRACT,
    PERMISSION,
    ABI,
    SELF_CHECK,
    SPACE,
    BROKEN,
    UNSUPPORTED,
}

data class RuntimeHealthCheck(
    val revision: String?,
    val healthy: Boolean,
    val nodeVersion: String? = null,
    val npmVersion: String? = null,
    val missingPaths: List<String> = emptyList(),
    val stderrTail: String = "",
    val failureKind: RuntimeFailureKind? = null,
)

sealed interface RuntimeHealth {
    data class Ready(val revision: String, val checkedPaths: List<String>) : RuntimeHealth
    data class Broken(val revision: String, val reason: String, val missingPaths: List<String>) : RuntimeHealth
}
