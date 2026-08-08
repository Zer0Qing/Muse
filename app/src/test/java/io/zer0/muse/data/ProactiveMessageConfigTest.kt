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

    // ── v1.0.72: 发送概率字段 ──

    @Test
    fun defaultSendProbabilityIsHundred() {
        // 默认 100:决策通过即发(行为与 v1.0.71 之前一致,老配置兼容)
        assertEquals(100, ProactiveMessageConfig().sendProbability)
    }

    @Test
    fun roundTripKeepsSendProbability() {
        val config = ProactiveMessageConfig(sendProbability = 50)
        val json = AppJson.encodeToString(ProactiveMessageConfig.serializer(), config)
        val decoded = AppJson.decodeFromString(ProactiveMessageConfig.serializer(), json)

        assertEquals(50, decoded.sendProbability)
    }

    @Test
    fun oldConfigWithoutProbabilityDecodesAsDefault() {
        // 老版本序列化 JSON 不含 sendProbability 字段 → 反序列化应为默认 100
        val oldJson = """{"enabled":true,"intervalMinutes":240,"lastTriggeredAt":0,"nextTriggerAt":0,"randomOffsetMinutes":60,"agentId":"","allowedHourStart":8,"allowedHourEnd":22,"agentOnly":true,"maxDailyMessages":3,"temperature":0.8}"""
        val decoded = AppJson.decodeFromString(ProactiveMessageConfig.serializer(), oldJson)

        assertEquals(100, decoded.sendProbability)
        assertEquals(true, decoded.enabled)
    }
}
