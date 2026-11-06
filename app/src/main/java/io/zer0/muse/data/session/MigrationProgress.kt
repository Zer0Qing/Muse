package io.zer0.muse.data.session

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.zer0.common.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * v2.7.x(S3): 数据库迁移的可见性 —— 步骤、进度、上一次的结果。
 *
 * 要解决的问题:`MuseDb` 注册了 126 条迁移,升级时用户看到的是一段没有解释的启动等待。
 * 迁移在 Room 打开数据库时同步执行,前端不知道要跑几步、现在第几步、上一次是否有某一步失败。
 *
 * 三条设计决定:
 *  1. **只做旁路观察,不改执行语义**。包装层原样调用被包装的迁移,异常照原样抛出;
 *     观察自身出错只丢一条日志,绝不吃掉迁移异常 —— 迁移失败必须让 Room 知道。
 *  2. 进度走进程内总线,启动界面订阅。冷启动不引入新的持久化依赖;进程死了进度自然归零,
 *     而不是显示一个假的 8/12。
 *  3. 「上次迁移耗时与结果」落一份轻量 prefs 给诊断导出用。它是**结果**而不是承诺:
 *     读不到就整段缺席,不写一份 0 毫秒的空记录冒充「迁移很快」。
 */

/** 一次迁移运行的阶段。 */
enum class MigrationPhase {
    /** 本进程内没有迁移在跑(也没跑过)。 */
    IDLE,

    /** 正在按链逐步执行。 */
    RUNNING,

    /** 本次升级的每一步都跑完了。 */
    DONE,

    /** 某一步抛异常;后续处理由 Room 决定,这里只负责说实话。 */
    FAILED,
}

/** 启动界面要画的那一格。字段全是「第几步 / 共几步 / 版本号」,不含任何用户数据。 */
data class MigrationProgress(
    val phase: MigrationPhase = MigrationPhase.IDLE,
    val totalSteps: Int = 0,
    val completedSteps: Int = 0,
    val currentFromVersion: Int = 0,
    val currentToVersion: Int = 0,
) {
    /** 界面用它决定要不要出现:不需要迁移时一条横线都不该画。 */
    val visible: Boolean get() = phase == MigrationPhase.RUNNING || phase == MigrationPhase.FAILED
}

/**
 * 迁移进度总线(进程内单例)。
 *
 * 做成可变单例而不是注入对象:Room 的迁移是在 `writableDatabase` 里同步跑的,
 * 那一刻 DI 图还没建完。这里不写任何数据,发布的是「正在第几步」这一事实。
 */
object MigrationProgressBus {

    private val state = MutableStateFlow(MigrationProgress())

    val progress: StateFlow<MigrationProgress> = state.asStateFlow()

    internal fun stepStarted(index: Int, totalSteps: Int, from: Int, to: Int) {
        state.value = MigrationProgress(
            phase = MigrationPhase.RUNNING,
            totalSteps = totalSteps,
            completedSteps = index,
            currentFromVersion = from,
            currentToVersion = to,
        )
    }

    internal fun stepFinished(index: Int, totalSteps: Int) {
        val phase = if (index >= totalSteps) MigrationPhase.DONE else MigrationPhase.RUNNING
        state.value = state.value.copy(phase = phase, totalSteps = totalSteps, completedSteps = index)
    }

    internal fun failed(totalSteps: Int, index: Int, from: Int, to: Int) {
        state.value = MigrationProgress(
            phase = MigrationPhase.FAILED,
            totalSteps = totalSteps,
            completedSteps = index,
            currentFromVersion = from,
            currentToVersion = to,
        )
    }

    /** 仅供测试:恢复到进程刚启动的样子。 */
    internal fun reset() {
        state.value = MigrationProgress()
    }
}

/** 上一次迁移的结果摘要(诊断用)。`failedAtVersion` 为 null 表示那一次全部成功。 */
data class LastMigrationRun(
    val fromVersion: Int,
    val toVersion: Int,
    val steps: Int,
    val totalMillis: Long,
    val slowestStepMillis: Long,
    val slowestFromVersion: Int,
    val failedAtVersion: Int?,
)

/** 上一次迁移结果的读写;键名在这里收口,不在调用方各写一遍 `getSharedPreferences`。 */
object MigrationRunStore {

    private const val TAG = "MigrationRunStore"
    private const val PREFS = "muse_migration_run"

    fun record(context: Context, run: LastMigrationRun) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                putInt("from_version", run.fromVersion)
                putInt("to_version", run.toVersion)
                putInt("steps", run.steps)
                putLong("total_millis", run.totalMillis)
                putLong("slowest_millis", run.slowestStepMillis)
                putInt("slowest_from", run.slowestFromVersion)
                // 失败标记必须单独删除:留着上一次的失败标记,会让这一次的成功读起来像失败。
                if (run.failedAtVersion == null) remove("failed_at") else putInt("failed_at", run.failedAtVersion)
            }.apply()
        }.onFailure { Logger.w(TAG, "迁移结果落盘失败", it) }
    }

    /** 读不到返回 null:「没有记录」与「迁移用了 0 毫秒」在诊断里是两件事。 */
    fun read(context: Context): LastMigrationRun? = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains("total_millis")) return null
        LastMigrationRun(
            fromVersion = prefs.getInt("from_version", 0),
            toVersion = prefs.getInt("to_version", 0),
            steps = prefs.getInt("steps", 0),
            totalMillis = prefs.getLong("total_millis", 0L),
            slowestStepMillis = prefs.getLong("slowest_millis", 0L),
            slowestFromVersion = prefs.getInt("slowest_from", 0),
            failedAtVersion = if (prefs.contains("failed_at")) prefs.getInt("failed_at", 0) else null,
        )
    }.getOrNull()
}

