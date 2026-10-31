package io.zer0.muse.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 按路径分配的进程内互斥锁。
 *
 * 粒度为「一个文件」：同一路径的读—改—写串行，不同路径各自并行。工作区是多会话、
 * 子代理、定时任务共用的，两处同时改同一份文件时若各读各的再各写各的，
 * 后写的那次会把先写的那次整段覆盖掉，且两边都拿到「成功」。
 *
 * 键先经 [WorkspacePathParser] 归一（`a.txt`、`./a.txt`、`/a.txt` 落到同一个键），
 * 再整体折成小写：大小写不敏感的文件系统上 `A.TXT` 与 `a.txt` 是同一个文件，
 * 必须共用一把锁；大小写敏感的文件系统上最多把两个不同文件排到一把锁上，
 * 只损失一点并行度，不会丢更新。
 *
 * 已知限制：键是「挂载标识 + 相对路径」，不做 canonicalPath 解析 —— SAF 挂载本来拿不到
 * 真实路径，只对本地挂载做合一会让「什么时候合并、什么时候不合并」变得不可预测；
 * 因此符号链接别名、以及两条挂载指向同一物理目录时，各拿各的锁，不保证互斥。
 *
 * 锁表是进程内、按 [WorkspaceManager] 实例隔离的。当前只有 `AppKoinModule` 里的单例
 * 参与写入；若将来出现第二个参与写入的实例，两份锁表互不感知 —— 新建 manager 去写文件前，
 * 要么复用它，要么把锁表提到共享作用域，否则会出现本类就是为了避免的那种丢更新。
 *
 * 本类是单进程内的表，不跨进程、不落盘。
 *
 * @param acquireTimeoutMs 等锁上限（毫秒）；超时抛 [WorkspaceIoException] 而不是无限等，
 *   这样一个未释放锁的调用方只会拖慢它自己那一次请求，不会让后续写入永久挂住
 */
internal class WorkspacePathLock(
    private val acquireTimeoutMs: Long = DEFAULT_ACQUIRE_TIMEOUT_MS,
) {
    /** 一个键上的锁与参与者计数。 */
    private class Entry {
        val mutex = Mutex()

        /** 持有者 + 等待者总数；归零时从表里摘掉，避免路径越用越多把表撑大。 */
        var participants = 0
    }

    /** 保护 [entries] 自身。 */
    private val registry = Mutex()

    private val entries = HashMap<String, Entry>()

    /**
     * 在目标路径的锁内执行 [block]。
     *
     * 锁不可重入：持锁块内部不要再调用会加锁的方法（见 [WorkspaceManager] 里的约定）。
     *
     * @param mountId 目标挂载标识
     * @param relative 挂载内相对路径
     * @param display 给用户看的路径，只用于超时文案
     */
    suspend fun <T> withLock(mountId: String, relative: String, display: String, block: suspend () -> T): T {
        val key = keyOf(mountId, relative)
        val entry = registry.withLock { entries.getOrPut(key) { Entry() }.also { it.participants++ } }
        val acquired = try {
            withTimeoutOrNull(acquireTimeoutMs) { entry.mutex.lock() }
        } catch (e: CancellationException) {
            release(key, entry)
            throw e
        }
        if (acquired == null) {
            release(key, entry)
            throw WorkspaceIoException(
                "等待文件锁超时(${acquireTimeoutMs}ms)：另一个会话或任务正在写「$display」," +
                    "本次未执行,请稍后重试",
            )
        }
        try {
            return block()
        } finally {
            entry.mutex.unlock()
            release(key, entry)
        }
    }

    /** 归一化锁键：挂载标识 + 规范化相对路径，整体折小写。 */
    fun keyOf(mountId: String, relative: String): String = WorkspacePathParser.format(mountId, relative).lowercase()

    /** 参与者退场；这张表只在还有人持有或等待时保留该键。 */
    private suspend fun release(key: String, entry: Entry) {
        withContext(NonCancellable) {
            registry.withLock {
                entry.participants--
                if (entry.participants <= 0 && entries[key] === entry) entries.remove(key)
            }
        }
    }

    companion object {
        /** 默认等锁上限：15 秒。单次写入是毫秒级的，等这么久说明有调用方异常未释放。 */
        const val DEFAULT_ACQUIRE_TIMEOUT_MS: Long = 15_000L
    }
}
