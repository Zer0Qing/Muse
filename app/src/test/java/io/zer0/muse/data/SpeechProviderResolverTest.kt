package io.zer0.muse.data

import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.muse.asr.AsrConfig
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TTS / ASR「跟随供应商配置」解析规则。
 *
 * 解析器的契约是「开关关闭时行为与旧版完全一致」,所以每个回退分支都既断言来源,
 * 也断言回退后拿到的确实是手动配置里的值 —— 只断言不崩等于没测。
 */
class SpeechProviderResolverTest {

    private val provider = ProviderConfig(
        id = "p1",
        displayName = "Relay",
        baseUrl = "https://relay.example.com/v1",
        apiKey = "sk-provider",
        models = listOf(
            Model(id = "tts-1", name = "TTS 1", providerId = "p1"),
            Model(id = "tts-2", name = "TTS 2", providerId = "p1"),
        ),
    )

    private fun media(
        follow: Boolean,
        providerId: String = "p1",
        modelId: String = "tts-1",
    ): MediaConfig = MediaConfig(
        ttsEngine = "openai",
        ttsApiKey = "sk-manual",
        ttsModel = "manual-model",
        ttsEndpoint = "https://manual.example.com/v1",
        ttsFollowProvider = follow,
        ttsProviderId = providerId,
        ttsModelId = modelId,
    )

    private fun asr(
        follow: Boolean,
        providerId: String = "p1",
        modelId: String = "tts-1",
    ): AsrConfig = AsrConfig(
        provider = io.zer0.muse.asr.AsrProviderType.OPENAI_WHISPER,
        apiKey = "sk-manual",
        model = "manual-model",
        baseUrl = "https://manual.example.com/v1",
        followProvider = follow,
        providerId = providerId,
        modelId = modelId,
    )

    @Test
    fun followOffWithCompleteManualConfigUsesManual() {
        val resolved = SpeechProviderResolver.resolveMedia(media(follow = false), listOf(provider))

        assertEquals(SpeechEndpointSource.MANUAL, resolved.source)
        assertEquals("https://manual.example.com/v1", resolved.baseUrl)
        assertEquals("sk-manual", resolved.apiKey)
        assertEquals("manual-model", resolved.model)
        assertNull("开关关闭不属于回退,不应带原因", resolved.fallbackReason)
    }

    @Test
    fun followOnWithEnabledProviderAndModelUsesProvider() {
        val resolved = SpeechProviderResolver.resolveMedia(media(follow = true), listOf(provider))

        assertEquals(SpeechEndpointSource.PROVIDER, resolved.source)
        assertEquals("https://relay.example.com/v1", resolved.baseUrl)
        assertEquals("sk-provider", resolved.apiKey)
        assertEquals("tts-1", resolved.model)
        assertNull(resolved.fallbackReason)
    }

    @Test
    fun followOnWithDeletedProviderFallsBackToManual() {
        val resolved = SpeechProviderResolver.resolveMedia(media(follow = true, providerId = "gone"), listOf(provider))

        assertEquals(SpeechEndpointSource.MANUAL, resolved.source)
        assertEquals(SpeechProviderResolver.REASON_PROVIDER_MISSING, resolved.fallbackReason)
        assertEquals("https://manual.example.com/v1", resolved.baseUrl)
        assertEquals("sk-manual", resolved.apiKey)
        assertEquals("manual-model", resolved.model)
    }

    @Test
    fun followOnWithDisabledProviderFallsBackToManual() {
        val disabled = provider.copy(enabled = false)
        val resolved = SpeechProviderResolver.resolveMedia(media(follow = true), listOf(disabled))

        assertEquals(SpeechEndpointSource.MANUAL, resolved.source)
        assertEquals(SpeechProviderResolver.REASON_PROVIDER_DISABLED, resolved.fallbackReason)
        assertEquals("sk-manual", resolved.apiKey)
        assertEquals("manual-model", resolved.model)
    }

