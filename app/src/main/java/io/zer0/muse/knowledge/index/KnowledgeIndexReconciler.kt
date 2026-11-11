package io.zer0.muse.knowledge.index

import io.zer0.common.Logger
import io.zer0.muse.data.knowledge.IndexGenerationState
import io.zer0.muse.data.knowledge.KnowledgeJobDao

/**
 * R4 索引协调器 — 设计 §8.3「outbox 负责 ANN、缓存失效和非阻塞清理」的清理/修复实现。
 *
 * 职责(不阻塞查询路径):
 *  - 清理崩溃留下的 FAILED 代残留(chunks/lexical 行);
 *  - 回收超过保留期的 RETIRED 代;
 *  - 回收过期内容保留租约;
 *  - 修复悬挂活动指针(指向不存在/FAILED 代的 doc → 清指针,报告而不是让查询读到半代)。
 *
 * 不变式(由 KnowledgeIndexReconcilerTest 钉住):
 *  - **绝不清理 ACTIVE 代**;
 *  - 清理失败逐项记录,不空 catch;
 *  - 活动指针只清"指向坏代"的,健康活动代原样保留。
 */
class KnowledgeIndexReconciler(
    private val jobDao: KnowledgeJobDao,
    private val v2Dao: io.zer0.muse.data.knowledge.KnowledgeV2Dao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retiredRetentionMs: Long = 7L * 24 * 60 * 60 * 1000,
) {

    data class ReconcileReport(
        val cleanedFailedGenerations: Int,
        val retiredGenerations: Int,
        val expiredLeases: Int,
        val danglingPointersCleared: Int,
        val errors: List<String>,
    )

    suspend fun reconcile(): ReconcileReport {
        val errors = mutableListOf<String>()
        var cleanedFailed = 0
        var retired = 0
        var dangling = 0

        // 1. 过期租约回收。
        val expiredLeases = try {
            v2Dao.deleteExpiredLeases(clock())
        } catch (e: Exception) {
            errors.add("租约回收失败: ${e.message}")
            0
        }

        // 2. 清理 FAILED 代残留(chunks/lexical 行可能因崩溃残留)。
        // 注:DAO 没有按 state 列全部代的查询,按 doc 纬度遍历成本高;这里由调用方传入候选 doc 列表。
        // 当前实现以「清理指定文档的坏代」为核心 API,见 reconcileDoc。
        for (docId in candidateDocIds()) {
            val docReport = reconcileDoc(docId)
            cleanedFailed += docReport.cleanedFailedGenerations
            retired += docReport.retiredGenerations
            dangling += docReport.danglingPointersCleared
            errors.addAll(docReport.errors)
        }

        if (errors.isNotEmpty()) {
            Logger.w("KnowledgeIndexReconciler", "协调完成但有错误: ${errors.joinToString("; ")}")
        }
        return ReconcileReport(
            cleanedFailedGenerations = cleanedFailed,
            retiredGenerations = retired,
            expiredLeases = expiredLeases,
            danglingPointersCleared = dangling,
            errors = errors,
        )
    }

    /** 单文档协调:清理坏代、回收过期 RETIRED 代、修复悬挂指针。 */
    suspend fun reconcileDoc(docId: String): ReconcileReport {
        val errors = mutableListOf<String>()
        var cleanedFailed = 0
        var retired = 0
        var dangling = 0

        val latest = try {
            jobDao.getLatestGeneration(docId)
        } catch (e: Exception) {
            return ReconcileReport(0, 0, 0, 0, listOf("读取代失败: ${e.message}"))
        }
        val activePointer = try {
            jobDao.getActiveGeneration(docId)
        } catch (e: Exception) {
            return ReconcileReport(0, 0, 0, 0, listOf("读取活动指针失败: ${e.message}"))
        }

        // 悬挂指针:指针非空但该代不存在或不是 ACTIVE。
        if (!activePointer.isNullOrBlank()) {
            val pointerGen = try {
                jobDao.getGeneration(activePointer)
            } catch (e: Exception) {
                null.also { errors.add("读取活动代失败: ${e.message}") }
            }
            if (pointerGen == null || pointerGen.state != IndexGenerationState.ACTIVE.name) {
                try {
                    jobDao.clearActiveGeneration(docId)
                    dangling++
                } catch (e: Exception) {
                    errors.add("清理悬挂指针失败: ${e.message}")
                }
            }
        }

        // 清理最新代若为 FAILED(崩溃残留)。
        if (latest != null && latest.state == IndexGenerationState.FAILED.name) {
            try {
                jobDao.deleteGenChunks(latest.generationId)
                jobDao.deleteGenLexical(latest.generationId)
                cleanedFailed++
            } catch (e: Exception) {
                errors.add("清理 FAILED 代失败: ${e.message}")
            }
        }

        // 回收过期 RETIRED 代(仅最新代可见;完整历史清理归 R9 生命周期服务)。
        if (latest != null && latest.state == IndexGenerationState.RETIRED.name) {
            val age = clock() - latest.createdAt
            if (age >= retiredRetentionMs) {
                try {
                    jobDao.deleteGenChunks(latest.generationId)
                    jobDao.deleteGenLexical(latest.generationId)
                    retired++
                } catch (e: Exception) {
                    errors.add("回收 RETIRED 代失败: ${e.message}")
                }
            }
        }

        return ReconcileReport(cleanedFailed, retired, 0, dangling, errors)
    }

    /**
     * 候选文档:所有存在 FAILED/RETIRED 代的文档(按状态列 docId,不做全表文档扫描)。
     */
    private suspend fun candidateDocIds(): List<String> = try {
        jobDao.getDocIdsByGenerationStates(
            listOf(IndexGenerationState.FAILED.name, IndexGenerationState.RETIRED.name),
        )
    } catch (e: Exception) {
        Logger.w("KnowledgeIndexReconciler", "读取候选文档失败: ${e.message}", e)
        emptyList()
    }
}
