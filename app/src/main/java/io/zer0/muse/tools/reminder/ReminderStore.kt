package io.zer0.muse.tools.reminder

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.notification.MuseNotificationTarget
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * v1.136: 本地定时提醒存储。
 *
 * 使用 JSON 文件持久化提醒列表,配合 [ReminderAlarmReceiver] + AlarmManager 在指定时间触发通知。
 * 提醒在触发后自动从存储中移除。
 *
 * v2.6.x: 除了「用户设的提醒」本身，条目还记录了闹钟是否真的排上了、用的是不是精确闹钟、
 * 上次排期失败的原始原因。这样 [io.zer0.muse.tools.ReminderToolsRegistrar] 的 `list_reminders`
 * 才能如实告诉用户「这条闹钟没排上，可能不会响」，而不是不管闹钟在不在都当成「未触发」列出。
 */
class ReminderStore(private val context: Context) {

    private val file by lazy { java.io.File(context.filesDir, "reminders/reminders.json") }

    /** 读改写共享锁：开机重排与前台重排可能并发，避免把文件写坏。 */
    private val ioLock = Any()

    @Volatile
    private var lastError: String? = null

    /** 消费最近一次损坏提示(UI 展示后调用)。 */
    fun consumeError(): String? {
        val e = lastError
        lastError = null
        return e
    }

    /** 读取全部提醒。 */
    fun list(): List<ReminderEntry> {
        return readAll()
    }

    /** 根据 id 获取单个提醒。 */
    fun get(id: String): ReminderEntry? {
        return readAll().find { it.id == id }
    }

    /**
     * 添加一条提醒并持久化（此时尚未排期，`alarmScheduledAt == 0`）。
     *
     * @return 提醒 id
     */
    fun add(
        title: String,
        message: String,
        triggerAtMillis: Long,
        id: String? = null,
        targetType: String = ReminderAlarmReceiver.TARGET_HOME,
        targetId: String = "",
    ): String {
        val entry = ReminderEntry(
            id = id?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString(),
            title = title,
            message = message,
            triggerAtMillis = triggerAtMillis,
            createdAtMillis = System.currentTimeMillis(),
            targetType = targetType,
            targetId = targetId,
        )
        synchronized(ioLock) {
            val all = readAll().toMutableList().apply { add(entry) }
            writeAll(all)
        }
        return entry.id
    }

    /** 删除指定 id 的提醒。 */
    fun remove(id: String): Boolean = synchronized(ioLock) {
        val all = readAll().toMutableList()
        val removed = all.removeIf { it.id == id }
        if (removed) writeAll(all)
        removed
    }

    /** 记录一次成功排期（供 `list_reminders` 判定「已排期」）。 */
    fun markScheduled(id: String, exact: Boolean, atMillis: Long) = synchronized(ioLock) {
        val all = readAll().toMutableList()
        val idx = all.indexOfFirst { it.id == id }
        if (idx >= 0) {
            all[idx] = all[idx].copy(
                alarmScheduledAt = atMillis,
                scheduledExact = exact,
                scheduleError = null,
            )
            writeAll(all)
        }
    }

    /** 记录一次排期失败（供 `list_reminders` 判定「可能不会响」）。 */
    fun markScheduleFailed(id: String, error: String) = synchronized(ioLock) {
        val all = readAll().toMutableList()
        val idx = all.indexOfFirst { it.id == id }
        if (idx >= 0) {
            all[idx] = all[idx].copy(alarmScheduledAt = 0L, scheduleError = error)
            writeAll(all)
        }
    }

    /**
     * 重启/恢复后重排全部提醒（进程重启后 AlarmManager 里的闹钟会整体丢失）。
     *
     * 规则：
     *  - 触发时间在未来：重新排期（同一 PendingIntent，幂等，不会重复触发）。
     *  - 已经过期（本该响但被错过）：**补发一次通知**后从存储移除，绝不重复补第二次。
     *    补发通知复用提醒自身的通知 id，即使并发重排也只会互相覆盖为同一条，不会刷屏。
     *
     * 顺序是「先落盘移除、再发通知」，不是「先发通知、再落盘」。
     * 反过来写的话，进程若在两者之间死亡，下次重排会看到那条依旧过期的提醒，
     * 于是再补一遍 —— 用户收到两条一模一样的「(补)」通知，而这是设计上最不愿发生的事。
     * 换成现在这个顺序，最坏情况只是丢一条通知（进程恰好在落盘后、通知前死亡），
     * 而「少发一条」比「重复发」可接受。
     *
     * @return 本次重排的统计，供日志与调用方参考
     */
    fun rescheduleAll(now: Long = System.currentTimeMillis()): RescheduleSummary {
        // 开机广播与冷启动可能同时触发重排；用进程内互斥避免对同一批过期提醒重复补发。
        if (!rescheduleGuard.compareAndSet(false, true)) {
            Logger.i(TAG, "已有重排在执行，跳过本次")
            return RescheduleSummary(
                rescheduled = 0,
                catchUpNotified = 0,
                failed = 0,
                exact = ReminderAlarmScheduler.canScheduleExact(context),
            )
        }
        try {
            return rescheduleAllLocked(now)
        } finally {
            rescheduleGuard.set(false)
        }
    }

