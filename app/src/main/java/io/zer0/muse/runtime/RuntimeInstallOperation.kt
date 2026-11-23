package io.zer0.muse.runtime

import kotlinx.serialization.Serializable

@Serializable
enum class RuntimeInstallPhase {
    IDLE,
    SELECTING,
    DOWNLOADING,
    PAUSED,
    VERIFYING,
    EXTRACTING,
    SELF_CHECKING,
    READY_TO_SWITCH,
    SWITCHING,
    COMPLETED,
    FAILED_RETRYABLE,
    FAILED_PERMANENT,
    CANCELLED,
}

@Serializable
data class RuntimeInstallOperation(
    val operationId: String,
    val runtimeId: String,
    val abi: String,
    val revision: String,
    val phase: RuntimeInstallPhase,
    val sourceTrust: RuntimeSourceTrust = RuntimeSourceTrust.CHECKSUM_PINNED,
    val expectedSha256: String? = null,
    val sourceUrl: String = "",
    val sourceIndex: Int = 0,
    val archivePath: String = "",
    val partPath: String = "",
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val attemptCount: Int = 0,
    val lastError: String? = null,
    val cancelReason: String? = null,
    val updatedAt: Long,
    val etag: String? = null,
    val lastModified: String? = null,
)
