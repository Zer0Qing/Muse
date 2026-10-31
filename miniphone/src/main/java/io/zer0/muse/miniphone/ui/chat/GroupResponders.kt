package io.zer0.muse.miniphone.ui.chat

import androidx.compose.runtime.Immutable
import io.zer0.muse.miniphone.data.db.MpActorType
import kotlin.random.Random

/** 一条用户消息最多让几个角色接话:再多就是刷屏。 */
internal const val MAX_GROUP_RESPONDERS: Int = 2

/** 「最近说过话」只看最近这么多条消息。 */
internal const val GROUP_RECENT_SPEAKER_WINDOW: Int = 10

/** 点名时允许只对上名字的前两个字,这样「阿远哥」「小满姐」也算叫到了人。 */
private const val MENTION_PREFIX_LENGTH: Int = 2

/** 接话候选:角色 id 与名字,后者用于点名匹配。 */
@Immutable
internal data class GroupResponderCandidate(
    val characterId: String,
    val name: String,
)

/**
 * 挑谁接这条消息。
 *
 * 三层优先级,选满 [limit] 个人或候选耗尽就停:
 * 1. 点名:消息里出现某个成员的名字(或名字前两个字),他必回;
 * 2. 最近说过话:该群最近一段消息里发过言的角色,按发言时间倒序;
 * 3. 其余成员按稳定随机补位。
 *
 * 「稳定」的意思是同一场景可复现:随机数种子由会话 id 与这条用户消息的 id 派生,
 * 同一个群、同一条消息永远得到同一组人。这样既不会每次发同一句话都换人接,
 * 测试也能把「谁接话」写成断言。
 *
 * @param recentSenderIds 最近发言的角色 id,由新到旧;只在 [GROUP_RECENT_SPEAKER_WINDOW] 条里取
 * @param seed 随机补位用的种子,见 [responderSeedOf]
 */
internal fun selectGroupResponders(
    members: List<GroupResponderCandidate>,
    text: String,
    recentSenderIds: List<String>,
    seed: Long,
    limit: Int = MAX_GROUP_RESPONDERS,
): List<String> {
    if (limit <= 0 || members.isEmpty()) return emptyList()
    val picked = LinkedHashSet<String>(limit)
    // 1. 被点名的人先占位,按成员的入群顺序,不按名字在句子里出现的先后
    members.filter { mentions(it.name, text) }.forEach { candidate ->
        if (picked.size < limit) picked += candidate.characterId
    }
    // 2. 最近说过话的:新 → 旧,已经点过名的人不重复占位
    recentSenderIds.forEach { characterId ->
        if (picked.size < limit && members.any { it.characterId == characterId }) picked += characterId
    }
    // 3. 剩下的按种子洗牌补位
    members.filterNot { it.characterId in picked }
        .shuffled(Random(seed))
        .forEach { candidate ->
            if (picked.size < limit) picked += candidate.characterId
        }
    return picked.toList()
}

/** 消息里是否叫到了这个人:「阿远」与「阿远哥」都算。名字为空一律不算。 */
internal fun mentions(name: String, text: String): Boolean {
    val trimmed = name.trim()
    if (trimmed.isEmpty() || text.isEmpty()) return false
    if (text.contains(trimmed)) return true
    return trimmed.length >= MENTION_PREFIX_LENGTH && text.contains(trimmed.take(MENTION_PREFIX_LENGTH))
}

/**
 * 随机补位的种子:会话 id 与用户消息 id 各取哈希拼在一起。
 *
 * [String.hashCode] 的实现由语言规范钉死,同一个字符串在任何机器上都得到同一个值,
 * 所以「谁接话」在真机与测试里是同一组人。
 */
internal fun responderSeedOf(threadId: String, messageId: String): Long = threadId.hashCode().toLong() * 31L + messageId.hashCode()

/**
 * 最近一段消息里发过言的角色,由新到旧、去重。
 *
 * 只看最近 [GROUP_RECENT_SPEAKER_WINDOW] 条:群里刚说完话的人最容易接得上,
 * 翻太远的旧话反而会让接话的人莫名其妙。
 */
internal fun recentSpeakerIdsOf(messages: List<ChatMessageUi>): List<String> = messages
    .takeLast(GROUP_RECENT_SPEAKER_WINDOW)
    .asReversed()
    .filter { it.sender == MpActorType.CHARACTER && it.characterId.isNotEmpty() }
    .map { it.characterId }
    .distinct()
