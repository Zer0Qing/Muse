package io.zer0.muse.channel

import java.io.ByteArrayOutputStream
import java.util.LinkedHashMap
import io.zer0.muse.ui.chat.PendingMessage

/**
 * 飞书长连接分片帧的有界拼装器。
 *
 * 同时存在过多未完成消息时只淘汰最旧的一条，不能清空其他仍可拼装的消息。
 * WebSocket 回调可能并发进入，因此状态转换在同一把锁内完成。
 */
internal class FeishuFrameAssembler(
    private val maxPendingMessages: Int = DEFAULT_MAX_PENDING_MESSAGES,
) {
    private data class PendingMessage(
        val expectedParts: Int,
        val parts: MutableMap<Int, ByteArray>,
    )

    private val pending = LinkedHashMap<String, PendingMessage>()

    init {
        require(maxPendingMessages > 0) { "maxPendingMessages must be positive" }
    }

    @Synchronized
    fun add(messageId: String, sum: Int, seq: Int, payload: ByteArray): String? {
        require(messageId.isNotBlank()) { "messageId must not be blank" }
        require(sum > 0) { "sum must be positive" }
        require(seq in 0 until sum) { "seq must be within the frame range" }

        val pendingMessage = pending[messageId] ?: run {
            while (pending.size >= maxPendingMessages) {
                val oldestId = pending.entries.firstOrNull()?.key ?: break
                pending.remove(oldestId)
            }
            PendingMessage(sum, LinkedHashMap<Int, ByteArray>()).also {
                pending[messageId] = it
            }
        }
        if (pendingMessage.expectedParts != sum) {
            pending.remove(messageId)
            return null
        }
        pendingMessage.parts[seq] = payload.copyOf()
        if (pendingMessage.parts.size < sum) return null

        val merged = ByteArrayOutputStream()
        for (index in 0 until sum) {
            val part = pendingMessage.parts[index] ?: return null
            merged.write(part)
        }
        pending.remove(messageId)
        return String(merged.toByteArray(), Charsets.UTF_8)
    }

    private companion object {
        const val DEFAULT_MAX_PENDING_MESSAGES = 64
    }
}