/**
 * 给整条迁移链套上计时与进度发布。
 *
 * 总步数取**本次真要跑的步数**:Room 不会预告有几个版本要升,但第一步的 `startVersion`
 * 就是当前落库版本,链按升序执行,所以「startVersion ≥ 第一步起点」的条数就是本次的步数。
 * 早先用整条链的 126 当总数,用户看到的是「1/126」——那比不说更误导。
 *
 * [clock] 与 [onRecord] 注入是为了测试不必真的 sleep、也不必真落盘。
 */
class MigrationRegistry(
    private val all: List<Migration>,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val onRecord: (LastMigrationRun) -> Unit = {},
) {

    /** 注册入口用:返回直接交给 `addMigrations` 的数组,顺序不变。 */
    fun timed(): Array<Migration> = all.map { wrap(it) }.toTypedArray()

    private fun wrap(migration: Migration): Migration = object : Migration(migration.startVersion, migration.endVersion) {
        // 这里必须接住任何异常:迁移失败的原因五花八门(SQLite 错误、约束、列不存在),
        // 漏接一种就会让「失败」在诊断里消失。接住之后原样 rethrow,执行语义不变。
        @Suppress("TooGenericExceptionCaught")
        override fun migrate(db: SupportSQLiteDatabase) {
            val index = steps
            val total = totalStepsFor(migration.startVersion)
            fromVersion = if (index == 0) migration.startVersion else fromVersion
            MigrationProgressBus.stepStarted(index, total, migration.startVersion, migration.endVersion)
            val started = clock()
            try {
                migration.migrate(db)
            } catch (error: Exception) {
                val millis = clock() - started
                observe(migration, millis)
                markFailure(migration.endVersion, total, index, migration.startVersion)
                throw error
            }
            observe(migration, clock() - started)
            MigrationProgressBus.stepFinished(steps, total)
            // Room 跑完最后一步不会再回调,所以「本轮成功」的结果只能在这里发布;
            // 漏掉这一句的话,诊断卡上永远只有失败过的迁移记录。
            if (steps >= total) publish()
        }
    }

    /**
     * 本次要跑的步数。
     *
     * 只在第一步算一次并冻结:每一步都按自己的 `startVersion` 重算的话,
     * 分母会从 3 缩到 2 再缩到 1 —— 于是「3/1」这种不可能的进度会出现,
     * 还会让汇总提前发布一次(实测被两条测试同时抓出)。
     */
    private fun totalStepsFor(currentVersion: Int): Int {
        frozenTotal?.let { return it }
        val computed = all.count { it.startVersion >= currentVersion }.coerceAtLeast(1)
        frozenTotal = computed
        return computed
    }

    private fun markFailure(to: Int, total: Int, index: Int, from: Int) {
        failedAt = to
        MigrationProgressBus.failed(total, index, from, to)
        publish()
    }

    /**
     * 单步观察结果的记账。**观察本身出错不许影响迁移**:任何异常只记日志,
     * 因为一次「让升级更快暴露」的改动如果反过来吃掉迁移异常,代价是数据损坏被静默吞掉。
     */
    private fun observe(migration: Migration, millis: Long) {
        runCatching {
            steps += 1
            totalMillis += millis
            if (millis > slowestMillis) {
                slowestMillis = millis
                slowestFrom = migration.startVersion
            }
            toVersion = migration.endVersion
        }.onFailure { Logger.w(TAG, "迁移记账失败(不影响迁移本身)", it) }
    }

    private fun publish() = runCatching { onRecord(summary()) }.onFailure { Logger.w(TAG, "迁移结果发布失败", it) }

    /** 目前为止的一轮汇总。Room 跑完最后一步后不会再回调,所以在最后一步与失败处发布。 */
    fun summary(): LastMigrationRun = LastMigrationRun(
        fromVersion = fromVersion,
        toVersion = toVersion,
        steps = steps,
        totalMillis = totalMillis,
        slowestStepMillis = slowestMillis,
        slowestFromVersion = slowestFrom,
        failedAtVersion = failedAt,
    )

    private companion object {
        private const val TAG = "MigrationRegistry"
    }

    // 记账状态:只在 Room 的迁移线程上被单线程调用(同一条链由同一个 OpenHelper 串行执行)。
    private var steps = 0
    private var totalMillis = 0L
    private var slowestMillis = 0L
    private var slowestFrom = 0
    private var fromVersion = 0
    private var toVersion = 0
    private var failedAt: Int? = null
    private var frozenTotal: Int? = null
}
