package io.zer0.memory.reflection

import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.fact.FactStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v12 (T3-1/3-2/3-3): 记忆反思任务 — 记忆系统的主动整理层。
 *
 * 触发: 每日 pipeline 末尾(deepMemory 之后)低频执行;失败不阻塞主流程。
 * 职责(确定性规则版,LLM 增强留后续):
 *  1. 回填历史实体键(entity_key 为 null 的存量数据)
 *  2. 合并同实体键重复事实(解决"同一用户名 3 条"的存量清洗)
 *  3. 检测同实体矛盾断言 → 生成待确认清单(不自动删除)
 *  4. 重复确认晋升(同实体多条事实 → 重要度提升)
 *
 * 定位: 与 [FactStore] 写入时查重(快路径)互补 — 写入时拦不住的历史重复,
 * 由反思任务在后台定期整理,形成\"写入防增量 + 反思清存量\"的双层闭环。
 */
class MemoryReflectionRunner(
    private val factStore: FactStore,
) {

    /**
     * 执行一轮完整反思。
     *
     * @param scope 记忆作用域(默认 main)
     * @param spaceId 记忆空间(默认 default)
     * @return 本轮整理统计
     */
    suspend fun runReflection(
        scope: String = "main",
        spaceId: String = "default",
    ): ReflectionResult = withContext(Dispatchers.IO) {
        // 1. 回填实体键(合并的前提)
        val backfilled = resultOf { factStore.backfillEntityKeys(scope, spaceId) }
            .onError { msg, t -> Logger.w(TAG, "反思回填实体键失败: ${t?.message ?: msg}") }
            .getOrNull() ?: 0

        // 2. 合并同实体重复
        val merged = resultOf { factStore.mergeSameEntityDuplicates(scope, spaceId) }
            .onError { msg, t -> Logger.w(TAG, "反思合并重复失败: ${t?.message ?: msg}") }
            .getOrNull() ?: 0

        // 3. 矛盾检测(只记录,不自动删除 — 用户确认后再处理)
        val contradictions = resultOf { factStore.detectContradictions(scope, spaceId) }
            .onError { msg, t -> Logger.w(TAG, "反思矛盾检测失败: ${t?.message ?: msg}") }
            .getOrNull() ?: emptyList()

        // 4. 重复确认晋升(同实体 ≥2 条 → 重要度 +1)
        val promoted = resultOf { factStore.promoteRepeatedFacts(scope, spaceId) }
            .onError { msg, t -> Logger.w(TAG, "反思晋升失败: ${t?.message ?: msg}") }
            .getOrNull() ?: 0

        val result = ReflectionResult(
            backfilled = backfilled,
            merged = merged,
            contradictions = contradictions.size,
            promoted = promoted,
        )
        if (result.hasWork) {
            Logger.i(TAG, "记忆反思完成: 回填实体键=$backfilled, 合并重复=$merged, 矛盾对=$contradictions.size, 晋升=$promoted")
        }
        result
    }

    data class ReflectionResult(
        val backfilled: Int = 0,
        val merged: Int = 0,
        val contradictions: Int = 0,
        val promoted: Int = 0,
    ) {
        val hasWork: Boolean get() = backfilled + merged + contradictions + promoted > 0
    }

    private companion object {
        const val TAG = "MemoryReflection"
    }
}
