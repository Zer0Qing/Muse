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
        val sourceRunId: String = "",
        val verificationState: String = VerificationState.PROPOSED,
        val contentHash: String = hashOf(content),
        val hasBaseSnapshot: Boolean = false,
        val baseContent: String? = null,
    )

    data class StagedArtifact(
        val ref: String,
        val targetPath: String,
        val contentHash: String,
        val baseHash: String,
        val verificationState: String,
        val sourceRunId: String,
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

    fun stageArtifact(candidate: Candidate): StagedArtifact {
        val ref = stage(candidate)
        val stored = read(ref) ?: error("候选产物未能完整写入暂存区")
        return StagedArtifact(
            ref = ref,
            targetPath = stored.targetRef,
            contentHash = stored.contentHash,
            baseHash = stored.baseHash,
            verificationState = stored.verificationState,
            sourceRunId = stored.sourceRunId,
        )
    }

    fun readArtifact(ref: String): StagedArtifact? = read(ref)?.let { candidate ->
        StagedArtifact(
            ref = ref,
            targetPath = candidate.targetRef,
            contentHash = candidate.contentHash,
            baseHash = candidate.baseHash,
            verificationState = candidate.verificationState,
            sourceRunId = candidate.sourceRunId,
        )
    }

    fun updateVerificationState(stagedId: String, state: String): StagedArtifact? {
        val candidate = read(stagedId) ?: return null
        val file = java.io.File(stagingDir, stagedId)
        return runCatching {
            file.writeText(encode(candidate.copy(verificationState = state)))
            readArtifact(stagedId)
        }.onFailure {
            Logger.w("StagedArtifact", "候选状态写入失败: $stagedId", it)
        }.getOrNull()
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

        if (candidate.verificationState == VerificationState.CONFLICTED) {
            return ApplyResult.Stale("候选曾与目标冲突，必须重新审核后再应用")
        }

        // V04：约束变了，旧候选满足的是旧要求。
        if (candidate.taskRevision != currentTaskRevision) {
            Logger.i(
                "StagedArtifact",
                "候选版本过期，丢弃: ${candidate.taskRevision} -> $currentTaskRevision",
            )
            discard(stagedId)
            return ApplyResult.Stale("任务约束已更新，旧候选不再适用")
        }

        if (currentTargetHash == candidate.contentHash) {
            updateVerificationState(stagedId, VerificationState.APPLIED_PENDING_COMMIT)
            return ApplyResult.Applied(candidate.targetRef)
        }

        if (candidate.verificationState == VerificationState.APPLIED_PENDING_COMMIT) {
            return ApplyResult.Conflict("候选已写入过，但目标随后又发生变化")
        }

        // V13：目标被改过（别的任务或用户手动改），不能覆盖。
        if (candidate.baseHash.isNotBlank() && candidate.baseHash != currentTargetHash) {
            Logger.i("StagedArtifact", "基线漂移，拒绝应用: ${candidate.targetRef}")
            // 不 discard：留着让用户能看到"这个候选本来想改成什么"。
            updateVerificationState(stagedId, VerificationState.CONFLICTED)
            return ApplyResult.Conflict("目标已被改动，这份结果不能直接覆盖")
        }

        return runCatching {
            write(candidate.targetRef, candidate.content)
            updateVerificationState(stagedId, VerificationState.APPLIED_PENDING_COMMIT)
            ApplyResult.Applied(candidate.targetRef)
        }.onFailure {
            Logger.w("StagedArtifact", "应用失败: ${candidate.targetRef}", it)
        }.getOrElse { ApplyResult.Conflict("写入失败: ${it.message}") }
    }

    /**
     * 暂存区清单的读取结果。
     *
     * ## 为什么必须把失败单独拎出来
     *
     * 只返回 `List` 时，"读失败"和"暂存区里没有候选"的返回值一模一样（都是空列表）。
     * 页面于是把一次 IO 故障显示成"候选没了" —— 文件还在磁盘上，用户却看不见，
     * 也没有任何重试入口。
     */
    sealed interface StagedListResult {
        /** 读成功。[entries] 为空只表示"暂存区里确实没有候选"。 */
        data class Success(val entries: List<StagedEntry>) : StagedListResult

        /** 读失败。调用方必须把它当故障上报，不能当成"没有候选"。 */
        data class Error(val reason: String, val cause: Throwable? = null) : StagedListResult
    }

    /**
     * 列出所有仍在暂存区的候选，并把"读失败"与"没有候选"分开。
     *
     * V04 要求"历史候选仍留审计"：冲突的候选不能被静默删掉，
     * 用户需要能看到"这份结果本来想改成什么"。页面走这一条。
     *
     * 返回按 id 排序（id 含 nanoTime，等价于按产出顺序）。
     */
    fun listStagedResult(): StagedListResult {
        // 目录还没建：这个 Bot 从没产出过候选，不是故障。
        if (!stagingDir.exists()) return StagedListResult.Success(emptyList())
        // 路径被别的东西占住了（目录名撞上普通文件、外置存储掉线）。
        // 此时 listFiles() 只会返回 null —— 与"空目录"分不开，所以必须显式判一次。
        if (!stagingDir.isDirectory) {
            return StagedListResult.Error("暂存区路径不是目录: ${stagingDir.absolutePath}")
        }
        val files = runCatching { stagingDir.listFiles() }
            .onFailure { Logger.w("StagedArtifact", "暂存区无法列出: ${it.message}", it) }
            .getOrNull()
            ?: return StagedListResult.Error("暂存区无法列出: ${stagingDir.absolutePath}")
        val entries = files
            .filter { it.isFile }
            .sortedBy { it.name }
            .mapNotNull { file ->
                // 单份候选损坏只丢这一条：一条坏文件不该让整个页面进错误态。
                // 目录级读取失败才是故障，上面已经拦掉了。
                val candidate = runCatching { decode(file.readText()) }
                    .onFailure { Logger.w("StagedArtifact", "候选解析失败: ${file.name}", it) }
                    .getOrNull()
                candidate?.let { StagedEntry(stagedId = file.name, candidate = it) }
            }
        return StagedListResult.Success(entries)
    }

    /**
     * 宽松入口：读失败按"没有候选"处理。
     *
     * 只给不关心失败原因的调用方（审计列表、测试）用。页面必须走 [listStagedResult] ——
     * 用这一条会把故障伪装成"候选没了"。
     */
    fun listStaged(): List<StagedEntry> = when (val result = listStagedResult()) {
        is StagedListResult.Success -> result.entries
        is StagedListResult.Error -> {
            Logger.w("StagedArtifact", "候选列表读取失败，按空列表处理: ${result.reason}")
            emptyList()
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
        appendLine(FORMAT_V3)
        appendLine(candidate.targetRef)
        appendLine(candidate.baseHash)
        appendLine(candidate.taskRevision.toString())
        appendLine(candidate.sourceRunId)
        appendLine(candidate.verificationState)
        appendLine(candidate.contentHash)
        appendLine(if (candidate.hasBaseSnapshot) "1" else "0")
        appendLine(if (candidate.baseContent == null) "0" else "1")
        appendLine(
            candidate.baseContent
                ?.toByteArray(Charsets.UTF_8)
                ?.let(java.util.Base64.getEncoder()::encodeToString)
                .orEmpty(),
        )
        append(candidate.content)
    }

    private fun decode(raw: String): Candidate? {
        val parts = raw.split("\n", limit = 11)
        if (parts.firstOrNull() == FORMAT_V3) {
            if (parts.size < 11) return null
            val revision = parts[3].toIntOrNull() ?: return null
            val content = parts[10]
            val contentHash = parts[6]
            if (contentHash != hashOf(content)) return null
            val hasBaseSnapshot = parts[7] == "1"
            val baseContent = if (parts[8] == "1") {
                runCatching {
                    java.util.Base64.getDecoder().decode(parts[9]).toString(Charsets.UTF_8)
                }.getOrNull() ?: return null
            } else {
                null
            }
            if (hasBaseSnapshot && parts[8] !in setOf("0", "1")) return null
            if (hasBaseSnapshot && hashSnapshot(baseContent) != parts[2]) return null
            return Candidate(
                targetRef = parts[1],
                baseHash = parts[2],
                taskRevision = revision,
                sourceRunId = parts[4],
                verificationState = parts[5],
                contentHash = contentHash,
                content = content,
                hasBaseSnapshot = hasBaseSnapshot,
                baseContent = baseContent,
            )
        }
        if (parts.firstOrNull() == FORMAT_V2) {
            val v2Parts = raw.split("\n", limit = 8)
            if (v2Parts.size < 8) return null
            val revision = v2Parts[3].toIntOrNull() ?: return null
            val content = v2Parts[7]
            val contentHash = v2Parts[6]
            if (contentHash != hashOf(content)) return null
            return Candidate(
                targetRef = v2Parts[1],
                baseHash = v2Parts[2],
                taskRevision = revision,
                sourceRunId = v2Parts[4],
                verificationState = v2Parts[5],
                contentHash = contentHash,
                content = content,
            )
        }
        val legacyParts = raw.split("\n", limit = 4)
        if (legacyParts.size < 4) return null
        val revision = legacyParts[2].toIntOrNull() ?: return null
        return Candidate(
            targetRef = legacyParts[0],
            baseHash = legacyParts[1],
            taskRevision = revision,
            content = legacyParts[3],
        )
    }

    companion object {
        private const val FORMAT_V3 = "MUSE_ARTIFACT_V3"
        private const val FORMAT_V2 = "MUSE_ARTIFACT_V2"

        /** 内容哈希。与标注服务同一口径，便于交叉比对。 */
        fun hashOf(content: String): String {
            if (content.isEmpty()) return ""
            val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
            return digest.take(16).joinToString("") { byte -> "%02x".format(byte) }
        }

        fun hashSnapshot(content: String?): String = when (content) {
            null -> MISSING_TARGET_HASH
            "" -> EMPTY_TARGET_HASH
            else -> hashOf(content)
        }

        const val MISSING_TARGET_HASH = "__muse_target_missing__"
        const val UNREADABLE_TARGET_HASH = "__muse_target_unreadable__"

        private const val EMPTY_TARGET_HASH = "__muse_target_empty__"
    }
}

object VerificationState {
    const val PROPOSED = "PROPOSED"
    const val VERIFIED = "VERIFIED"
    const val CONFLICTED = "CONFLICTED"
    const val APPLIED_PENDING_COMMIT = "APPLIED_PENDING_COMMIT"
}
