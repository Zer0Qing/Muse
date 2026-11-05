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
import java.util.UUID

/**
 * 用户对助手立下的具体规矩：「希望它做的事 / 不希望它做的事」。
 *
 * 参照平台设置页底部那张卡（图 4）就是这个东西；在 Muse 里它是**主动消息与闲聊都能读到**的
 * 一层长期偏好。人设卡粒度太粗（要重写整张卡才能说一句"别叫我宝贝"），
 * 会话级记忆又太不稳定（用户要的是长期规矩，不是"它可能记得"）。
 */
@Serializable
enum class DirectivePolarity {
    /** 要做的（如"早上提醒我吃早饭"）。 */
    SHOULD,

    /** 不要做的（如"别叫我宝贝"）。装配时永远排在 [SHOULD] 之前，冲突以它为准。 */
    MUST_NOT,
}

/**
 * 一条约定。
 *
 * @param agentId 属于哪个助手：**不按会话分**，因为这是"我对这个人的长期要求"；
 *                也不能全局共享，那样会让"给 A 立的规矩"作用到 B 身上。
 * @param position 用户手动排序的序号；相同则按创建时间。
 */
@Serializable
data class AgentDirective(
    val id: String = UUID.randomUUID().toString(),
    val agentId: String,
    val polarity: DirectivePolarity,
    val text: String,
    val position: Int = 0,
    val enabled: Boolean = true,
    val createdAt: Long = 0L,
)

/**
 * 文件型存储（`filesDir/agent_directives.json`）。
 *
 * 为什么不用 Room：新表要把 `MuseDb` 的 schema 版本 124 推到 125 并补一段迁移，
 * 而 `check_migration_coverage` 硬性要求迁移链覆盖 1..N-1 —— 为一份几百字的偏好表
 * 动 3893 行的 `MuseDb` 与迁移链，风险与收益不成比例。
 * 写法沿用本仓既有先例（`WorkspaceMountStore`、`BrowserSettingsStore`）：
 * 文件是唯一事实，内存 `StateFlow` 只是它的镜像。
 */
@Serializable
private data class AgentDirectiveFile(
    val directives: List<AgentDirective> = emptyList(),
)

class AgentDirectiveStore private constructor(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val mutex = Mutex()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val _all = MutableStateFlow(emptyList<AgentDirective>())

    /** 全部约定（未按助手过滤）；界面用 [directivesFor] 取自己那一份。 */
    val all: StateFlow<List<AgentDirective>> = _all.asStateFlow()

    init {
        // 读文件是 IO，但构造点在 UI 线程；这里只把结果塞进内存镜像，失败留空表
        // （空表 = 与改动前逐字相同的提示词，不会因读盘失败而乱加内容）。
        runCatching { _all.value = readFromDisk() }
    }

    /** 某个助手的约定，已按「不希望在前 + position + 创建时间」排好装配顺序。 */
    suspend fun directivesFor(agentId: String): List<AgentDirective> = withContext(Dispatchers.IO) {
        mutex.withLock { current() }.filter { it.agentId == agentId }.sortedWith(
            compareByDescending<AgentDirective> { it.polarity == DirectivePolarity.MUST_NOT }
                .thenBy { it.position }
                .thenBy { it.createdAt },
        )
    }

    /** 追加一条。空文本与超长文本都在这里拒掉，界面才不用再判一遍。 */
    suspend fun add(agentId: String, polarity: DirectivePolarity, text: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val trimmed = text.trim()
            when {
                trimmed.isEmpty() -> Result.Error("内容不能为空")
                trimmed.length > MAX_TEXT_CHARS ->
                    Result.Error("单条最多 $MAX_TEXT_CHARS 字")

                else -> {
                    val list = current()
                    val next = list + AgentDirective(
                        agentId = agentId,
                        polarity = polarity,
                        text = trimmed,
                        position = nextPosition(list, polarity),
                        createdAt = System.currentTimeMillis(),
                    )
                    return@withLock resultOf<Unit> {
                        writeToDisk(next)
                        _all.value = next
                    }
                }
            }
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean): Result<Unit> = mutate { list ->
        list.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }

    suspend fun remove(id: String): Result<Unit> = mutate { list -> list.filterNot { it.id == id } }

    /**
     * 在同极性内上下移动。越界时**原样返回并成功**：
     * 界面上首行点"上移"不该弹错误，只是不动。
     */
    suspend fun move(id: String, delta: Int): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = current()
            val target = list.firstOrNull { it.id == id }
                ?: return@withLock Result.Error("约定不存在")
            val same = list.filter { it.agentId == target.agentId && it.polarity == target.polarity }
                .sortedBy { it.position }
            val index = same.indexOfFirst { it.id == id }
            val swapWith = index + delta
            if (index < 0 || swapWith !in same.indices) {
                return@withLock Result.Success(Unit)
            }
            val reordered = same.toMutableList().apply { add(swapWith, removeAt(index)) }
            val positions = reordered.mapIndexed { i, item -> item.id to i }.toMap()
            val next = list.map { item -> item.copy(position = positions[item.id] ?: item.position) }
            val outcome = resultOf { writeToDisk(next) }
            if (outcome.isSuccess) _all.value = next
            outcome
        }
    }

    private suspend fun mutate(block: (List<AgentDirective>) -> List<AgentDirective>): Result<Unit> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                resultOf<Unit> {
                    val next = block(current())
                    writeToDisk(next)
                    _all.value = next
                }
            }
        }

    private suspend fun current(): List<AgentDirective> =
        _all.value.ifEmpty { readFromDisk().also { _all.value = it } }

    private fun readFromDisk(): List<AgentDirective> {
        if (!file.isFile) return emptyList()
        return runCatching { json.decodeFromString(AgentDirectiveFile.serializer(), file.readText()).directives }
            .getOrDefault(emptyList())
    }

    private fun writeToDisk(directives: List<AgentDirective>) {
        // 先写临时文件再原子替换：进程被杀不该留下半截 JSON
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(json.encodeToString(AgentDirectiveFile.serializer(), AgentDirectiveFile(directives)))
        if (file.exists() && !file.delete() && !tmp.delete()) {
            error("旧文件删不掉，约定未保存")
        }
        if (!tmp.renameTo(file)) {
            error("写入失败")
        }
    }

    private fun nextPosition(list: List<AgentDirective>, polarity: DirectivePolarity): Int =
        list.filter { it.polarity == polarity }.maxOfOrNull { it.position }?.plus(1) ?: 0

    companion object {
        const val FILE_NAME = "agent_directives.json"

        /** 单条与总量的上限。超量的条目由装配层挑出来并让界面说清"哪几条没生效"。 */
        const val MAX_TEXT_CHARS = 200
        const val MAX_TOTAL_CHARS = 600

        @Volatile
        private var shared: AgentDirectiveStore? = null

        fun of(context: Context): AgentDirectiveStore {
            shared?.let { return it }
            return synchronized(this) {
                shared ?: AgentDirectiveStore(context).also { shared = it }
            }
        }

        /** 仅测试用：隔离单例，避免用例互相污染。 */
        internal fun resetForTests() {
            synchronized(this) { shared = null }
        }
    }
}
