package io.zer0.muse.data.relationship

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 角色与小手机主人的关系状态（P1-1）。
 *
 * 为什么单独建表而不往 `assistants` 加列：
 * `assistants` 已经有 50+ 列，而且那些列是"角色配置"（人设、模型、工具、绑定）。
 * 关系是**运行时演化的状态**（亲密度会变、最近互动时间会变），
 * 塞进配置表会让备份、导入导出、配置对比都变脏。
 *
 * 用途：
 *  - 主动私信概率从固定 40% 改为「基础概率 × 亲密度系数 × 意愿档位」
 *  - 朋友圈互动挑人从随机改为按亲密度加权
 *  - 系统提示注入一行关系描述，让角色知道"你们现在是什么关系"
 *
 * 设计取舍：
 *  - 亲密度 [affinity] 用 0-100 整数，不用浮点：避免累加误差，也便于迁移
 *  - [lastInteractionAt] 记录最近一次真实互动，用于"多久没聊了"这类判断
 *  - [proactiveLevel] 用字符串而非枚举序号，将来加档位不需要迁移
 */
@kotlinx.serialization.Serializable
@Entity(tableName = "assistant_relationship")
data class AssistantRelationshipEntity(
    /** 与 `assistants.id` 对应。 */
    @PrimaryKey val assistantId: String,
    /** 关系标签，自由文本（女朋友 / 同事 / 陌生人…）。空串表示未设置。 */
    @ColumnInfo(defaultValue = "") val relationLabel: String = "",
    /** 亲密度 0-100。0 = 完全陌生，100 = 极亲近。 */
    @ColumnInfo(defaultValue = "0") val affinity: Int = 0,
    /** 最近一次真实互动时间（毫秒）。0 = 从未互动。 */
    @ColumnInfo(defaultValue = "0") val lastInteractionAt: Long = 0,
    /** 主动私信意愿档位，见 [ProactiveLevel]。 */
    @ColumnInfo(defaultValue = "normal") val proactiveLevel: String = ProactiveLevel.NORMAL,
    /** 是否允许这个角色主动私信。默认允许，用户可单独关掉。 */
    @ColumnInfo(defaultValue = "1") val proactiveEnabled: Boolean = true,
    /** 每日主动私信上限，0 = 不限。 */
    @ColumnInfo(defaultValue = "0") val dailyProactiveLimit: Int = 0,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
) {
    /** 亲密度对应的私信概率系数：0 → 0.3，50 → 1.0，100 → 1.7。 */
    val affinityFactor: Float
        get() = 0.3f + affinity.coerceIn(0, 100) / 100f * 1.4f

    /** 意愿档位对应的系数。 */
    val proactiveFactor: Float
        get() = ProactiveLevel.factorOf(proactiveLevel)
}

/** 主动私信意愿档位。字符串常量，新增档位不需要数据库迁移。 */
object ProactiveLevel {
    /** 很少主动：几乎不找你。 */
    const val LOW = "low"

    /** 正常：默认档。 */
    const val NORMAL = "normal"

    /** 黏人：明显更常主动。 */
    const val HIGH = "high"

    val ALL = listOf(LOW, NORMAL, HIGH)

    /** 档位 → 概率系数。 */
    fun factorOf(level: String): Float = when (level) {
        LOW -> 0.4f
        HIGH -> 1.8f
        else -> 1.0f
    }
}

@Dao
interface AssistantRelationshipDao {

    @Query("SELECT * FROM assistant_relationship WHERE assistantId = :assistantId")
    suspend fun get(assistantId: String): AssistantRelationshipEntity?

    @Query("SELECT * FROM assistant_relationship")
    suspend fun getAll(): List<AssistantRelationshipEntity>

    @Query("SELECT * FROM assistant_relationship")
    fun observeAll(): Flow<List<AssistantRelationshipEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AssistantRelationshipEntity)

    @Query("DELETE FROM assistant_relationship WHERE assistantId = :assistantId")
    suspend fun delete(assistantId: String)

    /** v2.6.7: 恢复备份前清空（与其它表同口径）。 */
    @Query("DELETE FROM assistant_relationship")
    suspend fun deleteAll()

    /**
     * 累加亲密度（带上下限）。
     *
     * 用 SQL 做增量而不是先读后写，避免并发场景下丢更新；
     * 同时把 [lastInteractionAt] 一并更新，调用方只需传一个时间戳。
     */
    @Query(
        """
        UPDATE assistant_relationship
        SET affinity = MIN(100, MAX(0, affinity + :delta)),
            lastInteractionAt = :now,
            updatedAt = :now
        WHERE assistantId = :assistantId
        """,
    )
    suspend fun bumpAffinity(assistantId: String, delta: Int, now: Long): Int

    /** 只更新最近互动时间（不改变亲密度）。 */
    @Query(
        """
        UPDATE assistant_relationship
        SET lastInteractionAt = :now, updatedAt = :now
        WHERE assistantId = :assistantId
        """,
    )
    suspend fun touchLastInteraction(assistantId: String, now: Long): Int
}
