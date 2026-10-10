package io.zer0.muse.ui.chat
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppDispatchers
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.ticker.MemoryTicker
import io.zer0.muse.R
import io.zer0.muse.session.WarmupHistory
import io.zer0.muse.transformer.CompressionDiagnostics
import io.zer0.muse.transformer.ContextCompressTransformer
import io.zer0.muse.transformer.TransformContext
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.runManualCompressionStages
import io.zer0.muse.util.TokenEstimator
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * v2.x 重构 S2: 从 ChatViewModel 提取的上下文压缩编排。
 *
 * 原位置:ChatViewModel 的自动压缩链(triggerAutoCompress /
 * finishCompressionWithCheckpoint / persistLocalCheckpointFallback /
 * persistContextCheckpoint)与手动压缩链(manualCompress /
 * manualCompressContext / announceManualCompressDone / runManualCompress)。
 *
 * 迁移性质:**纯抽取**——代码原样搬入,不改动任何逻辑/顺序/异常语义。
 * ChatViewModel 原位置保留委托调用。
 */
@Suppress(
    "LongParameterList", "LongMethod", "ComplexMethod", "TooGenericExceptionCaught",
    "ReturnCount", "CyclomaticComplexMethod", "NestedBlockDepth", "TooManyFunctions",
    "LargeClass", "ComplexCondition", "UnusedPrivateMember", "unused",
)
internal class ChatCompressionCoordinator(
    private val host: ChatCompressionHostBridge,
) {
    fun triggerAutoCompress(sessionId: String) {
        val maxTokens = host.stateStore.state.value.contextMaxTokens
        val currentTokens = host.stateStore.state.value.contextTokenCount
        if (maxTokens <= 0 || currentTokens <= 0 || !host.shouldAutoCompress(currentTokens, maxTokens)) return
        val currentMessages = host.stateStore.messages.value
        if (currentMessages.size < 2) return
        // v2.x（诊断导出项目 A）: 压缩触发入 trace（纯观察者）
        runCatching { io.zer0.muse.diagnostic.GenerationTrace.noteCompression() }

        host.coroutineScope.launch(AppDispatchers.io) {
            val keepRecent = minOf(COMPRESSION_SAFETY_TAIL_MESSAGES, currentMessages.size - 1).coerceAtLeast(1)
            // v2.x: 取上一次检查点的边界 —— 既用于"只统计本次新增并入的消息",也用于滚动改写
            val existingCheckpoint =
                try {
                    host.checkpointReader.get(sessionId)
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Logger.w("ChatVM", io.zer0.muse.transformer.CompressionDiagnostics.readCheckpointFailed(t.message))
                    null
                }
            val currentCheckpointBoundaryId = existingCheckpoint?.lastCoveredMessageId
            val compressContext =
                TransformContext(
                    sessionId = sessionId,
                    modelId = host.stateStore.state.value.currentAssistant?.modelId,
                    extras =
                    mapOf(
                        "compress_enabled" to true,
                        // X-5: 手动强制触发阈值统一走 CompressionPolicy（语义：手动压缩不设条数门槛）。
                        "compress_threshold" to
                            io.zer0.muse.transformer.CompressionPolicy.FORCE_TRIGGER_THRESHOLD,
                        "compress_keep_recent" to keepRecent,
                    ),
                )
            // H-01 修复: transform 是 suspend 函数,改用 resultOf 避免吞没 CancellationException
            val compressed =
                resultOf {
                    host.contextCompressTransformer.transform(currentMessages, compressContext)
                }.onError { msg, t ->
                    Logger.w("ChatVM", "Auto-compress transform failed: $msg")
                }.getOrNull() ?: currentMessages
            // 本次实际并入摘要的消息(进程内水位线记录);为空说明没压成,不写检查点
            val compressedEntry = io.zer0.muse.transformer.CompressionSummaryStore.entry(sessionId)
            val coveredIds: Set<String> = compressedEntry?.coveredIds.orEmpty()
            val maxCoveredId: String? = coveredIds.maxOrNull()
            if (compressed.size < currentMessages.size) {
                // v2.3.2: 会话守卫 — 压缩是耗时 LLM 调用,期间用户可能已切走会话;
                // 过期结果绝不能写进当前会话(否则 A 会话的摘要会盖到 B 会话的消息列表上,
                // 界面出现"另一个会话的内容",继续发送还会把 A 的上下文送给模型)。
                if (host.displayedSessionId() != sessionId) {
                    Logger.i("ChatVM", io.zer0.muse.transformer.CompressionDiagnostics.staleAutoCompressResult(sessionId))
                    return@launch
                }
                finishCompressionWithCheckpoint(
                    sessionId = sessionId,
                    currentMessages = currentMessages,
                    compressed = compressed,
                    coveredIds = coveredIds,
                    maxCoveredId = maxCoveredId,
                    previousBoundaryId = currentCheckpointBoundaryId,
                    previousTotalCovered = existingCheckpoint?.totalCoveredCount ?: 0,
                    keepRecent = keepRecent,
                    tokensBefore = currentTokens,
                    reason = io.zer0.memory.summary.ContextCheckpointEntity.REASON_AUTO,
                )
                Logger.i(
                    "ChatVM",
                    CompressionDiagnostics.checkpointSaved(
                        ratio = "%.2f".format(currentTokens.toFloat() / maxTokens),
                        covered = coveredIds.size,
                        before = currentMessages.size,
                        after = compressed.size,
                    ),
                )
            } else if (host.displayedSessionId() == sessionId) {
                // v2.x 降级阶梯最后一级：摘要没产出（网络差 / 摘要模型报错 / 超时 / 无净收益）。
                // 此时**不空手而归** —— 用零请求的确定性文本摘录做本地重建，仍然写出一条检查点。
                persistLocalCheckpointFallback(
                    sessionId = sessionId,
                    messages = currentMessages,
                    keepRecent = keepRecent,
                    previousBoundaryId = currentCheckpointBoundaryId,
                    previousTotalCovered = existingCheckpoint?.totalCoveredCount ?: 0,
                    tokensBefore = currentTokens,
                )
            }
        }
    }

    /**
     * v2.x: 一次**成功**压缩的收尾 —— 落检查点并刷新"已覆盖/占用率"口径。
     *
     * 自动与手动压缩共用：两条路径在"模型看到什么、占用率怎么算"上必须完全一致，
     * 否则用户主动点"压缩"会得到与自动压缩不同的结果（曾经就是这样的行为分叉）。
     *
     * 落检查点而不是改写内存消息列表：界面继续显示完整历史（老消息默认收起），
     * 只有发给模型的历史按边界过滤；重开应用/切回会话依然生效。
     *
     * @param previousBoundaryId 上一次检查点的边界（`newlyCoveredCount` 的起点）
     * @param previousTotalCovered 上一次检查点的累计条数（界面展示用，滚动累加）
     * @param maxCoveredId 本次覆盖的边界 id 兜底值（正常应取 [ContextCompressTransformer.lastCoveredBoundaryId]）
     */
    private suspend fun finishCompressionWithCheckpoint(
        sessionId: String,
        currentMessages: List<UIMessage>,
        compressed: List<UIMessage>,
        coveredIds: Set<String>,
        maxCoveredId: String?,
        previousBoundaryId: String?,
        previousTotalCovered: Int,
        keepRecent: Int,
        tokensBefore: Int,
        reason: String,
    ) {
        if (maxCoveredId == null) {
            Logger.w("ChatVM", CompressionDiagnostics.NO_COVERAGE_RECORD)
            return
        }
        persistContextCheckpoint(
            sessionId = sessionId,
            previousBoundaryId = previousBoundaryId,
            previousTotalCovered = previousTotalCovered,
            generatedBoundaryId = maxCoveredId,
            tokensBefore = tokensBefore,
            tokensAfter = TokenEstimator.estimate(compressed),
            reason = reason,
            // 边界与条数都取自"有序的压缩集合"，不用消息 id 排序推断：
            // 消息 id 是随机 UUID，集合无序，靠排序会选错边界并丢掉未覆盖的消息。
            boundaryMessageId = host.contextCompressTransformer.lastCoveredBoundaryId,
            newlyCoveredCount = newlyCoveredCount(
                messages = currentMessages,
                coveredIds = coveredIds,
                previousBoundaryId = previousBoundaryId,
            ),
        )
        // 覆盖集合先刷新再算占用率 —— 刷新后 host.checkpointCoveredIds 才是本次压缩的结果
        host.checkpointCoveredSessionId = sessionId
        host.refreshCheckpointCoveredIds(sessionId)
        host.updateContextTokenCount()
        // keepRecent 目前不参与落库，但保留在签名里以免调用方以为"保留策略只由压缩器决定"
        Logger.d("ChatVM", "checkpoint finished (keepRecent=$keepRecent)")
    }

    /**
     * v2.x: 数出"本次新并入摘要"的消息条数。
     *
     * 用**有序的消息列表**来数（而不是对覆盖 id 集合排序）：消息 id 是随机 UUID，
     * 集合本身无序，任何基于 id 比较的推断都会在真实数据上算错。
     * 上次边界之后、且落在覆盖集合内的消息才算"本次新增"。
     */
    internal fun newlyCoveredCount(messages: List<UIMessage>, coveredIds: Set<String>, previousBoundaryId: String?): Int =
        host.newlyCoveredCount(messages, coveredIds, previousBoundaryId)

    /**
     * v2.x: 摘要不可用时的**本地重建**检查点（零模型请求）。
     *
     * 覆盖范围与正常压缩一致：保留最近 [keepRecent] 条，其余做确定性文本摘录。
     * 摘录只截取每条的自然语言片段，不做提炼 —— 它的价值不在于"摘要得多好"，
     * 而在于**边界立刻生效**：被覆盖的消息不再进模型，且这一状态是持久的。
     */
    private suspend fun persistLocalCheckpointFallback(
        sessionId: String,
        messages: List<UIMessage>,
        keepRecent: Int,
        previousBoundaryId: String?,
        previousTotalCovered: Int,
        tokensBefore: Int,
    ) {
        val compressibleEnd = messages.size - keepRecent
        val covered = if (compressibleEnd <= 0) {
            emptyList()
        } else {
            messages.subList(0, compressibleEnd).filterNot {
                it.role == MessageRole.SYSTEM && it.content.startsWith(io.zer0.muse.transformer.ContextCheckpointMerge.MARKER)
            }
        }
        if (covered.isEmpty()) return
        try {
            val boundary = covered.last().id.toString()
            val coveredSeq = runCatching { host.sessionRepository.getMessageById(boundary)?.seq ?: 0L }.getOrDefault(0L)
            val checkpoint = host.contextCompressTransformer.buildLocalCheckpoint(
                io.zer0.muse.transformer.ContextCompressTransformer.LocalCheckpointRequest(
                    sessionId = sessionId,
                    covered = covered,
                    previousBoundaryId = previousBoundaryId,
                    coveredSeq = coveredSeq,
                    tokensBefore = tokensBefore,
                    // 本地摘录的收益是"不再带原文"，按摘录正文长度估算即可（不精确，但方向正确）
                    tokensAfter = TokenEstimator.estimate(covered.last().content) + covered.size * 20,
                    reason = io.zer0.memory.summary.ContextCheckpointEntity.REASON_AUTO,
                ),
            ) ?: return
            host.checkpointReader.put(
                checkpoint.copy(totalCoveredCount = previousTotalCovered + covered.size),
            )
            host.checkpointCoveredSessionId = sessionId
            host.refreshCheckpointCoveredIds(sessionId)
            host.updateContextTokenCount()
            Logger.i(
                "ChatVM",
                CompressionDiagnostics.localRebuildSaved(covered = covered.size, before = messages.size),
            )
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Logger.w("ChatVM", CompressionDiagnostics.writeCheckpointFailed(t.message))
        }
    }

    /**
     * v2.x: 把一次压缩的结果写成会话检查点。
     *
     * 边界与前一次的差值才是"本次并入摘要"的消息集合 —— 检查点是滚动覆盖的，
     * 新检查点已经包含旧检查点的内容，若把旧边界之前也计入，会让 `coveredCount`/`tokensBefore`
     * 逐次虚增（表现为"压缩收益"越报越大，实际并没有省那么多）。
     *
     * 失败只记录日志：检查点写不进去最多是下一次还要重新摘要，绝不能影响本轮对话。
     */
    private suspend fun persistContextCheckpoint(
        sessionId: String,
        previousBoundaryId: String?,
        previousTotalCovered: Int,
        generatedBoundaryId: String,
        tokensBefore: Int,
        tokensAfter: Int,
        reason: String,
        /** 本次"已并入摘要"的边界消息 id（按时间顺序的最后一条，由 transform 给出）。 */
        boundaryMessageId: String? = null,
        /** 本次新并入摘要的条数（按有序列表求出，不用消息 id 排序推断）。 */
        newlyCoveredCount: Int = 0,
    ) {
        try {
            // 边界消息在消息表里的真实 seq（查不到就传 0：组装侧以消息 id 为准，seq 只作上界）
            val boundaryForSeq = boundaryMessageId ?: generatedBoundaryId
            val coveredSeq =
                runCatching { host.sessionRepository.getMessageById(boundaryForSeq)?.seq ?: 0L }
                    .getOrDefault(0L)
            val checkpoint = host.contextCompressTransformer.buildCheckpoint(
                io.zer0.muse.transformer.ContextCompressTransformer.CheckpointRequest(
                    sessionId = sessionId,
                    previousBoundaryId = previousBoundaryId,
                    generatedBoundaryId = generatedBoundaryId,
                    coveredSeq = coveredSeq,
                    tokensBefore = tokensBefore,
                    tokensAfter = tokensAfter,
                    reason = reason,
                    boundaryMessageId = boundaryMessageId,
                    previousTotalCovered = previousTotalCovered,
                    newlyCoveredCount = newlyCoveredCount,
                ),
            ) ?: return
            host.checkpointReader.put(checkpoint)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Logger.w("ChatVM", io.zer0.muse.transformer.CompressionDiagnostics.writeCheckpointFailed(t.message))
        }
    }

    /**
     * v0.45: 手动触发上下文压缩(记忆压缩常态化)。
     *
     * @param updateMemoryFirst true = 先调 [MemoryTicker.forceCompileNow] 更新记忆(fact/摘要),
     *                          再压缩历史;false = 只压缩历史(纯压缩)
     *
     * UI 入口是一键后台压缩：系统自动选择安全尾部，不向用户暴露保留条数或压缩指令。
     * 纯压缩模式仍供斜杠命令内部使用。
     *
     * 压缩结果只替换内存中的 messages(host.stateStore.messages.value),不持久化到 DB
     * (DB 保留完整历史用于搜索/导出,内存版本用于 LLM 上下文)。
     * 切换会话后从 DB 重新加载,下次发送时自动压缩器会再次处理。
     */
    fun manualCompress(updateMemoryFirst: Boolean = true) {
        val sessionId = host.displayedSessionId()
        val currentMessages = host.stateStore.messages.value
        // 前置校验统一收敛为单出口:无会话/消息过少/流式中/压缩中均直接返回。
        // 保持原语义:仅"消息过少"提示用户,其余前置状态静默跳过。
        if (sessionId == null || currentMessages.size < 2 ||
            host.stateStore.state.value.isStreaming || host.stateStore.state.value.isCompressing
        ) {
            if (sessionId != null && currentMessages.size < 2) {
                host.reportError(host.appContext.getString(R.string.err_chat_compress_too_few))
            }
            return
        }

        host.stateStore.state.update {
            it.copy(
                toolsState = it.toolsState.copy(
                    isCompressing = true,
                    compressionPhase = if (updateMemoryFirst) {
                        io.zer0.muse.ui.ManualCompressionPhase.UPDATING_MEMORY
                    } else {
                        io.zer0.muse.ui.ManualCompressionPhase.COMPRESSING_CONTEXT
                    },
                ),
            )
        }
        host.coroutineScope.launch(AppDispatchers.io) {
            runManualCompress(
                sessionId = sessionId,
                currentMessages = currentMessages,
                updateMemoryFirst = updateMemoryFirst,
            )
        }
    }

    /**
     * 手动压缩用的 transform 上下文。
     *
     * 与自动压缩的区别只在"强制触发 + 强制兜底"：用户主动点了压缩，就不该因为
     * 收益不明显而什么都不做；字符预算仍按当前窗口推导，避免切出超窗的巨块。
     */
    internal fun manualCompressContext(sessionId: String, keepRecent: Int): TransformContext = TransformContext(
        sessionId = sessionId,
        modelId = host.stateStore.state.value.currentAssistant?.modelId,
        extras =
        mapOf(
            "compress_enabled" to true,
            // 强制触发：用户主动点了压缩，不该因为收益不明显而什么都不做
            "compress_threshold" to io.zer0.muse.transformer.CompressionPolicy.FORCE_TRIGGER_THRESHOLD,
            "compress_keep_recent" to keepRecent,
            "compress_force_fallback" to true,
            "compress_char_budget" to
                WarmupHistory.compressTokenBudgetFor(host.stateStore.state.value.contextMaxTokens),
        ),
    )

    /**
     * 手动压缩成功后的对外反馈。
     *
     * v2.x: 不再报"已压缩: N → M 条" —— 条数变化对用户没有意义，还会让人以为会话被砍掉了。
     * 压缩在界面上的表达是对话流里的常驻分隔线（见 refreshContextInfo），这里只给一句状态。
     */
    internal fun announceManualCompressDone(beforeCount: Int, afterCount: Int, keepRecent: Int) {
        // 日志为内部诊断,不使用中文字面量(避免 CJK 门禁误判)
        Logger.i(
            "ChatVM",
            "manualCompress: $beforeCount -> $afterCount msgs (keepRecent=$keepRecent)",
        )
        MuseToast.show(host.appContext.getString(R.string.err_chat_compress_done))
    }

    /** 手动压缩的实际执行体（从 [manualCompress] 抽出，便于阅读与测试）。 */
    internal suspend fun runManualCompress(sessionId: String, currentMessages: List<UIMessage>, updateMemoryFirst: Boolean) {
        try {
            val effectiveKeepRecent =
                minOf(COMPRESSION_SAFETY_TAIL_MESSAGES, currentMessages.size - 1).coerceAtLeast(1)
            // v2.x: 手动压缩也走检查点，因此先取一次现有检查点（边界 + 累计条数）
            val manualExisting =
                runCatching { host.checkpointReader.get(sessionId) }.getOrNull()
            val manualPreviousBoundaryId = manualExisting?.lastCoveredMessageId
            val manualPreviousTotalCovered = manualExisting?.totalCoveredCount ?: 0
            val manualTokensBefore = host.stateStore.state.value.contextTokenCount
            val context = manualCompressContext(sessionId, effectiveKeepRecent)

            val compressed = runManualCompressionStages(
                updateMemoryFirst = updateMemoryFirst,
                onPhase = { phase ->
                    host.stateStore.state.update { state ->
                        state.copy(
                            toolsState = state.toolsState.copy(compressionPhase = phase),
                        )
                    }
                },
                updateMemory = {
                    // 强制提炼 fact + deep memory + 刷新 today;失败不阻断后续压缩。
                    val model = resultOf { host.settings.getSelectedModel() }.getOrNull()
                    resultOf {
                        host.memoryTicker.forceCompileNow(model = model)
                    }.onError { msg, _ ->
                        Logger.w("ChatVM", "forceCompileNow failed: $msg")
                        MuseToast.show(host.appContext.getString(R.string.err_chat_compress_memory_failed))
                    }
                },
                compress = {
                    // H-01 修复: transform 是 suspend 函数,改用 resultOf 避免吞没 CancellationException
                    resultOf {
                        host.contextCompressTransformer.transform(currentMessages, context)
                    }.onError { msg, _ ->
                        Logger.w("ChatVM", "manualCompress transform failed: $msg")
                    }.getOrNull() ?: currentMessages
                },
            )
            // 3. 替换内存中的 messages(不持久化,DB 保留完整历史)
            if (compressed.size < currentMessages.size) {
                // v2.3.2: 会话守卫 — 同 triggerAutoCompress,压缩期间切走会话则丢弃过期结果
                // (isCompressing 由外层 finally 复位,这里直接返回即可)
                if (host.displayedSessionId() != sessionId) {
                    Logger.i("ChatVM", "manualCompress 结果已过期(会话已切换),丢弃: $sessionId")
                    return
                }
                // v1.117: 修复消息丢失竞态 — 压缩是 suspend LLM 调用,耗时数秒,
                // 期间用户可能继续发送新消息(已 append 到 host.stateStore.messages.value)。
                // 直接用旧快照的 compressed 覆盖会丢弃这些新消息。
                // v2.x: 与自动压缩统一 —— 压缩结果落成**会话检查点**，不再改写内存消息列表。
                // 两层含义：① 界面保留完整历史（老消息默认收起，见 ChatScreen 的分隔线），
                // 只有发给模型的历史按边界过滤；② 手动与自动路径的行为完全一致，不会因为
                // 用户主动点"压缩"而回到旧行为。收尾逻辑与自动路径共用同一个 helper。
                val manualCoveredIds =
                    io.zer0.muse.transformer.CompressionSummaryStore.entry(sessionId)?.coveredIds.orEmpty()
                finishCompressionWithCheckpoint(
                    sessionId = sessionId,
                    currentMessages = currentMessages,
                    compressed = compressed,
                    coveredIds = manualCoveredIds,
                    maxCoveredId = manualCoveredIds.maxOrNull(),
                    previousBoundaryId = manualPreviousBoundaryId,
                    previousTotalCovered = manualPreviousTotalCovered,
                    keepRecent = effectiveKeepRecent,
                    tokensBefore = manualTokensBefore,
                    reason = io.zer0.memory.summary.ContextCheckpointEntity.REASON_MANUAL,
                )
                announceManualCompressDone(currentMessages.size, compressed.size, effectiveKeepRecent)
            } else if (updateMemoryFirst) {
                // 压缩未生效(可能消息太少或 LLM 返回空摘要),但记忆已更新
                MuseToast.show(host.appContext.getString(R.string.err_chat_compress_no_need))
            }
            // 4. 刷新 token 计数(重建 system prompt 因为记忆可能已更新)
            host.refreshContextInfo()
        } catch (e: Exception) {
            Logger.w("ChatVM", "manualCompress failed: ${e.message}")
            host.reportError(host.appContext.getString(R.string.err_chat_compress_failed, e.message ?: ""))
        } finally {
            host.stateStore.state.update {
                it.copy(
                    toolsState = it.toolsState.copy(
                        isCompressing = false,
                        compressionPhase = io.zer0.muse.ui.ManualCompressionPhase.IDLE,
                    ),
                )
            }
        }
    }

    /** 从 ChatViewModel.companion 随迁的常量(原压缩链直接引用)。 */
    private companion object {
        const val COMPRESSION_SAFETY_TAIL_MESSAGES = 10
    }
}
