package io.zer0.muse.data.card

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 卡片实体（v2.6.8 / MuseDb 118）。
 *
 * 为什么要有这张表：卡片此前是「消息文本尾巴里的一段 JSON + 进程内 card_data.json」，
 * 一旦卡片离开消息流（被拖到侧边停靠、或被系统悬浮窗渲染），就没有任何地方存放它的内容与状态，
 * 也没有跨重启稳定的身份（`c_xxx` 是时间戳 + 进程内自增，重启可撞）。本表把卡片升为
 * 一等实体：UUID 主键、内容（code/dataJson）与停靠状态（dockSlot/dockedAt）都落在这里，
 * 消息侧只保留 `cardId` 引用，供占位条与拖回定位。
 *
 * 与消息的关系（不要在这里加外键）：
 * - 卡片可以从消息流脱离独立存在，消息被删也不该连带删卡；
 * - [originMessageId] / [originSessionId] 只是溯源信息，可空。
 *
 * 停靠语义：
 * - [dockSlot] 为 NULL = 仅在消息流内联渲染，不参与停靠区；
 * - [dockSlot] >= 0 = 停靠顺序（0 起），唯一索引保证同一槽位不会有两张卡。
 *   SQLite 的唯一索引把 NULL 视为互不相等，因此「多张卡都未停靠」不会互相冲突。
 * - 拖回只是把 [dockSlot] 置回 NULL，不删行（见设计规格 §3.2）。
 */
@Entity(
    tableName = "card_entities",
    indices = [
        Index(value = ["dockSlot"], unique = true, name = "index_card_entities_dockSlot"),
        Index(value = ["originMessageId"], name = "index_card_entities_originMessageId"),
    ],
)
data class CardEntity(
    /** UUID，跨重启稳定的卡片身份。 */
    @PrimaryKey val id: String,
    /**
     * 旧 `c_xxx` 卡片 id，仅供存量数据回填匹配。
     *
     * 读取侧保留「按旧 id 也能找到卡」的能力，写入侧不再生成这种 id。
     */
    @ColumnInfo(defaultValue = "NULL") val legacyCardId: String? = null,
    /** 来源会话 id。卡片脱离消息流后仍可追溯它属于哪次对话。 */
    @ColumnInfo(defaultValue = "NULL") val originSessionId: String? = null,
    /** 来源消息 id。用于消息流占位条与拖回定位。 */
    @ColumnInfo(defaultValue = "NULL") val originMessageId: String? = null,
    /** 卡片标题。 */
    @ColumnInfo(defaultValue = "") val title: String = "",
    /** HTML / SVG 源。 */
    @ColumnInfo(defaultValue = "") val code: String = "",
    /** 渲染分支：true = SVG，false = HTML。 */
    @ColumnInfo(defaultValue = "0") val isSvg: Boolean = false,
    /** 绑定数据（原 card_data.json 里的 JSON 文本）。 */
    @ColumnInfo(defaultValue = "NULL") val dataJson: String? = null,
    /** NULL = 仅在消息流；>= 0 = 停靠顺序。 */
    @ColumnInfo(defaultValue = "NULL") val dockSlot: Int? = null,
    /** 停靠时间，用于排序兜底（dockSlot 相同或历史数据缺槽位时）。 */
    @ColumnInfo(defaultValue = "NULL") val dockedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0L,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0L,
)

/**
 * 卡片实体 DAO。
 *
 * 只做存取，不做业务判断：槽位怎么分配、拖出/拖回何时发生、回填是否成功，
 * 都由上层仓储决定。这里的方法一律返回原始行或受影响行数。
 */
@Dao
interface CardEntityDao {

    @Query("SELECT * FROM card_entities WHERE id = :id")
    suspend fun getById(id: String): CardEntity?

    /**
     * 按来源消息取卡。
     *
     * 返回列表而非单条：一条消息可以承载多张卡（卡片组），一张卡一行。
     * 需要「该消息的卡片占位」时由调用方按 dockSlot / createdAt 自行挑选。
     */
    @Query("SELECT * FROM card_entities WHERE originMessageId = :originMessageId ORDER BY createdAt ASC")
    suspend fun getByOriginMessageId(originMessageId: String): List<CardEntity>

    /** 按旧 `c_xxx` id 读（存量数据兼容）。 */
    @Query("SELECT * FROM card_entities WHERE legacyCardId = :legacyCardId LIMIT 1")
    suspend fun getByLegacyCardId(legacyCardId: String): CardEntity?

    /** 全部停靠卡，按槽位升序（停靠区 Pager 的顺序来源）。 */
    @Query("SELECT * FROM card_entities WHERE dockSlot IS NOT NULL ORDER BY dockSlot ASC")
    suspend fun getDockedCards(): List<CardEntity>

    /** 同上，供停靠区订阅。 */
    @Query("SELECT * FROM card_entities WHERE dockSlot IS NOT NULL ORDER BY dockSlot ASC")
    fun observeDockedCards(): Flow<List<CardEntity>>

    @Upsert
    suspend fun upsert(card: CardEntity)

    /** 写槽位（拖出）。调用方负责给出槽位号与时间戳。 */
    @Query("UPDATE card_entities SET dockSlot = :dockSlot, dockedAt = :dockedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateDockSlot(id: String, dockSlot: Int, dockedAt: Long, updatedAt: Long): Int

    /** 清槽位（拖回）。卡片行保留，只取消停靠。 */
    @Query("UPDATE card_entities SET dockSlot = NULL, dockedAt = NULL, updatedAt = :updatedAt WHERE id = :id")
    suspend fun clearDockSlot(id: String, updatedAt: Long): Int
}
