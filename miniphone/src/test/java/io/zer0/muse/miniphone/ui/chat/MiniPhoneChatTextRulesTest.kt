package io.zer0.muse.miniphone.ui.chat

import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageType
import io.zer0.muse.miniphone.api.MpActivityKind
import io.zer0.muse.miniphone.ui.shell.initialOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小手机聊天文案与历史的三条纯规则（P2b）。
 *
 * 分开看都不起眼，但都是"错了没人报错、用户直接看见"的那种：
 *
 *  - `chatHistoryOf` 决定**交给模型看什么**：系统提示、图片、语音行不能进；
 *    群里角色说的话必须带「谁：」前缀，否则后接话的人看不出前面那句是谁说的，
 *    会重复上一句或答错人（宿主只有"用户/非用户"两档身份）。
 *  - `MiniPhoneActivityText` 是生成期间那一行拟人文案；
 *  - `initialOf` 是每个头像上的那个字。
 */
class MiniPhoneChatTextRulesTest {

    private fun msg(
        content: String,
        sender: MpActorType = MpActorType.USER,
        type: MpMessageType = MpMessageType.TEXT,
        characterId: String = "c1",
    ) = ChatMessageUi(
        id = "id-$content",
        characterId = characterId,
        sender = sender,
        type = type,
        content = content,
        createdAt = 1L,
    )

    @Test
    fun `系统提示与图片语音行不进交给模型的历史`() {
        val state = ChatUiState(
            messages = listOf(
                msg("你好"),
                msg("看这张", type = MpMessageType.IMAGE),
                msg("听这段", type = MpMessageType.VOICE),
                msg("（已撤回）", type = MpMessageType.SYSTEM),
                msg("   "),
            ),
        )

        val history = chatHistoryOf(state)

        assertEquals(listOf("你好"), history.map { it.content })
        assertEquals(MpActorType.USER, history.single().sender)
    }

    @Test
    fun `群里角色说的话必须带发言人前缀`() {
        val state = ChatUiState(
            isGroup = true,
            members = listOf(ChatMemberUi(characterId = "c1", name = "小美"), ChatMemberUi(characterId = "c2", name = "阿力")),
            messages = listOf(
                msg("你们俩聊", sender = MpActorType.USER),
                msg("我先把图存好", sender = MpActorType.CHARACTER, characterId = "c2"),
                msg("我记账", sender = MpActorType.CHARACTER, characterId = "c1"),
            ),
        )

        val history = chatHistoryOf(state)

        assertEquals("用户的话原样走", "你们俩聊", history[0].content)
        assertEquals("谁说的要看得出来", "阿力：我先把图存好", history[1].content)
        assertEquals("小美：我记账", history[2].content)
    }

    @Test
    fun `私信里不无前缀哪怕成员表空着`() {
        val state = ChatUiState(
            messages = listOf(msg("在吗"), msg("在的", sender = MpActorType.CHARACTER)),
        )

        val history = chatHistoryOf(state)

        assertEquals(listOf("在吗", "在的"), history.map { it.content })
    }

    @Test
    fun `成员名字查不到时退回原句而不是打出空前缀`() {
        val state = ChatUiState(
            isGroup = true,
            members = emptyList(),
            messages = listOf(msg("没人知道是谁说的", sender = MpActorType.CHARACTER, characterId = "gone")),
        )

        val history = chatHistoryOf(state)

        // 空成员表 → 前缀只剩「：」，模型会把它当成消息的一部分
        assertEquals("没人知道是谁说的", history.single().content)
        assertFalse(history.single().content.startsWith(SPEAKER_SEPARATOR))
    }

    @Test
    fun `每一种活动都有独立且非空的一句话案`() {
        val kinds = MpActivityKind.entries
        val ids = kinds.map { MiniPhoneActivityText.resOf(it) }

        assertEquals("每种活动各自一句，不能挤成同一句", kinds.size, ids.distinct().size)
        assertTrue("资源 id 不能是 0", ids.all { it != 0 })
        // 句子本身在模块的 strings.xml 里，渲染后的文字由组合测试盯；
        // 这条纯 JVM 测试只保证"种类→资源"这层映射不塌成同一句。
    }

    @Test
    fun `头像首字拉丁转大写汉字原样空名给占位`() {
        assertEquals("A", initialOf("alice"))
        assertEquals("A", initialOf("   alice"))
        assertEquals("自", initialOf("自嘲熊"))
        assertEquals("?", initialOf(""))
        assertEquals("?", initialOf("   "))
    }
}
