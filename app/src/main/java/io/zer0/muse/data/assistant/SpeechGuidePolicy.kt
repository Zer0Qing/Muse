package io.zer0.muse.data.assistant

import android.content.Context
import io.zer0.common.Result
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 「发言指南」：约束最终发到对话里的**回复正文**怎么写。
 *
 * 为什么做成提示词政策而不是客户端后处理：
 * 客户端拿不到「哪部分是正文、哪部分是内省/工具记录」这层语义，硬改只会把工具调用记录也一起改坏。
 * 所以只往系统提示里加一段写作约束，让模型自己区分。
 *
 * 与语音约定（[VoiceReplyPolicy]）同一套做法：纯函数注入，**默认档原样返回 base** ——
 * 这个功能上线不能让所有用户的系统提示悄悄多出一段。
 */
@Serializable
enum class SpeechGuideStyle {
    /** 默认档：**不注入任何文案**，与这个功能出现之前的行为逐字一致。 */
    OFF,

    /** 口语优先：像即时通讯里的自然发言；内容长或需要结构化时才转正式表达。 */
    CONVERSATIONAL,
    ;

    companion object {
        /** 存不认识的值（降级安装、手改文件）回落默认档，不能抛。 */
        fun fromName(raw: String?): SpeechGuideStyle = entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: OFF
    }
}

internal object SpeechGuidePolicy {

    /**
     * 把发言指南拼到已组装好的提示词末尾。
     *
     * 默认档（[SpeechGuideStyle.OFF]）返回的串与传进来的**完全相同** —— 这条要有断言钉住：
     * 拼接处若多一个空行或分隔符，所有用户的提示词尾端都会变。
     */
    fun inject(base: String, style: SpeechGuideStyle): String {
        val section = instruction(style)
        if (section.isBlank()) return base
        return if (base.isBlank()) section else "$base$SPEECH_GUIDE_SEPARATOR$section"
    }

    fun instruction(style: SpeechGuideStyle): String = when (style) {
        SpeechGuideStyle.OFF -> ""
        SpeechGuideStyle.CONVERSATIONAL ->
            "发言指南：发到对话里的回复正文要像即时通讯里的自然发言，优先口语化、轻一点，" +
                "避免写成正式文章；如果内容很长，或需要清单、步骤、代码、严谨解释，可以改用清楚的结构化表达。" +
                "这条只约束最终发送的正文，不约束思考过程、工具调用记录，也不约束给用户看的界面文案。"
    }
}

/** 发言指南与既有提示词之间的分隔符（与语音约定同一个，避免两段政策拼出两种排版）。 */
internal const val SPEECH_GUIDE_SEPARATOR: String = VOICE_POLICY_SEPARATOR

/**
 * 发言指南的落盘：`filesDir/speech_guide_policy.json`。
 *
 * 只做**全局一档**，不按助手覆盖 —— 这是「回复像不像聊天」这种全应用一致的写作习惯，
 * 逐助手配置只会多出一层要维护的覆盖表。要按助手分档时再扩，不动现有键。
 */
@Serializable
private data class SpeechGuideFile(
    val style: String = SpeechGuideStyle.OFF.name,
    val schema: Int = 0,
)

class SpeechGuideStore private constructor(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val styleState = MutableStateFlow(SpeechGuideStyle.OFF)

    /** 当前档位（供界面显示）。坏文件当默认档处理。 */
    val snapshot: StateFlow<SpeechGuideStyle> = styleState.asStateFlow()

    init {
        runCatching { styleState.value = readFromDisk() }
    }

    suspend fun style(): SpeechGuideStyle = styleState.value.ifEmpty { readFromDisk().also { styleState.value = it } }

    suspend fun set(style: SpeechGuideStyle): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            resultOf<Unit> {
                writeToDisk(style)
                styleState.value = style
            }
        }
    }

    /** 默认档是「未设置」的等价物：读回不认识的值一律回落 OFF，不抛。 */
    private fun SpeechGuideStyle.ifEmpty(read: () -> SpeechGuideStyle): SpeechGuideStyle =
        if (this == SpeechGuideStyle.OFF) read() else this

    private fun readFromDisk(): SpeechGuideStyle {
        if (!file.isFile) return SpeechGuideStyle.OFF
        return runCatching {
            val decoded = json.decodeFromString(SpeechGuideFile.serializer(), file.readText())
            SpeechGuideStyle.fromName(decoded.style)
        }.getOrDefault(SpeechGuideStyle.OFF)
    }

    private fun writeToDisk(style: SpeechGuideStyle) {
        // 与语音档位同一套纪律：临时文件 + 替换，不留半截 JSON
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(json.encodeToString(SpeechGuideFile.serializer(), SpeechGuideFile(style.name, CURRENT_SCHEMA)))
        if (file.exists() && !file.delete() && !tmp.delete()) {
            error("旧文件删不掉，发言指南未保存")
        }
        if (!tmp.renameTo(file)) {
            error("写入失败")
        }
    }

    companion object {
        const val FILE_NAME = "speech_guide_policy.json"

        /** 当前文件 schema；旧文件无此字段解码为 0。 */
        private const val CURRENT_SCHEMA = 1

        @Volatile
        private var shared: SpeechGuideStore? = null

        fun of(context: Context): SpeechGuideStore {
            shared?.let { return it }
            return synchronized(this) {
                shared ?: SpeechGuideStore(context).also { shared = it }
            }
        }

        /** 仅测试用：隔离单例。 */
        internal fun resetForTests() {
            synchronized(this) { shared = null }
        }
    }
}
