package io.zer0.muse.data.knowledge

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * R8 目录与绑定 — 设计 §6.2 与 §13「目录使用稳定 ID，路径是投影不是绑定身份」。
 *
 * 与旧 `knowledge_folders`(path 派生主键)的关系:V2 表用**稳定 id + parentId + name**,
 * 重命名/移动不改变身份,助手/会话绑定不会因显示路径变化失效。旧表保持原样供旧读路径。
 *
 * 表:
 *  - knowledge_v2_folders:稳定 id、kbId、parentId(空串=根)、name;同父同名唯一;
 *  - knowledge_v2_bindings:助手/会话主体 → 明确目标(KB/FOLDER/DOC),「恰好一种目标」由唯一约束保。
 */
@Entity(
    tableName = "knowledge_v2_folders",
    indices = [
        Index(value = ["kbId"]),
        Index(value = ["kbId", "parentId", "name"], unique = true),
    ],
)
data class KnowledgeV2FolderEntity(
    @PrimaryKey val folderId: String,
    val kbId: String,
    /** 空串 = 库根。 */
    @ColumnInfo(defaultValue = "") val parentId: String = "",
    val name: String,
    @ColumnInfo(name = "created_at", defaultValue = "0") val createdAt: Long = 0L,
    @ColumnInfo(name = "updated_at", defaultValue = "0") val updatedAt: Long = 0L,
)

@Entity(
    tableName = "knowledge_v2_bindings",
    indices = [
        Index(value = ["ownerType", "ownerId"]),
        Index(value = ["ownerType", "ownerId", "targetType", "targetId"], unique = true),
    ],
)
data class KnowledgeV2BindingEntity(
    @PrimaryKey val bindingId: String,
    /** Assistant / Session。 */
    val ownerType: String,
    val ownerId: String,
    /** KB / FOLDER / DOC —— 恰好一种目标由唯一约束保证。 */
    val targetType: String,
    val targetId: String,
    @ColumnInfo(name = "dataset_epoch", defaultValue = "0") val datasetEpoch: Long = 0L,
    @ColumnInfo(name = "created_at", defaultValue = "0") val createdAt: Long = 0L,
)

@Dao
interface KnowledgeFolderV2Dao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFolder(folder: KnowledgeV2FolderEntity)

    @Query("SELECT * FROM knowledge_v2_folders WHERE folderId = :folderId")
    suspend fun getFolder(folderId: String): KnowledgeV2FolderEntity?

    @Query("SELECT * FROM knowledge_v2_folders WHERE kbId = :kbId ORDER BY parentId, name")
    suspend fun getByKb(kbId: String): List<KnowledgeV2FolderEntity>

    /** R9 便携备份:全量目录。 */
    @Query("SELECT * FROM knowledge_v2_folders ORDER BY kbId, parentId, name")
    suspend fun getAllFolders(): List<KnowledgeV2FolderEntity>

    @Query("SELECT * FROM knowledge_v2_folders WHERE kbId = :kbId AND parentId = :parentId ORDER BY name")
    suspend fun getChildren(kbId: String, parentId: String): List<KnowledgeV2FolderEntity>

    @Query("SELECT * FROM knowledge_v2_folders WHERE kbId = :kbId AND parentId = :parentId AND name = :name")
    suspend fun getByNameInParent(kbId: String, parentId: String, name: String): KnowledgeV2FolderEntity?

    @Query("SELECT COUNT(*) FROM knowledge_v2_folders WHERE kbId = :kbId AND parentId = :parentId")
    suspend fun countChildren(kbId: String, parentId: String): Int

    @Query("UPDATE knowledge_v2_folders SET name = :name, updated_at = :updatedAt WHERE folderId = :folderId")
    suspend fun updateName(folderId: String, name: String, updatedAt: Long)

    @Query("UPDATE knowledge_v2_folders SET parentId = :parentId, updated_at = :updatedAt WHERE folderId = :folderId")
    suspend fun updateParent(folderId: String, parentId: String, updatedAt: Long)

    @Query("DELETE FROM knowledge_v2_folders WHERE folderId = :folderId")
    suspend fun deleteFolder(folderId: String)

    // ---------- bindings ----------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBinding(binding: KnowledgeV2BindingEntity)

    @Query("SELECT * FROM knowledge_v2_bindings WHERE ownerType = :ownerType AND ownerId = :ownerId")
    suspend fun getBindings(ownerType: String, ownerId: String): List<KnowledgeV2BindingEntity>

    /** R9 便携备份:全量绑定。 */
    @Query("SELECT * FROM knowledge_v2_bindings")
    suspend fun getAllBindings(): List<KnowledgeV2BindingEntity>

    @Query("DELETE FROM knowledge_v2_bindings WHERE ownerType = :ownerType AND ownerId = :ownerId")
    suspend fun deleteBindings(ownerType: String, ownerId: String)

    @Query("DELETE FROM knowledge_v2_bindings WHERE targetType = :targetType AND targetId = :targetId")
    suspend fun deleteBindingsByTarget(targetType: String, targetId: String): Int

    // ---------- R9 备份恢复:清空 ----------

    @Query("DELETE FROM knowledge_v2_folders")
    suspend fun deleteAllFolders(): Int

    @Query("DELETE FROM knowledge_v2_bindings")
    suspend fun deleteAllBindings(): Int
}
