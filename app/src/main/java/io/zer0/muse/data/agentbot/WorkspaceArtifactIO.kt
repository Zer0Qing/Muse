package io.zer0.muse.data.agentbot

import io.zer0.common.Logger
import java.io.File

/**
 * 工作区文件的读写实现 —— [BotResultApplier] 依赖的两个回调。
 *
 * ## 为什么单独抽出来
 *
 * `BotResultApplier` 只关心"读当前哈希、写新内容"，不关心内容存在哪。
 * 抽出来之后：
 *  - 单测可以注入内存实现，不必碰真实文件系统；
 *  - 将来接文档库 / 知识库只需换一个实现，applier 一行不改。
 *
 * ## 路径约束
 *
 * 所有路径都被限制在工作区根目录下。`..` 与绝对路径一律拒绝 ——
 * 这是"可改动范围"的最后一道闸，不能靠上层校验兜住。
 */
class WorkspaceArtifactIO(
    /** 工作区根目录。 */
    private val root: File,
    /** 上次兜底扫描时刻。用 @Volatile 因为写入与扫描可能在不同线程。 */
    @Volatile private var lastSweepAt: Long = 0L,
) {

    /** 读目标内容。文件不存在或越界返回 null。 */
    suspend fun read(targetRef: String): String? {
        val file = resolveSafely(targetRef) ?: return null
        if (!file.isFile) return null
        return runCatching { file.readText() }
            .onFailure { Logger.w("WorkspaceArtifactIO", "读取失败: $targetRef", it) }
            .getOrNull()
    }

    /**
     * 写目标内容。越界抛异常（不静默跳过 —— 那会让"任务完成"变成谎言）。
     *
     * ## 为什么先写临时文件再 rename
     *
     * `File.writeText` 直接覆写：写到一半进程被杀，目标文件就变成截断的半个文件。
     * 用户原本的内容没了，新的也没写完 —— 这是最坏的结果。
     *
     * 同目录写临时文件再 `renameTo` 是原子的（同一文件系统内），
     * 要么完全成功，要么目标完全没动。
     */
    suspend fun write(targetRef: String, content: String) {
        val file = resolveSafely(targetRef)
            ?: error("路径越界或非法: $targetRef")
        val parent = file.parentFile
        parent?.mkdirs()

        // 临时文件必须与目标同目录 —— 跨文件系统 rename 不是原子操作。
        val temp = File(parent, file.name + TEMP_MARKER + System.nanoTime())
        try {
            temp.writeText(content)
            // renameTo 在多数平台上覆盖已存在文件；失败时先删再试。
            if (!temp.renameTo(file)) {
                if (file.exists() && !file.delete()) {
                    error("无法替换目标文件: $targetRef")
                }
                if (!temp.renameTo(file)) {
                    error("无法重命名临时文件: $targetRef")
                }
            }
        } catch (t: Throwable) {
            // 失败时清掉临时文件，不在工作区留垃圾。
            runCatching { temp.delete() }
            Logger.w("WorkspaceArtifactIO", "写入失败: $targetRef", t)
            throw t
        }
    }

    /**
     * 清理孤儿临时文件。
     *
     * ## 什么时候会有孤儿
     *
     * [write] 的流程是"写临时文件 → rename"。如果进程在两步之间被杀，
     * 临时文件会留在工作区，目标文件没变。用户看不到任何影响，
     * 但这些文件会一直占空间 —— 每次失败的写入留一个。
     *
     * ## 判据为什么是"比目标旧"而不是"比某个时刻旧"
     *
     * 拿当前时间减固定阈值会误删正在写的临时文件。
     * 这里要求临时文件**不比同名目标新**才算孤儿：
     *  - 目标存在且不旧于临时 → 那次 rename 成功了，临时文件是残留；
     *  - 目标不存在 → 目标本身被删了，临时文件也是垃圾。
     *
     * 正在写的临时文件不满足这两条：它要么还没 rename（目标旧于临时），
     * 要么 rename 后临时已删。
     *
     * @return 清理掉的临时文件数
     */
    suspend fun sweepOrphanTemps(): Int {
        // 低频兜底：主路径是 [write] 失败时自清。这里只处理"进程在
        // writeText 与 rename 之间被杀"这种 write 自己来不及清的情况。
        //
        // 每 5 秒 walk 整个工作区是常驻 IO 开销，工作区文件多时明显。
        // 用一个时间闸把频率压到每 10 分钟一次。
        val now = System.currentTimeMillis()
        if (now - lastSweepAt < SWEEP_INTERVAL_MS) return 0
        lastSweepAt = now

        val all = runCatching { root.walkTopDown().toList() }
            .onFailure { Logger.w("WorkspaceArtifactIO", "扫描工作区失败", it) }
            .getOrDefault(emptyList())

        var removed = 0
        all.filter { it.isFile && it.name.contains(TEMP_MARKER) }.forEach { temp ->
            val original = File(temp.parentFile, temp.name.substringBefore(TEMP_MARKER))
            val isOrphan = !original.exists() || original.lastModified() >= temp.lastModified()
            if (!isOrphan) return@forEach
            val ok = runCatching { temp.delete() }
                .onFailure { Logger.w("WorkspaceArtifactIO", "清理临时文件失败: ${temp.name}", it) }
                .getOrDefault(false)
            if (ok) removed++
        }
        if (removed > 0) Logger.i("WorkspaceArtifactIO", "清理孤儿临时文件 ${removed} 个")
        return removed
    }

    companion object {
        /**
         * 临时文件名标记。
         *
         * 用 `.muse-tmp.` 而不是裸 `.tmp.`：前者几乎不可能出现在用户的真实文件名里，
         * 避免清理时误删用户的同名文件。
         */
        private const val TEMP_MARKER = ".muse-tmp."

        /**
         * 兜底扫描间隔。
         *
         * 主路径是写入失败时自清，这里是防"进程中途被杀"的兜底。
         * 10 分钟足够——孤儿临时文件不占多少空间，也不影响功能。
         */
        private const val SWEEP_INTERVAL_MS = 10 * 60_000L
    }
    /**
     * 把 ref 解析成工作区内的 File；越界返回 null。
     *
     * 三道检查：绝对路径拒绝、`..` 拒绝、规范化后必须仍在根目录下。
     * 第三道是关键 —— 前两道挡不住符号链接或大小写差异带来的绕行。
     */
    private fun resolveSafely(targetRef: String): File? {
        val trimmed = targetRef.trim()
        if (trimmed.isBlank()) return null
        if (trimmed.startsWith("/") || trimmed.startsWith("\\")) return null
        if (trimmed.contains("..")) return null
        val candidate = File(root, trimmed)
        val rootPath = runCatching { root.canonicalPath }
            .onFailure { Logger.w("WorkspaceArtifactIO", "根目录解析失败", it) }
            .getOrNull() ?: return null
        val candidatePath = runCatching { candidate.canonicalPath }
            .onFailure { Logger.w("WorkspaceArtifactIO", "目标路径解析失败: $trimmed", it) }
            .getOrNull() ?: return null
        // 规范化后必须仍在根目录下：这条挡住符号链接与平台差异。
        if (!candidatePath.startsWith(rootPath + File.separator) && candidatePath != rootPath) {
            Logger.w("WorkspaceArtifactIO", "路径越界，拒绝: $trimmed")
            return null
        }
        return candidate
    }
}
