package io.zer0.muse.ui.speech

import io.zer0.common.AppDispatchers
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/**
 * v1.97: 云端 TTS 服务 — 支持 OpenAI / MiniMax / Edge TTS。
 *
 * 当 [MediaConfig.ttsEngine] != "system" 时,TtsManager 委托给本服务合成音频。
 * 返回 mp3 字节数组,写入临时文件后交由 MediaPlayer 播放(与系统 TTS 路径一致)。
 *
 * 设计既有实现 的 TTSProvider 抽象,但简化为单文件多 provider 分发。
 *
 * v1.99: 增强 —
 *  - 修复 Edge TTS 假域名(改为可配置 endpoint,默认空)
 *  - 补全 xAI TTS(POST /tts,body {text, voice_id, language})
 *  - 新增 [listVoices] 动态拉取音色列表
 *  - 新增 [CloudTtsConfig] 透传 stability/similarity/emotion/speed/response_format
 */
class CloudTtsService(
    private val client: OkHttpClient,
) {
    companion object {
        private const val TAG = "CloudTtsService"
        /** OpenAI TTS 默认 endpoint。 */
        private const val OPENAI_DEFAULT_ENDPOINT = "https://api.openai.com/v1"
        /** OpenAI TTS 默认模型。 */
        private const val OPENAI_DEFAULT_MODEL = "gpt-4o-mini-tts"
        /** OpenAI TTS 默认音色。 */
        private const val OPENAI_DEFAULT_VOICE = "alloy"
        /** MiniMax TTS 默认 endpoint。 */
        private const val MINIMAX_DEFAULT_ENDPOINT = "https://api.minimaxi.com/v1"
        /** MiniMax TTS 默认模型。 */
        private const val MINIMAX_DEFAULT_MODEL = "speech-2.6-turbo"
        /** MiniMax TTS 默认音色。 */
        private const val MINIMAX_DEFAULT_VOICE = "female-shaonv"
        /**
         * Edge TTS 免费 endpoint(兼容 OpenAI 接口格式)。
         *
         * v1.99: 原默认值 https://edge-tts-api.example.com/v1 是占位假域名,
         * 无法访问。Edge TTS 没有官方 OpenAI 兼容服务,需用户自行部署/填写
         * 兼容代理地址(如社区 edge-tts-openai 代理)。默认空串:留空时合成失败,
         * 上层回退系统 TTS,避免误导。
         */
        private const val EDGE_DEFAULT_ENDPOINT = ""
        /** DashScope TTS (阿里云) 默认 endpoint。 */
        private const val DASHSCOPE_DEFAULT_ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1"
        /** DashScope TTS 默认模型。 */
        private const val DASHSCOPE_DEFAULT_MODEL = "cosyvoice-v2"
        /** DashScope TTS 默认音色。 */
        private const val DASHSCOPE_DEFAULT_VOICE = "longxiaochun"
        /** Fish Audio TTS 默认 endpoint。 */
        private const val FISH_DEFAULT_ENDPOINT = "https://api.fish.audio/v1"
        /** Fish Audio TTS 默认模型。 */
        private const val FISH_DEFAULT_MODEL = "s1"
        /** ElevenLabs TTS 默认 endpoint。 */
        private const val ELEVENLABS_DEFAULT_ENDPOINT = "https://api.elevenlabs.io/v1"
        /** ElevenLabs TTS 默认模型(多语言 v2)。 */
        private const val ELEVENLABS_DEFAULT_MODEL = "eleven_multilingual_v2"
        /** ElevenLabs TTS 默认 Voice ID(Rachel)。 */
        private const val ELEVENLABS_DEFAULT_VOICE = "21m00Tcm4TlvDq8ikWAM"
        /** Groq TTS 默认 endpoint(OpenAI 兼容路径)。 */
        private const val GROQ_DEFAULT_ENDPOINT = "https://api.groq.com/openai/v1"
        /** Groq TTS 默认模型。 */
        private const val GROQ_DEFAULT_MODEL = "playai-tts"
        /** Groq TTS 默认音色。 */
        private const val GROQ_DEFAULT_VOICE = "Fritz-PlayAI"
        /** Qwen TTS(DashScope 原生接口)默认 endpoint。与 dashscope(OpenAI 兼容模式)不同。 */
        private const val QWEN_DEFAULT_ENDPOINT = "https://dashscope.aliyuncs.com/api/v1"
        /** Qwen TTS 默认模型(cosyvoice-v1)。 */
        private const val QWEN_DEFAULT_MODEL = "cosyvoice-v1"
        /** Qwen TTS 默认音色(龙小淳)。 */
        private const val QWEN_DEFAULT_VOICE = "longxiaochun"
        /** StepFun TTS 默认 endpoint。 */
        private const val STEP_DEFAULT_ENDPOINT = "https://api.stepfun.com/v1"
        /** StepFun TTS 默认模型。 */
        private const val STEP_DEFAULT_MODEL = "step-tts-mini"
        /** StepFun TTS 默认音色。 */
        private const val STEP_DEFAULT_VOICE = "speaker1"
        /** xAI TTS 默认 endpoint。 */
        private const val XAI_DEFAULT_ENDPOINT = "https://api.x.ai/v1"
        /** xAI TTS 默认音色。 */
        private const val XAI_DEFAULT_VOICE = "Alloy"

        // ── 各引擎硬编码常用音色(无 /voices 接口或离线兜底) ──
        private val OPENAI_VOICES = listOf(
            VoiceInfo("alloy", "Alloy", "en"),
            VoiceInfo("echo", "Echo", "en"),
            VoiceInfo("fable", "Fable", "en"),
            VoiceInfo("onyx", "Onyx", "en"),
            VoiceInfo("nova", "Nova", "en"),
            VoiceInfo("shimmer", "Shimmer", "en"),
            VoiceInfo("coral", "Coral", "en"),
            VoiceInfo("sage", "Sage", "en"),
            VoiceInfo("ash", "Ash", "en"),
            VoiceInfo("ballad", "Ballad", "en"),
        )
        private val EDGE_VOICES = listOf(
            VoiceInfo("zh-CN-XiaoxiaoNeural", "晓晓 (zh-CN)", "zh-CN", "female"),
            VoiceInfo("zh-CN-YunxiNeural", "云希 (zh-CN)", "zh-CN", "male"),
            VoiceInfo("zh-CN-YunyangNeural", "云扬 (zh-CN)", "zh-CN", "male"),
            VoiceInfo("zh-CN-XiaoyiNeural", "晓伊 (zh-CN)", "zh-CN", "female"),
            VoiceInfo("en-US-JennyNeural", "Jenny (en-US)", "en-US", "female"),
            VoiceInfo("en-US-GuyNeural", "Guy (en-US)", "en-US", "male"),
        )
        private val GEMINI_VOICES = listOf(
            VoiceInfo("Kore", "Kore", "en"),
            VoiceInfo("Puck", "Puck", "en"),
            VoiceInfo("Charon", "Charon", "en"),
            VoiceInfo("Fenrir", "Fenrir", "en"),
            VoiceInfo("Leda", "Leda", "en"),
            VoiceInfo("Orus", "Orus", "en"),
            VoiceInfo("Aoede", "Aoede", "en"),
        )
        private val DASHSCOPE_VOICES = listOf(
            VoiceInfo("longxiaochun", "龙小淳", "zh-CN", "female"),
            VoiceInfo("longyan", "龙岩", "zh-CN", "male"),
            VoiceInfo("longxiao", "龙小", "zh-CN", "female"),
            VoiceInfo("longshu", "龙叔", "zh-CN", "male"),
            VoiceInfo("longjing", "龙晶", "zh-CN", "female"),
            VoiceInfo("longmiao", "龙淼", "zh-CN", "female"),
        )
        private val FISH_VOICES = listOf(
            VoiceInfo("", "默认音色(空 reference_id)"),
        )
        private val GROQ_VOICES = listOf(
            VoiceInfo("Fritz-PlayAI", "Fritz", "en"),
            VoiceInfo("Gwendolyn-PlayAI", "Gwendolyn", "en"),
            VoiceInfo("Hugo-PlayAI", "Hugo", "en"),
            VoiceInfo("Bella-PlayAI", "Bella", "en"),
        )
        private val QWEN_VOICES = DASHSCOPE_VOICES
        private val STEP_VOICES = listOf(
            VoiceInfo("speaker1", "speaker1", "zh-CN"),
            VoiceInfo("speaker2", "speaker2", "zh-CN"),
            VoiceInfo("speaker3", "speaker3", "zh-CN"),
        )
        private val XAI_VOICES = listOf(
            VoiceInfo("Alloy", "Alloy", "en"),
            VoiceInfo("Echo", "Echo", "en"),
            VoiceInfo("Nova", "Nova", "en"),
            VoiceInfo("Onyx", "Onyx", "en"),
        )
    }

    /**
     * 合成文本为音频文件。
     *
     * @param text 待合成文本(已剥离 Markdown)
     * @param engine TTS 引擎:"openai" / "minimax" / "edge" 等
     * @param apiKey API Key
     * @param model 模型名(留空用默认)
     * @param voice 音色(留空用默认)
     * @param endpoint 自定义 endpoint(留空用默认)
     * @param outputFile 输出文件(.mp3)
     * @param cloudConfig v1.99: 引擎参数(stability/similarity/emotion/speed/response_format)
     * @return 成功返回 true,失败返回 false
     */
    suspend fun synthesizeToFile(
        text: String,
        engine: String,
        apiKey: String,
        model: String,
        voice: String,
        endpoint: String,
        outputFile: File,
        cloudConfig: CloudTtsConfig = CloudTtsConfig(),
    ): Boolean = withContext(AppDispatchers.io) {
        // Edge TTS 走兼容代理,endpoint 必须由用户提供(无官方 endpoint)
        if (apiKey.isBlank() && engine != "edge") {
            Logger.w(TAG, "云端 TTS apiKey 为空,跳过")
            return@withContext false
        }

        val audioBytes = resultOf {
            when (engine) {
                "openai" -> synthesizeOpenAI(text, apiKey, model, voice, endpoint, cloudConfig)
                "minimax" -> synthesizeMiniMax(text, apiKey, model, voice, endpoint, cloudConfig)
                "edge" -> synthesizeEdge(text, apiKey, model, voice, endpoint, cloudConfig)
                "gemini" -> synthesizeGemini(text, apiKey, model, voice, endpoint)
                "dashscope" -> synthesizeDashScope(text, apiKey, model, voice, endpoint, cloudConfig)
                "fish" -> synthesizeFishAudio(text, apiKey, model, voice, endpoint)
                "elevenlabs" -> synthesizeElevenLabs(text, apiKey, model, voice, endpoint, cloudConfig)
                "groq" -> synthesizeGroq(text, apiKey, model, voice, endpoint, cloudConfig)
                "qwen" -> synthesizeQwen(text, apiKey, model, voice, endpoint)
                "step" -> synthesizeStep(text, apiKey, model, voice, endpoint, cloudConfig)
                "xai" -> synthesizeXai(text, apiKey, model, voice, endpoint)
                else -> {
                    Logger.w(TAG, "未知 TTS 引擎: $engine")
                    null
                }
            }
        }.onError { _, t ->
            Logger.w(TAG, "云端 TTS 合成失败($engine)", t)
        }.getOrNull()

        if (audioBytes == null || audioBytes.isEmpty()) {
            return@withContext false
        }

        resultOf {
            outputFile.writeBytes(audioBytes)
        }.onError { _, t ->
            Logger.w(TAG, "写入 TTS 音频文件失败", t)
        }.isSuccess
    }

    /**
     * v1.99: 动态拉取指定引擎的音色列表。
     *
     * - ElevenLabs / MiniMax: 走 /voices / /list_voice 实时拉取
     * - 其他引擎: 返回硬编码常用音色(无公开接口或需鉴权)
     * - CLONED: 由 [VoiceCloningService] 管理,返回空(TtsManager 侧处理)
     *
     * @param provider 引擎枚举
     * @param apiKey API Key
     * @param endpoint 自定义 endpoint(留空用默认)
     * @return 音色列表;失败返回空列表
     */
    suspend fun listVoices(
        provider: CloudTtsProvider,
        apiKey: String,
        endpoint: String,
    ): List<VoiceInfo> = withContext(AppDispatchers.io) {
        resultOf {
            when (provider) {
                CloudTtsProvider.ELEVENLABS -> listElevenLabsVoices(apiKey, endpoint)
                CloudTtsProvider.MINIMAX -> listMiniMaxVoices(apiKey, endpoint)
                CloudTtsProvider.OPENAI -> OPENAI_VOICES
                CloudTtsProvider.EDGE -> EDGE_VOICES
                CloudTtsProvider.GEMINI -> GEMINI_VOICES
                CloudTtsProvider.DASHSCOPE -> DASHSCOPE_VOICES
                CloudTtsProvider.FISH -> FISH_VOICES
                CloudTtsProvider.GROQ -> GROQ_VOICES
                CloudTtsProvider.QWEN -> QWEN_VOICES
                CloudTtsProvider.STEP -> STEP_VOICES
                CloudTtsProvider.XAI -> XAI_VOICES
                CloudTtsProvider.CLONED -> emptyList()
            }
        }.onError { _, t ->
            Logger.w(TAG, "listVoices($provider) 失败", t)
        }.getOrNull() ?: emptyList()
    }

    /**
     * OpenAI TTS — POST {endpoint}/audio/speech
     *
     * 请求体: {"model": "...", "input": "...", "voice": "...",
     *          "response_format": "mp3", "speed": 1.0}
     * 响应: mp3 二进制流
     */
    private suspend fun synthesizeOpenAI(
        text: String,
        apiKey: String,
        model: String,
        voice: String,
        endpoint: String,
        cloudConfig: CloudTtsConfig,
    ): ByteArray {
        val baseUrl = endpoint.ifBlank { OPENAI_DEFAULT_ENDPOINT }
        val modelName = model.ifBlank { OPENAI_DEFAULT_MODEL }
        val voiceName = voice.ifBlank { OPENAI_DEFAULT_VOICE }

        val payload = kotlinx.serialization.json.buildJsonObject {
            put("model", JsonPrimitive(modelName))
            put("input", JsonPrimitive(text))
            put("voice", JsonPrimitive(voiceName))
            put("response_format", JsonPrimitive(cloudConfig.responseFormat.ifBlank { "mp3" }))
            if (cloudConfig.speed != 1.0f) {
                put("speed", JsonPrimitive(cloudConfig.speed.coerceIn(0.25f, 4.0f).toDouble()))
            }
        }.toString()

        val req = Request.Builder().url("$baseUrl/audio/speech")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Logger.w(TAG, "OpenAI TTS failed: HTTP ${resp.code}")
                return ByteArray(0)
            }
            return resp.body.bytes()
        }
    }

    /**
     * MiniMax TTS — POST {endpoint}/t2a_v2
     *
     * 请求体: {"model": "...", "text": "...",
     *         "voice_setting": {"voice_id": "...", "speed": ..., "emotion": "..."}}
     * 响应: {"data": {"audio": "hex_encoded_mp3"}}
     */
    private suspend fun synthesizeMiniMax(
        text: String,
        apiKey: String,
        model: String,
        voice: String,
        endpoint: String,
        cloudConfig: CloudTtsConfig,
    ): ByteArray {
        val baseUrl = endpoint.ifBlank { MINIMAX_DEFAULT_ENDPOINT }
        val modelName = model.ifBlank { MINIMAX_DEFAULT_MODEL }
        val voiceName = voice.ifBlank { MINIMAX_DEFAULT_VOICE }

        val payload = kotlinx.serialization.json.buildJsonObject {
            put("model", JsonPrimitive(modelName))
            put("text", JsonPrimitive(text))
            put("voice_setting", kotlinx.serialization.json.buildJsonObject {
                put("voice_id", JsonPrimitive(voiceName))
                if (cloudConfig.speed != 1.0f) {
                    put("speed", JsonPrimitive(cloudConfig.speed.coerceIn(0.5f, 2.0f).toDouble()))
                }
                if (cloudConfig.emotion.isNotBlank()) {
                    put("emotion", JsonPrimitive(cloudConfig.emotion))
                }
            })
        }.toString()

        val req = Request.Builder().url("$baseUrl/t2a_v2")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Logger.w(TAG, "MiniMax TTS failed: HTTP ${resp.code}")
                return ByteArray(0)
            }
            val body = resp.body.string()
            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(body) as? JsonObject ?: return ByteArray(0)
            val dataObj = root["data"] as? JsonObject ?: return ByteArray(0)
            val hexAudio = dataObj["audio"]?.jsonPrimitive?.contentOrNull ?: return ByteArray(0)
            // MiniMax 返回 hex 编码的 mp3
            return hexToBytes(hexAudio)
        }
    }

    /**
     * Edge TTS — 兼容 OpenAI 接口格式。
     *
     * v1.99: 没有官方 OpenAI 兼容 endpoint,[endpoint] 必须由用户填写
     * (社区自建 edge-tts 代理)。endpoint 为空时直接返回空,上层回退系统 TTS。
     */
    private suspend fun synthesizeEdge(
        text: String,
        apiKey: String,
        model: String,
        voice: String,
        endpoint: String,
        cloudConfig: CloudTtsConfig,
    ): ByteArray {
        val baseUrl = endpoint.ifBlank { EDGE_DEFAULT_ENDPOINT }
        if (baseUrl.isBlank()) {
            Logger.w(TAG, "Edge TTS endpoint 未配置,跳过(需用户在设置中填写兼容代理地址)")
            return ByteArray(0)
        }
        val voiceName = voice.ifBlank { "zh-CN-XiaoxiaoNeural" }

        val payload = kotlinx.serialization.json.buildJsonObject {
            put("model", JsonPrimitive(model.ifBlank { "tts-1" }))
            put("input", JsonPrimitive(text))
            put("voice", JsonPrimitive(voiceName))
            put("response_format", JsonPrimitive(cloudConfig.responseFormat.ifBlank { "mp3" }))
            if (cloudConfig.speed != 1.0f) {
                put("speed", JsonPrimitive(cloudConfig.speed.coerceIn(0.25f, 4.0f).toDouble()))
            }
        }.toString()

        val reqBuilder = Request.Builder().url("$baseUrl/audio/speech")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
        // Edge TTS 代理可能不需要 API Key
        if (apiKey.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer $apiKey")
        }
        val req = reqBuilder.build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Logger.w(TAG, "Edge TTS failed: HTTP ${resp.code}")
                return ByteArray(0)
            }
            return resp.body.bytes()
        }
    }

    /** hex 字符串转字节数组。 */
    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    /**
     * Gemini TTS — POST {endpoint}/models/{model}:generateContent
     *
     * 通过 generateContent 端点使用 Gemini 的文本转语音能力。
     * 响应内含 base64 编码的音频数据。
     */
    private suspend fun synthesizeGemini(
        text: String,
        apiKey: String,
        model: String,
        voice: String,
        endpoint: String,
    ): ByteArray {
        val baseUrl = endpoint.ifBlank { "https://generativelanguage.googleapis.com/v1beta" }
        val modelName = model.ifBlank { "gemini-2.5-flash-preview-tts" }
        val voiceName = voice.ifBlank { "Kore" }

        val payload = kotlinx.serialization.json.buildJsonObject {
            put("contents", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.buildJsonObject {
                    put("parts", kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.buildJsonObject {
                            put("text", JsonPrimitive(text))
                        })
                    })
                })
            })
            put("generationConfig", kotlinx.serialization.json.buildJsonObject {
                put("responseModalities", kotlinx.serialization.json.buildJsonArray {
                    add(JsonPrimitive("AUDIO"))
                })
                put("speechConfig", kotlinx.serialization.json.buildJsonObject {
                    put("voiceConfig", kotlinx.serialization.json.buildJsonObject {
                        put("prebuiltVoiceConfig", kotlinx.serialization.json.buildJsonObject {
                            put("voiceName", JsonPrimitive(voiceName))
                        })
                    })
                })
            })
        }.toString()

        val req = Request.Builder().url("$baseUrl/models/$modelName:generateContent")
            .header("Content-Type", "application/json")
            .header("x-goog-api-key", apiKey)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Logger.w(TAG, "Gemini TTS failed: HTTP ${resp.code}")
                return ByteArray(0)
            }
            val body = resp.body.string()
            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(body) as? JsonObject ?: return ByteArray(0)
            // 从 candidates[0].content.parts[0].inlineData.data 提取 base64 音频
            val candidates = root["candidates"]?.let { it as? JsonArray }
            val firstCandidate = candidates?.firstOrNull() as? JsonObject ?: return ByteArray(0)
            val content = firstCandidate["content"] as? JsonObject ?: return ByteArray(0)
            val parts = content["parts"]?.let { it as? JsonArray } ?: return ByteArray(0)
            val firstPart = parts.firstOrNull() as? JsonObject ?: return ByteArray(0)
            val inlineData = firstPart["inlineData"] as? JsonObject ?: return ByteArray(0)
            val base64Audio = inlineData["data"]?.jsonPrimitive?.contentOrNull ?: return ByteArray(0)
            return android.util.Base64.decode(base64Audio, android.util.Base64.DEFAULT)
        }
    }

    /**
     * DashScope TTS (阿里云) — OpenAI 兼容接口。
     *
     * POST {endpoint}/audio/speech
     * 请求体与 OpenAI TTS 格式一致(支持 speed / response_format)。
     */
    private suspend fun synthesizeDashScope(
        text: String,
        apiKey: String,
        model: String,
        voice: String,
        endpoint: String,
        cloudConfig: CloudTtsConfig,
    ): ByteArray {
        val baseUrl = endpoint.ifBlank { DASHSCOPE_DEFAULT_ENDPOINT }
        val modelName = model.ifBlank { DASHSCOPE_DEFAULT_MODEL }
        val voiceName = voice.ifBlank { DASHSCOPE_DEFAULT_VOICE }

        val payload = kotlinx.serialization.json.buildJsonObject {
            put("model", JsonPrimitive(modelName))
            put("input", JsonPrimitive(text))
            put("voice", JsonPrimitive(voiceName))
            put("response_format", JsonPrimitive(cloudConfig.responseFormat.ifBlank { "mp3" }))
            if (cloudConfig.speed != 1.0f) {
                put("speed", JsonPrimitive(cloudConfig.speed.coerceIn(0.25f, 4.0f).toDouble()))
            }
        }.toString()

        val req = Request.Builder().url("$baseUrl/audio/speech")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Logger.w(TAG, "DashScope TTS failed: HTTP ${resp.code}")
                return ByteArray(0)
            }
            return resp.body.bytes()
        }
    }

    /**
     * Fish Audio TTS — POST {endpoint}/tts
     *
     * 请求体: {"text": "...", "format": "mp3", "reference_id": "...", "normalize": true}
     * 响应: mp3 二进制流
     */
    private suspend fun synthesizeFishAudio(
        text: String,
        apiKey: String,
        model: String,
        voice: String,
        endpoint: String,
    ): ByteArray {
        val baseUrl = endpoint.ifBlank { FISH_DEFAULT_ENDPOINT }

        val payload = kotlinx.serialization.json.buildJsonObject {
            put("text", JsonPrimitive(text))
            put("format", JsonPrimitive("mp3"))
            put("normalize", JsonPrimitive(true))
            if (voice.isNotBlank()) {
                put("reference_id", JsonPrimitive(voice))
            }
            if (model.isNotBlank() && model != FISH_DEFAULT_MODEL) {
                put("model", JsonPrimitive(model))
            }
        }.toString()

        val req = Request.Builder().url("$baseUrl/tts")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Logger.w(TAG, "Fish Audio TTS failed: HTTP ${resp.code}")
                return ByteArray(0)
            }
            return resp.body.bytes()
        }
    }

    /**
     * ElevenLabs TTS — POST {endpoint}/text-to-speech/{voice_id}
     *
     * 请求头: xi-api-key + Accept: audio/mpeg
     * 请求体: {"text": "...", "model_id": "eleven_multilingual_v2",
     *         "voice_settings": {"stability": 0.5, "similarity_boost": 0.75}}
     * 响应: audio/mpeg 二进制流
     *
     * voice 参数为 ElevenLabs 的 Voice ID(如 21m00Tcm4TlvDq8ikWAM,含克隆音色)。
     * v1.99: stability / similarity_boost 由 [cloudConfig] 透传(4.8 参数可配置)。
     */
    private suspend fun synthesizeElevenLabs(
        text: String,
        apiKey: String,
        model: String,
        voice: String,
        endpoint: String,
        cloudConfig: CloudTtsConfig,
    ): ByteArray {
        val baseUrl = endpoint.ifBlank { ELEVENLABS_DEFAULT_ENDPOINT }
        val modelName = model.ifBlank { ELEVENLABS_DEFAULT_MODEL }
        val voiceId = voice.ifBlank { ELEVENLABS_DEFAULT_VOICE }

        val payload = kotlinx.serialization.json.buildJsonObject {
            put("text", JsonPrimitive(text))
            put("model_id", JsonPrimitive(modelName))
            put("voice_settings", kotlinx.serialization.json.buildJsonObject {
                put("stability", JsonPrimitive(cloudConfig.stability.coerceIn(0f, 1f).toDouble()))
                put("similarity_boost", JsonPrimitive(cloudConfig.similarityBoost.coerceIn(0f, 1f).toDouble()))
            })
        }.toString()

        val req = Request.Builder().url("$baseUrl/text-to-speech/$voiceId")
            .header("Content-Type", "application/json")
            .header("xi-api-key", apiKey)
            .header("Accept", "audio/mpeg")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Logger.w(TAG, "ElevenLabs 合成失败: HTTP ${resp.code}")
                return ByteArray(0)
            }
            return resp.body.bytes()
        }
    }

    /**
     * Groq TTS — POST {endpoint}/audio/speech(OpenAI 兼容格式)
     *
     * 请求头: Authorization: Bearer {apiKey}
     * 请求体: {"model": "playai-tts", "input": "...", "voice": "Fritz-PlayAI"}
     * 响应: audio/mpeg 二进制流
     */
    private suspend fun synthesizeGroq(
        text: String,
        apiKey: String,
        model: String,
        voice: String,
        endpoint: String,
        cloudConfig: CloudTtsConfig,
    ): ByteArray {
        val baseUrl = endpoint.ifBlank { GROQ_DEFAULT_ENDPOINT }
        val modelName = model.ifBlank { GROQ_DEFAULT_MODEL }
        val voiceName = voice.ifBlank { GROQ_DEFAULT_VOICE }

        val payload = kotlinx.serialization.json.buildJsonObject {
            put("model", JsonPrimitive(modelName))
            put("input", JsonPrimitive(text))
            put("voice", JsonPrimitive(voiceName))
            put("response_format", JsonPrimitive(cloudConfig.responseFormat.ifBlank { "mp3" }))
            if (cloudConfig.speed != 1.0f) {
                put("speed", JsonPrimitive(cloudConfig.speed.coerceIn(0.25f, 4.0f).toDouble()))
            }
        }.toString()

        val req = Request.Builder().url("$baseUrl/audio/speech")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Logger.w(TAG, "Groq 合成失败: HTTP ${resp.code}")
                return ByteArray(0)
            }
            return resp.body.bytes()
        }
    }

    /**
     * Qwen TTS(DashScope 原生接口)— POST {endpoint}/services/audio/tts/text-to-audio
     *
     * 与 dashscope(OpenAI 兼容模式)不同,本接口走 DashScope 原生 API:
     * 请求头: Authorization: Bearer {apiKey}, X-DashScope-DataInspection: enable
     * 请求体: {"model": "cosyvoice-v1", "input": {"text": "..."},
     *         "parameters": {"voice": "longxiaochun"}}
     * 响应: JSON,音频在 output.audio.url 字段(需二次下载)。
     */
    private suspend fun synthesizeQwen(
        text: String,
        apiKey: String,
        model: String,
        voice: String,
        endpoint: String,
    ): ByteArray {
        val baseUrl = endpoint.ifBlank { QWEN_DEFAULT_ENDPOINT }
        val modelName = model.ifBlank { QWEN_DEFAULT_MODEL }
        val voiceName = voice.ifBlank { QWEN_DEFAULT_VOICE }

        val payload = kotlinx.serialization.json.buildJsonObject {
            put("model", JsonPrimitive(modelName))
            put("input", kotlinx.serialization.json.buildJsonObject {
                put("text", JsonPrimitive(text))
            })
            put("parameters", kotlinx.serialization.json.buildJsonObject {
                put("voice", JsonPrimitive(voiceName))
            })
        }.toString()

        val req = Request.Builder().url("$baseUrl/services/audio/tts/text-to-audio")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .header("X-DashScope-DataInspection", "enable")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Logger.w(TAG, "Qwen 合成失败: HTTP ${resp.code}")
                return ByteArray(0)
            }
            val body = resp.body.string()
            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(body) as? JsonObject
                ?: run {
                    Logger.w(TAG, "Qwen 合成失败: 响应非 JSON 格式")
                    return ByteArray(0)
                }
            // DashScope 原生返回结构:output.audio.url(兼容顶层 audio.url)
            val audioUrl = (root["output"] as? JsonObject)
                ?.let { it["audio"] as? JsonObject }
                ?.let { it["url"]?.jsonPrimitive?.contentOrNull }
                ?: (root["audio"] as? JsonObject)?.let { it["url"]?.jsonPrimitive?.contentOrNull }
                ?: run {
                    Logger.w(TAG, "Qwen 合成失败: 响应缺少 output.audio.url")
                    return ByteArray(0)
                }
            // 二次下载音频文件
            val audioReq = Request.Builder().url(audioUrl).build()
            client.newCall(audioReq).execute().use { audioResp ->
                if (!audioResp.isSuccessful) {
                    Logger.w(TAG, "Qwen 合成失败: 音频下载失败 HTTP ${audioResp.code}")
                    return ByteArray(0)
                }
                return audioResp.body.bytes()
            }
        }
    }

    /**
     * StepFun TTS — POST {endpoint}/audio/speech(OpenAI 兼容格式)
     *
     * 请求头: Authorization: Bearer {apiKey}
     * 请求体: {"model": "step-tts-mini", "input": "...", "voice": "speaker1"}
     * 响应: audio/mpeg 二进制流
     */
    private suspend fun synthesizeStep(
        text: String,
        apiKey: String,
        model: String,
        voice: String,
        endpoint: String,
        cloudConfig: CloudTtsConfig,
    ): ByteArray {
        val baseUrl = endpoint.ifBlank { STEP_DEFAULT_ENDPOINT }
        val modelName = model.ifBlank { STEP_DEFAULT_MODEL }
        val voiceName = voice.ifBlank { STEP_DEFAULT_VOICE }

        val payload = kotlinx.serialization.json.buildJsonObject {
            put("model", JsonPrimitive(modelName))
            put("input", JsonPrimitive(text))
            put("voice", JsonPrimitive(voiceName))
            put("response_format", JsonPrimitive(cloudConfig.responseFormat.ifBlank { "mp3" }))
            if (cloudConfig.speed != 1.0f) {
                put("speed", JsonPrimitive(cloudConfig.speed.coerceIn(0.25f, 4.0f).toDouble()))
            }
        }.toString()

        val req = Request.Builder().url("$baseUrl/audio/speech")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Logger.w(TAG, "Step 合成失败: HTTP ${resp.code}")
                return ByteArray(0)
            }
            return resp.body.bytes()
        }
    }

    /**
     * xAI TTS — POST {endpoint}/tts
     *
     * v1.99: 补全占位实现。既有 XAITTSProvider 契约:
     * 请求体: {"text": "...", "voice_id": "...", "language": "..."}
     * 响应: 音频二进制流(mp3)。
     *
     * language 按音色名简单推断(含 "zh" → zh-CN,含 "en" → en-US,否则默认 zh-CN)。
     * 若上游接口实际不可用,调用失败后由上层 [synthesizeToFile] 回退系统 TTS。
     */
    private suspend fun synthesizeXai(
        text: String,
        apiKey: String,
        model: String,
        voice: String,
        endpoint: String,
    ): ByteArray {
        val baseUrl = endpoint.ifBlank { XAI_DEFAULT_ENDPOINT }
        val voiceName = voice.ifBlank { XAI_DEFAULT_VOICE }
        val language = when {
            voiceName.contains("zh", ignoreCase = true) -> "zh-CN"
            voiceName.contains("en", ignoreCase = true) -> "en-US"
            else -> "zh-CN"
        }

        val payload = kotlinx.serialization.json.buildJsonObject {
            put("text", JsonPrimitive(text))
            put("voice_id", JsonPrimitive(voiceName))
            put("language", JsonPrimitive(language))
        }.toString()

        val reqBuilder = Request.Builder().url("$baseUrl/tts")
            .header("Content-Type", "application/json")
            .header("Accept", "audio/mpeg")
            .post(payload.toRequestBody("application/json".toMediaType()))
        if (apiKey.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer $apiKey")
        }
        val req = reqBuilder.build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Logger.w(TAG, "xAI 合成失败: HTTP ${resp.code} (endpoint=$baseUrl, voice=$voiceName)")
                return ByteArray(0)
            }
            return resp.body.bytes()
        }
    }

    /**
     * ElevenLabs 列出所有音色(含克隆 + 预设)。
     *
     * GET {baseUrl}/voices,响应:{"voices":[{"voice_id","name","category",...}]}
     */
    private suspend fun listElevenLabsVoices(apiKey: String, endpoint: String): List<VoiceInfo> {
        if (apiKey.isBlank()) return emptyList()
        val baseUrl = endpoint.ifBlank { ELEVENLABS_DEFAULT_ENDPOINT }
        val req = Request.Builder().url("$baseUrl/voices")
            .header("xi-api-key", apiKey)
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Logger.w(TAG, "ElevenLabs listVoices failed: HTTP ${resp.code}")
                return emptyList()
            }
            val root = Json { ignoreUnknownKeys = true }
                .parseToJsonElement(resp.body.string()) as? JsonObject
                ?: return emptyList()
            val arr = root["voices"] as? JsonArray ?: return emptyList()
            return arr.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val id = obj["voice_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: id
                val category = obj["category"]?.jsonPrimitive?.contentOrNull
                VoiceInfo(id, name, description = category)
            }
        }
    }

    /**
     * MiniMax 列出音色。
     *
     * GET {baseUrl}/list_voice,响应:{"voices":[{"voice_id","voice_name",...}]} 或
     * {"system_support_voices":[{"voice_id",...}]}(兼容两种字段名)。
     */
    private suspend fun listMiniMaxVoices(apiKey: String, endpoint: String): List<VoiceInfo> {
        if (apiKey.isBlank()) return emptyList()
        val baseUrl = endpoint.ifBlank { MINIMAX_DEFAULT_ENDPOINT }
        val req = Request.Builder().url("$baseUrl/list_voice")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Logger.w(TAG, "MiniMax listVoices failed: HTTP ${resp.code}")
                return emptyList()
            }
            val root = Json { ignoreUnknownKeys = true }
                .parseToJsonElement(resp.body.string()) as? JsonObject
                ?: return emptyList()
            val arr = (root["voices"] as? JsonArray)
                ?: (root["system_support_voices"] as? JsonArray)
                ?: return emptyList()
            return arr.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val id = obj["voice_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val name = obj["voice_name"]?.jsonPrimitive?.contentOrNull
                    ?: obj["name"]?.jsonPrimitive?.contentOrNull
                    ?: id
                VoiceInfo(id, name)
            }
        }
    }
}

