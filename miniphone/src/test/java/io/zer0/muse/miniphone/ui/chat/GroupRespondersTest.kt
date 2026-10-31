package io.zer0.muse.miniphone.ui.chat

import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 群聊选人:点名 → 最近说过话 → 稳定随机,最多两个。
 *
 * 纯函数,不需要库也不需要界面:规则本身对不对,这里逐条钉死。
 */
class GroupRespondersTest {

    @Test
    fun aNamedMemberIsAlwaysPickedEvenWithTheNicknamePrefix() {
        val picked = select(members = FIVE, text = "阿远哥,你昨天说的那个")

        assertEquals("点名的人排在第一个", "c1", picked.first())
        assertTrue(picked.contains("c1"))
    }

    @Test
    fun thePrefixOfALongNameAlsoCounts() {
        val members = listOf(candidate("c9", "小满同学"), candidate("c1", "阿远"))

        val picked = select(members = members, text = "小满在吗")

        assertEquals("名字前两个字对得上就算叫到人", "c9", picked.first())
    }

    @Test
    fun aSingleCharacterNameIsMatchedOnlyAsIs() {
        val members = listOf(candidate("c1", "阿"), candidate("c2", "小满"))

        assertEquals("c1", select(members = members, text = "阿,你在吗").first())
        assertFalse("名字没出现就不算叫到", mentions("阿", "晚上好"))
    }

    @Test
    fun aNamedMemberPlusTheRecentSpeakerAreExactlyTheTwo() {
        val picked = select(members = FIVE, text = "小满你怎么看", recentSenderIds = listOf("c4"))

        assertEquals("先点名,再补最近说话的", listOf("c2", "c4"), picked)
    }

    @Test
    fun neverMoreThanTwoEvenInAFiveMemberGroup() {
        val picked = select(members = FIVE, text = "大家晚上好")

        assertEquals(2, picked.size)
        assertEquals("不能重复挑同一个人", picked.size, picked.distinct().size)
        assertTrue("只能从成员里挑", FIVE.map { it.characterId }.containsAll(picked))
    }

    @Test
    fun namingThreePeopleStillStopsAtTwo() {
        val picked = select(members = FIVE, text = "阿远、小满、阿吾都来")

        assertEquals("按成员顺序取前两个", listOf("c1", "c2"), picked)
    }

    @Test
    fun theSameSceneAlwaysPicksTheSamePeople() {
        val seed = responderSeedOf(threadId = "g1", messageId = "m1")

        val first = select(members = FIVE, text = "在吗", seed = seed)
        val second = select(members = FIVE, text = "在吗", seed = seed)

        assertEquals("同一个群同一条消息换不了人", first, second)
    }

    @Test
    fun recentSpeakersAreDedupedAndOnlyFromTheLastTenMessages() {
        val messages = buildList {
            add(message("old", characterId = "c1", sender = MpActorType.CHARACTER))
            repeat(GROUP_RECENT_SPEAKER_WINDOW) { index ->
                add(message("m$index", characterId = if (index < 3) "c3" else "c2", sender = MpActorType.CHARACTER))
            }
        }

        val recent = recentSpeakerIdsOf(messages)

        assertEquals("只留最近的发言者,去掉重复,旧的 c1 不在窗口里", listOf("c2", "c3"), recent)
    }

    @Test
    fun ownMessagesAreNotRecentSpeakers() {
        val messages = listOf(
            message("u1", characterId = "", sender = MpActorType.USER),
            message("m1", characterId = "c2", sender = MpActorType.CHARACTER),
        )

        assertEquals(listOf("c2"), recentSpeakerIdsOf(messages))
    }

    @Test
    fun anEmptyGroupPicksNobody() {
        assertEquals(emptyList<String>(), selectGroupResponders(emptyList(), "在吗", emptyList(), seed = 1L))
    }

    private fun select(
        members: List<GroupResponderCandidate>,
        text: String,
        recentSenderIds: List<String> = emptyList(),
        seed: Long = responderSeedOf("g1", "m1"),
    ): List<String> = selectGroupResponders(members = members, text = text, recentSenderIds = recentSenderIds, seed = seed)

    private fun candidate(id: String, name: String): GroupResponderCandidate = GroupResponderCandidate(characterId = id, name = name)

    private fun message(id: String, characterId: String, sender: MpActorType): ChatMessageUi = ChatMessageUi(
        id = id,
        characterId = characterId,
        sender = sender,
        type = MpMessageType.TEXT,
        content = "第 $id 句",
        createdAt = 1L,
    )

    private companion object {
        val FIVE: List<GroupResponderCandidate> = listOf(
            GroupResponderCandidate(characterId = "c1", name = "阿远"),
            GroupResponderCandidate(characterId = "c2", name = "小满"),
            GroupResponderCandidate(characterId = "c3", name = "阿吾"),
            GroupResponderCandidate(characterId = "c4", name = "阿岑"),
            GroupResponderCandidate(characterId = "c5", name = "阿迟"),
        )
    }
}
