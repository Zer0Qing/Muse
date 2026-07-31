package io.zer0.muse.tools.script

import io.zer0.common.Logger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Skill 引擎池 (P3-1)。
 *
 * 管理多个 [SkillEngine] 实例，通过 Channel 实现复用，避免频繁创建/销毁。
 *
 * 设计要点：
 *  - 池大小默认 1（当前受 JsSandbox 单例 WebView 限制，实际串行执行）
 *  - [withEngine] 借用-归还模式：从 Channel 取引擎，执行完后归还
 *  - 若池为空（所有引擎都在使用），调用方挂起等待
 *  - 未来接入 QuickJS 后，可设置 poolSize=4 实现真正并行
 *
 * 用法：
 * ```kotlin
 * val pool = SkillEnginePool(poolSize = 1)
 * val result = pool.withEngine { engine ->
 *     engine.eval("1 + 2")
 * }
 * ```
 *
 * 参考: Operit SkillEnginePool 设计，适配 Muse 的 WebView V8 后端。
 *
 * @param poolSize 池大小，默认 1（WebView V8 串行限制）
 */
class SkillEnginePool(
    poolSize: Int = DEFAULT_POOL_SIZE,
    private val engineFactory: () -> SkillEngine = ::WebViewSkillEngine,
) {
    private val channel = Channel<SkillEngine>(capacity = poolSize.coerceAtLeast(1))
    private val creationMutex = Mutex()
    private val maxPoolSize = poolSize.coerceAtLeast(1)
    @Volatile private var createdCount = 0
    @Volatile private var closed = false

    init {
        // 预创建第一个引擎（其余按需创建）
        // 注意：WebView 必须在主线程创建，延迟到首次 withEngine 调用时创建
    }

    /**
     * 借用引擎执行操作，执行完后自动归还到池中。
     *
     * @param block 使用引擎的挂起代码块
     * @return 代码块的返回值
     */
    suspend fun <T> withEngine(block: suspend (SkillEngine) -> T): T {
        val engine = acquire()
        return try {
            block(engine)
        } finally {
            release(engine)
        }
    }

    /**
     * 获取池中可用引擎（若池空则按需创建，达上限则挂起等待）。
     */
    private suspend fun acquire(): SkillEngine {
        // 先尝试从 Channel 非阻塞取
        val polled = channel.tryReceive()
        if (polled.isSuccess) {
            return polled.getOrThrow()
        }
        // 池空，尝试创建新引擎
        if (createdCount < maxPoolSize) {
            creationMutex.withLock {
                if (createdCount < maxPoolSize) {
                    createdCount++
                    Logger.d(TAG, "创建新 SkillEngine (count=$createdCount/$maxPoolSize)")
                    return engineFactory()
                }
            }
        }
        // 已达上限，挂起等待归还
        Logger.d(TAG, "引擎池已满，等待可用引擎...")
        return channel.receive()
    }

    /**
     * 归还引擎到池中。
     */
    private fun release(engine: SkillEngine) {
        if (closed) return
        val result = channel.trySend(engine)
        if (result.isFailure) {
            Logger.w(TAG, "引擎池已关闭或已满，丢弃引擎")
        }
    }

    /**
     * 关闭引擎池，释放资源。
     */
    fun close() {
        if (closed) return
        closed = true
        channel.close()
        Logger.i(TAG, "SkillEnginePool 已关闭")
    }

    companion object {
        private const val TAG = "SkillEnginePool"

        /** 默认池大小 1（受 WebView 单例限制）。 */
        const val DEFAULT_POOL_SIZE = 1
    }
}
