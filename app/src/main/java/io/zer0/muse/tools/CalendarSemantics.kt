package io.zer0.muse.tools

import android.provider.CalendarContract
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * 日历的**语义层**：三件容易错、错了又很难被发现的事。
 *
 *  1. **重复日程要查 Instances**：`Events` 表里一条重复日程只有一行（首次的 DTSTART），
 *     查它会漏掉今天、明天以及之后的每一次 —— 用户看到的是"我的站会今天不见了"。
 *  2. **全天事件是 UTC 午夜**：CalendarContract 要求全天事件的 DTSTART/DTEND 落在 UTC 零点，
 *     DTEND 不含当日。用本地毫秒写入，东半球看着没事，西半球会整体前移一天。
 *  3. **写哪个日历必须明确**：用户有多个可写日历（工作/个人/家庭）时，
 *     静默写进"第一个"等于把内容放进了错误的日程表 —— 这比报错难查得多。
 */
object CalendarSemantics {

    /** 实例查询列。带 EVENT_ID 是为了后续能按对象更新/删除。 */
    val INSTANCE_PROJECTION: Array<String> = arrayOf(
        CalendarContract.Instances.TITLE,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END,
        CalendarContract.Instances.EVENT_LOCATION,
        CalendarContract.Instances.EVENT_ID,
        CalendarContract.Instances.ALL_DAY,
    )

    /**
     * 实例查询的 URI：把展开窗口编进路径（`instances/when/<begin>/<end>`）。
     *
     * 窗口必须显式给出，CalendarContract 不接受"无窗口"的实例查询。
     */
    fun instancesUri(startMs: Long, endMsExclusive: Long) = CalendarContract.Instances.CONTENT_URI.buildUpon()
        .appendPath(startMs.toString())
        .appendPath(endMsExclusive.toString())
        .build()

    /**
     * 全天事件的 UTC 归一化。
     *
     * @param startMs 用户意图中的开始（任意时刻，取其所在日期）
     * @param endMsExclusive 用户意图中的结束（不含），同样取其所在日期
     * @return (DTSTART, DTEND)，两者都是 UTC 零点，且 DTEND = 结束日 + 1 天（不含）
     */
    fun normalizeAllDay(startMs: Long, endMsExclusive: Long, zone: TimeZone = TimeZone.getDefault()): Pair<Long, Long> {
        val zoneId = zone.toZoneId()
        val startDate = LocalDate.ofInstant(Instant.ofEpochMilli(startMs), zoneId)
        // 结束时间按"最后一毫秒所属的那一天"算，避免 23:59:59.999 被判成次日
        val endDate = LocalDate.ofInstant(Instant.ofEpochMilli(endMsExclusive - 1), zoneId).plusDays(1)
        val startUtc = startDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val endUtc = endDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return startUtc to endUtc
    }

    /** 重复日程的作用域：整个系列，还是只这一次。 */
    enum class RecurrenceScope {
        ALL,
        THIS,
        ;

        companion object {
            fun parse(raw: String?): RecurrenceScope? = when (raw?.trim()?.lowercase()) {
                null, "", "all", "series" -> ALL
                "this", "instance", "single" -> THIS
                else -> null
            }
        }
    }

    /** 更新/删除被拒绝的原因。文案由调用方翻译，这里只给代号（保持纯函数可测）。 */
    enum class MutationRejection {
        INVALID_ID,
        NOTHING_TO_UPDATE,
        THIS_SCOPE_NEEDS_OCCURRENCE_TIME,
        THIS_SCOPE_UPDATE_UNSUPPORTED,
    }

    /**
     * 更新前的校验。
     *
     * `scope=THIS` 目前**只支持取消某一次**（走删除），不支持"只改这一次"：
     * 修改单个实例要写例外行并重算系列，做不对会把整个重复日程弄乱 ——
     * 与其半吊子支持，不如明确拒绝并给出可行替代。
     */
    fun checkUpdate(eventId: Long?, scope: RecurrenceScope?, hasAnyField: Boolean, occurrenceTime: Long?): MutationRejection? = when {
        eventId == null || eventId <= 0L -> MutationRejection.INVALID_ID
        !hasAnyField -> MutationRejection.NOTHING_TO_UPDATE
        scope == RecurrenceScope.THIS && occurrenceTime == null -> MutationRejection.THIS_SCOPE_NEEDS_OCCURRENCE_TIME
        scope == RecurrenceScope.THIS -> MutationRejection.THIS_SCOPE_UPDATE_UNSUPPORTED
        else -> null
    }

    /** 删除前的校验：单次取消必须给出该次的开始时间。 */
    fun checkDelete(eventId: Long?, scope: RecurrenceScope?, occurrenceTime: Long?): MutationRejection? = when {
        eventId == null || eventId <= 0L -> MutationRejection.INVALID_ID
        scope == RecurrenceScope.THIS && occurrenceTime == null -> MutationRejection.THIS_SCOPE_NEEDS_OCCURRENCE_TIME
        else -> null
    }

    /** 目标日历的选择结果。 */
    sealed interface CalendarChoice {
        data class Chosen(val id: Long, val displayName: String) : CalendarChoice

        /** 有多个可写日历而调用方没指定：**不能替用户猜**。 */
        data class Ambiguous(val candidates: List<Pair<Long, String>>) : CalendarChoice

        data class UnknownId(val id: Long) : CalendarChoice

        data object None : CalendarChoice
    }

    /**
     * 决定写进哪个日历。
     *
     * 规则很直白：指定了就必须存在；没指定且只有一个就选它；
     * 有多个就返回 [CalendarChoice.Ambiguous] 让调用方去问用户，而不是随便挑一个。
     */
    fun chooseCalendar(candidates: List<Pair<Long, String>>, requested: Long?): CalendarChoice = when {
        requested != null -> candidates.firstOrNull { it.first == requested }
            ?.let { CalendarChoice.Chosen(it.first, it.second) }
            ?: CalendarChoice.UnknownId(requested)
        candidates.isEmpty() -> CalendarChoice.None
        candidates.size == 1 -> CalendarChoice.Chosen(candidates[0].first, candidates[0].second)
        else -> CalendarChoice.Ambiguous(candidates)
    }
}
