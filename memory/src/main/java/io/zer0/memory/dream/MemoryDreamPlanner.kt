package io.zer0.memory.dream

/**
 * Dream 计划生成器。
 *
 * 放在接口后面而不是直接调模型，是为了让「记忆整理」这件事能被单测钉住：
 * 校验、指纹、写门这些安全边界全在 [MemoryDreamPlanValidator] 与 service 里，
 * 生成器只负责「把当前内容变成一份候选计划」。
 *
 * 实现方允许调用现有模型链路，但**不得**直接写库 —— 落库只发生在 service 的
 * Room 事务里，且必须经过校验与 fingerprint 比对。
 */
interface MemoryDreamPlanner {
    /**
     * 基于当前 sections 生成一份候选计划。
     *
     * @param current 当前 compiled sections（只读快照）
     * @return 候选计划；实现方失败时应抛异常或返回空计划，由 service 决定如何记录
     */
    suspend fun plan(current: Map<String, String>): MemoryDreamPlan
}
