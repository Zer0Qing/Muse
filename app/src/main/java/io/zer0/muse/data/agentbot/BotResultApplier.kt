package io.zer0.muse.data.agentbot

import io.zer0.common.Logger

/**
 * 审核 → 应用 的闭环。
 *
 * ## 为什么需要单独一层
 *
 * [BotReviewWorker] 只判断"这份结果够不够格算完成"，
 * [StagedArtifactStore] 只管"候选怎么安全落到目标"。
 * 两者之间缺的正是这个类：**拿到事件里的 staged id → 审核 → 决定应用还是丢弃**。
 *
 * 没有这一层，暂存区就是死的 —— 候选躺在那里，永远不会被应用，
 * 用户看到"任务完成"但目标文件没有任何变化。
 *
 * ## 为什么审核和应用必须在同一个函数里
 *
 * 分开写会出现"审核说可以，但应用前目标又变了"的窗口。
 * 这里把版本核对与应用放在一起，中间不让出控制权。
 */
class BotResultApplier(
    private val repository: AgentBotRepository,
    private val staging: StagedArtifactStore?,
    private val reviewer: BotReviewWorker,
    /** 读目标当前内容。为 null 表示无法读取（资源已删除等）。 */
    private val readTarget: (suspend (targetRef: String) -> String?)? = null,
    /** 写目标。由宿主提供，因为"写"依赖具体资源类型。 */
    private val writeTarget: (suspend (targetRef: String, content: String) -> Unit)? = null,
) {

    /** 一次审核+应用的结果。 */
    data class Outcome(
        val disposition: String,
        /** 候选是否已应用到目标。 */
        val applied: Boolean = false,
        /** 给主 Bot 看的具体发现。 */
        val findings: List<String> = emptyList(),
        /** 失败/冲突原因；空表示没出问题。 */
        val blockedReason: String = "",
    )

    /**
     * 处理一次执行结果。
     *
     * @param payloadRef 事件里的 payloadRef，形如 "摘要\n[staged=cand-123]"
     * @param expectedBaseHash 产出这份结果时目标的哈希
     * @param criteria 任务的验收标准
     */
    suspend fun process(
        task: BotTaskEntity,
        run: TaskRunEntity,
        payloadRef: String,
        expectedBaseHash: String,
        criteria: String,
    ): Outcome {
        val stagedId = parseStagedId(payloadRef)
        // 摘要取标记**之前**的全部内容。用 lastIndexOf 同样的理由：
        // 摘要里含假标记时，substringBefore 会在假标记处截断。
        val markerAt = payloadRef.lastIndexOf(STAGED_MARKER)
        val summary = if (markerAt >= 0) payloadRef.substring(0, markerAt).trim() else payloadRef.trim()

        // 没有候选 = 子任务只留了一段文字。审核会判 REWORK（无产物不算完成）。
        val candidate = stagedId?.let { staging?.read(it) }

        // 应用前读目标当前哈希：与产出时的基线比对，V13 的落点。
        val currentHash = candidate?.let { c ->
            readTarget?.invoke(c.targetRef)?.let { StagedArtifactStore.hashOf(it) }
        }

        // 从步骤账本汇总"已经发生、不能重放"的操作。
        //
        // 这是 V23 的数据来源：StepSideEffect.NO_BLIND_REPLAY 标记的步骤意味着
        // 副作用已经产生，重跑不安全。步骤表里早就记着这些，只是此前没人汇总。
        // 读失败时**按"有副作用"处理**，不是按"没有"。
        //
        // 默认成空列表会把"读不到步骤"当成"没有副作用"，从而误判 ACCEPT ——
        // 那是在用户不知情的情况下声称"干净地做完了"。宁可保守地说
        // "可能有已发生的操作，请核验"，也不要乐观地说"什么都没发生"。
        //
        // ## 但空账本不能一律当"不可确认"
        //
        // 第一版实现直接对空账本报"无法确认"，结果把纯对话任务也判成了
        // 需要核验 —— 那些任务本来就没执行任何工具，账本空是正确的。
        // 第二版用"有产物候选"当判据，又把只测审核逻辑的用例全判红了。
        //
        // 最终判据：**只把"读失败"当成不可确认**。
        //
        // 空账本的成因在 `process` 这一层无法可靠区分 —— 它可能是"没执行工具"，
        // 也可能是"记账丢了"。这个判断应该由**记录侧**负责：
        // `BotTaskScheduler.onToolStarted` 里记账失败会打 error 级日志并带上工具名，
        // 那是唯一能区分两者的地方。
        //
        // 在这里猜会把正常场景判红；在记录侧留痕则不会。
        val stepRead = runCatching { repository.listSteps(run.runId) }
            .onFailure { Logger.w("BotResultApplier", "读取步骤失败: ${it.message}") }
        val sideEffects: List<String> = if (stepRead.isSuccess) {
            stepRead.getOrThrow()
                .filter { step -> step.sideEffect == StepSideEffect.NO_BLIND_REPLAY }
                .map { step -> step.toolName.ifBlank { "未知操作" } }
        } else {
            listOf("无法确认已发生的操作（步骤账本读取失败）")
        }

        val decision = reviewer.review(
            criteria = criteria,
            claimedSummary = summary,
            producedArtifacts = if (candidate != null) listOf(candidate.targetRef) else emptyList(),
            verifiedChecks = if (currentHash != null) listOf("目标基线核对") else emptyList(),
            expectedBaseHash = expectedBaseHash,
            actualBaseHash = currentHash.orEmpty(),
            attempt = run.attempt,
            irreversibleSideEffects = sideEffects,
        )

        if (!decision.accepted) {
            // 不接受就不应用：候选留着或丢弃由审核结论决定。
            if (decision.disposition == ReviewDisposition.REWORK ||
                decision.disposition == ReviewDisposition.REJECT
            ) {
                stagedId?.let { staging?.discard(it) }
            }
            applyTransition(task.taskId, decision)
            return Outcome(
                disposition = decision.disposition,
                findings = decision.findings,
                blockedReason = decision.findings.firstOrNull().orEmpty(),
            )
        }

        // 审核通过：现在才允许写目标。
        if (candidate == null || stagedId == null) {
            // 通过但没有候选，说明 producedArtifacts 那条校验没起作用 —— 不该发生。
            //
            // 审查 P1-10：这里此前调了 `applyTransition`，把任务转成 SUCCEEDED。
            // 那是"声称做了却没做" —— 用户看到完成，目标一个字没变。
            // 现在转 NEEDS_USER，让用户知道这份结果没落地。
            Logger.e("BotResultApplier", "审核通过但无候选，不应用，转待处理: ${task.taskId}")
            repository.transition(task.taskId, BotTaskStatusMachine.Event.REVIEW_NEEDS_USER)
            return Outcome(
                disposition = decision.disposition,
                applied = false,
                findings = decision.findings,
                blockedReason = "审核通过但没有可应用的产物",
            )
        }

        val write = writeTarget
        if (write == null) {
            // 宿主没提供写能力（只读场景）。候选保留，等用户手动处理。
            //
            // 同理不能落 SUCCEEDED：那会让用户以为结果已经进到目标里。
            repository.transition(task.taskId, BotTaskStatusMachine.Event.REVIEW_NEEDS_USER)
            return Outcome(
                disposition = decision.disposition,
                applied = false,
                findings = decision.findings,
                blockedReason = "没有写入能力，结果已保留待手动应用",
            )
        }

        val store = staging ?: return Outcome(
            disposition = decision.disposition,
            applied = false,
            findings = decision.findings,
            blockedReason = "没有暂存区，无法应用",
        )
        val result = store.apply(
            stagedId = stagedId,
            currentTaskRevision = repository.getTask(task.taskId)?.revision ?: task.revision,
            currentTargetHash = currentHash.orEmpty(),
        ) { targetRef, content ->
            write(targetRef, content)
        }

        return when (result) {
            is StagedArtifactStore.ApplyResult.Applied -> {
                applyTransition(task.taskId, decision)
                Outcome(decision.disposition, applied = true, findings = decision.findings)
            }
            is StagedArtifactStore.ApplyResult.Conflict -> {
                // 目标被改过：不应用，也不报成功。用户需要知道"这份结果没进去"。
                Logger.w("BotResultApplier", "冲突，未应用: ${result.reason}")
                repository.transition(task.taskId, BotTaskStatusMachine.Event.MARK_UNKNOWN)
                Outcome(
                    disposition = ReviewDisposition.UNKNOWN,
                    applied = false,
                    findings = decision.findings + result.reason,
                    blockedReason = result.reason,
                )
            }
            is StagedArtifactStore.ApplyResult.Stale -> {
                Logger.i("BotResultApplier", "候选过期，未应用: ${result.reason}")
                applyTransition(task.taskId, decision)
                Outcome(
                    disposition = decision.disposition,
                    applied = false,
                    findings = decision.findings + result.reason,
                    blockedReason = result.reason,
                )
            }
        }
    }

    private suspend fun applyTransition(taskId: String, decision: BotReviewWorker.Decision) {
        val event = reviewer.transitionFor(decision) ?: return
        repository.transition(taskId, event)
    }

    /**
     * 列出仍在暂存区的候选，供用户审计（V04 的"历史候选仍留审计"）。
     *
     * 冲突的候选不会被删，用户能在这里看到"这份结果本来想改成什么"。
     */
    fun listPendingCandidates(): List<StagedArtifactStore.StagedEntry> =
        staging?.listStaged().orEmpty()

    /** 用户确认后丢弃某条候选。 */
    fun discardCandidate(stagedId: String) {
        staging?.discard(stagedId)
    }

    /** 从 payloadRef 里取 staged id。取不到返回 null。 */
    /**
     * 从 payloadRef 里取 staged id。取不到返回 null。
     *
     * ## 为什么从后往前找
     *
     * 摘要正文是模型输出，里面可能恰好出现 `[staged=` 这样的字样。
     * 从前往后找会命中摘要里的假标记，解析出错误的 id。
     *
     * 写入侧总是把标记**追加在最后**，所以从后往前找是唯一正确的方向。
     *
     * ## 为什么还要校验格式
     *
     * 即便从后往前找，摘要尾部若也含标记仍会误判。id 由 [StagedArtifactStore]
     * 生成，格式固定为 `cand-<数字>`，不符合就当没找到 —— 宁可当作"没有候选"，
     * 也不能拿一个错误的 id 去应用别人的产物。
     */
    private fun parseStagedId(payloadRef: String): String? {
        val start = payloadRef.lastIndexOf(STAGED_MARKER)
        if (start < 0) return null
        val end = payloadRef.indexOf(']', start)
        if (end < 0) return null
        val id = payloadRef.substring(start + STAGED_MARKER.length, end).trim()
        return id.takeIf { STAGED_ID_PATTERN.matches(it) }
    }

    companion object {
        /** 事件 payloadRef 里的暂存标记。与 [BotTaskScheduler] 写入侧必须一致。 */
        const val STAGED_MARKER = "[staged="

        /**
         * 暂存 id 的合法格式。
         *
         * 由 [StagedArtifactStore.stage] 生成：`cand-<nanoTime>`。
         * 校验它是为了防止"摘要里恰好含标记"时把错误字符串当成 id。
         */
        private val STAGED_ID_PATTERN = Regex("^cand-\\d+$")
    }
}