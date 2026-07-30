package io.zer0.muse.data

import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.ticker.MemoryTicker
import io.zer0.muse.data.session.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * v1.0.51: 存量记忆迁移 — 升级后首次启动补跑历史 session 的 rollingSummary。
 *
 * 背景:
 *  v1.0.50 时期 MemoryLlmClientImpl 丢弃 reasoning_content,推理模型全空响应 →
 *  rollingSummary 返回 changed=false → SessionSummaryManager 不 saveSummary →
 *  session_summaries 表无这些 session 的行 → compileToday/Daily/Facts 拉不到数据 →
 *  memory.md 永远是 4 段占位符。
 *
 *  v1.0.51 修复了 LLM 兜底,但只对新会话生效。本类负责补跑旧 session:
 *  1. 遍历所有活跃 session,找出 session_summaries 表里无对应行的
 *  2. 从 MessageDao 读全量 messages → 喂 SessionSummaryManager.rollingSummary
 *  3. 完成后调 tick() 跑完整 daily pipeline(compileToday/Daily/Facts/longterm)
 *  4. 持久化迁移完成标志,下次启动不再执行
 *
 * 并发控制:
 *  - [SettingsRepository.tryStartMemoryBackfillMigration] 用 AtomicBoolean 保证只跑一次
 *  - [MemoryTicker.backfillSession] 复用 doRollingSummary 的 Semaphore(3) 全局限流
 *  - 单 session 失败不中断整体流程,记入 failed 计数
 *
 * 进度报告:
 *  - 通过 [MemoryTicker.backfillProgressFlow] 实时报告 (total/processed/succeeded/failed)
 *  - MemoryScreen 订阅该 flow 在顶部显示进度条
 */
class MemoryBackfillMigration(
    private val sessionRepository: SessionRepository,
    private val memoryTicker: MemoryTicker,
    private val settings: SettingsRepository,
) {
    companion object {
        private const val TAG = "MemoryBackfill"
    }

    /**
     * 检查并执行迁移。已迁移过则直接返回。
     *
     * 三道守卫保证幂等安全:
     *  1. DataStore 标志位([SettingsRepository.memoryBackfillMigrationDoneFlow])— 跨进程/重启持久化
     *  2. AtomicBoolean compareAndSet([SettingsRepository.tryStartMemoryBackfillMigration])— 防并发首调
     *  3. 记忆开关([SettingsRepository.isMemoryEnabled])— 尊重用户关闭记忆的设置
     *
     * @return true 表示执行了迁移(无论成功与否);false 表示已迁移过/无需迁移/记忆关闭
     */
    suspend fun migrateIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        // 守卫 1: 持久化标志位检查
        if (settings.memoryBackfillMigrationDoneFlow.first()) {
            return@withContext false
        }
        // 守卫 2: AtomicBoolean compareAndSet,防止并发首调重复执行
        if (!settings.tryStartMemoryBackfillMigration()) {
            return@withContext false
        }
        // 守卫 3: 记忆开关关闭则不跑(尊重用户设置),但仍标记为已迁移避免下次再检查
        if (!settings.isMemoryEnabled()) {
            Logger.i(TAG, "记忆开关关闭,跳过 backfill 迁移")
            settings.saveMemoryBackfillMigrationDone(true)
            return@withContext false
        }

        Logger.i(TAG, "开始存量记忆迁移:扫描无摘要的历史 session")
        val model = resultOf { settings.getSelectedModel() }.getOrNull()
        // locale/timeZone 用默认值,与 ChatViewModel.notifyTurn 调用保持一致
        val locale = "zh-CN"
        val timeZone = io.zer0.memory.time.TimeContext.DEFAULT_TIMEZONE

        val allSessions = resultOf { sessionRepository.getAllActiveSessionsForBackfill() }
            .onError { msg, t -> Logger.w(TAG, "获取 session 列表失败: $msg", t) }
            .getOrNull() ?: emptyList()
        val summarizedIds = resultOf { memoryTicker.getAllSummarizedSessionIds() }
            .onError { msg, t -> Logger.w(TAG, "获取已有摘要列表失败: $msg", t) }
            .getOrNull() ?: emptySet()
        val toBackfill = allSessions.filter { it.id !in summarizedIds }

        if (toBackfill.isEmpty()) {
            Logger.i(TAG, "无需迁移:所有 ${allSessions.size} 个 session 均已有摘要")
            settings.saveMemoryBackfillMigrationDone(true)
            return@withContext false
        }

        Logger.i(TAG, "需补跑 ${toBackfill.size}/${allSessions.size} 个 session 的摘要")
        var succeeded = 0
        var failed = 0
        for ((i, session) in toBackfill.withIndex()) {
            // 报告进度(当前正在处理第 i+1 个)
            memoryTicker.reportBackfillProgress(
                MemoryTicker.BackfillProgress(
                    total = toBackfill.size,
                    processed = i,
                    succeeded = succeeded,
                    failed = failed,
                    currentSessionId = session.id,
                    done = false,
                ),
            )

            val messages = resultOf { sessionRepository.getAllMessagesForBackfill(session.id) }
                .onError { msg, t -> Logger.w(TAG, "读取 session ${session.id.take(8)}… 消息失败: $msg", t) }
                .getOrNull() ?: emptyList()
            if (messages.isEmpty()) {
                // 空会话无摘要属正常,不计入 failed
                continue
            }

            val changed = resultOf {
                memoryTicker.backfillSession(session.id, messages, model, locale, timeZone, session.assistantId)
            }.onError { msg, t ->
                Logger.w(TAG, "backfill session ${session.id.take(8)}… 失败: $msg", t)
            }.getOrNull() ?: false

            if (changed) succeeded++ else failed++
        }

        // 最终进度报告(done=true,UI 据此显示完成状态后自动隐藏)
        memoryTicker.reportBackfillProgress(
            MemoryTicker.BackfillProgress(
                total = toBackfill.size,
                processed = toBackfill.size,
                succeeded = succeeded,
                failed = failed,
                currentSessionId = null,
                done = true,
            ),
        )
        Logger.i(TAG, "摘要补跑完成: 成功 $succeeded, 失败 $failed, 共 ${toBackfill.size}")

        // 跑完整 daily pipeline,让新摘要进入 memory.md(compileToday/Daily/Facts/longterm)
        resultOf { memoryTicker.tick(model, locale, timeZone) }
            .onError { msg, t -> Logger.w(TAG, "迁移后 tick() 失败: $msg", t) }

        // 持久化迁移完成标志
        settings.saveMemoryBackfillMigrationDone(true)
        // v1.0.51: 不在此 clear 进度流 — 保留 done=true 状态,
        // 让用户进入记忆中心时能看到"已完成"提示(页面展示后自行 clear)
        true
    }
}