    private fun rescheduleAllLocked(now: Long): RescheduleSummary {
        val snapshot = synchronized(ioLock) { readAll() }
        if (snapshot.isEmpty()) {
            return RescheduleSummary(
                rescheduled = 0,
                catchUpNotified = 0,
                failed = 0,
                exact = ReminderAlarmScheduler.canScheduleExact(context),
            )
        }
        var rescheduled = 0
        var caughtUp = 0
        var failed = 0
        val remaining = mutableListOf<ReminderEntry>()
        val overdue = mutableListOf<ReminderEntry>()
        for (entry in snapshot) {
            if (entry.triggerAtMillis <= now) {
                // 已过期：只收集，不在这里发通知（不加入 remaining）。
                overdue.add(entry)
                caughtUp++
            } else {
                val res = ReminderAlarmScheduler.schedule(
                    context = context,
                    id = entry.id,
                    title = entry.title,
                    message = entry.message,
                    triggerAtMillis = entry.triggerAtMillis,
                    targetType = entry.targetType,
                    targetId = entry.targetId,
                )
                if (res.scheduled) {
                    rescheduled++
                    remaining.add(
                        entry.copy(alarmScheduledAt = now, scheduledExact = res.exact, scheduleError = null),
                    )
                } else {
                    failed++
                    remaining.add(entry.copy(alarmScheduledAt = 0L, scheduleError = res.error ?: "alarm_schedule_failed"))
                }
            }
        }
        // 先落盘：把过期条目从存储里去掉之后，才开始发通知。
        synchronized(ioLock) { writeAll(remaining) }
        overdue.forEach { catchUpNotify(it) }
        Logger.i(
            TAG,
            "提醒重排完成: 成功=$rescheduled, 补发=$caughtUp, 失败=$failed",
        )
        return RescheduleSummary(
            rescheduled = rescheduled,
            catchUpNotified = caughtUp,
            failed = failed,
            exact = ReminderAlarmScheduler.canScheduleExact(context),
        )
    }

    /**
     * 补发一条已错过的提醒：标题加「(补)」，发完即止。
     *
     * 调用方必须在调它之前就把条目从存储里移除（见 [rescheduleAllLocked] 的顺序说明），
     * 否则进程死亡会让同一条被补第二次。
     */
    private fun catchUpNotify(entry: ReminderEntry) {
        val title = context.getString(R.string.tool_reminder_catchup_title, entry.title)
        val target = if (
            entry.targetType == ReminderAlarmReceiver.TARGET_QUICK_NOTE && entry.targetId.isNotBlank()
        ) {
            MuseNotificationTarget.QuickNote(entry.targetId)
        } else {
            MuseNotificationTarget.Home
        }
        MuseNotificationManager(context).notifyReminder(
            title = title,
            message = entry.message,
            notificationId = entry.id.hashCode(),
            target = target,
        )
        Logger.i(TAG, "补发错过的提醒: id=${entry.id}, title=${entry.title}")
    }

    /** 内部读取全部提醒(文件不存在时返回空列表)。 */
    private fun readAll(): List<ReminderEntry> {
        return try {
            if (!file.exists()) return emptyList()
            AppJson.decodeFromString(ListSerializer(ReminderEntry.serializer()), file.readText())
        } catch (e: Exception) {
            backupCorruptFile()
            lastError = "提醒列表文件损坏，已备份原文件并重建"
            Logger.e(TAG, "读取提醒列表失败，已备份坏文件: ${e.message}", e)
            emptyList()
        }
    }

    private fun backupCorruptFile() {
        runCatching {
            if (file.exists()) {
                val backup = java.io.File(file.parentFile, "${file.name}.bak-${System.currentTimeMillis()}")
                file.copyTo(backup, overwrite = true)
                Logger.w(TAG, "损坏文件已备份到 ${backup.absolutePath}")
            }
        }.onFailure { Logger.w(TAG, "备份损坏文件失败: ${it.message}") }
    }

    /** 内部写入全部提醒(先写临时文件再改名，避免写到一半崩溃留下损坏文件)。 */
    private fun writeAll(list: List<ReminderEntry>) {
        try {
            file.parentFile?.mkdirs()
            val payload = AppJson.encodeToString(ListSerializer(ReminderEntry.serializer()), list)
            val tmp = java.io.File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(payload)
            if (!tmp.renameTo(file)) {
                // 部分文件系统 rename 到已存在目标会失败：退回覆盖写。
                file.writeText(payload)
                tmp.delete()
            }
        } catch (e: Exception) {
            Logger.w(TAG, "写入提醒列表失败: ${e.message}")
        }
    }

    /** 重排结果统计。 */
    data class RescheduleSummary(
        val rescheduled: Int,
        val catchUpNotified: Int,
        val failed: Int,
        val exact: Boolean,
    )

    companion object {
        private const val TAG = "ReminderStore"

        /** 跨实例的重排互斥（Receiver 与 MuseApp 各自 new 一个 Store，需共享此标记）。 */
        private val rescheduleGuard = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}

@Serializable
data class ReminderEntry(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    @SerialName("message") val message: String,
    @SerialName("trigger_at") val triggerAtMillis: Long,
    @SerialName("created_at") val createdAtMillis: Long,
    /** 通知点击后要跳转的目标类型（home / quick_note）。 */
    @SerialName("target_type") val targetType: String = ReminderAlarmReceiver.TARGET_HOME,
    /** 目标 id（快速记录时为笔记 id）。 */
    @SerialName("target_id") val targetId: String = "",
    /** 上次成功排期的时间戳；0 表示尚未排上（或排期失败）。 */
    @SerialName("alarm_scheduled_at") val alarmScheduledAt: Long = 0L,
    /** 上次排期用的是否为精确闹钟；false 表示已降级为非精确，可能延迟。 */
    @SerialName("scheduled_exact") val scheduledExact: Boolean = true,
    /** 上次排期失败的原始原因；null 表示没有失败记录。 */
    @SerialName("schedule_error") val scheduleError: String? = null,
) {
    /** 闹钟是否当前有效：已排期且未记录失败。 */
    val alarmLive: Boolean get() = alarmScheduledAt > 0L && scheduleError == null
}
