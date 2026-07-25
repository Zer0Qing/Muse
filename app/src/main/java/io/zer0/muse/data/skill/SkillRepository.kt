package io.zer0.muse.data.skill

import kotlinx.coroutines.flow.Flow

/**
 * Phase 8.8: Skill Repository。
 */
class SkillRepository(private val dao: SkillDao) {
    val observeAll: Flow<List<SkillEntity>> = dao.observeAll()

    suspend fun listEnabled(): List<SkillEntity> = dao.listEnabled()

    // L-SR2: getById 调用频率低(主要在工具执行路由时单次查询),暂不加缓存。
    // 若未来出现热路径(如循环调用),可考虑加 LRU 内存缓存。
    suspend fun getById(id: String): SkillEntity? = dao.getById(id)

    suspend fun upsert(entity: SkillEntity) = dao.upsert(entity)

    suspend fun update(entity: SkillEntity) = dao.update(entity)

    suspend fun delete(id: String) = dao.delete(id)

    /** 切换 Skill 启用状态(同时刷新 updatedAt)。 */
    suspend fun setEnabled(id: String, enabled: Boolean) =
        dao.setEnabled(id, enabled, System.currentTimeMillis())

    /**
     * 按 id 列表过滤启用的 Skills。
     * @param skillIds 启用的 skill id 列表;null 表示全部启用
     *
     * M-SR1: 改用 DAO 层 `WHERE id IN (:ids)` 查询,避免全量加载再内存 filter。
     */
    suspend fun listEnabledByIds(skillIds: List<String>?): List<SkillEntity> {
        if (skillIds.isNullOrEmpty()) return dao.listEnabled()
        return dao.listEnabledByIds(skillIds)
    }
}
