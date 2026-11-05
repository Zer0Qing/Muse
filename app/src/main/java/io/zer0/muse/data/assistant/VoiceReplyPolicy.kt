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
 * 「语音条：多常用语音回你」的档位（整改计划 第 4 条 ④，参照图 3/图 4）。
 *
 * 为什么必须做成**提示词政策而不是客户端开关**：
 * 主聊天里的语音回复是模型自己调用「发语音」工具产生的（`tools/MediaGenToolsRegistrar` 的工具描述），
 * 客户端既没有该拦的东西，硬拦还会变成"用户明确要语音却没发出来"的黑箱。
 * 所以这里只改注入给模型的约定文案。
 */
@Serializable
enum class VoiceReplyFrequency {
    /** 默认档：**不注入任何文案**，与这个功能出现之前的行为逐字一致。 */
    RARELY,

    /** 明确不要：仍然允许用户当场要求时发（政策不是硬闸）。 */
    NEVER,

    /** 常用：气氛合适就主动发。 */
    OFTEN,

    /** 总是：每条正文都配一段语音。 */
    ALWAYS,
    ;

    companion object {
        /** 存不认识的值（比如降级安装、别人手改文件）要回落默认档，不能抛。 */
        fun fromName(raw: String?): VoiceReplyFrequency =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: RARELY
    }
}

/**
 * 档位对应的注入文案（纯函数，独立出来是为了能测"默认档必须什么都不加"这条）。
 *
 * 关键约束：`RARELY`（默认）返回空串。否则这个功能一上线就等于悄悄改了所有用户的系统提示，
 * 而用户什么都没做 —— 这类"上线即变更行为"是我在本项目里反复避免的（浏览器设置默认值同理）。
 */
internal object VoiceReplyPolicy {

    fun instruction(frequency: VoiceReplyFrequency): String = when (frequency) {
        VoiceReplyFrequency.RARELY -> ""
        VoiceReplyFrequency.NEVER ->
            "语音消息约定：默认不要发语音。用户当场明确要求时可以发，但不要把文字内容额外念一遍发出去。"
        VoiceReplyFrequency.OFTEN ->
            "语音消息约定：在气氛合适时主动发语音（尤其是安慰、撒娇、读短诗这类适合听的表达），" +
                "不必等用户开口；纯信息性的回答仍以文字为主。"
        VoiceReplyFrequency.ALWAYS ->
            "语音消息约定：每条回复都配一段语音，内容就是本条正文里适合听的那部分，不要念旁白或代码。"
    }
}

/** 每个助手一档，落 `filesDir/voice_reply_policy.json`。 */
@Serializable
private data class VoiceReplyPolicyFile(
    val byAgent: Map<String, String> = emptyMap(),
)

class VoiceReplyPolicyStore private constructor(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val _byAgent = MutableStateFlow(emptyMap<String, String>())

    /** 原始映射（供界面显示当前档位）；坏文件当空表处理。 */
    val snapshot: StateFlow<Map<String, String>> = _byAgent.asStateFlow()

    init {
        runCatching { _byAgent.value = readFromDisk() }
    }

    suspend fun frequencyFor(agentId: String): VoiceReplyFrequency =
        VoiceReplyFrequency.fromName(current()[agentId])

    suspend fun set(agentId: String, frequency: VoiceReplyFrequency): Result<Unit> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                resultOf<Unit> {
                    val next = current().toMutableMap().apply { put(agentId, frequency.name) }
                    writeToDisk(next)
                    _byAgent.value = next
                }
            }
        }

    private suspend fun current(): Map<String, String> =
        _byAgent.value.ifEmpty { readFromDisk().also { _byAgent.value = it } }

    private fun readFromDisk(): Map<String, String> {
        if (!file.isFile) return emptyMap()
        return runCatching { json.decodeFromString(VoiceReplyPolicyFile.serializer(), file.readText()).byAgent }
            .getOrDefault(emptyMap())
    }

    private fun writeToDisk(map: Map<String, String>) {
        // 与约定表同一套纪律：临时文件 + 替换，不留半截 JSON
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(json.encodeToString(VoiceReplyPolicyFile.serializer(), VoiceReplyPolicyFile(map)))
        if (file.exists() && !file.delete() && !tmp.delete()) {
            error("旧文件删不掉，语音档位未保存")
        }
        if (!tmp.renameTo(file)) {
            error("写入失败")
        }
    }

    companion object {
        const val FILE_NAME = "voice_reply_policy.json"

        @Volatile
        private var shared: VoiceReplyPolicyStore? = null

        fun of(context: Context): VoiceReplyPolicyStore {
            shared?.let { return it }
            return synchronized(this) {
                shared ?: VoiceReplyPolicyStore(context).also { shared = it }
            }
        }

        /** 仅测试用：隔离单例。 */
        internal fun resetForTests() {
            synchronized(this) { shared = null }
        }
    }
}
