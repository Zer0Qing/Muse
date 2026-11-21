package io.zer0.muse.data.agentbot

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Agent 中枢的任务账本：用户交代的一件事。
 *
 * ## 为什么任务要单独成表，而不是复用 sessions
 *
 * 会话是"一段对话"，任务是"一件要办的事"：一个任务可能跨多轮对话被追加约束、
 * 被暂停再续，也可能在用户切到别的对话之后才完成。把任务塞进会话表会让
 * "切会话"与"切任务"两件事绑死，而中枢的核心承诺恰恰是**做事时还能继续聊天**。
 *
 * ## 状态为什么是字符串而不是枚举列
 *
 * Room 没有原生枚举列；用字符串 + 仓储里的穷尽转移函数，可以让非法转移
 * （例如 CANCELLED 之后再变 SUCCEEDED）在编译期就被 when 覆盖检查拦住。
 * 直接写任意字符串是本项目明令禁止的写法。
 */
@Serializable
@Entity(
    tableName = "bot_tasks",
    indices = [
        Index(value = ["botId", "status"]),
        Index(value = ["originSessionId"]),
        Index(value = ["updatedAt"]),
    ],
)
data class BotTaskEntity(
    @PrimaryKey val taskId: String,
    /** 哪个 Bot 接的单。一个 Bot 一个协调者，任务不跨 Bot 迁移。 */
    @ColumnInfo(defaultValue = "") val botId: String = "",
    /** 发起任务的那段对话。结果回流只认它，不认"用户现在正在看哪个会话"。 */
    @ColumnInfo(defaultValue = "") val originSessionId: String = "",
    /** 触发这条任务的用户消息 id（用于把任务挂回具体那一句）。 */
    @ColumnInfo(defaultValue = "") val requestMessageId: String = "",
    /** 目标。存业务库、不进日志。 */
    @ColumnInfo(defaultValue = "") val goal: String = "",
    /** 验收标准，审核时逐条核对。 */
    @ColumnInfo(defaultValue = "") val acceptanceCriteria: String = "",
    /** 可改动的资源范围（路径 / 设备面 / 浏览器），空表示只读任务。 */
    @ColumnInfo(defaultValue = "") val resourceScope: String = "",
    /** 权限范围标识；空表示沿用会话默认。 */
    @ColumnInfo(defaultValue = "") val permissionScopeId: String = "",
    /** 约束版本，从 1 起递增。用户追加约束时 +1，旧版本的迟到结果不得发布。 */
    @ColumnInfo(defaultValue = "1") val revision: Int = 1,
    /** 见 [BotTaskStatus]。 */
    @ColumnInfo(defaultValue = "QUEUED") val status: String = BotTaskStatus.QUEUED,
    /** 越大越先跑。前台接单的任务给高值。 */
    @ColumnInfo(defaultValue = "0") val priority: Int = 0,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0L,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0L,
)

/**
 * 任务状态的完整取值。
 *
 * 用常量而不是 enum：Room 存字符串，enum 需要 TypeConverter，而转换器一旦
 * 漏注册就会在运行期抛异常。这里的取值集合由 [BotTaskStatus.isTerminal] 与
 * 仓储的穷尽 when 共同守住。
 */
object BotTaskStatus {
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val WAITING_RESOURCE = "WAITING_RESOURCE"
    const val WAITING_APPROVAL = "WAITING_APPROVAL"
    const val REVIEWING = "REVIEWING"
    const val CANCEL_REQUESTED = "CANCEL_REQUESTED"
    const val SUCCEEDED = "SUCCEEDED"
    const val PARTIAL = "PARTIAL"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
    const val NEEDS_USER = "NEEDS_USER"
    const val UNKNOWN = "UNKNOWN"

    /**
     * 终态：任何事件都不再改写。
     *
     * ## NEEDS_USER 为什么不在里面
     *
     * 它一度被列进来，但那是错的 —— 它有两个出口：
     *  - `USER_CONFIRMED_CONTINUE` → QUEUED（用户确认重跑）
     *  - `CANCEL_REQUEST` → CANCEL_REQUESTED
     *
     * 把它当终态会让"用户点继续"这个动作永远不生效，而界面上那个按钮还在。
     * 它是**阻塞态**（[isBlocked]），不是终态。
     */
    val TERMINAL: Set<String> = setOf(SUCCEEDED, PARTIAL, FAILED, CANCELLED, UNKNOWN)

    /**
     * 全部状态。
     *
     * ## 为什么手写而不是反射派生
     *
     * 试过反射读 `declaredFields` 自动派生，回退了。原因：
     *
     * `const val String` 是**编译期内联**的，R8 会把字段本身裁掉。
     * 本项目 release 开了 `isMinifyEnabled = true`，且没有针对本 object 的
     * keep 规则 —— 那时 `declaredFields` 返回空，`ALL` 变成空列表，
     * 而所有"ALL 与常量一致"的测试会**全部通过**（空和空一致）。
     *
     * 手写清单会漂移，但漂移能被源码扫描的测试抓住；反射在 release 下
     * 静默失效，抓不住。**能抓住的缺陷比抓不住的更可接受。**
     */
    val ALL: List<String> = listOf(
        QUEUED, RUNNING, WAITING_RESOURCE, WAITING_APPROVAL, REVIEWING,
        CANCEL_REQUESTED, SUCCEEDED, PARTIAL, FAILED, CANCELLED, NEEDS_USER, UNKNOWN,
    )

    /** 非终态。等价于 [ALL] 减去 [TERMINAL]，单独列出便于状态机与测试引用。 */
    val NON_TERMINAL: List<String> = ALL - TERMINAL
    fun isTerminal(status: String): Boolean = status in TERMINAL
}