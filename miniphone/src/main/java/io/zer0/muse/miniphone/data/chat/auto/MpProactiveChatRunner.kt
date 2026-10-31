package io.zer0.muse.miniphone.data.chat.auto

import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageType
import io.zer0.muse.miniphone.data.db.dao.MpMessageDao
import io.zer0.muse.miniphone.data.db.dao.MpThreadDao
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import io.zer0.muse.miniphone.data.moment.MpCharacterMaterial
import io.zer0.muse.miniphone.data.settings.MpMomentAutoStore
import io.zer0.muse.miniphone.ui.chat.ChatMessageWriter
import io.zer0.muse.miniphone.ui.chat.ChatOutgoingMessage
import kotlinx.coroutines.flow.first
import java.time.ZoneId
import java.util.UUID
import kotlin.random.Random

/** 一轮主动私信的结果:发出去了、跳过了、还是失败了。日志里能看到的判断,这里也能断言。 */
sealed interface MpProactiveChatOutcome {
    /** 消息落库成功,并且该会话未读 +1。 */
    data class Sent(val threadId: String, val characterId: String) : MpProactiveChatOutcome

    /** 判定为不发(或生成为空),原因见 [reason]。 */
    data class Skipped(val reason: MpProactiveChatSkipReason) : MpProactiveChatOutcome

    /** 读设置 / 落库这类硬失败;[reason] 是写进日志的取值。 */
    data class Failed(val reason: String) : MpProactiveChatOutcome
}

/**
 * 「让角色主动发一条私信」的一轮完整流程。
 *
 * 顺序与 [MpProactiveChatPlan.decide] 一致:先选出会话、取到「用户最后说过话的时间」,再过八道判定,
 * 判完才挑角色、调宿主生成、落库、记时间。
 * 失败**不重试** —— 主动消息不该因为一次失败反复打扰,下个周期自然会再来。
 *
 * 「最近活跃的会话」= 有消息的会话里最后一条最新的那个:角色接的是真实聊过的那条线,
 * 不会凭空开一个新的。用户刚在那条会话里说过话(10 分钟内)时整轮不发 —— 插话就是打断。
 *
 * 时间、时区、随机源、主键都由调用方交进来(默认取系统值),于是这套流程能在测试里完整跑一遍。
 *
 * 开口的素材由 [material] 拼:最近聊过什么、现在几点,都不在本类里查 ——
 * 角色说什么得像接着上文说,而不是凭空冒一句。
 *
 * @param now 时间源,默认系统时钟
 * @param random 概率骰子,一次一轮,测试可钉死
 * @param zone 判定「现在几点」用的时区
 * @param newId 消息主键生成器,默认 UUID
 */
