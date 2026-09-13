package io.zer0.muse.session

/**
 * 一次生成在跨层事件中的稳定身份。
 *
 * [generationId] 区分同一会话中的不同生成代际；[turnId] 表示业务回合；
 * [streamId] 表示其中一次 Provider 流。重试或恢复可以通过
 * [parentGenerationId] 关联到原代，但不能复用原代身份。
 */
data class GenerationIdentity(
    val sessionId: String,
    val turnId: String,
    val generationId: String,
    val streamId: String,
    val parentGenerationId: String? = null,
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(turnId.isNotBlank()) { "turnId must not be blank" }
        require(generationId.isNotBlank()) { "generationId must not be blank" }
        require(streamId.isNotBlank()) { "streamId must not be blank" }
    }
}