/**
 * v1.99: 云端 TTS 引擎枚举(用于 [CloudTtsService.listVoices] 类型安全)。
 *
 * 与 [MediaConfig.ttsEngine] 字符串一一对应,CLONED 由 [VoiceCloningService] 管理。
 */
enum class CloudTtsProvider(val engineId: String) {
    OPENAI("openai"),
    MINIMAX("minimax"),
    EDGE("edge"),
    GEMINI("gemini"),
    DASHSCOPE("dashscope"),
    FISH("fish"),
    ELEVENLABS("elevenlabs"),
    GROQ("groq"),
    QWEN("qwen"),
    STEP("step"),
    XAI("xai"),
    CLONED("cloned");

    companion object {
        fun fromEngine(id: String): CloudTtsProvider? = values().firstOrNull { it.engineId == id }
    }
}

/**
 * v1.99: 音色信息(用于动态拉取的音色列表展示与选择)。
 */
data class VoiceInfo(
    val id: String,
    val name: String,
    val language: String? = null,
    val gender: String? = null,
    val description: String? = null,
)

/**
 * v1.99: 云端 TTS 引擎参数(4.8 参数可配置)。
 *
 * 各引擎按需取用:
 *  - ElevenLabs: [stability] / [similarityBoost]
 *  - MiniMax: [emotion] / [speed]
 *  - OpenAI 兼容(openai/dashscope/groq/step/edge): [speed] / [responseFormat]
 *  - 不支持 SSML/参数的引擎降级为默认
 */
data class CloudTtsConfig(
    /** ElevenLabs stability(0-1,默认 0.5)。 */
    val stability: Float = 0.5f,
    /** ElevenLabs similarity_boost(0-1,默认 0.75)。 */
    val similarityBoost: Float = 0.75f,
    /** MiniMax 情感(happy/sad/angry/neutral 等,空表示默认)。 */
    val emotion: String = "",
    /** 合成语速倍率(0.25-4.0,1.0 为正常)。 */
    val speed: Float = 1.0f,
    /** 音频格式(mp3/opus/aac/flac/wav)。 */
    val responseFormat: String = "mp3",
)
