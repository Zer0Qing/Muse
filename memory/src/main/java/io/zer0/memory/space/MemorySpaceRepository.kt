package io.zer0.memory.space

import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

/**
 * v1.0.52 P2-2: 记忆空间仓库 — Space 的 CRUD + 事实迁移。
 *
 * 职责:
 *  - 列出/创建/重命名/删除 Space
 *  - 删除 Space 时将其中事实迁移到默认 Space(避免数据丢失)
 *  - 调整 Space 排序
 *
 * 与 [io.zer0.memory.fact.FactStore] 的关系:
 *  - FactStore 按 spaceId 隔离 facts 表
 *  - 本仓库管理 Space 元数据本身
 *  - 删除 Space 时通过 [MemorySpaceDao.migrateFacts] 把事实迁回 default
 *
 * 默认 Space("default") 不可删除,保证用户始终有一个可写入的空间。
 */
class MemorySpaceRepository(
    private val spaceDao: MemorySpaceDao,
) {

    /**
     * 观察 Space 列表(含事实数量,Flow 形式)。
     * UI 通过此 Flow 实时刷新 Space 列表与统计。
     */
    fun observeSpacesWithCount(): Flow<List<MemorySpaceWithCount>> =
        spaceDao.observeAllWithCount()

    /**
     * 观察 Space 列表(不含事实数量,轻量)。
     * 用于切换器下拉,避免每次切换都做 LEFT JOIN COUNT。
     */
    fun observeSpaces(): Flow<List<MemorySpaceEntity>> =
        spaceDao.observeAll()

    /** 列出所有 Space(含事实数量)。 */
    suspend fun listSpacesWithCount(): List<MemorySpaceWithCount> = withContext(Dispatchers.IO) {
        spaceDao.listAllWithCount()
    }

    /** 列出所有 Space(轻量,不含事实数量)。 */
    suspend fun listSpaces(): List<MemorySpaceEntity> = withContext(Dispatchers.IO) {
        spaceDao.listAll()
    }

    /** 按 id 查询 Space。 */
    suspend fun getSpace(id: String): MemorySpaceEntity? = withContext(Dispatchers.IO) {
        spaceDao.getById(id)
    }

    /**
     * 创建新 Space。
     *
     * @param name 显示名称(必填,trim 后非空)
     * @param icon 图标标识(可选)
     * @param description 描述(可选)
     * @return 新建 Space 的 id(失败返回 null)
     */
    suspend fun createSpace(
        name: String,
        icon: String? = null,
        description: String = "",
    ): String? = withContext(Dispatchers.IO) {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            Logger.w("MemorySpaceRepository", "createSpace: name is empty")
            return@withContext null
        }
        val id = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        val maxSortIndex = spaceDao.listAll().maxOfOrNull { it.sortIndex } ?: 0
        val space = MemorySpaceEntity(
            id = id,
            name = trimmedName,
            icon = icon,
            description = description.trim(),
            createdAt = now,
            sortIndex = maxSortIndex + 1,
        )
        spaceDao.upsert(space)
        Logger.i("MemorySpaceRepository", "Created space: id=$id name=$trimmedName")
        id
    }

    /**
     * 重命名 Space。
     * @return 是否成功(name 非空且 Space 存在)
     */
    suspend fun renameSpace(id: String, name: String): Boolean = withContext(Dispatchers.IO) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@withContext false
        spaceDao.rename(id, trimmed) > 0
    }

    /**
     * 更新 Space 描述。
     */
    suspend fun updateDescription(id: String, description: String): Boolean = withContext(Dispatchers.IO) {
        val existing = spaceDao.getById(id) ?: return@withContext false
        spaceDao.upsert(existing.copy(description = description.trim())) > 0
    }

    /**
     * 更新 Space 图标。
     */
    suspend fun updateIcon(id: String, icon: String?): Boolean = withContext(Dispatchers.IO) {
        val existing = spaceDao.getById(id) ?: return@withContext false
        spaceDao.upsert(existing.copy(icon = icon)) > 0
    }

    /**
     * 删除 Space。
     *
     * 行为:
     *  1. 默认 Space("default") 不可删除,返回 false
     *  2. 删除前将 Space 内所有事实迁移到默认 Space,避免数据丢失
     *  3. 删除 Space 元数据
     *
     * @return 是否删除成功(默认 Space / 不存在的 Space 返回 false)
     */
    suspend fun deleteSpace(id: String): Boolean = withContext(Dispatchers.IO) {
        if (id == MemorySpaceEntity.DEFAULT_SPACE_ID) {
            Logger.w("MemorySpaceRepository", "Cannot delete default space")
            return@withContext false
        }
        val existing = spaceDao.getById(id) ?: return@withContext false
        // 迁移事实到默认 Space
        val migrated = spaceDao.migrateFacts(id, MemorySpaceEntity.DEFAULT_SPACE_ID)
        if (migrated > 0) {
            Logger.i("MemorySpaceRepository", "Migrated $migrated facts from $id → default")
        }
        spaceDao.deleteById(id)
        Logger.i("MemorySpaceRepository", "Deleted space: id=$id name=${existing.name}")
        true
    }

    /**
     * 调整 Space 排序。
     * @param orderedIds 按新顺序排列的 Space id 列表
     */
    suspend fun reorderSpaces(orderedIds: List<String>): Boolean = withContext(Dispatchers.IO) {
        if (orderedIds.isEmpty()) return@withContext false
        orderedIds.forEachIndexed { index, id ->
            spaceDao.updateSortIndex(id, index)
        }
        true
    }

    /**
     * 统计指定 Space 的事实数量。
     */
    suspend fun countFactsInSpace(spaceId: String): Int = withContext(Dispatchers.IO) {
        spaceDao.countFactsInSpace(spaceId)
    }

    /**
     * 确保默认 Space 存在。
     * 应用启动时调用,防止数据库迁移异常导致默认 Space 缺失。
     */
    suspend fun ensureDefaultSpaceExists() = withContext(Dispatchers.IO) {
        val existing = spaceDao.getById(MemorySpaceEntity.DEFAULT_SPACE_ID)
        if (existing == null) {
            val now = Instant.now().toString()
            spaceDao.upsert(
                MemorySpaceEntity(
                    id = MemorySpaceEntity.DEFAULT_SPACE_ID,
                    name = MemorySpaceEntity.DEFAULT_SPACE_NAME,
                    icon = MemorySpaceEntity.DEFAULT_SPACE_ICON,
                    description = "",
                    createdAt = now,
                    sortIndex = 0,
                )
            )
            Logger.i("MemorySpaceRepository", "Default space was missing, recreated")
        }
    }
}
