package io.zer0.muse.asr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OpenAI Realtime ASR 客户端的 GA 协议约定测试。
 *
 * 覆盖:
 *  - 握手请求不再发送 `OpenAI-Beta: realtime=v1`(Beta 通道已下线,带该头会被拒);
 *  - `session.update` 的 `session.type` 固定为 `transcription`(GA 必填);
 *  - 输入侧配置走 GA 的嵌套路径 `session.audio.input.*`,Beta 的扁平键不再出现;
 *  - `format.rate` 等于采集采样率(16000),不是文档示例值;
 *  - 模型/热词/语种原样保留在嵌套路径下；turn_detection 显式写 null（关闭服务端 VAD，
 *    改为客户端手动 commit），commit 消息只带 type；
 *  - OPENAI_REALTIME 的默认模型已换代到实时转写模型。
 *
 * 运行: ./gradlew :app:testDebugUnitTest --tests "*OpenAiRealtimeAsrProtocolTest*"
 */
class OpenAiRealtimeAsrProtocolTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val endpoint = "wss://api.openai.com/v1/realtime?intent=transcription"

    /** Beta 时代的扁平键,GA 已不识别,payload 里必须一个都不剩。 */
    private val betaFlatSessionKeys = listOf(
        "input_audio_format",
        "input_audio_transcription",
        "input_audio_noise_reduction",
        "input_audio_noise_reduction_types",
    )

    /** `turn_detection` 只在 session 顶层是 Beta 写法;搬到 audio.input 下才是 GA 位置。 */
    private val betaSessionLevelOnlyKeys = betaFlatSessionKeys + "turn_detection"

    private fun session(
        model: String = "gpt-realtime-whisper",
        hotwords: List<String> = emptyList(),
        language: String? = null,
        sampleRate: Int = 16_000,
    ): JsonObject = buildRealtimeSessionUpdatePayload(
        model = model,
        hotwords = hotwords,
        language = language,
        sampleRate = sampleRate,
    )["session"]!!.jsonObject

    /** 按路径逐层取嵌套对象,任一层缺失返回 null(断言失败而非抛 NPE 才好读)。 */
    private fun JsonObject.at(vararg path: String): JsonObject? {
        var cur: JsonObject = this
        for (key in path) {
            cur = cur[key] as? JsonObject ?: return null
        }
        return cur
    }

    private fun JsonObject.atValue(vararg path: String): JsonElement? {
        if (path.isEmpty()) return this
        val parent = at(*path.dropLast(1).toTypedArray()) ?: return null
        return parent[path.last()]
    }

    // ── 握手头 ──────────────────────────────────────────────────────────

    @Test
    fun `handshake request sends no OpenAI-Beta header`() {
        val request = buildRealtimeRequest(endpoint, "sk-test-key")

        assertNull("GA 不需要 Beta 版本标记头", request.header("OpenAI-Beta"))
        assertFalse(
            "任何大小写的 beta 标记头都不应出现",
            request.headers.names().any { it.contains("beta", ignoreCase = true) },
        )
        assertEquals("Bearer sk-test-key", request.header("Authorization"))
    }

    @Test
    fun `handshake request keeps the GA realtime endpoint untouched`() {
        val request = buildRealtimeRequest(endpoint, "sk-test-key")
        val url = request.url

        // OkHttp 的 HttpUrl 会把 wss 规整成 https,故逐段断言
        assertEquals("api.openai.com", url.host)
        assertEquals("/v1/realtime", url.encodedPath)
        assertEquals("transcription", url.queryParameter("intent"))
        // GA 端点不带日期版本号与 api-version 查询参数
        assertFalse(url.toString().contains("api-version"))
        assertFalse(url.encodedPath.contains("20"))
    }

    // ── session.update:整体形状 ─────────────────────────────────────────

    @Test
    fun `session update matches the GA nested shape exactly`() {
        val payload = buildRealtimeSessionUpdatePayload(
            model = "gpt-realtime-whisper",
            hotwords = listOf("Muse", "子奇"),
            language = "zh",
            sampleRate = 16_000,
        )
        val expected = json.parseToJsonElement(
            """
            {"type":"session.update","session":{"type":"transcription","audio":{"input":{
              "format":{"type":"audio/pcm","rate":16000},
              "transcription":{"model":"gpt-realtime-whisper","prompt":"Muse, 子奇","language":"zh"},
              "turn_detection":null,
              "noise_reduction":null}}}}
            """.trimIndent(),
        )
        assertEquals("payload 必须整体落在 GA 的嵌套路径上(且 turn_detection 显式 null)", expected, payload)
    }

    @Test
    fun `session update declares transcription session type`() {
        val payload = buildRealtimeSessionUpdatePayload(
            model = "gpt-realtime-whisper",
            hotwords = emptyList(),
            language = null,
        )

        assertEquals("session.update", payload["type"]!!.jsonPrimitive.content)
        assertEquals(
            "GA 用 session.type 区分会话形态,缺了会被拒",
            "transcription",
            payload["session"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
    }

    // ── session.update:嵌套路径 ─────────────────────────────────────────

    @Test
    fun `transcription model lives under session audio input transcription`() {
        val transcription = session(model = "gpt-realtime-whisper").at("audio", "input", "transcription")

        assertNotNull("转写配置必须在 session.audio.input.transcription", transcription)
        assertEquals(
            "gpt-realtime-whisper",
            transcription!!["model"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `input format declares pcm with the capture sample rate`() {
        val format = session().at("audio", "input", "format")

        assertNotNull("输入格式必须在 session.audio.input.format", format)
        assertEquals("audio/pcm", format!!["type"]!!.jsonPrimitive.content)
        assertEquals(
            "16000 来自 AsrAudioCapture 的采集参数,不是文档示例的 24000",
            16_000,
            format["rate"]!!.jsonPrimitive.int,
        )
    }

    @Test
    fun `input format rate follows the configured capture sample rate`() {
        val format = session(sampleRate = 48_000).at("audio", "input", "format")

        assertEquals(
            "声明采样率必须跟着实际采集走,否则服务端按错误帧长解 PCM",
            48_000,
            format!!["rate"]!!.jsonPrimitive.int,
        )
    }

    @Test
    fun `session update keeps model hotwords and language`() {
        val transcription = session(
            model = "gpt-realtime-whisper",
            hotwords = listOf("Muse", "子奇"),
            language = "zh",
        ).at("audio", "input", "transcription")

        assertEquals("gpt-realtime-whisper", transcription!!["model"]!!.jsonPrimitive.content)
        assertEquals("Muse, 子奇", transcription["prompt"]!!.jsonPrimitive.content)
        assertEquals("zh", transcription["language"]!!.jsonPrimitive.content)
    }

    @Test
    fun `session update omits optional prompt and language when unset`() {
        val transcription = session(hotwords = emptyList(), language = null)
            .at("audio", "input", "transcription")

        assertNull(transcription!!["prompt"])
        assertNull(transcription["language"])
    }

    @Test
    fun `turn detection is explicitly disabled for manual commit`() {
        val input = session().at("audio", "input")

        assertNotNull("turn_detection 键必须存在(显式 null 表示手动 commit)", input!!["turn_detection"])
        assertEquals(
            "服务端 VAD 必须关闭:一段输入的结束点由用户点「结束」决定,不能由静音时长代俓",
            JsonNull,
            input["turn_detection"],
        )
        assertNull("不得残留 VAD 参数", input["turn_detection"] as? JsonObject)
    }

    @Test
    fun `manual commit message is emitted on user finish`() {
        val commit = json.parseToJsonElement(buildRealtimeCommitMessage()).jsonObject

        assertEquals(
            "turn_detection=null 时,input_audio_buffer.commit 是唯一能开点一段输入的信号",
            "input_audio_buffer.commit",
            commit["type"]!!.jsonPrimitive.content,
        )
        assertEquals("commit 事件只带 type", 1, commit.size)
    }

    @Test
    fun `noise reduction is declared as explicit json null`() {
        val input = session().at("audio", "input")

        assertNotNull("noise_reduction 键必须存在(显式 null 表示关闭)", input!!["noise_reduction"])
        assertEquals(
            "未启用噪声抑制时应显式写 null 而不是省略",
            JsonNull,
            input["noise_reduction"],
        )
    }

    // ── session.update:不得残留 Beta 扁平键 ─────────────────────────────

    @Test
    fun `session update has no beta flat keys`() {
        val session = session(model = "gpt-realtime-whisper", hotwords = listOf("Muse"), language = "zh")

        for (key in betaSessionLevelOnlyKeys) {
            assertNull("Beta 扁平键 $key 在 GA 已不识别,应从 session 顶层移除", session[key])
        }
        for (key in betaFlatSessionKeys) {
            assertNull(
                "扁平键 $key 不应藏在 session.audio.input 里",
                session.at("audio", "input")?.get(key),
            )
        }
        // 纯转写客户端不发对话态配置
        assertNull("纯转写不应配置 session.audio.output", session.at("audio")?.get("output"))
        assertNull(session["instructions"])
        assertNull(session["voice"])
    }

    @Test
    fun `payload round trips through json encoder`() {
        val payload = buildRealtimeSessionUpdatePayload("gpt-realtime-whisper", emptyList(), null)
        val encoded = json.encodeToString(JsonObject.serializer(), payload)
        val decoded = json.parseToJsonElement(encoded).jsonObject

        assertEquals("session.update", decoded["type"]!!.jsonPrimitive.content)
        val session = decoded["session"]!!.jsonObject
        assertEquals("transcription", session["type"]!!.jsonPrimitive.content)
        assertEquals(
            "gpt-realtime-whisper",
            session.at("audio", "input", "transcription")!!["model"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "16000",
            (session.atValue("audio", "input", "format", "rate") as JsonPrimitive).content,
        )
        assertTrue(
            "编码后仍不得出现 Beta 扁平键",
            betaSessionLevelOnlyKeys.none { session[it] != null },
        )
    }

    // ── 默认模型 ────────────────────────────────────────────────────────

    @Test
    fun `openai realtime default model is gpt-realtime-whisper`() {
        assertEquals(
            "gpt-realtime-whisper",
            AsrConfig(provider = AsrProviderType.OPENAI_REALTIME).defaultModel(),
        )
    }
}
