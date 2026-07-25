package io.zer0.muse.data.experience

import kotlinx.coroutines.flow.Flow

/**
 * v1.98: 经验库仓库 — 提供 CRUD + observeAll。
 *
 * 注入到 SystemPromptAssembler(经验注入)、MemoryViewModel(UI 展示)、ChatViewModel(手动 CRUD)。
 */
class ExperienceRepository(
    private val dao: ExperienceDao,
) {
    fun observeAll(): Flow<List<ExperienceEntity>> = dao.observeAll()

    suspend fun getAll(): List<ExperienceEntity> = dao.getAll()

    suspend fun getById(id: String): ExperienceEntity? = dao.getById(id)

    suspend fun upsert(entity: ExperienceEntity) = dao.upsert(entity)

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun count(): Int = dao.count()
}
