package io.zer0.muse.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一次性转写器的响应解析与端点拼接。
 *
 * 两条纯函数单独钉住:没有 text 字段、text 是空白,都算「没听清」——
 * 把一段空字符串填进输入框,用户只会以为麦克风坏了。
 */
class OneShotAsrTranscriberTest {

    @Test
    fun `响应里没有 text 字段时返回 null`() {
        assertNull(parseOneShotTranscription("""{"duration":1.2,"task":"transcribe"}"""))
    }

    @Test
    fun `text 全是空白时返回 null`() {
        assertNull(parseOneShotTranscription("""{"text":"   \n\t "}"""))
    }

    @Test
    fun `text 为空串时返回 null`() {
        assertNull(parseOneShotTranscription("""{"text":""}"""))
    }

    @Test
    fun `text 是 JSON null 时返回 null`() {
        assertNull(parseOneShotTranscription("""{"text":null}"""))
    }

    @Test
    fun `text 不是字符串时返回 null`() {
        assertNull(parseOneShotTranscription("""{"text":42}"""))
    }

    @Test
    fun `响应不是 JSON 时返回 null`() {
        assertNull(parseOneShotTranscription("<html><body>502 Bad Gateway</body></html>"))
    }

    @Test
    fun `空响应体返回 null`() {
        assertNull(parseOneShotTranscription(""))
    }

    @Test
    fun `正常响应取出去掉首尾空白的文本`() {
        assertEquals("今天下午三点开会", parseOneShotTranscription("""{"text":"  今天下午三点开会 \n"}"""))
    }

    @Test
    fun `多余字段不影响解析`() {
        assertEquals("好", parseOneShotTranscription("""{"text":"好","language":"zh","segments":[]}"""))
    }

    @Test
    fun `配到 v1 的 baseUrl 不重复拼版本段`() {
        assertEquals(
            "https://api.openai.com/v1/audio/transcriptions",
            oneShotTranscriptionUrl("https://api.openai.com/v1"),
        )
        assertEquals(
            "https://api.openai.com/v1/audio/transcriptions",
            oneShotTranscriptionUrl("https://api.openai.com/v1/"),
        )
    }

    @Test
    fun `裸域名补上 v1 与端点路径`() {
        assertEquals(
            "https://asr.example.com/v1/audio/transcriptions",
            oneShotTranscriptionUrl("https://asr.example.com"),
        )
    }

    @Test
    fun `直接粘完整端点时原样使用`() {
        assertEquals(
            "https://asr.example.com/audio/transcriptions",
            oneShotTranscriptionUrl("https://asr.example.com/audio/transcriptions"),
        )
    }

    @Test
    fun `只有 Whisper 兼容引擎配上 key 才判为支持`() {
        assertTrue(AsrConfig(provider = AsrProviderType.OPENAI_WHISPER, apiKey = "sk-x").supportsFileTranscription())
        assertTrue(AsrConfig(provider = AsrProviderType.AGNES, apiKey = "sk-x").supportsFileTranscription())
        assertFalse("没填 key 就连请求都发不出去", AsrConfig(provider = AsrProviderType.OPENAI_WHISPER).supportsFileTranscription())
    }

    @Test
    fun `流式与异步引擎即便有 key 也不支持整段转写`() {
        val unsupported = listOf(
            AsrProviderType.SYSTEM,
            AsrProviderType.DASHSCOPE,
            AsrProviderType.DASHSCOPE_FILE,
            AsrProviderType.STEP,
            AsrProviderType.OPENAI_REALTIME,
        )

        unsupported.forEach { provider ->
            assertFalse(
                "$provider 没有 /audio/transcriptions 端点,不该被判为支持",
                AsrConfig(provider = provider, apiKey = "sk-x").supportsFileTranscription(),
            )
        }
    }

    @Test
    fun `baseUrl 不是 http 时判为不支持`() {
        assertFalse(
            AsrConfig(
                provider = AsrProviderType.OPENAI_WHISPER,
                apiKey = "sk-x",
                baseUrl = "wss://asr.example.com/v1",
            ).supportsFileTranscription(),
        )
        assertTrue(
            AsrConfig(
                provider = AsrProviderType.OPENAI_WHISPER,
                apiKey = "sk-x",
                baseUrl = "https://asr.example.com/v1",
            ).supportsFileTranscription(),
        )
    }

    @Test
    fun `每一种引擎在两个出口上的结论都一致`() {
        // 新增一个 AsrProviderType 时，这里逼着回答"它到底能不能整段转写"，
        // 而不是让"置灰麦克风"的判据与"真正发请求"的判据各走一边。
        AsrProviderType.entries.forEach { provider ->
            val config = AsrConfig(provider = provider, apiKey = "sk-x")

            val asSupports = asrGate(config, hasAudio = true)
            val asTranscribe =
                if (config.supportsFileTranscription()) AsrGate.GO else AsrGate.UNSUPPORTED_ENGINE

            assertEquals("$provider 两个出口结论必须一致", asTranscribe, asSupports)
        }
    }

    @Test
    fun `没配置时说没配置，但没音频优先说没音频`() {
        assertEquals(AsrGate.NOT_CONFIGURED, asrGate(null, hasAudio = true))
        // 顺序是刻意的：手上根本没音频时，问题不是"你没配服务"，而是"这趟没东西可转"。
        // 反过来会把用户引去设置页瞎找，而真正的原因是那段录音是空的。
        assertEquals(AsrGate.EMPTY_AUDIO, asrGate(null, hasAudio = false))
    }

    @Test
    fun `空音频只影响这一趟，不影响「支不支持录音」`() {
        val supported = AsrConfig(provider = AsrProviderType.OPENAI_WHISPER, apiKey = "sk-x")

        assertEquals("手上没音频不该改成『不支持』", AsrGate.EMPTY_AUDIO, asrGate(supported, hasAudio = false))
        assertEquals(AsrGate.GO, asrGate(supported, hasAudio = true))
    }

    @Test
    fun `引擎没有整段转写端点时明确说引擎不支持而不是没配置`() {
        val streamingOnly = AsrConfig(provider = AsrProviderType.SYSTEM, apiKey = "sk-x")

        assertEquals(AsrGate.UNSUPPORTED_ENGINE, asrGate(streamingOnly, hasAudio = true))
    }
}
