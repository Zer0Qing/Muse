package io.zer0.muse.miniphone.data.misc

import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.entity.MpHolidayEventEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * 节日与纪念日门面：记录日子、按 dateKey 查当天。
 *
 * 关键区别是「每年重复」与「只发生一次」：
 * 每年重复用 `MM-dd` 作 dateKey、year=0；只发生一次用 `yyyy-MM-dd` 作 dateKey、year=那一年。
 * dateKey 必须与 year 自洽（重复的必须是月日、一次的必须是完整日期且年份为正），
 * 不自洽就当作脏数据拒绝，绝不让一个「只发生一次」的日子伪装成每年重复。
 * 空白名字不落库。
 */
internal class MpHolidayStore(
    private val db: MiniPhoneDb,
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    fun observeAll(): Flow<List<MpHolidayEventEntity>> = db.holidayEventDao().observeAll()

    fun observeByDate(dateKey: String): Flow<List<MpHolidayEventEntity>> = db.holidayEventDao().observeByDate(dateKey)

    /** 某一天的节日 = 每年重复的（按月日命中）+ 只发生一次的（按完整日期命中），合并成一个列表。 */
    fun observeForDay(monthDay: String, fullDate: String): Flow<List<MpHolidayEventEntity>> = combine(
        db.holidayEventDao().observeByDate(monthDay),
        db.holidayEventDao().observeByDate(fullDate),
    ) { recurring, once -> recurring + once }

    /**
     * 记一个节日：[year]=0 走每年重复（dateKey 须为 `MM-dd`），
     * 否则走只发生一次（dateKey 须为 `yyyy-MM-dd`）。名字空白或格式与 year 不自洽都不落库。
     */
    suspend fun add(dateKey: String, name: String, note: String = "", year: Int = 0): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val pattern = if (year == YEAR_RECURRING) RECURRING_KEY else ONCE_KEY
        if (!pattern.matches(dateKey)) return null
        val id = newId()
        db.holidayEventDao().insert(
            MpHolidayEventEntity(id = id, dateKey = dateKey, name = trimmed, note = note.trim(), year = year),
        )
        return id
    }

    suspend fun delete(id: String) {
        db.holidayEventDao().delete(id)
    }

    companion object {
        /** year 为该值表示「每年重复」，与建库时约定的 0 默认值一致。 */
        const val YEAR_RECURRING: Int = 0

        /** 是否每年重复：0 为重复，非 0 为那一年的具体日子。 */
        fun isRecurring(event: MpHolidayEventEntity): Boolean = event.year == YEAR_RECURRING

        private val RECURRING_KEY = Regex("""\d{2}-\d{2}""")
        private val ONCE_KEY = Regex("""\d{4}-\d{2}-\d{2}""")
    }
}
