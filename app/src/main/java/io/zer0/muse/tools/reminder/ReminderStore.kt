package io.zer0.muse.tools.reminder

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * v1.136: 本地定时提醒存储。
 *
 * 使用 JSON 文件持久化提醒列表,配合 [ReminderAlarmReceiver] + AlarmManager 在指定时间触发通知。
 * 提醒在触发后自动从存储中移除。
 */
class ReminderStore(private val context: Context) {

    private val file by lazy { java.io.File(context.filesDir, "reminders/reminders.json") }

    /** 读取全部提醒。 */
    fun list(): List<ReminderEntry> {
        return readAll()
    }

    /** 根据 id 获取单个提醒。 */
    fun get(id: String): ReminderEntry? {
        return readAll().find { it.id == id }
    }

    /**
     * 添加一条提醒并持久化。
     *
     * @return 提醒 id
     */
    fun add(title: String, message: String, triggerAtMillis: Long): String {
        val entry = ReminderEntry(
            id = java.util.UUID.randomUUID().toString(),
            title = title,
            message = message,
            triggerAtMillis = triggerAtMillis,
            createdAtMillis = System.currentTimeMillis(),
        )
        val all = readAll().toMutableList().apply { add(entry) }
        writeAll(all)
        return entry.id
    }

    /** 删除指定 id 的提醒。 */
    fun remove(id: String): Boolean {
        val all = readAll().toMutableList()
        val removed = all.removeIf { it.id == id }
        if (removed) writeAll(all)
        return removed
    }

    /** 内部读取全部提醒(文件不存在时返回空列表)。 */
    private fun readAll(): List<ReminderEntry> {
        return try {
            if (!file.exists()) return emptyList()
            AppJson.decodeFromString(ListSerializer(ReminderEntry.serializer()), file.readText())
        } catch (e: Exception) {
            Logger.w(TAG, "读取提醒列表失败: ${e.message}")
            emptyList()
        }
    }

    /** 内部写入全部提醒。 */
    private fun writeAll(list: List<ReminderEntry>) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(AppJson.encodeToString(ListSerializer(ReminderEntry.serializer()), list))
        } catch (e: Exception) {
            Logger.w(TAG, "写入提醒列表失败: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ReminderStore"
    }
}

@Serializable
data class ReminderEntry(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    @SerialName("message") val message: String,
    @SerialName("trigger_at") val triggerAtMillis: Long,
    @SerialName("created_at") val createdAtMillis: Long,
)
