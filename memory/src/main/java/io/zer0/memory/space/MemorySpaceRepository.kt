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
    /**
     * v2.6.2（记忆审计）: 知识图谱边 DAO。
     *
     * 删除 Space 时事实迁回 default，图谱边也必须同步迁移，否则边成为孤儿
     * （指向 default 中的事实，却标记在已删除的 Space 下）。
     */
    private val memoryLinkDao: io.zer0.memory.ai.MemoryLinkDao? = null,
) {

    /**
     * 观察 Space 列表(含事实数量,Flow 形式)。
     * UI 通过此 Flow 实时刷新 Space 列表与统计。
     */
    fun observeSpacesWithCount(): Flow<List<MemorySpaceWithCount>> = spaceDao.observeAllWithCount()

    /**
     * 观察 Space 列表(不含事实数量,轻量)。
     * 用于切换器下拉,避免每次切换都做 LEFT JOIN COUNT。
     */
    fun observeSpaces(): Flow<List<MemorySpaceEntity>> = spaceDao.observeAll()

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
    suspend fun createSpace(name: String, icon: String? = null, description: String = ""): String? = withContext(Dispatchers.IO) {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            Logger.w("MemorySpaceRepository", "createSpace: name is empty")
            return@withContext null
        }
        // 同名拒绝：列表、切换器与「助手 · 空间」那行元数据都**只显示名字**，
        // 两个都叫「工作」的空间用户分不出来，只能靠逐个改名去猜哪个是哪个。
        if (findByName(trimmedName, excludeId = null) != null) {
            Logger.w("MemorySpaceRepository", "createSpace: name already used: $trimmedName")
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
        // 改成一个已经存在的名字，等于把两个空间变成同一个标签：拒绝，而不是让用户去猜。
        if (findByName(trimmed, excludeId = id) != null) {
            Logger.w("MemorySpaceRepository", "renameSpace: name already used: $trimmed")
            return@withContext false
        }
        spaceDao.rename(id, trimmed) > 0
    }

    /**
     * 更新 Space 描述。
     */
    /** 按显示名找空间（去首尾空白、忽略大小写）；[excludeId] 用于改名时放过自己。 */
    private suspend fun findByName(name: String, excludeId: String?): MemorySpaceEntity? =
        spaceDao.listAll().firstOrNull { space ->
            space.id != excludeId && space.name.trim().equals(name, ignoreCase = true)
        }

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
        // v2.6.2（记忆审计）: 同步迁移图谱边，否则边留在已删除 Space 成为孤儿。
        val migratedLinks = memoryLinkDao?.migrateBySpaceId(id, MemorySpaceEntity.DEFAULT_SPACE_ID) ?: 0
        if (migratedLinks > 0) {
            Logger.i("MemorySpaceRepository", "Migrated $migratedLinks links from $id → default")
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
        val existing = spaceDao.listAll().associateBy({ it.id }, { it })
        var index = 0
        orderedIds.forEach { id ->
            // 传进来的 id 可能已经不存在（别的窗口刚删掉）。写它没有意义，
            // 也会把序号白占一格，让后面的空间跟着错位。
            if (id in existing) {
                spaceDao.updateSortIndex(id, index)
                index += 1
            }
        }
        // 没被点到的空间不能留在旧序号上：那会和新写的撞车，
        // 而列表是按 `sort_index, created_at` 排的 —— 撞车时顺序取决于建得早晚，
        // 用户看到的就是"我拖好了，一刷新又弹回原位"。
        existing.values
            .filterNot { space -> orderedIds.contains(space.id) }
            .sortedBy { space -> space.sortIndex }
            .forEach { space ->
                spaceDao.updateSortIndex(space.id, index)
                index += 1
            }
        index > 0
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
                ),
            )
            Logger.i("MemorySpaceRepository", "Default space was missing, recreated")
        }
    }
}
