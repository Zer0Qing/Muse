package io.zer0.muse.notification

import java.nio.ByteBuffer
import java.security.MessageDigest

object MuseNotificationIdPolicy {
    private const val PROACTIVE_ID_PREFIX = 0x1000_0000
    private const val PROACTIVE_ID_MASK = 0x0FFF_FFFF

    fun notificationIdForOutcome(outcomeId: String): Int = stableId("outcome:$outcomeId")

    fun notificationIdForDispatch(dispatchId: String): Int = stableId("dispatch:$dispatchId")

    fun isProactiveId(id: Int): Boolean = (id and 0xF000_0000.toInt()) == PROACTIVE_ID_PREFIX

    private fun stableId(key: String): Int {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val value = ByteBuffer.wrap(digest, 0, Int.SIZE_BYTES).int and PROACTIVE_ID_MASK
        return PROACTIVE_ID_PREFIX or value
    }
}
