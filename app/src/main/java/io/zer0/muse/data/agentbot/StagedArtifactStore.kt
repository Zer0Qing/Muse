package io.zer0.muse.data.agentbot

import io.zer0.common.Logger
import java.security.MessageDigest

/**
 * 候选产物的暂存与独占发布。
 *
 * ## 解决的两个问题
 *
 * **V04**：任务跑到一半，用户又追加了约束。此时旧候选**不能**应用到目标资源 ——
 * 它满足的是旧约束。所以候选先落暂存区，审核通过且版本仍是最新才应用。
 *
 * **V13**：两个任务改同一个文件。后完成的那个如果直接写，会覆盖前一个的成果，
 * 也可能覆盖用户在这期间的手动修改。所以应用前必须核对基线哈希。
 *
 * ## 为什么暂存而不是直接写
 *
 * "先写再校验"有个致命问题：写坏了就已经坏了。用户不会因为系统说了句
 * "抱歉覆盖了你的修改"就接受文件被改回去。暂存让"应用"成为一次原子决定。
 */
class StagedArtifactStore(
    /** 暂存区目录。由宿主提供（App 侧是 cacheDir/agentbot-staging）。 */
    private val stagingDir: java.io.File,
) {

    /** 一份候选产物。 */
    data class Candidate(
        /** 目标资源标识（路径 / 文档 id）。 */
        val targetRef: String,
        /** 候选内容。 */
        val content: String,
        /** 产出这份候选时，目标的哈希。应用前要核对它没变。 */
        val baseHash: String,
        /** 产出这份候选时的任务版本。约束变了旧候选即失效。 */
        val taskRevision: Int,
    )

    /** 应用结果。 */
    sealed interface ApplyResult {
        /** 已应用到目标。 */
        data class Applied(val targetRef: String) : ApplyResult

        /** 目标已被改动，候选作废（V13）。 */
        data class Conflict(val reason: String) : ApplyResult

        /** 任务版本已推进，候选满足的是旧约束（V04）。 */
        data class Stale(val reason: String) : ApplyResult
    }

    /**
     * 把候选写入暂存区。
     *
     * @return 暂存条目 id；调用方拿它去应用或丢弃
     */
    fun stage(candidate: Candidate): String {
        val id = "cand-" + System.nanoTime()
        val file = java.io.File(stagingDir, id)
        runCatching {
            stagingDir.mkdirs()
            file.writeText(encode(candidate))
        }.onFailure {
            Logger.w("StagedArtifact", "暂存写入失败: ${it.message}")
        }
        return id
    }

    /** 读回暂存条目；不存在或损坏返回 null。 */
    fun read(stagedId: String): Candidate? {
        val file = java.io.File(stagingDir, stagedId)
        if (!file.isFile) return null
        return runCatching { decode(file.readText()) }
            .onFailure { Logger.w("StagedArtifact", "暂存读取失败: ${it.message}") }
            .getOrNull()
    }

    /**
     * 应用候选。
     *
     * 三道检查，全部通过才写目标：
     *  1. 任务版本仍是最新（V04）；
     *  2. 目标当前哈希等于产出时的基线（V13）；
     *  3. 写入本身成功。
     *
     * @param currentTaskRevision 当前任务版本
     * @param currentTargetHash 目标此刻的哈希
     * @param write 实际写目标。由调用方提供，因为"写"依赖具体资源类型
     */
    suspend fun apply(
        stagedId: String,
        currentTaskRevision: Int,
        currentTargetHash: String,
        write: suspend (targetRef: String, content: String) -> Unit,
    ): ApplyResult {
        val candidate = read(stagedId)
            ?: return ApplyResult.Stale("暂存条目不存在或已损坏")

        // V04：约束变了，旧候选满足的是旧要求。
        if (candidate.taskRevision != currentTaskRevision) {
            Logger.i(
                "StagedArtifact",
                "候选版本过期，丢弃: ${candidate.taskRevision} -> $currentTaskRevision",
            )
            discard(stagedId)
            return ApplyResult.Stale("任务约束已更新，旧候选不再适用")
        }

        // V13：目标被改过（别的任务或用户手动改），不能覆盖。
        if (candidate.baseHash.isNotBlank() && candidate.baseHash != currentTargetHash) {
            Logger.i("StagedArtifact", "基线漂移，拒绝应用: ${candidate.targetRef}")
            // 不 discard：留着让用户能看到"这个候选本来想改成什么"。
            return ApplyResult.Conflict("目标已被改动，这份结果不能直接覆盖")
        }

        return runCatching {
            write(candidate.targetRef, candidate.content)
            discard(stagedId)
            ApplyResult.Applied(candidate.targetRef)
        }.onFailure {
            Logger.w("StagedArtifact", "应用失败: ${candidate.targetRef}", it)
        }.getOrElse { ApplyResult.Conflict("写入失败: ${it.message}") }
    }

    /**
     * 列出所有仍在暂存区的候选。
     *
     * V04 要求"历史候选仍留审计"：冲突的候选不能被静默删掉，
     * 用户需要能看到"这份结果本来想改成什么"。这个方法是那个入口。
     *
     * 返回按 id 排序（id 含 nanoTime，等价于按产出顺序）。
     */
    fun listStaged(): List<StagedEntry> {
        val files = runCatching { stagingDir.listFiles() }.getOrNull() ?: return emptyList()
        return files
            .filter { it.isFile }
            .sortedBy { it.name }
            .mapNotNull { file ->
                val candidate = runCatching { decode(file.readText()) }
                    .onFailure { Logger.w("StagedArtifact", "候选解析失败: ${file.name}", it) }
                    .getOrNull()
                candidate?.let { StagedEntry(stagedId = file.name, candidate = it) }
            }
    }

    /** 一条暂存记录：id 与内容。 */
    data class StagedEntry(
        val stagedId: String,
        val candidate: Candidate,
    )
    /** 丢弃暂存条目。 */
    fun discard(stagedId: String) {
        runCatching { java.io.File(stagingDir, stagedId).delete() }
            .onFailure { Logger.w("StagedArtifact", "暂存清理失败: ${it.message}") }
    }

    /** 清空暂存区（任务终结 / 恢复备份时调）。 */
    fun clearAll() {
        runCatching { stagingDir.listFiles()?.forEach { it.delete() } }
            .onFailure { Logger.w("StagedArtifact", "暂存区清理失败: ${it.message}") }
    }

    private fun encode(candidate: Candidate): String = buildString {
        appendLine(candidate.targetRef);
        appendLine(candidate.baseHash);
        appendLine(candidate.taskRevision.toString());
        append(candidate.content);
    }

    private fun decode(raw: String): Candidate? {
        val parts = raw.split("\n", limit = 4)
        if (parts.size < 4) return null
        val revision = parts[2].toIntOrNull() ?: return null
        return Candidate(
            targetRef = parts[0],
            baseHash = parts[1],
            taskRevision = revision,
            content = parts[3],
        )
    }

    companion object {
        /** 内容哈希。与标注服务同一口径，便于交叉比对。 */
        fun hashOf(content: String): String {
            if (content.isEmpty()) return ""
            val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
            return digest.take(16).joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}
