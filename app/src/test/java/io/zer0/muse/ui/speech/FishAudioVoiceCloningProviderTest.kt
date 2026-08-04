package io.zer0.muse.ui.speech

import org.junit.Assert.assertEquals
import org.junit.Test

/** B8-05: Fish Audio 语音克隆服务地址归一化测试。 */
class FishAudioVoiceCloningProviderTest {

    @Test
    fun normalizesTrailingSlashAndWhitespace() {
        assertEquals(
            "https://api.fish.audio/v1",
            FishAudioVoiceCloningProvider.normalizeEndpoint("  https://api.fish.audio/v1/  "),
        )
    }

    @Test
    fun fallsBackToDefaultEndpointWhenBlank() {
        assertEquals(
            FishAudioVoiceCloningProvider.DEFAULT_ENDPOINT,
            FishAudioVoiceCloningProvider.normalizeEndpoint("   "),
        )
    }
}
