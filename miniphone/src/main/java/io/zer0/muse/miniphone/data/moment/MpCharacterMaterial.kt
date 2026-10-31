package io.zer0.muse.miniphone.data.moment

import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.dao.MpMessageDao
import io.zer0.muse.miniphone.data.db.dao.MpMomentDao
import io.zer0.muse.miniphone.data.db.dao.MpThreadDao
import io.zer0.muse.miniphone.data.db.entity.MpMessageEntity
import java.time.Instant
import java.time.ZoneId

/**
 * 角色自发内容（朋友圈动态 / 主动私信）共用的「最近发生了什么」。
 *
 * 两种内容的上下文完全一样 —— 最近和用户聊了什么、现在是几点；差别只有两处：
 * 动态会多带一条「上一条我发的动态」当参照，收尾指令的措辞也不同（「这次可以写」/「此刻的由头」）。
 * 所以拼装只有一份：顺序固定为「上下文 → 时间 → 收尾指令」，收尾指令永远在最后一句。
 *
 * 素材本身在模块内挑选，宿主只负责把它变成角色的一段话 —— 它知道会话在哪张表、
 * 动态按什么顺序排，宿主不必知道这些。
 *
 * 三段素材都是**可选**的：没有会话、没发过动态、库读不出来，都只是少一段，
 * 不会让这一轮什么都发不出去。
 *
 * 时间、时区由调用方交进来（默认系统值），于是「周二晚上九点该说什么」可以在
 * 纯测试里钉死。
 *
 * @param now 时间源，默认系统时钟
 * @param zone 判断「现在几点」用的时区
 */
