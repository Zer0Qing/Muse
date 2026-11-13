package io.zer0.muse.asr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音输入入口可用性判据的矩阵测试。
 *
 * 这份判据同时决定两件事：输入栏显示语音键还是发送键、长按说话是否真的建控制器。
 * 两边漂移的后果是「键在但按下去没反应」，真机上极难发现，所以每个引擎一行。
 */
class AsrInputAvailabilityTest {

    @Test
    fun `system intent path never shows an in-app mic`() {
        assertFalse(isAsrInputAvailable(AsrProviderType.SYSTEM, apiKey = "any", localModelReady = true))
    }

    @Test
    fun `file transcription provider never shows an in-app mic`() {
        assertFalse(isAsrInputAvailable(AsrProviderType.DASHSCOPE_FILE, apiKey = "any", localModelReady = true))
    }

    @Test
    fun `local engine depends on model files not api key`() {
        assertTrue(isAsrInputAvailable(AsrProviderType.LOCAL_SENSEVOICE, apiKey = "", localModelReady = true))
        assertFalse(isAsrInputAvailable(AsrProviderType.LOCAL_SENSEVOICE, apiKey = "sk-xxx", localModelReady = false))
    }

    @Test
    fun `cloud engines require an api key`() {
        listOf(
            AsrProviderType.DASHSCOPE,
            AsrProviderType.STEP,
            AsrProviderType.OPENAI_WHISPER,
            AsrProviderType.OPENAI_REALTIME,
            AsrProviderType.AGNES,
        ).forEach { provider ->
            assertFalse("$provider 无 key 不应显示语音键", isAsrInputAvailable(provider, apiKey = "", localModelReady = true))
            assertTrue("$provider 有 key 应显示语音键", isAsrInputAvailable(provider, apiKey = "sk-xxx", localModelReady = false))
        }
    }

    @Test
    fun `local model readiness never unlocks a cloud engine`() {
        assertFalse(
            isAsrInputAvailable(AsrProviderType.DASHSCOPE, apiKey = "", localModelReady = true),
        )
    }
}
