package io.zer0.muse.data

import io.zer0.common.AppJson
import org.junit.Assert.assertEquals
import org.junit.Test

/** B8-01: 主动消息配置持久化字段测试。 */
class ProactiveMessageConfigTest {

    @Test
    fun defaultNextTriggerAtIsZero() {
        assertEquals(0L, ProactiveMessageConfig().nextTriggerAt)
    }

    @Test
    fun roundTripKeepsNextTriggerAt() {
        val config = ProactiveMessageConfig(nextTriggerAt = 1_234_567_890L)
        val json = AppJson.encodeToString(ProactiveMessageConfig.serializer(), config)
        val decoded = AppJson.decodeFromString(ProactiveMessageConfig.serializer(), json)

        assertEquals(1_234_567_890L, decoded.nextTriggerAt)
    }
}
