package io.zer0.memory.runtime

import io.zer0.memory.summary.ScopedCompiledSectionEntity

/**
 * 单段编译产物的来源信息。
 *
 * 存在的意义是让「这段记忆是什么时候、基于什么生成的」可被追问：
 * 只有内容没有来源时，用户看到过期或失败的内容也无从判断。
 */
data class SectionProvenance(
    val sectionKey: String,
    val status: MemoryProjectionStatus,
    /** 内容指纹：内容变了它就变。 */
    val contentFingerprint: String,
    /** 输入指纹：事实/摘要变了它就变，用来判断投影是否过期。 */
    val sourceFingerprint: String,
    val updatedAt: String,
)

/**
 * 编译产物的可见性 / 来源投影规则。
 *
 * 全部是纯函数：可见性判断一旦写进查询里，就无法在不建库的情况下验证
 * 「失败的行不会进 prompt」这件事，而这正是最该被钉住的一条。
 */
object MemoryCompiledProjection {

    /**
     * 只返回可被当作「当前记忆」使用的段落。
     *
     * [includeNonReady] 仅供 UI 诊断使用；prompt / 工具读取必须保持 false，
     * 否则失败或过期的投影会以「当前事实」的身份进入模型上下文。
     */
    fun visibleSections(
        rows: List<ScopedCompiledSectionEntity>,
        includeNonReady: Boolean,
    ): Map<String, String> = rows
        .filter { row -> includeNonReady || statusOf(row) == MemoryProjectionStatus.READY }
        .filter { row -> row.content.isNotBlank() }
        .associate { row -> row.sectionKey to row.content }

    /** 把一行投影成来源信息。未知状态按 FAILED 处理（不可信优先）。 */
    fun provenanceOf(row: ScopedCompiledSectionEntity): SectionProvenance = SectionProvenance(
        sectionKey = row.sectionKey,
        status = statusOf(row),
        contentFingerprint = row.fingerprint.orEmpty(),
        sourceFingerprint = row.sourceFingerprint,
        updatedAt = row.updatedAt,
    )

    /**
     * 投影失败时的下一行状态。
     *
     * 关键点：**保留上一版的正文与指纹**，只把状态改成 FAILED。
     * 失败时清空内容等于让用户今天的记忆凭空消失；正确做法是标记「这段不可信」并留着旧值，
     * 由可见性规则把它挡在 prompt 之外，同时 UI 仍能显示「上次成功的内容 + 失败提示」。
     */
    fun onProjectionFailure(
        previous: ScopedCompiledSectionEntity?,
        reason: String,
        now: String,
    ): ScopedCompiledSectionEntity? = previous?.copy(
        status = MemoryProjectionStatus.FAILED.name,
        updatedAt = now,
    )

    /**
     * 旧备份的来源标记。
     *
     * 早于 provenance 的备份里没有 source_fingerprint / status 字段，反序列化会落成默认值
     * （空指纹 + READY）。若原样入库，就等于把「来路不明的内容」标成「当前可信」——
     * 用户以后看到这段记忆，无法分辨它是新算出来的还是几年前的遗留。
     * 这里给它一个显式标记，内容照常可见，但来源可被追问。
     */
    const val LEGACY_BASELINE = "legacy-baseline"

    /**
     * 归一化「从备份恢复的一行」。
     *
     * 两条规则：
     *  1. 完全没有指纹信息的行 → 打上 [LEGACY_BASELINE]，不假装它有已知来源；
     *  2. status 为空（旧备份）→ 保持 READY（内容仍是用户记忆，不能因缺字段就藏起来）。
     *
     * ORPHANED / STALE / FAILED 一律原样保留：恢复不该把「已知不可用」的行洗成可用。
     */
    fun normalizeRestored(row: ScopedCompiledSectionEntity): ScopedCompiledSectionEntity {
        val hasProvenance = row.sourceFingerprint.isNotBlank() || !row.fingerprint.isNullOrBlank()
        return row.copy(
            sourceFingerprint = if (hasProvenance) row.sourceFingerprint else LEGACY_BASELINE,
            status = row.status.ifBlank { MemoryProjectionStatus.READY.name },
        )
    }

    /** 状态字符串 → 枚举；未知值按 FAILED 处理。 */
    fun statusOf(row: ScopedCompiledSectionEntity): MemoryProjectionStatus =
        runCatching { MemoryProjectionStatus.valueOf(row.status.uppercase()) }
            .getOrDefault(MemoryProjectionStatus.FAILED)
}
