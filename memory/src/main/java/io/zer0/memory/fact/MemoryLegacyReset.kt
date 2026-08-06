package io.zer0.memory.fact

import android.content.Context

/**
 * R-DB-03: 记录早期 facts 数据库被归档重建的事件,供 app 层在记忆页给出可见提示。
 */
object MemoryLegacyReset {

    private const val PREFS_NAME = "muse_memory_legacy_reset"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 标记指定数据库发生归档重建。 */
    fun mark(context: Context, dbName: String) {
        prefs(context).edit().putBoolean(dbName, true).apply()
    }

    /** 消费一次提示;返回是否有待提示的归档重建,并清空标记。 */
    fun consume(context: Context): Boolean {
        val p = prefs(context)
        val hasPending = p.all.values.any { it == true }
        if (hasPending) p.edit().clear().apply()
        return hasPending
    }
}
