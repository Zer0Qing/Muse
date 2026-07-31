package io.zer0.muse.data.subagent

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.util.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v1.0.53: 子 agent 会话历史 JSONL 持久化(对标 Hana childSessionPath)。
 *
 * 路径: filesDir/subagent_sessions/<threadId>.jsonl
 * 每行一个 JSON: UIMessage 序列化
 *
 * 裁剪策略(按 token 估算):
 *  - 用 [TokenEstimator] 估算累计 token,超过 maxContextTokens 时从首部裁剪
 *  - 保留 SYSTEM 消息(子 agent 系统提示词永不裁剪)
 *
 * 容错:
 *  - 读取时遇到非法行(损坏)跳过并 log warn,不抛异常
 *  - 文件不存在时返回空列表(视为全新线程)
 */
class SubagentSessionStore(
    private val sessionsDir: File,
    private val tokenEstimator: TokenEstimator = TokenEstimator,
) {
    companion object {
        private const val TAG = "SubagentSessionStore"
    }

    init {
        if (!sessionsDir.exists()) sessionsDir.mkdirs()
    }

    /**
     * 追加一批消息到 thread 的会话文件(增量写)。
     * 文件不存在时自动创建。
     */
    suspend fun append(threadId: String, messages: List<UIMessage>): io.zer0.common.Result<Unit> = withContext(Dispatchers.IO) {
        resultOf {
            if (messages.isEmpty()) return@resultOf
            val file = pathOf(threadId)
            file.parentFile?.mkdirs()
            file.appendText(messages.joinToString("") { msg ->
                AppJson.encodeToString(UIMessage.serializer(), msg) + "\n"
            })
        }
    }

    /**
     * 加载 thread 的会话历史,按 token 预算裁剪首部。
     *
     * @param maxContextTokens 裁剪上限(默认 6000,约为子助手上下文窗口的 50%)
     * @return 裁剪后的消息列表(SYSTEM 消息保留 + 最近 N 条非 SYSTEM 消息)
     */
    suspend fun load(threadId: String, maxContextTokens: Int = 6000): List<UIMessage> = withContext(Dispatchers.IO) {
        val file = pathOf(threadId)
        if (!file.exists()) return@withContext emptyList()

        val all = mutableListOf<UIMessage>()
        file.useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val parsed = resultOf {
                    AppJson.decodeFromString(UIMessage.serializer(), line)
                }.getOrNull()
                if (parsed != null) {
                    all.add(parsed)
                } else {
                    Logger.w(TAG, "跳过损坏的 JSONL 行: ${line.take(80)}")
                }
            }
        }

        if (all.isEmpty()) return@withContext emptyList()

        // 分离 SYSTEM 和非 SYSTEM 消息
        val systemMsgs = all.filter { it.role == MessageRole.SYSTEM }
        val nonSystem = all.filter { it.role != MessageRole.SYSTEM }

        // 从末尾向前累加 token,超限时停止
        val kept = mutableListOf<UIMessage>()
        var spent = 0
        for (msg in nonSystem.asReversed()) {
            val cost = tokenEstimator.estimate(msg.content) + 4
            if (spent + cost > maxContextTokens && kept.isNotEmpty()) break
            spent += cost
            kept.add(0, msg) // 保持原顺序
        }

        // SYSTEM 在前 + 裁剪后的非 SYSTEM
        return@withContext systemMsgs + kept
    }

    /** 删除 thread 的会话文件(close 时可选调用)。 */
    suspend fun delete(threadId: String): io.zer0.common.Result<Unit> = withContext(Dispatchers.IO) {
        resultOf {
            val file = pathOf(threadId)
            if (file.exists()) file.delete()
        }
    }

    /** 诊断:获取 thread 的会话文件路径(可能不存在)。 */
    fun pathOf(threadId: String): File = File(sessionsDir, "$threadId.jsonl")
}