    @Test
    fun followOnWithoutModelFallsBackToManual() {
        val resolved = SpeechProviderResolver.resolveMedia(media(follow = true, modelId = "  "), listOf(provider))

        assertEquals(SpeechEndpointSource.MANUAL, resolved.source)
        assertEquals(SpeechProviderResolver.REASON_MODEL_MISSING, resolved.fallbackReason)
        assertEquals("manual-model", resolved.model)
    }

    @Test
    fun followOffWinsEvenWhenProviderExists() {
        val resolved = SpeechProviderResolver.resolveMedia(media(follow = false), listOf(provider))

        assertEquals(SpeechEndpointSource.MANUAL, resolved.source)
        assertEquals("sk-manual", resolved.apiKey)
        assertNull(resolved.fallbackReason)
    }

    @Test
    fun asrResolvesProviderEndpointAndKeepsOtherFields() {
        val config = asr(follow = true)
        val resolved = SpeechProviderResolver.resolveAsr(config, listOf(provider))
        val applied = config.withResolvedEndpoint(resolved)

        assertEquals(SpeechEndpointSource.PROVIDER, resolved.source)
        assertEquals("sk-provider", applied.apiKey)
        assertEquals("https://relay.example.com/v1", applied.baseUrl)
        assertEquals("tts-1", applied.model)
        // 非凭据字段不受影响
        assertEquals(config.sampleRate, applied.sampleRate)
        assertEquals(config.provider, applied.provider)
    }

    @Test
    fun asrFallbackKeepsManualValuesUntouched() {
        val config = asr(follow = true, providerId = "gone")
        val resolved = SpeechProviderResolver.resolveAsr(config, listOf(provider))
        val applied = config.withResolvedEndpoint(resolved)

        assertEquals(SpeechProviderResolver.REASON_PROVIDER_MISSING, resolved.fallbackReason)
        assertEquals("沿用原配置对象,手填值不被覆盖", config, applied)
    }

    @Test
    fun asrDashScopeIgnoresFollowSwitchEntirely() {
        // DashScope 走内置 wss 端点:即使开关开着、供应商已被删,也不能报回退(否则会出现假告警)
        val config = AsrConfig(
            provider = io.zer0.muse.asr.AsrProviderType.DASHSCOPE,
            apiKey = "sk-manual",
            model = "paraformer-realtime-v2",
            followProvider = true,
            providerId = "gone",
            modelId = "",
        )
        val resolved = SpeechProviderResolver.resolveAsr(config, listOf(provider))

        assertEquals(SpeechEndpointSource.MANUAL, resolved.source)
        assertNull(resolved.fallbackReason)
        assertEquals("sk-manual", resolved.apiKey)
        assertEquals("paraformer-realtime-v2", resolved.model)
    }

    @Test
    fun legacyMediaConfigJsonWithoutNewFieldsDeserializesWithDefaults() {
        val legacyJson = """
            {
              "ttsSpeechRate": 1.2,
              "ttsEngine": "openai",
              "ttsApiKey": "sk-legacy",
              "ttsModel": "gpt-4o-mini-tts",
              "ttsVoice": "alloy",
              "ttsEndpoint": "https://legacy.example.com/v1"
            }
        """.trimIndent()

        val decoded = Json.decodeFromString(MediaConfig.serializer(), legacyJson)

        assertEquals(false, decoded.ttsFollowProvider)
        assertEquals("", decoded.ttsProviderId)
        assertEquals("", decoded.ttsModelId)
        // 旧字段照旧生效
        assertEquals("sk-legacy", decoded.ttsApiKey)
        assertEquals("https://legacy.example.com/v1", decoded.ttsEndpoint)
        assertEquals(1.2f, decoded.ttsSpeechRate, 0f)
        // 关着开关时解析结果必须与旧版一致
        val resolved = SpeechProviderResolver.resolveMedia(decoded, listOf(provider))
        assertEquals(SpeechEndpointSource.MANUAL, resolved.source)
        assertEquals("https://legacy.example.com/v1", resolved.baseUrl)
        assertTrue(resolved.fallbackReason == null)
    }
}