class MpProactiveChatRunner(
    private val host: MiniPhoneHost,
    private val threadDao: MpThreadDao,
    private val messageDao: MpMessageDao,
    private val store: MpMomentAutoStore,
    private val material: MpCharacterMaterial,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: () -> Float = { Random.nextFloat() },
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    /** 跑一轮。可失败但**不抛异常**:所有异常都归一成 [MpProactiveChatOutcome]。 */
    suspend fun run(): MpProactiveChatOutcome {
        val at = now()
        val enabled = resultOf { store.isChatProactiveEnabled() }
            .onError { msg, t -> fail("settings_unreadable", t, msg) }
            .getOrNull()
            ?: return MpProactiveChatOutcome.Failed(FAIL_SETTINGS)
        val lastProactiveAt = resultOf { store.lastProactiveChatAt() }
            .onError { msg, t -> fail("settings_unreadable", t, msg) }
            .getOrNull()
        val characters = resultOf { host.observeCharacters().first() }
            .onError { msg, t -> fail("characters_unreadable", t, msg) }
            .getOrNull()
            .orEmpty()
        val thread = latestActiveThread()
        val lastUserMessageAt = thread?.let { lastUserMessageAt(it.id) }
        val decision = MpProactiveChatPlan.decide(
            nowMillis = at,
            enabled = enabled,
            lastUserMessageAtMillis = lastUserMessageAt,
            characterCount = characters.size,
            hasEligibleSession = thread != null,
            lastProactiveAtMillis = lastProactiveAt,
            random = random,
            zone = zone,
        )
        return when (decision) {
            is MpProactiveChatDecision.Skip -> skip(decision.reason)
            MpProactiveChatDecision.Send -> send(characters, thread, at)
        }
    }

    /** 判定为不发:留一条能看出原因的日志,然后结束。 */
    private fun skip(reason: MpProactiveChatSkipReason): MpProactiveChatOutcome {
        Logger.d(TAG, "主动私信跳过 | reason=${reason.value}")
        return MpProactiveChatOutcome.Skipped(reason)
    }

    /** 挑角色 → 让宿主生成 → 落库 → 未读 +1 → 记下这次的时间。任一步不成立就收手。 */
    private suspend fun send(characters: List<MpCharacter>, thread: MpThreadEntity?, at: Long): MpProactiveChatOutcome {
        val target = thread ?: return skip(MpProactiveChatSkipReason.NO_HISTORY)
        // 会话里的角色可能已经在宿主侧被删掉:那就没有能开口的人
        val character = characters.firstOrNull { it.id == target.characterId }
        // 空白输出不落库:落一条空消息比不发更糟,而且会白白吃掉 4 小时的间隔
        val content = if (character == null) null else compose(character, at)
        return when {
            character == null -> skip(MpProactiveChatSkipReason.NO_CHARACTER)
            content.isNullOrBlank() -> {
                Logger.w(TAG, "主动私信跳过 | reason=${MpProactiveChatSkipReason.NO_CONTENT.value}")
                MpProactiveChatOutcome.Skipped(MpProactiveChatSkipReason.NO_CONTENT)
            }

            !write(target, content) -> MpProactiveChatOutcome.Failed(FAIL_WRITE)
            else -> sent(target, content, at)
        }
    }

    /** 生成一句:素材带最近聊过什么与现在几点,收尾一句是此刻的由头;失败返回 null。 */
    private suspend fun compose(character: MpCharacter, at: Long): String? {
        val hint = resultOf { material.forProactiveChat(character.id, focusFor(at)) }
            .onError { msg, t -> fail("material_error", t, msg) }
            .getOrNull()
        return resultOf { host.composeProactiveMessage(character.id, hint) }
            .onError { msg, t -> fail("compose_error", t, msg) }
            .getOrNull()
    }

    /** 落库 + 未读 +1。两步要么都成,要么当成这一轮失败(下一轮会重新生成)。 */
    private suspend fun write(thread: MpThreadEntity, content: String): Boolean {
        val appended = resultOf {
            ChatMessageWriter(
                threadId = thread.id,
                messageDao = messageDao,
                threadDao = threadDao,
                now = now,
                newId = newId,
                // 后台没有界面挂着:界面自己从 Room 的 Flow 收到这条消息,不需要回调
                onStored = {},
            ).append(
                ChatOutgoingMessage(
                    threadId = thread.id,
                    characterId = thread.characterId,
                    sender = MpActorType.CHARACTER,
                    type = MpMessageType.TEXT,
                    content = content,
                ),
            )
            threadDao.increaseUnread(thread.id)
        }.onError { msg, t -> fail("insert_error", t, msg) }
        return appended.isSuccess
    }

    /** 真的发出去了才记时间:生成失败、落库失败都不记,下一轮还能再来一次。 */
    private suspend fun sent(thread: MpThreadEntity, content: String, at: Long): MpProactiveChatOutcome {
        // 时间戳写失败不影响已经落库的消息:最多让下一轮少一次防抖保护
        resultOf { store.markProactiveChat(at) }
            .onError { msg, t -> fail("settings_write", t, msg) }
        Logger.d(TAG, "主动私信落库 | threadId=${thread.id} | characterId=${thread.characterId} | len=${content.length}")
        return MpProactiveChatOutcome.Sent(threadId = thread.id, characterId = thread.characterId)
    }

    /**
     * 有消息的会话里最后一条最新的那个;一条消息都没有的会话不算「能搭话」。
     *
     * 群会话一律跳过:主动私信是「某个角色单独找你」,而群会话的 characterId 只是第一个成员 ——
     * 拿它去开口就会在群里以一个人的身份插一句跟上下文无关的话。
     */
    private suspend fun latestActiveThread(): MpThreadEntity? {
        val threads = resultOf { threadDao.observeThreads().first() }
            .onError { msg, t -> fail("threads_unreadable", t, msg) }
            .getOrNull()
        return threads?.filter { it.lastMessageAt > 0L && !it.isGroup }?.maxByOrNull { it.lastMessageAt }
    }

    /** 选中会话里最后一条「用户发出」的消息时间;没有则 null。 */
    private suspend fun lastUserMessageAt(threadId: String): Long? {
        val sent = resultOf { messageDao.lastMessageAt(threadId, MpActorType.USER.value) }
            .onError { msg, t -> fail("messages_unreadable", t, msg) }
            .getOrNull()
        return sent?.takeIf { it > 0L }
    }

    /** 按小时给一句开口的由头,交给宿主的生成器当素材的收尾指令。 */
    private fun focusFor(atMillis: Long): String = when (MpProactiveChatPlan.hourOf(atMillis, zone)) {
        in 8..10 -> "早上刚醒,随口打个招呼"
        in 11..13 -> "中午休息,问一句吃了没"
        in 14..17 -> "下午想起对方,闲聊一句"
        else -> "晚上,随口聊两句"
    }

    private fun fail(reason: String, throwable: Throwable?, message: String) {
        Logger.w(TAG, "主动私信失败 | reason=$reason | ${throwable?.message ?: message}", throwable)
    }

    private companion object {
        const val TAG: String = "MiniPhoneChat"

        const val FAIL_SETTINGS: String = "settings_unreadable"

        const val FAIL_WRITE: String = "insert_error"
    }
}
