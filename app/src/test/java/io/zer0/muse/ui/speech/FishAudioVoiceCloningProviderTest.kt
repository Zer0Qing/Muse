package io.zer0.muse.ui.speech

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B8-05: Fish Audio 语音克隆服务地址归一化测试。
 *
 * v2.6.1 (#3): 克隆端点与 TTS 端点不同域 —— 克隆走 https://api.fish.audio/model,
 * TTS 走 https://api.fish.audio/v1/tts。归一化需剥掉用户从 TTS 配置带过来的 /v1 后缀,
 * 否则会请求并不存在的 /v1/voices（用户实测报错根因）。
 */
class FishAudioVoiceCloningProviderTest {

    @Test
    fun stripsTtsV1SuffixLeftFromTtsConfig() {
        assertEquals(
            "https://api.fish.audio",
            FishAudioVoiceCloningProvider.normalizeEndpoint("  https://api.fish.audio/v1/  "),
        )
    }

    @Test
    fun keepsHostWithoutSuffix() {
        assertEquals(
            "https://api.fish.audio",
            FishAudioVoiceCloningProvider.normalizeEndpoint("https://api.fish.audio/"),
        )
    }

    @Test
    fun fallsBackToDefaultEndpointWhenBlank() {
        assertEquals(
            FishAudioVoiceCloningProvider.DEFAULT_ENDPOINT,
            FishAudioVoiceCloningProvider.normalizeEndpoint("   "),
        )
        assertEquals("https://api.fish.audio", FishAudioVoiceCloningProvider.DEFAULT_ENDPOINT)
    }
}