class MpCharacterMaterial(
    private val threadDao: MpThreadDao,
    private val messageDao: MpMessageDao,
    private val momentDao: MpMomentDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    /**
     * 一条朋友圈动态的素材。
     *
     * @param characterId 要发动态的角色
     * @param focus 这一轮的由头（时段提示之类）；为空时以时间收尾
     */
    suspend fun forMoment(characterId: String, focus: String? = null): String = compose(characterId, focus, Kind.MOMENT)

    /**
     * 一条主动私信的素材：与动态同一份上下文，但不带「上一条我发的动态」——
     * 私信不是朋友圈的续集，拿动态当参照只会让私信跑题。
     *
     * @param characterId 要开口的角色
     * @param focus 这一轮的由头（时段提示之类）；为空时以时间收尾
     */
    suspend fun forProactiveChat(characterId: String, focus: String? = null): String = compose(characterId, focus, Kind.CHAT)

    /** 两类内容的差别只有两处：要不要带上一条动态、收尾指令叫什么。 */
    private enum class Kind(val focusLabel: String, val includesLastMoment: Boolean) {
        MOMENT(FOCUS_LABEL_MOMENT, includesLastMoment = true),
        CHAT(FOCUS_LABEL_CHAT, includesLastMoment = false),
    }

    /** 唯一的拼装处：上下文 → 时间 → 收尾指令，顺序与两类共用。 */
    private suspend fun compose(characterId: String, focus: String?, kind: Kind): String = buildString {
        appendLine("最近和用户的对话（旧→新）：")
        val turns = recentTurns(characterId)
        if (turns.isEmpty()) {
            appendLine("还没有和用户聊过。")
        } else {
            turns.forEach { turn -> appendLine(turn) }
        }
        if (kind.includesLastMoment) {
            lastMoment(characterId)?.let { own ->
                appendLine("上一条我发的动态：$own")
                appendLine("别和上一条重复同样的话题。")
            }
        }
        appendLine(timeLine())
        focus?.trim()?.takeIf { it.isNotBlank() }?.let { brief -> appendLine("${kind.focusLabel}$brief") }
    }.trim()

    /**
     * 最近几条对话，按时间正序，每条形如 `用户：…` / `我：…`。
     *
     * 系统提示（例如「对方没有回应」）不算对话，取完再滤掉；正文截到
     * [MAX_TURN_CHARS] 字，只留能看出在聊什么的那一截。
     */
    private suspend fun recentTurns(characterId: String): List<String> {
        val threadId = resultOf { threadDao.findThreadByCharacter(characterId) }
            .onError { msg, t -> Logger.w(TAG, "读取私信会话失败: ${t?.message ?: msg}") }
            .getOrNull()
            ?.id
            ?: return emptyList()
        val newestFirst = resultOf { messageDao.pageMessages(threadId, RECENT_TURN_LIMIT, 0) }
            .onError { msg, t -> Logger.w(TAG, "读取私信记录失败: ${t?.message ?: msg}") }
            .getOrNull()
            .orEmpty()
        return newestFirst.asReversed()
            .filter { message -> message.sender != MpActorType.SYSTEM && message.content.isNotBlank() }
            .map { message -> "${speakerOf(message)}：${message.content.trim().take(MAX_TURN_CHARS)}" }
    }

    /** 上一条自己发的动态，截到 [MAX_LAST_MOMENT_CHARS] 字；没发过就是 null。 */
    private suspend fun lastMoment(characterId: String): String? = resultOf { momentDao.lastMomentByAuthor(characterId) }
        .onError { msg, t -> Logger.w(TAG, "读取上一条动态失败: ${t?.message ?: msg}") }
        .getOrNull()
        ?.content
        ?.trim()
        ?.take(MAX_LAST_MOMENT_CHARS)
        ?.takeIf { it.isNotBlank() }

    /** 「现在是周二晚上九点（夜）。」：星期 + 时段说法 + 精确时间，最后附判定用的时段名。 */
    private fun timeLine(): String {
        val at = Instant.ofEpochMilli(now()).atZone(zone)
        val hour = at.hour
        val hourWord = HOUR_WORDS[if (hour % 12 == 0) 12 else hour % 12]
        // 「两点」不是「二点」:钟点上的 2 读作两
        val spokenHour = if (hourWord == "二") "两" else hourWord
        return "现在是${WEEKDAY_WORDS[at.dayOfWeek.value - 1]}${clockPrefixOf(hour)}$spokenHour" +
            "点（${phaseOf(hour)}）。"
    }

    private fun speakerOf(message: MpMessageEntity): String = if (message.sender == MpActorType.USER) "用户" else "我"

    /** 给时钟用的说法：读起来像人话（「晚上九点」而不是「21 点」）。 */
    private fun clockPrefixOf(hour: Int): String = when (hour) {
        in 5..7 -> "清晨"
        in 8..11 -> "上午"
        12, 13 -> "中午"
        in 14..17 -> "下午"
        in 18..19 -> "傍晚"
        in 20..22 -> "晚上"
        else -> "深夜"
    }

    /** 判定用的时段名：早 / 上午 / 午间 / 下午 / 傍晚 / 夜 / 深夜。 */
    private fun phaseOf(hour: Int): String = when (hour) {
        in 5..7 -> "早"
        in 8..10 -> "上午"
        in 11..13 -> "午间"
        in 14..17 -> "下午"
        in 18..19 -> "傍晚"
        in 20..22 -> "夜"
        else -> "深夜"
    }

    private companion object {
        const val TAG: String = "MiniPhoneMaterial"

        /** 收尾指令的措辞：朋友圈说「这次可以写」，私信说「此刻的由头」。 */
        const val FOCUS_LABEL_MOMENT: String = "这次可以写："
        const val FOCUS_LABEL_CHAT: String = "此刻的由头："

        /** 最近几条对话：再多也写不进一条 30-80 字的动态。 */
        const val RECENT_TURN_LIMIT: Int = 8

        /** 单条对话的截断长度。 */
        const val MAX_TURN_CHARS: Int = 100

        /** 上一条动态的截断长度：够看出话题就行。 */
        const val MAX_LAST_MOMENT_CHARS: Int = 60

        val WEEKDAY_WORDS: List<String> = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

        /** 1..12 的中文说法（下标 0 占位）。 */
        val HOUR_WORDS: List<String> = listOf(
            "零", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十", "十一", "十二",
        )
    }
}
