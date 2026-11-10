package io.zer0.muse.data.plugin

import android.content.Context
import io.zer0.common.Logger
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * 定时任务的**上次执行时间**（落盘）。
 *
 * 以前这份记录只在内存里（`HashMap`），后果有两个：
 *
 *  1. 进程被杀之后每个任务都"从未执行过"，下次启动会**一起立刻触发**；
 *  2. 应用长时间不在前台时，任务既不补跑、也无从判断"到底该不该跑"。
 *
 * 落盘之后，判定就有依据了：`now - lastRun` 是真实的，[PluginScheduleDue] 的
 * "过期补跑"才有意义 —— 这也就是 `schedule.background` 能成立的前提。
 *
 * 写入用临时文件 + 原子替换：这份文件很小，但**写坏等于所有任务的时间记录丢失**，
 * 那会让它们在下次启动时集体重跑。
 */
class PluginScheduleStore(private val file: File) {

    private val serializer = MapSerializer(String.serializer(), Long.serializer())

    /** 读全部记录；文件缺失或损坏都返回空表（不能因为读不到就让任务全部重跑）。 */
    @Synchronized
    fun load(): Map<String, Long> {
        if (!file.isFile) return emptyMap()
        return runCatching {
            io.zer0.common.AppJson.decodeFromString(serializer, file.readText())
        }.onFailure { Logger.w(TAG, "读取定时任务记录失败: ${it.message}") }
            .getOrDefault(emptyMap())
    }

    /** 记录一次执行。 */
    @Synchronized
    fun record(key: String, atMillis: Long) {
        val updated = load().toMutableMap().apply { put(key, atMillis) }
        save(updated)
    }

    /** 清掉某个任务（插件被卸载时用，避免键越积越多）。 */
    @Synchronized
    fun forget(key: String) {
        val current = load()
        if (key !in current) return
        save(current - key)
    }

    private fun save(values: Map<String, Long>) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(io.zer0.common.AppJson.encodeToString(serializer, values))
            if (!temp.renameTo(file)) {
                // 某些文件系统上 rename 会失败，退化为直接写
                file.writeText(temp.readText())
                temp.delete()
            }
        }.onFailure { Logger.w(TAG, "写入定时任务记录失败: ${it.message}") }
    }

    companion object {
        private const val TAG = "PluginScheduleStore"

        /** 记录文件名（应用私有目录）。 */
        const val FILE_NAME = "plugin_schedules.json"

        fun forContext(context: Context): PluginScheduleStore = PluginScheduleStore(File(context.applicationContext.filesDir, FILE_NAME))
    }
}
