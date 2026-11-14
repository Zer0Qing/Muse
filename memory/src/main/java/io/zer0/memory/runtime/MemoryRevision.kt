package io.zer0.memory.runtime

/**
 * 摘要 revision 合同。
 *
 * [sourceFingerprint] 应由与消息相关的稳定输入生成，不能拿当前时间伪造来源版本。
 * revision 必须非负。
 */
data class SummaryRevisionToken(
    val sessionId: String,
    val revision: Long,
    val messageCount: Int,
    val sourceFingerprint: String,
    val assistantId: String,
    val spaceId: String,
) {
    fun isValid(): Boolean = revision >= 0L &&
        sessionId.isNotBlank() &&
        assistantId.isNotBlank() &&
        spaceId.isNotBlank()
}
