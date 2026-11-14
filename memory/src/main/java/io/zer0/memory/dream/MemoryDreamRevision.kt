package io.zer0.memory.dream

import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
enum class DreamTrigger { MANUAL, AUTOMATIC }

/**
 * Dream 单次发布快照。
 *
 * [beforeFingerprint] 来自运行开始前的 compiled sections；发布前必须与当前
 * compiled sections 重新计算对比，不一致时拒绝旧结果，防止把旧整理结果覆盖新数据。
 */
@Serializable
data class MemoryDreamRevision(
    val revisionId: String,
    val trigger: DreamTrigger,
    val beforeFingerprint: String,
    val before: Map<String, String>,
    val nextSections: Map<String, String>,
    val publishedAt: String,
)

/**
 * 当前 Dream 状态。
 *
 * lastRevisionId 为 null 表示尚未产生可恢复 revision。
 */
data class DreamStatus(
    val running: Boolean,
    val runId: String?,
    val lastRevisionId: String?,
    val lastError: String?,
)

sealed interface DreamStartResult {
    data class Started(val runId: String) : DreamStartResult
    data class Rejected(val reason: String) : DreamStartResult
}

sealed interface DreamRestoreResult {
    data class Restored(val revisionId: String) : DreamRestoreResult
    data class Rejected(val reason: String) : DreamRestoreResult
}

sealed interface DreamStopResult {
    data object Stopped : DreamStopResult
}

/** 对有序 section 内容生成稳定 fingerprint。 */
fun fingerprintCompiled(sections: Map<String, String>): String {
    val text = sections.entries.sortedBy { it.key }.joinToString("\u0000") { "${it.key}=${it.value}" }
    return MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
