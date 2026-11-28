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
    private val workspaceManager: io.zer0.muse.workspace.WorkspaceManager? = null,
) {

    /** 一次审核+应用的结果。 */
    data class Outcome(
        val disposition: String,
        /** 候选是否已应用到目标。 */
        val applied: Boolean = false,
        /** 审核结果与任务状态已成功结算。 */
        val accepted: Boolean = false,
        /** 给主 Bot 看的具体发现。 */
        val findings: List<String> = emptyList(),
        /** 失败/冲突原因；空表示没出问题。 */
        val blockedReason: String = "",
    )

    suspend fun recover(task: BotTaskEntity, run: TaskRunEntity, payloadRef: String): Outcome {
        val candidates = parseStagedIds(payloadRef).mapNotNull { staging?.read(it) }
        return process(
            task = task,
            run = run,
            payloadRef = payloadRef,
            expectedBaseHash = candidates.singleOrNull()?.baseHash.orEmpty(),
            criteria = task.acceptanceCriteria,
        )
    }

    /**
     * 处理一次执行结果。
     *
     * @param payloadRef 事件里的 payloadRef，形如 "摘要\n[staged=cand-123]"
     * @param expectedBaseHash 产出这份结果时目标的哈希
     * @param criteria 任务的验收标准
     */
    suspend fun process(task: BotTaskEntity, run: TaskRunEntity, payloadRef: String, expectedBaseHash: String, criteria: String): Outcome {
        val preparation = prepareReview(task, run, payloadRef, expectedBaseHash, criteria)
        val summaryOnly = preparation.summaryOnly

        if (!preparation.decision.accepted) {
            return recordRejectedReview(task, run, preparation)
        }

        if (summaryOnly) {
            return recordSummaryReview(task, run, preparation)
        }

        return applyAcceptedReview(task, run, preparation)
    }

    private suspend fun recordRejectedReview(
        task: BotTaskEntity,
        run: TaskRunEntity,
        preparation: ReviewPreparation,
    ): Outcome {
        val decision = preparation.decision
        val settled = recordReview(
            task = task,
            run = run,
            decision = decision,
            transition = reviewer.transitionFor(decision) ?: BotTaskStatusMachine.Event.MARK_UNKNOWN,
            evidence = ReviewEvidence(preparation.verifiedChecks, preparation.sideEffects, preparation.candidateRefs),
        )
        if (settled != AgentBotRepository.ReviewCommitResult.STALE &&
            (decision.disposition == ReviewDisposition.REWORK || decision.disposition == ReviewDisposition.REJECT)
        ) {
            preparation.stagedIds.forEach { staging?.discard(it) }
        }
        return Outcome(
            disposition = decision.disposition,
            findings = decision.findings,
            blockedReason = decision.findings.firstOrNull().orEmpty(),
        )
    }

    private suspend fun recordSummaryReview(
        task: BotTaskEntity,
        run: TaskRunEntity,
        preparation: ReviewPreparation,
    ): Outcome {
        val committed = recordReview(
            task = task,
            run = run,
            decision = preparation.decision,
            transition = BotTaskStatusMachine.Event.REVIEW_ACCEPT,
            evidence = ReviewEvidence(preparation.verifiedChecks, preparation.sideEffects, emptyList()),
            summaryOnly = true,
            resultSummary = preparation.summary,
        )
        return Outcome(
            disposition = preparation.decision.disposition,
            accepted = committed != AgentBotRepository.ReviewCommitResult.STALE &&
                committed != AgentBotRepository.ReviewCommitResult.EFFECT_REJECTED,
            findings = preparation.decision.findings,
        )
    }

    private suspend fun applyAcceptedReview(
        task: BotTaskEntity,
        run: TaskRunEntity,
        preparation: ReviewPreparation,
    ): Outcome {
        val decision = preparation.decision
        val stagedIds = preparation.stagedIds
        val candidates = preparation.candidates
        val currentHashes = preparation.currentHashes
        val sideEffects = preparation.sideEffects
        val candidateRefs = preparation.candidateRefs
        val verifiedChecks = preparation.verifiedChecks
        // 审核通过：现在才允许写目标。
        if (candidates.isEmpty()) {
            // 通过但没有候选，说明 producedArtifacts 那条校验没起作用 —— 不该发生。
            //
            // 审查 P1-10：这里此前调了 `applyTransition`，把任务转成 SUCCEEDED。
            // 那是"声称做了却没做" —— 用户看到完成，目标一个字没变。
            // 现在转 NEEDS_USER，让用户知道这份结果没落地。
            Logger.e("BotResultApplier", "审核通过但无候选，不应用，转待处理: ${task.taskId}")
            return recordBlockedOutcome(
                task = task,
                run = run,
                decision = decision,
                evidence = ReviewEvidence(verifiedChecks, sideEffects, candidateRefs),
                reason = "审核通过但没有可应用的产物",
            )
        }

        if (writeTarget == null && workspaceManager == null) {
            // 宿主没提供写能力（只读场景）。候选保留，等用户手动处理。
            //
            // 同理不能落 SUCCEEDED：那会让用户以为结果已经进到目标里。
            return recordBlockedOutcome(
                task = task,
                run = run,
                decision = decision,
                evidence = ReviewEvidence(verifiedChecks, sideEffects, candidateRefs),
                reason = "没有写入能力，候选尚未应用",
                blockedReason = "没有写入能力，结果已保留待手动应用",
            )
        }

        val store = staging ?: run {
            return recordBlockedOutcome(
                task = task,
                run = run,
                decision = decision,
                evidence = ReviewEvidence(verifiedChecks, sideEffects, candidateRefs),
                reason = "没有暂存区，无法应用候选",
                blockedReason = "没有暂存区，无法应用",
            )
        }
        val verificationFailed = stagedIds.any { store.updateVerificationState(it, VerificationState.VERIFIED) == null }
        if (verificationFailed) {
            return recordBlockedOutcome(
                task = task,
                run = run,
                decision = decision,
                evidence = ReviewEvidence(verifiedChecks, sideEffects, candidateRefs),
                reason = "候选验证状态无法保存，目标未修改",
                blockedReason = "候选验证状态无法保存",
            )
        }
        val attempt = applyCandidates(
            task = task,
            run = run,
            decision = decision,
            candidates = candidates,
            currentHashes = currentHashes,
            evidence = ReviewEvidence(verifiedChecks, sideEffects, candidateRefs),
            store = store,
        )
        return settleAppliedReview(task, run, preparation, attempt)
    }

    private suspend fun applyCandidates(
        task: BotTaskEntity,
        run: TaskRunEntity,
        decision: BotReviewWorker.Decision,
        candidates: List<Pair<String, StagedArtifactStore.Candidate>>,
        currentHashes: Map<String, String>,
        evidence: ReviewEvidence,
        store: StagedArtifactStore,
    ): ApplyAttempt {
        var failure: StagedArtifactStore.ApplyResult? = null
        val appliedTargets = mutableListOf<String>()
        val committed = recordReview(
            task = task,
            run = run,
            decision = decision,
            transition = BotTaskStatusMachine.Event.REVIEW_ACCEPT,
            evidence = evidence,
        ) {
            for ((id, candidate) in candidates) {
                val result = store.apply(
                    stagedId = id,
                    currentTaskRevision = task.revision,
                    currentTargetHash = currentHashes[id].orEmpty(),
                ) { targetRef, content -> writeArtifact(task, targetRef, content, candidate) }
                if (result !is StagedArtifactStore.ApplyResult.Applied) {
                    failure = result
                    return@recordReview false
                }
                appliedTargets += candidate.targetRef
            }
            true
        }
        return ApplyAttempt(committed, failure, appliedTargets)
    }

    private suspend fun settleAppliedReview(
        task: BotTaskEntity,
        run: TaskRunEntity,
        preparation: ReviewPreparation,
        attempt: ApplyAttempt,
    ): Outcome {
        val decision = preparation.decision
        val appliedTargets = attempt.appliedTargets
        if (attempt.committed == AgentBotRepository.ReviewCommitResult.COMMITTED ||
            attempt.committed == AgentBotRepository.ReviewCommitResult.ALREADY_COMMITTED
        ) {
            preparation.stagedIds.forEach { staging?.discard(it) }
            return Outcome(decision.disposition, applied = true, accepted = true, findings = decision.findings)
        }
        if (attempt.committed == AgentBotRepository.ReviewCommitResult.STALE) {
            val latestTask = repository.getTask(task.taskId)
            if (latestTask != null && latestTask.revision > run.taskRevision && latestTask.status == BotTaskStatus.REVIEWING) {
                val staleDecision = decision.copy(
                    disposition = ReviewDisposition.UNKNOWN,
                    findings = decision.findings + "任务约束已更新，本轮旧版本结果未应用",
                )
                val staleCommit = recordReview(
                    task = latestTask,
                    run = run,
                    decision = staleDecision,
                    transition = BotTaskStatusMachine.Event.MARK_UNKNOWN,
                    evidence = ReviewEvidence(preparation.verifiedChecks, preparation.sideEffects, preparation.candidateRefs),
                )
                return Outcome(
                    disposition = ReviewDisposition.UNKNOWN,
                    findings = staleDecision.findings,
                    blockedReason = "任务约束已更新，本轮旧版本结果未应用",
                    accepted = staleCommit != AgentBotRepository.ReviewCommitResult.STALE,
                )
            }
        }
        val failedApply = attempt.failure
        if (attempt.committed == AgentBotRepository.ReviewCommitResult.EFFECT_REJECTED && failedApply != null) {
            val reason = when (failedApply) {
                is StagedArtifactStore.ApplyResult.Conflict -> failedApply.reason
                is StagedArtifactStore.ApplyResult.Stale -> failedApply.reason
                is StagedArtifactStore.ApplyResult.Applied -> "候选写入完成，但审核事务未能结算"
            }
            val unknownDecision = decision.copy(
                disposition = ReviewDisposition.UNKNOWN,
                findings = decision.findings + reason + appliedTargets.takeIf { it.isNotEmpty() }
                    ?.let { "已应用但未全部结算: ${it.joinToString()}" }.orEmpty(),
            )
            val unknownCommit = recordReview(
                task = task,
                run = run,
                decision = unknownDecision,
                transition = BotTaskStatusMachine.Event.MARK_UNKNOWN,
                evidence = ReviewEvidence(preparation.verifiedChecks, preparation.sideEffects, preparation.candidateRefs),
            )
            Logger.w("BotResultApplier", "候选未能结算为成功: ${task.taskId} | $reason")
            return Outcome(
                disposition = ReviewDisposition.UNKNOWN,
                applied = appliedTargets.isNotEmpty(),
                accepted = unknownCommit != AgentBotRepository.ReviewCommitResult.STALE,
                findings = unknownDecision.findings,
                blockedReason = reason,
            )
        }
        return Outcome(
            disposition = ReviewDisposition.UNKNOWN,
            applied = appliedTargets.isNotEmpty(),
            findings = decision.findings + "审核状态已变化，候选未能结算",
            blockedReason = "审核状态已变化，候选未能结算",
        )
    }

    private suspend fun prepareReview(
        task: BotTaskEntity,
        run: TaskRunEntity,
        payloadRef: String,
        expectedBaseHash: String,
        criteria: String,
    ): ReviewPreparation {
        val stagedIds = parseStagedIds(payloadRef)
        val markerAt = payloadRef.lastIndexOf(STAGED_MARKER)
        val summary = if (markerAt >= 0) payloadRef.substring(0, markerAt).trim() else payloadRef.trim()
        val candidates = stagedIds.mapNotNull { id -> staging?.read(id)?.let { id to it } }
        val malformedReference = markerAt >= 0 && !stagedReferencesAreValid(payloadRef, markerAt)
        val missingCandidates = malformedReference || candidates.size != stagedIds.size
        val summaryOnly = stagedIds.isEmpty() && markerAt < 0 && task.resourceScope.isBlank()
        val reviewFacts = buildReviewFacts(task, candidates, stagedIds, expectedBaseHash)
        val sideEffects = readSideEffects(run.runId)
        val candidateRefs = candidates.map { (_, candidate) -> candidate.targetRef }
        val duplicateTargets = candidateRefs.size != candidateRefs.distinct().size
        val verifiedChecks = if (reviewFacts.baselineChecks) listOf(BASELINE_VERIFICATION) else emptyList()
        var decision = reviewer.review(
            BotReviewWorker.ReviewInput(
                criteria = criteria,
                claimedSummary = summary,
                producedArtifacts = candidateRefs,
                verifiedChecks = verifiedChecks,
                expectedBaseHash = reviewFacts.expectedHash,
                actualBaseHash = reviewFacts.actualHash,
                attempt = run.attempt,
                irreversibleSideEffects = sideEffects,
                summaryOnly = summaryOnly,
            ),
        )
        decision = applySafetyOverrides(
            task = task,
            run = run,
            decision = decision,
            candidates = candidates,
            safety = SafetyContext(
                missingCandidates = missingCandidates,
                invalidMarker = markerAt >= 0 && stagedIds.isEmpty(),
                duplicateTargets = duplicateTargets,
                hasUnreadableBaseline = reviewFacts.hasUnreadableBaseline,
            ),
        )
        return ReviewPreparation(
            stagedIds = stagedIds,
            summary = summary,
            candidates = candidates,
            currentHashes = reviewFacts.currentHashes,
            sideEffects = sideEffects,
            candidateRefs = candidateRefs,
            verifiedChecks = verifiedChecks,
            summaryOnly = summaryOnly,
            decision = decision,
        )
    }

    private suspend fun readSideEffects(runId: String): List<String> {
        val stepRead = runCatching { repository.listSteps(runId) }
            .onFailure { Logger.w("BotResultApplier", "读取步骤失败: ${it.message}") }
        return if (stepRead.isSuccess) {
            stepRead.getOrThrow()
                .filter { step -> step.sideEffect == StepSideEffect.NO_BLIND_REPLAY }
                .map { step -> step.toolName.ifBlank { "未知操作" } }
        } else {
            listOf("无法确认已发生的操作（步骤账本读取失败）")
        }
    }

    private suspend fun buildReviewFacts(
        task: BotTaskEntity,
        candidates: List<Pair<String, StagedArtifactStore.Candidate>>,
        stagedIds: List<String>,
        expectedBaseHash: String,
    ): ReviewFacts {
        val currentHashes = candidates.associate { (id, candidate) ->
            id to currentTargetHash(task, candidate.targetRef)
        }
        val baselineEntries = candidates.mapNotNull { (id, candidate) ->
            candidate.baseHash.takeIf { it.isNotBlank() }?.let { baseHash ->
                val currentHash = currentHashes[id].orEmpty()
                val effectiveHash = if (currentHash == candidate.contentHash) baseHash else currentHash
                Triple(candidate.targetRef, baseHash, effectiveHash)
            }
        }.sortedBy { it.first }
        val expectedHash = when {
            candidates.size == 1 -> baselineEntries.singleOrNull()?.second ?: expectedBaseHash
            baselineEntries.isNotEmpty() -> compositeBaselineHash(baselineEntries.map { it.first to it.second })
            else -> expectedBaseHash
        }
        val actualHash = when {
            candidates.size == 1 -> baselineEntries.singleOrNull()?.third
                ?: currentHashes[stagedIds.single()].orEmpty()
            baselineEntries.isNotEmpty() -> compositeBaselineHashFromTriples(baselineEntries)
            else -> ""
        }
        val hasUnreadableBaseline = currentHashes.values.any { it == StagedArtifactStore.UNREADABLE_TARGET_HASH }
        val baselineChecks = candidates.isNotEmpty() && baselineEntries.size == candidates.size &&
            !hasUnreadableBaseline && candidates.all { (id, candidate) ->
                currentHashes[id] == candidate.baseHash || currentHashes[id] == candidate.contentHash
            }
        return ReviewFacts(currentHashes, expectedHash, actualHash, baselineChecks, hasUnreadableBaseline)
    }

    private fun applySafetyOverrides(
        task: BotTaskEntity,
        run: TaskRunEntity,
        decision: BotReviewWorker.Decision,
        candidates: List<Pair<String, StagedArtifactStore.Candidate>>,
        safety: SafetyContext,
    ): BotReviewWorker.Decision {
        var result = decision
        when {
            safety.missingCandidates || safety.invalidMarker -> {
                result = result.copy(
                    disposition = ReviewDisposition.UNKNOWN,
                    findings = result.findings + "候选引用无效或产物缺失，不能确认目标结果",
                )
            }
            candidates.any { (_, candidate) ->
                candidate.taskRevision != run.taskRevision ||
                    (candidate.sourceRunId.isNotBlank() && candidate.sourceRunId != run.runId) ||
                    candidate.verificationState == VerificationState.CONFLICTED
            } -> {
                result = result.copy(
                    disposition = ReviewDisposition.UNKNOWN,
                    findings = result.findings + "候选与当前执行轮次或任务版本不匹配，或此前已发生冲突",
                )
            }
            safety.duplicateTargets -> {
                result = result.copy(
                    disposition = ReviewDisposition.UNKNOWN,
                    findings = result.findings + "同一目标存在多个独立候选，无法确认应用顺序",
                )
            }
            safety.hasUnreadableBaseline -> {
                result = result.copy(
                    disposition = ReviewDisposition.NEEDS_USER,
                    findings = result.findings + "无法读取一个或多个目标基线，不能安全应用候选",
                )
            }
            candidates.isNotEmpty() && workspaceManager != null -> {
                val mountIds = workspaceManager.mounts().mapTo(mutableSetOf()) { it.id }
                val scopeErrors = candidates.mapNotNull { (_, candidate) ->
                    io.zer0.muse.tools.WorkspaceTool.resourceScopeError(
                        name = io.zer0.muse.tools.WorkspaceTool.NAME_WRITE,
                        args = mapOf("path" to candidate.targetRef),
                        declaredScope = task.resourceScope,
                        knownMountIds = mountIds,
                    )
                }.distinct()
                if (scopeErrors.isNotEmpty()) {
                    result = result.copy(
                        disposition = ReviewDisposition.NEEDS_USER,
                        findings = result.findings + scopeErrors,
                    )
                }
            }
            candidates.isEmpty() && task.resourceScope.isNotBlank() -> {
                result = result.copy(
                    disposition = ReviewDisposition.NEEDS_USER,
                    findings = result.findings + "任务声明了可写范围，但没有结构化文件产物；摘要不能替代文件内容",
                )
            }
        }
        return result
    }

    private data class ReviewPreparation(
        val stagedIds: List<String>,
        val summary: String,
        val candidates: List<Pair<String, StagedArtifactStore.Candidate>>,
        val currentHashes: Map<String, String>,
        val sideEffects: List<String>,
        val candidateRefs: List<String>,
        val verifiedChecks: List<String>,
        val summaryOnly: Boolean,
        val decision: BotReviewWorker.Decision,
    )

    private data class ReviewFacts(
        val currentHashes: Map<String, String>,
        val expectedHash: String,
        val actualHash: String,
        val baselineChecks: Boolean,
        val hasUnreadableBaseline: Boolean,
    )

    private data class SafetyContext(
        val missingCandidates: Boolean,
        val invalidMarker: Boolean,
        val duplicateTargets: Boolean,
        val hasUnreadableBaseline: Boolean,
    )

    private suspend fun recordBlockedOutcome(
        task: BotTaskEntity,
        run: TaskRunEntity,
        decision: BotReviewWorker.Decision,
        evidence: ReviewEvidence,
        reason: String,
        blockedReason: String = reason,
    ): Outcome {
        val blocked = decision.copy(
            disposition = ReviewDisposition.NEEDS_USER,
            findings = decision.findings + reason,
        )
        recordReview(
            task = task,
            run = run,
            decision = blocked,
            transition = BotTaskStatusMachine.Event.REVIEW_NEEDS_USER,
            evidence = evidence,
        )
        return Outcome(
            disposition = blocked.disposition,
            applied = false,
            findings = blocked.findings,
            blockedReason = blockedReason,
        )
    }

    /**
     * 落审核事实；明确允许发布时再落可发布事实。
     *
     * ## 顺序为什么不能反
     *
     * 发布器只看 `TASK_PUBLICATION_READY`。如果先发它再写 REVIEW_READY，
     * 中间崩溃就会出现"有发布事实、没有审核依据"的消息 —— 用户看到一句
     * 无从追溯的"已完成"。先写依据，再写许可。
     */
    private suspend fun recordReview(
        task: BotTaskEntity,
        run: TaskRunEntity,
        decision: BotReviewWorker.Decision,
        transition: BotTaskStatusMachine.Event,
        evidence: ReviewEvidence,
        summaryOnly: Boolean = false,
        resultSummary: String = "",
        applyEffect: suspend () -> Boolean = { true },
    ): AgentBotRepository.ReviewCommitResult {
        val publicationAllowed = transition == BotTaskStatusMachine.Event.REVIEW_ACCEPT
        val reviewedFact = reviewer.reviewDecision(
            decision = decision,
            goal = task.goal,
            verifiedChecks = evidence.verifiedChecks,
            sideEffects = evidence.sideEffects,
            candidateRefs = evidence.candidateRefs,
            publicationAllowed = publicationAllowed,
            publicationKey = PublicationService.publicationKeyFor(
                taskId = task.taskId,
                runId = run.runId,
                taskRevision = run.taskRevision,
            ),
            taskRevision = run.taskRevision,
        )
        val fact = if (summaryOnly) {
            reviewedFact.copy(
                userVisibleSummary = "「${task.goal.trim().ifBlank { "这件事" }}」只读任务摘要（未独立验证）：\n${resultSummary.trim()}",
                unverifiedItems = (reviewedFact.unverifiedItems + "摘要来自执行回合，未独立验证").distinct(),
            )
        } else {
            reviewedFact
        }
        return repository.commitReview(
            taskId = task.taskId,
            runId = run.runId,
            taskRevision = run.taskRevision,
            transition = transition,
            reviewPayload = fact.encode(),
            applyEffect = applyEffect,
        )
    }

    private data class ReviewEvidence(
        val verifiedChecks: List<String>,
        val sideEffects: List<String>,
        val candidateRefs: List<String>,
    )

    private data class ApplyAttempt(
        val committed: AgentBotRepository.ReviewCommitResult,
        val failure: StagedArtifactStore.ApplyResult?,
        val appliedTargets: List<String>,
    )

    private suspend fun currentTargetHash(task: BotTaskEntity, targetRef: String): String {
        val manager = workspaceManager
        if (manager != null) {
            val mountScope = manager.sessionMountIds(task.originSessionId)
            return when (val snapshot = manager.readSnapshotForWrite(targetRef, mountScope)) {
                is io.zer0.muse.workspace.WorkspaceManager.WriteSnapshot.Existing ->
                    StagedArtifactStore.hashSnapshot(snapshot.content)
                io.zer0.muse.workspace.WorkspaceManager.WriteSnapshot.Missing ->
                    StagedArtifactStore.MISSING_TARGET_HASH
                is io.zer0.muse.workspace.WorkspaceManager.WriteSnapshot.Refused -> {
                    Logger.w("BotResultApplier", "无法核对候选基线: $targetRef | ${snapshot.reason}")
                    StagedArtifactStore.UNREADABLE_TARGET_HASH
                }
            }
        }
        return StagedArtifactStore.hashSnapshot(readTarget?.invoke(targetRef))
    }

    private suspend fun writeArtifact(task: BotTaskEntity, targetRef: String, content: String, candidate: StagedArtifactStore.Candidate) {
        val manager = workspaceManager
        if (manager != null) {
            val mountScope = manager.sessionMountIds(task.originSessionId)
            val expectedSnapshot = if (!candidate.hasBaseSnapshot) {
                null
            } else if (candidate.baseContent == null) {
                io.zer0.muse.workspace.WorkspaceManager.WriteSnapshot.Missing
            } else {
                io.zer0.muse.workspace.WorkspaceManager.WriteSnapshot.Existing(candidate.baseContent)
            }
            val result = if (expectedSnapshot == null) {
                manager.writeFile(targetRef, content, mountScope)
            } else {
                manager.writeFileIfSnapshot(targetRef, expectedSnapshot, content, mountScope)
            }
            when (result) {
                is io.zer0.muse.workspace.WorkspaceManager.OpResult.Success -> return
                is io.zer0.muse.workspace.WorkspaceManager.OpResult.Error -> error(result.message)
            }
        }
        val writer = writeTarget ?: error("没有写入能力")
        writer(targetRef, content)
    }

    /**
     * 严格列出仍在暂存区的候选，供用户审计（V04 的"历史候选仍留审计"）。
     *
     * 冲突的候选不会被删，用户能在这里看到"这份结果本来想改成什么"。
     *
     * 用密封结果而不是 `List`：读失败折成空列表后，页面会显示成"候选没了"，
     * 而文件其实还在磁盘上。页面必须能区分这两件事。
     */
    fun listPendingCandidatesResult(): StagedArtifactStore.StagedListResult =
        staging?.listStagedResult() ?: StagedArtifactStore.StagedListResult.Success(emptyList())

    /** 宽松入口：读失败按"没有候选"处理。页面不要用这一条。 */
    fun listPendingCandidates(): List<StagedArtifactStore.StagedEntry> = when (val result = listPendingCandidatesResult()) {
        is StagedArtifactStore.StagedListResult.Success -> result.entries
        is StagedArtifactStore.StagedListResult.Error -> {
            Logger.w("BotResultApplier", "读取候选列表失败: ${result.reason}", result.cause)
            emptyList()
        }
    }

    /**
     * 清理"候选已经不在暂存区"的事件引用。
     *
     * 页面靠事件 payload 末尾的 `[staged=...]` 找到候选，所以保留策略不能删还有文件的行
     * （否则用户看着候选，7 天后它自己消失，文件却还躺在磁盘上）。
     * 反过来，文件被应用或丢弃之后那行引用就没用了，可以在超过保留期后收走。
     *
     * SQL 看不到文件系统，所以"文件还在不在"必须由持有暂存区的一方判断。
     * 暂存区读失败时一条都不删 —— 宁可多留几行，也不能让页面上的候选凭空消失。
     */
    suspend fun pruneSettledStagedEvents(retentionMs: Long = AgentBotRepository.EVENT_RETENTION_MS): Int {
        val store = staging ?: return 0
        val live = when (val listed = store.listStagedResult()) {
            is StagedArtifactStore.StagedListResult.Success ->
                listed.entries.mapTo(mutableSetOf()) { it.stagedId }
            is StagedArtifactStore.StagedListResult.Error -> {
                Logger.w("BotResultApplier", "暂存区读取失败，本轮不清理候选引用: ${listed.reason}", listed.cause)
                return 0
            }
        }
        return repository.pruneStagedReferences(live, retentionMs)
    }

    /** 用户确认后丢弃某条候选。 */
    fun discardCandidate(stagedId: String) {
        staging?.discard(stagedId)
    }

    fun readCandidate(stagedId: String): StagedArtifactStore.Candidate? = staging?.read(stagedId)

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
    private fun parseStagedIds(payloadRef: String): List<String> {
        val start = payloadRef.lastIndexOf(STAGED_MARKER)
        if (start < 0) return emptyList()
        val end = payloadRef.indexOf(']', start)
        if (end < 0) return emptyList()
        return payloadRef.substring(start + STAGED_MARKER.length, end)
            .split(',')
            .map(String::trim)
            .filter(STAGED_ID_PATTERN::matches)
            .distinct()
    }

    private fun stagedReferencesAreValid(payloadRef: String, markerAt: Int): Boolean {
        val end = payloadRef.indexOf(']', markerAt)
        if (end < 0) return false
        val references = payloadRef.substring(markerAt + STAGED_MARKER.length, end)
            .split(',')
            .map(String::trim)
        return references.isNotEmpty() && references.all(STAGED_ID_PATTERN::matches)
    }

    private fun compositeBaselineHash(entries: List<Pair<String, String>>): String =
        StagedArtifactStore.hashOf(entries.joinToString("\n") { (target, hash) -> "$target\u001f$hash" })

    private fun compositeBaselineHashFromTriples(entries: List<Triple<String, String, String>>): String =
        compositeBaselineHash(entries.map { (target, _, hash) -> target to hash })

    companion object {
        /** 事件 payloadRef 里的暂存标记。与 [BotTaskScheduler] 写入侧必须一致。 */
        const val STAGED_MARKER = "[staged="

        /**
         * 审核事实里的"已核验"措辞。
         *
         * 发布正文直接引用它，不另写一句 —— 两处各写一句必然漂移，
         * 用户看到的那句就和审核记录对不上了。
         */
        const val BASELINE_VERIFICATION = "目标基线核对"

        /**
         * 暂存 id 的合法格式。
         *
         * 由 [StagedArtifactStore.stage] 生成：`cand-<nanoTime>`。
         * 校验它是为了防止"摘要里恰好含标记"时把错误字符串当成 id。
         */
        private val STAGED_ID_PATTERN = Regex("^cand-\\d+$")
    }
}
