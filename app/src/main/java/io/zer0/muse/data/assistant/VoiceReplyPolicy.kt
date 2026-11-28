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
        fun fromName(raw: String?): VoiceReplyFrequency = entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: RARELY
    }
}

/**
 * 档位对应的注入文案（纯函数，独立出来是为了能测"默认档必须什么都不加"这条）。
 *
 * 关键约束：`RARELY`（默认）返回空串。否则这个功能一上线就等于悄悄改了所有用户的系统提示，
 * 而用户什么都没做 —— 这类"上线即变更行为"是我在本项目里反复避免的（浏览器设置默认值同理）。
 */
/** 政策段与既有提示词之间的统一分隔符（顶层常量；放 object 里会被 ktlint 的 property-naming 判成驼峰违规）。 */
internal const val VOICE_POLICY_SEPARATOR = "\n\n---\n\n"

internal object VoiceReplyPolicy {

    /**
     * 把政策段拼到已组装好的提示词末尾。
     *
     * 做成纯函数是要能断言这一条：**默认档返回的串与传进来的串完全相同**。
     * 只在字符串函数上测"空串"是不够的 —— 拼接处若加了空行或分隔符，
     * 上线时所有用户的提示词尾端都会多出东西，而功能默认是关的。
     */
    fun inject(base: String, frequency: VoiceReplyFrequency): String {
        val section = instruction(frequency)
        if (section.isBlank()) return base
        return if (base.isBlank()) section else "$base$VOICE_POLICY_SEPARATOR$section"
    }

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

/** 全局默认档 + 按助手覆盖 + 自动生成语音条开关，落 `filesDir/voice_reply_policy.json`。 */
@Serializable
private data class VoiceReplyPolicyFile(
    val byAgent: Map<String, String> = emptyMap(),
    val autoVoiceBarByAgent: Map<String, Boolean> = emptyMap(),
    val schema: Int = 0,
)

class VoiceReplyPolicyStore private constructor(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    // 反例字段没用 `_x` 前缀：ktlint 的 property-naming 不容忍下划线开头（Compose 惯例在此让位于门禁）。
    private val byAgentState = MutableStateFlow(emptyMap<String, String>())
    private val autoBarState = MutableStateFlow(emptyMap<String, Boolean>())

    /** 原始映射（供界面显示当前档位）；坏文件当空表处理。 */
    val snapshot: StateFlow<Map<String, String>> = byAgentState.asStateFlow()

    /** 自动语音条开关映射（`__global__` + 各 assistantId 显式覆盖）。 */
    val autoVoiceBarSnapshot: StateFlow<Map<String, Boolean>> = autoBarState.asStateFlow()

    init {
        runCatching {
            val loaded = readPolicyFile()
            byAgentState.value = loaded.first
            autoBarState.value = loaded.second
        }
    }

    /** 某助手生效档：显式覆盖优先，否则跟随全局默认。 */
    suspend fun frequencyFor(agentId: String): VoiceReplyFrequency {
        val override = current()[agentId]
        if (override != null) return VoiceReplyFrequency.fromName(override)
        return globalFrequency()
    }

    /** 某助手是否自动附带语音条（默认关闭；显式覆盖优先于全局）。 */
    suspend fun autoVoiceBarFor(agentId: String): Boolean {
        val map = currentAutoBars()
        return map[agentId] ?: map[GLOBAL_KEY] ?: false
    }

    /** 全局默认档；未设回落 RARELY。 */
    suspend fun globalFrequency(): VoiceReplyFrequency = VoiceReplyFrequency.fromName(current()[GLOBAL_KEY])

    suspend fun setGlobal(frequency: VoiceReplyFrequency): Result<Unit> = mutate { it[GLOBAL_KEY] = frequency.name }

    suspend fun set(agentId: String, frequency: VoiceReplyFrequency): Result<Unit> = mutate { it[agentId] = frequency.name }

    /** 清除某助手覆盖＝让它跟随全局默认。 */
    suspend fun clearOverride(agentId: String): Result<Unit> = mutate { it.remove(agentId) }

    suspend fun setGlobalAutoVoiceBar(enabled: Boolean): Result<Unit> = mutateAutoBar { it[GLOBAL_KEY] = enabled }

    suspend fun setAutoVoiceBar(agentId: String, enabled: Boolean): Result<Unit> = mutateAutoBar { it[agentId] = enabled }

    suspend fun clearAutoVoiceBarOverride(agentId: String): Result<Unit> = mutateAutoBar { it.remove(agentId) }

    private suspend fun mutate(transform: (MutableMap<String, String>) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            resultOf<Unit> {
                val (freqMap, autoMap) = readPolicyFile()
                val nextFreq = freqMap.toMutableMap().apply(transform)
                writePolicyFile(nextFreq, autoMap)
                byAgentState.value = nextFreq
                autoBarState.value = autoMap
            }
        }
    }

    private suspend fun mutateAutoBar(transform: (MutableMap<String, Boolean>) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            resultOf<Unit> {
                val (freqMap, autoMap) = readPolicyFile()
                val nextAuto = autoMap.toMutableMap().apply(transform)
                writePolicyFile(freqMap, nextAuto)
                byAgentState.value = freqMap
                autoBarState.value = nextAuto
            }
        }
    }

    private suspend fun current(): Map<String, String> = byAgentState.value.ifEmpty {
        readPolicyFile().first.also { byAgentState.value = it }
    }

    private suspend fun currentAutoBars(): Map<String, Boolean> = autoBarState.value.ifEmpty {
        readPolicyFile().second.also { autoBarState.value = it }
    }

    private fun readPolicyFile(): Pair<Map<String, String>, Map<String, Boolean>> {
        if (!file.isFile) return emptyMap<String, String>() to emptyMap()
        return runCatching {
            val decoded = json.decodeFromString(VoiceReplyPolicyFile.serializer(), file.readText())
            val freq = if (decoded.schema < CURRENT_SCHEMA) {
                decoded.byAgent.filterValues { VoiceReplyFrequency.fromName(it) != VoiceReplyFrequency.RARELY }
            } else {
                decoded.byAgent
            }
            freq to decoded.autoVoiceBarByAgent
        }.getOrDefault(emptyMap<String, String>() to emptyMap())
    }

    private fun writePolicyFile(freqMap: Map<String, String>, autoMap: Map<String, Boolean>) {
        // 与约定表同一套纪律：临时文件 + 替换，不留半截 JSON
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(
            json.encodeToString(
                VoiceReplyPolicyFile.serializer(),
                VoiceReplyPolicyFile(byAgent = freqMap, autoVoiceBarByAgent = autoMap, schema = CURRENT_SCHEMA),
            ),
        )
        if (file.exists() && !file.delete() && !tmp.delete()) {
            error("旧文件删不掉，语音档位未保存")
        }
        if (!tmp.renameTo(file)) {
            error("写入失败")
        }
    }

    companion object {
        const val FILE_NAME = "voice_reply_policy.json"

        /** 当前文件 schema；旧文件无此字段解码为 0，触发一次性迁移。 */
        private const val CURRENT_SCHEMA = 1

        /** 全局默认档在 byAgent 表里的保留键（不会与真实 agentId 冲突）。 */
        internal const val GLOBAL_KEY = "__global__"

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
