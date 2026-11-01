package io.zer0.muse.data.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationTreeOrderingTest {
    @Test
    fun `snapshot placeholder is never merged into real messages`() {
        val realUser = UIMessage(
            id = Uuid.random(),
            role = MessageRole.USER,
            content = "真实问题",
            createdAt = 100L,
            seq = 1L,
        )
        val realAssistant = UIMessage(
            id = Uuid.random(),
            role = MessageRole.ASSISTANT,
            content = "真实回答",
            createdAt = 101L,
            seq = 2L,
            parentGroupId = realUser.id.toString(),
            variantGroupId = "assistant-group",
        )
        val snapshotPlaceholder = UIMessage(
            id = Uuid.random(),
            role = MessageRole.USER,
            content = "",
            createdAt = 0L,
            variantGroupId = "old-group",
        )

        val merged = mergeRebuildMessages(
            ConversationTree.build(listOf(snapshotPlaceholder)),
            listOf(realAssistant, realUser),
        )

        assertEquals(listOf(realUser.id, realAssistant.id), merged.map { it.id })
    }

    @Test
    fun `message ordering uses stable sequence before createdAt`() {
        val first = UIMessage(
            id = Uuid.random(),
            role = MessageRole.USER,
            content = "第一轮",
            createdAt = 9_999L,
            seq = 1L,
        )
        val second = UIMessage(
            id = Uuid.random(),
            role = MessageRole.ASSISTANT,
            content = "第二条",
            createdAt = 1L,
            seq = 2L,
        )

        assertEquals(
            listOf(first.id, second.id),
            orderConversationMessages(listOf(second, first)).map { it.id },
        )
    }

    @Test
    fun `混合 seq 与 createdAt 时排序仍是全序(回归-比较器成环)`() {
        // 审查发现的成环三元组:旧实现会同时得出 A>C(按 seq)、A<B、B<C(按 createdAt)
        val a =
            UIMessage(
                id = Uuid.random(),
                role = MessageRole.USER,
                content = "A",
                createdAt = 3_000L,
                seq = 10L,
            )
        val b =
            UIMessage(
                id = Uuid.random(),
                role = MessageRole.USER,
                content = "B(无序旧消息)",
                createdAt = 1_000L,
                seq = 0L,
            )
        val c =
            UIMessage(
                id = Uuid.random(),
                role = MessageRole.ASSISTANT,
                content = "C",
                createdAt = 100L,
                seq = 20L,
            )

        // 不应抛 "Comparison method violates its general contract!"
        val first = orderConversationMessages(listOf(a, b, c)).map { it.id }
        // 有序消息仍按 seq(A 在 C 前),无序消息按 createdAt 插回时间段(B 的时间早于 A、晚于 C)
        assertEquals(listOf(a.id, b.id, c.id), first)
        // 结果与输入顺序无关(全序的判据)
        assertEquals(first, orderConversationMessages(listOf(c, b, a)).map { it.id })
        assertEquals(first, orderConversationMessages(listOf(b, c, a)).map { it.id })
    }

    @Test
    fun `无序旧消息按 createdAt 插回时间段而不是统一塞到一端`() {
        val m1 =
            UIMessage(
                id = Uuid.random(),
                role = MessageRole.USER,
                content = "m1",
                createdAt = 10L,
                seq = 1L,
            )
        val m2 =
            UIMessage(
                id = Uuid.random(),
                role = MessageRole.ASSISTANT,
                content = "m2",
                createdAt = 20L,
                seq = 2L,
            )
        val legacy =
            UIMessage(
                id = Uuid.random(),
                role = MessageRole.USER,
                content = "legacy",
                createdAt = 15L,
                seq = 0L,
            )

        assertEquals(
            listOf(m1.id, legacy.id, m2.id),
            orderConversationMessages(listOf(m2, legacy, m1)).map { it.id },
        )
    }

    @Test
    fun `已摘要的原文不再从旧树合并回来`() {
        val user =
            UIMessage(
                id = Uuid.random(),
                role = MessageRole.USER,
                content = "老问题",
                createdAt = 1L,
                seq = 1L,
            )
        val assistant =
            UIMessage(
                id = Uuid.random(),
                role = MessageRole.ASSISTANT,
                content = "老回答",
                createdAt = 2L,
                seq = 2L,
                parentGroupId = user.id.toString(),
                variantGroupId = "assistant-group",
            )
        val tree = ConversationTree.build(listOf(user, assistant))

        // 旧行为(不传已摘要集合):旧树原文会被合并回来
        assertEquals("旧行为下原文应被合并回来", 2, mergeRebuildMessages(tree, emptyList()).size)
        // 已被摘要覆盖的原文不得复活,否则"摘要 + 原文"共存导致 token 双计
        val filtered =
            mergeRebuildMessages(
                tree,
                emptyList(),
                setOf(user.id.toString(), assistant.id.toString()),
            )
        assertEquals("已摘要的原文不得复活", 0, filtered.size)
        // 只覆盖一部分时,未被覆盖的那条仍要保留
        val partial = mergeRebuildMessages(tree, emptyList(), setOf(user.id.toString()))
        assertEquals("未覆盖的消息仍需合并回来", listOf(assistant.id), partial.map { it.id })
    }

    @Test
    fun `editing a middle user message keeps the branch in place after rebuild`() {
        // 用户反馈：编辑中间某条消息后，新分支跑到最后。
        // 这里走一遍真实链路：建树 → 编辑第 2 轮 → 用 displayMessages 重建树。
        fun turn(index: Int): Pair<UIMessage, UIMessage> {
            val user = UIMessage(
                id = Uuid.random(),
                role = MessageRole.USER,
                content = "问题 $index",
                createdAt = index * 10L,
                seq = index * 2L,
                variantGroupId = "ug-$index",
            )
            val assistant = UIMessage(
                id = Uuid.random(),
                role = MessageRole.ASSISTANT,
                content = "回答 $index",
                createdAt = index * 10L + 1,
                seq = index * 2L + 1,
                variantGroupId = "ag-$index",
                parentGroupId = user.id.toString(),
            )
            return user to assistant
        }

        val (u1, a1) = turn(1)
        val (u2, a2) = turn(2)
        val (u3, a3) = turn(3)
        val tree = ConversationTree.build(listOf(u1, a1, u2, a2, u3, a3))

        val edit = tree.editUserMessage(u2.id, "问题 2（改）")
        assertNotNull("编辑应成功", edit)
        val shown = edit!!.tree.displayMessages

        // 1) 树投影里，新变体仍在第 2 轮的位置
        assertEquals(
            listOf("问题 1", "问题 2（改）", "问题 3"),
            shown.filter { it.role == MessageRole.USER }.map { it.content },
        )

        // 2) 用这份投影重建树（模拟流式结束后的 rebuild），顺序不能变
        val rebuilt = ConversationTree.build(shown)
        assertEquals(
            listOf("问题 1", "问题 2（改）", "问题 3"),
            rebuilt.displayMessages.filter { it.role == MessageRole.USER }.map { it.content },
        )
    }
}
