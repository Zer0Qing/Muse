package io.zer0.muse.data.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTreeTest {

    private fun user(content: String, group: String? = null, index: Int = 0, count: Int = 1, at: Long = System.currentTimeMillis()) =
        UIMessage(
            id = Uuid.random(),
            role = MessageRole.USER,
            content = content,
            createdAt = at,
            variantGroupId = group,
            variantIndex = index,
            variantCount = count,
        )

    private fun assistant(content: String, group: String? = null, index: Int = 0, count: Int = 1, parentGroup: String? = null, at: Long = System.currentTimeMillis()) =
        UIMessage(
            id = Uuid.random(),
            role = MessageRole.ASSISTANT,
            content = content,
            createdAt = at,
            variantGroupId = group,
            variantIndex = index,
            variantCount = count,
            parentGroupId = parentGroup,
        )

    @Test
    fun build_createsTwoLevelTree() {
        val u = user("你好")
        val a = assistant("你好！", group = "ag1", parentGroup = u.id.toString(), at = u.createdAt + 1)

        val tree = ConversationTree.build(listOf(u, a))

        assertEquals(1, tree.userNodes.size)
        assertEquals(u.id, tree.selectedUserVariant?.id)
        assertEquals(1, tree.userNodes.first().currentVariant?.assistantNodes?.size)
        assertEquals(a.id, tree.userNodes.first().currentVariant?.assistantNodes?.first()?.currentVariant?.id)
        assertEquals(listOf(u.id, a.id), tree.displayMessages.map { it.id })
    }

    @Test
    fun build_separateUserNodes_showsAllTurnsInOrder() {
        val u1 = user("你好")
        val a1 = assistant("回答1", group = "ag1", parentGroup = u1.id.toString(), at = u1.createdAt + 1)
        val u2 = user("引用回复测试")
        val a2 = assistant("回答2", group = "ag2", parentGroup = u2.id.toString(), at = u2.createdAt + 1)

        val tree = ConversationTree.build(listOf(u1, a1, u2, a2))

        assertEquals(2, tree.userNodes.size)
        assertEquals(
            listOf("你好", "回答1", "引用回复测试", "回答2"),
            tree.displayMessages.map { it.content },
        )
        assertEquals("引用回复测试", tree.selectedUserVariant?.content)
    }

    @Test
    fun build_legacyAssistantWithoutParentGroup_attachesByCreationTime() {
        // 编辑场景: 用户消息 u0 -> 助手 a1(旧数据,无 parentGroupId)
        // 编辑后: 新用户版本 u1 出现, 新回复 a2(parentGroupId = u1.id)
        // 旧助手 a1 必须挂在旧版本 u0 下, 不能因为"取最后一个版本"挂到 u1 导致消息分裂
        val u0 = user("写首诗", at = 1000L)
        val a1 = assistant("旧回复", group = "ag1", at = 1001L) // 旧数据: 无 parentGroupId
        val u1 = user("改成写词", group = u0.id.toString(), index = 1, count = 2, at = 2000L)
        val a2 = assistant("新回复", group = "ag2", parentGroup = u1.id.toString(), at = 2001L)

        val tree = ConversationTree.build(listOf(u0, a1, u1, a2))

        assertEquals(1, tree.userNodes.size)
        val node = tree.userNodes.first()
        assertEquals(2, node.variants.size)
        // 选中最新版本 u1
        assertEquals(u1.id, node.currentVariant?.message?.id)
        // u1 下只有新回复, 不应包含旧回复
        val u1Assistants = node.currentVariant?.assistantNodes?.map { it.currentVariant?.content }
        assertEquals(listOf("新回复"), u1Assistants)
        // u0 下挂旧回复
        val u0Assistants = node.variants.first().assistantNodes.map { it.currentVariant?.content }
        assertEquals(listOf("旧回复"), u0Assistants)
    }

    @Test
    fun rebuild_appendedNewTurn_selectsLatestUserNode() {
        val u1 = user("你好")
        val a1 = assistant("回答1", group = "ag1", parentGroup = u1.id.toString(), at = u1.createdAt + 1)
        val initial = ConversationTree.build(listOf(u1, a1))

        val u2 = user("第二问")
        val placeholder = assistant("", group = "ag2", parentGroup = u2.id.toString(), at = u2.createdAt + 1)
        val rebuilt = ConversationTree.build(listOf(u1, a1, u2, placeholder), initial)

        assertEquals(2, rebuilt.userNodes.size)
        assertEquals("第二问", rebuilt.selectedUserVariant?.content)
        assertEquals(
            listOf("你好", "回答1", "第二问", ""),
            rebuilt.displayMessages.map { it.content },
        )
    }

    @Test
    fun retry_appendsVariantToSameAssistantGroup() {
        val u = user("问题", group = "ug1")
        val a1 = assistant("回答1", group = "ag1", index = 0, count = 1, parentGroup = u.id.toString(), at = u.createdAt + 1)
        val tree = ConversationTree.build(listOf(u, a1))

        val update = tree.retryLastAssistant()

        assertNotNull(update.newMessage)
        assertEquals("ag1", update.changedGroupId)
        assertEquals(2, update.tree.userNodes.first().currentVariant?.assistantNodes?.first()?.variants?.size)
        assertEquals(1, update.tree.userNodes.first().currentVariant?.assistantNodes?.first()?.selectIndex)
        assertEquals("", update.tree.displayMessages.last().content)
        assertEquals("ag1", update.newMessage?.variantGroupId)
        assertEquals(u.id.toString(), update.newMessage?.parentGroupId)
    }

    @Test
    fun edit_createsNewUserVariantWithFreshReply() {
        val u = user("原始提问", group = "ug1")
        val a1 = assistant("回答1", group = "ag1", index = 0, count = 1, parentGroup = u.id.toString(), at = u.createdAt + 1)
        val tree = ConversationTree.build(listOf(u, a1))

        val edit = tree.editUserMessage(u.id, "修改后的提问")

        assertNotNull(edit)
        assertEquals("ug1", edit?.newUserMessage?.variantGroupId)
        assertEquals(1, edit?.newUserMessage?.variantIndex)
        assertEquals(2, edit?.tree?.userNodes?.first()?.variants?.size)
        assertEquals("修改后的提问", edit?.tree?.selectedUserVariant?.content)
        // 新版本只有占位回复，不复制旧版本的回复
        val assistants = edit?.tree?.selectedUserNode?.currentVariant?.assistantNodes.orEmpty()
        assertEquals(1, assistants.size)
        assertEquals("", assistants[0].currentVariant?.content)
        assertEquals(edit?.newUserMessage?.id?.toString(), assistants[0].currentVariant?.parentGroupId)
        // 旧版本仍保留自己的回复
        val oldVariant = edit?.tree?.userNodes?.first()?.variants?.first()
        assertEquals("回答1", oldVariant?.assistantNodes?.first()?.currentVariant?.content)
    }

    @Test
    fun rebuild_restoresTreeFromFlatMessages() {
        val u1 = user("原始提问", group = "ug1", index = 0, count = 2, at = 100)
        val a1 = assistant("回答1", group = "ag1", index = 0, count = 1, parentGroup = u1.id.toString(), at = 101)
        val u2 = user("修改后的提问", group = "ug1", index = 1, count = 2, at = 102)
        val placeholder = assistant("", group = "ag2", index = 0, count = 1, parentGroup = u2.id.toString(), at = 103)

        val tree = ConversationTree.build(listOf(u1, a1, u2, placeholder))

        assertEquals(1, tree.userNodes.size)
        assertEquals(2, tree.userNodes.first().variants.size)
        assertEquals(1, tree.userNodes.first().variants[0].assistantNodes.size)
        assertEquals(1, tree.userNodes.first().variants[1].assistantNodes.size)
        assertEquals("回答1", tree.userNodes.first().variants[0].assistantNodes.first().currentVariant?.content)
        assertEquals("", tree.userNodes.first().variants[1].assistantNodes.first().currentVariant?.content)
        // 默认选中最新用户版本
        assertEquals("修改后的提问", tree.displayMessages.first().content)
        assertEquals("", tree.displayMessages.last().content)
    }

    @Test
    fun legacyData_withoutParentGroup_infersParent() {
        val u = user("旧数据提问")
        val a = assistant("旧数据回答", group = "ag1", at = u.createdAt + 1)

        val tree = ConversationTree.build(listOf(u, a))

        assertEquals(1, tree.userNodes.size)
        assertEquals("旧数据回答", tree.userNodes.first().currentVariant?.assistantNodes?.first()?.currentVariant?.content)
        assertEquals(2, tree.displayMessages.size)
    }

    @Test
    fun userVariants_isolateAssistantSubtrees() {
        val g1 = user("提问A", group = "gA", index = 0, count = 2, at = 100)
        val a1 = assistant("回答A-1", group = "agA", index = 0, count = 1, parentGroup = g1.id.toString(), at = 101)
        val g1v2 = user("提问A改", group = "gA", index = 1, count = 2, at = 102)
        val a1v2 = assistant("回答A-2", group = "agB", index = 0, count = 1, parentGroup = g1v2.id.toString(), at = 103)
        val g2 = user("提问B", group = "gB", index = 0, count = 1, at = 104)
        val a2 = assistant("回答B", group = "agC", index = 0, count = 1, parentGroup = g2.id.toString(), at = 105)

        val tree = ConversationTree.build(listOf(g1, a1, g1v2, a1v2, g2, a2))

        assertEquals(2, tree.userNodes.size)
        // 默认选中最新一轮；同一提问组内只显示该组当前选中的版本
        assertEquals("提问B", tree.selectedUserVariant?.content)
        assertEquals(listOf("提问A改", "回答A-2", "提问B", "回答B"), tree.displayMessages.map { it.content })
        // 切回旧版本时，A 组切到旧版回复，B 组保持不变
        val switched = tree.selectUserVariant(tree.userNodes.first().userId, 0)
        assertEquals("提问A", switched.selectedUserVariant?.content)
        assertEquals(listOf("提问A", "回答A-1", "提问B", "回答B"), switched.displayMessages.map { it.content })
        // 提问B 与提问A 互不干扰
        assertEquals("回答B", tree.userNodes[1].variants.first().assistantNodes.first().currentVariant?.content)
    }

    @Test
    fun selectAssistantVariant_changesOnlyTargetGroup() {
        val u = user("提问", group = "ug1")
        val a1 = assistant("回答1", group = "ag1", index = 0, count = 2, parentGroup = u.id.toString(), at = 101)
        val a2 = assistant("回答2", group = "ag1", index = 1, count = 2, parentGroup = u.id.toString(), at = 102)

        val tree = ConversationTree.build(listOf(u, a1, a2))

        assertEquals("回答2", tree.displayMessages.last().content)

        val switched = tree.selectAssistantVariant(u.id.toString(), "ag1", 0)
        assertEquals("回答1", switched.displayMessages.last().content)
    }

    @Test
    fun emptyAndNullSafe() {
        val empty = ConversationTree.build(emptyList())
        assertTrue(empty.displayMessages.isEmpty())
        assertNull(empty.selectedUserNode)
        val tree = ConversationTree()
        val update = tree.retryLastAssistant()
        assertNull(update.newMessage)
        assertNull(update.changedGroupId)
        assertNull(tree.editUserMessage(Uuid.random(), "x"))
    }

    @Test
    fun rebuild_keepsUserVariantSelection() {
        val u1 = user("原始提问", group = "ug1", index = 0, count = 2, at = 100)
        val u2 = user("修改后的提问", group = "ug1", index = 1, count = 2, at = 102)

        val initial = ConversationTree.build(listOf(u1, u2))
        val switched = initial.selectUserVariant(initial.userNodes.first().userId, 0)
        val rebuilt = ConversationTree.build(listOf(u1, u2), switched)

        assertEquals("原始提问", rebuilt.selectedUserVariant?.content)
        assertEquals("原始提问", rebuilt.displayMessages.first().content)
    }

    @Test
    fun rebuild_keepsAssistantVariantSelection() {
        val u = user("提问", group = "ug1")
        val a1 = assistant("回答1", group = "ag1", index = 0, count = 2, parentGroup = u.id.toString(), at = 101)
        val a2 = assistant("回答2", group = "ag1", index = 1, count = 2, parentGroup = u.id.toString(), at = 102)

        val initial = ConversationTree.build(listOf(u, a1, a2))
        val switched = initial.selectAssistantVariant(u.id.toString(), "ag1", 0)
        val rebuilt = ConversationTree.build(listOf(u, a1, a2), switched)

        assertEquals("回答1", rebuilt.userNodes.first().currentVariant?.assistantNodes?.first()?.currentVariant?.content)
        assertEquals("回答1", rebuilt.displayMessages.last().content)
    }

    @Test
    fun branchInfoFor_findsUserAndAssistantGroups() {
        val u1 = user("提问A", group = "ug1", index = 0, count = 2, at = 100)
        val u2 = user("提问A改", group = "ug1", index = 1, count = 2, at = 102)
        val a1 = assistant("回答1", group = "ag1", index = 0, count = 2, parentGroup = u2.id.toString(), at = 101)
        val a2 = assistant("回答2", group = "ag1", index = 1, count = 2, parentGroup = u2.id.toString(), at = 103)

        val tree = ConversationTree.build(listOf(u1, a1, u2, a2))

        val userInfo = tree.branchInfoFor(u1.id)
        assertEquals("ug1", userInfo?.groupId)
        assertEquals(2, userInfo?.branchCount)

        val assistantInfo = tree.branchInfoFor(a1.id)
        assertEquals("ag1", assistantInfo?.groupId)
        assertEquals(u2.id.toString(), assistantInfo?.parentGroupId)
        assertEquals(2, assistantInfo?.branchCount)
    }

    @Test
    fun build_normalizesDuplicateVariantIndexes() {
        val u = user("提问", group = "ug1")
        val a1 = assistant("回答1", group = "ag1", index = 1, count = 2, parentGroup = u.id.toString(), at = 101)
        val a2 = assistant("回答2", group = "ag1", index = 1, count = 2, parentGroup = u.id.toString(), at = 102)

        val tree = ConversationTree.build(listOf(u, a1, a2))

        val variants = tree.userNodes.first().currentVariant?.assistantNodes?.first()?.variants.orEmpty()
        assertEquals(listOf(0, 1), variants.map { it.variantIndex })
        assertEquals(2, variants[0].variantCount)
        assertEquals(2, variants[1].variantCount)
    }

    @Test
    fun selectedVariantFlatMessages_containsAllRetryVariants() {
        val u = user("提问", group = "ug1")
        val a1 = assistant("回答1", group = "ag1", index = 0, count = 1, parentGroup = u.id.toString(), at = 101)
        val tree = ConversationTree.build(listOf(u, a1))

        val update = tree.retryLastAssistant()

        val flat = update.tree.selectedVariantFlatMessages
        assertEquals(3, flat.size)
        assertEquals("回答1", flat[1].content)
        assertEquals("", flat[2].content)
    }

    @Test
    fun allFlatMessages_preservesEveryUserVariantAndRetryVariant() {
        val u1 = user("提问1", group = "ug1", index = 0, count = 2, at = 100)
        val a1 = assistant("回答1", group = "ag1", index = 0, count = 1, parentGroup = u1.id.toString(), at = 101)
        val u2 = user("提问2", group = "ug1", index = 1, count = 2, at = 102)
        val placeholder = assistant("", group = "ag2", index = 0, count = 1, parentGroup = u2.id.toString(), at = 103)

        val tree = ConversationTree.build(listOf(u1, a1, u2, placeholder))
        val afterRetry = tree.retryLastAssistant().tree

        val flat = afterRetry.allFlatMessages
        assertEquals(5, flat.size)
        assertEquals(listOf("提问1", "回答1", "提问2", "", ""), flat.map { it.content })
    }

    @Test
    fun editThenRetry_keepsTwoUserVariantsAndTwoAssistantVariants() {
        val u1 = user("原始提问", group = "ug1", index = 0, count = 1, at = 100)
        val a1 = assistant("回答1", group = "ag1", index = 0, count = 1, parentGroup = u1.id.toString(), at = 101)
        val tree = ConversationTree.build(listOf(u1, a1))

        val edit = tree.editUserMessage(u1.id, "修改后的提问") ?: error("edit failed")
        val afterRetry = edit.tree.retryLastAssistant().tree

        val newUser = edit.newUserMessage ?: error("new user missing")
        val placeholder = edit.newAssistantPlaceholder ?: error("placeholder missing")
        assertEquals(2, afterRetry.branchInfoFor(newUser.id)?.branchCount)
        assertEquals(2, afterRetry.branchInfoFor(placeholder.id)?.branchCount)
        assertEquals(2, afterRetry.userNodes.first().currentVariant?.assistantNodes?.first()?.variants?.size)
        assertEquals("修改后的提问", afterRetry.selectedUserVariant?.content)
    }

    @Test
    fun mergeRebuildMessages_preservesRetryVariantsAndNewMessages() {
        val u1 = user("提问1", group = "ug1", at = 100)
        val a1 = assistant("回答1", group = "ag1", index = 0, count = 2, parentGroup = u1.id.toString(), at = 101)
        val a2 = assistant("回答2", group = "ag1", index = 1, count = 2, parentGroup = u1.id.toString(), at = 102)
        val tree = ConversationTree.build(listOf(u1, a1, a2))

        // 当前显示只包含当前选中的重试版本,合并后必须保留旧版本。
        val merged = mergeRebuildMessages(tree, tree.displayMessages)
        assertEquals(listOf("提问1", "回答1", "回答2"), merged.map { it.content })
        val rebuilt = ConversationTree.build(merged, tree)
        assertEquals(2, rebuilt.userNodes.first().currentVariant?.assistantNodes?.first()?.variants?.size)

        // 新追加的用户消息/助手占位不在旧树中,也必须进入合并结果。
        val u2 = user("提问2")
        val placeholder = assistant("", group = "ag2", parentGroup = u2.id.toString(), at = u2.createdAt + 1)
        val mergedWithNew = mergeRebuildMessages(tree, tree.displayMessages + u2 + placeholder)
        assertEquals(listOf("提问1", "回答1", "回答2", "提问2", ""), mergedWithNew.map { it.content })
    }

    @Test
    fun removeMessage_removesAssistantVariantAndReindexes() {
        val u = user("提问", group = "ug1")
        val a1 = assistant("回答1", group = "ag1", index = 0, count = 2, parentGroup = u.id.toString(), at = 101)
        val a2 = assistant("回答2", group = "ag1", index = 1, count = 2, parentGroup = u.id.toString(), at = 102)
        val tree = ConversationTree.build(listOf(u, a1, a2))

        val removed = tree.removeMessage(a2.id)

        val variants = removed.userNodes.first().currentVariant?.assistantNodes?.first()?.variants.orEmpty()
        assertEquals(1, variants.size)
        assertEquals(listOf("回答1"), variants.map { it.content })
        assertEquals(0, variants[0].variantIndex)
        assertEquals(1, variants[0].variantCount)
        assertEquals(listOf("提问", "回答1"), removed.displayMessages.map { it.content })
    }

    @Test
    fun removeMessage_removesUserVariantWithItsAssistants() {
        val u1 = user("提问1", group = "ug1", at = 100)
        val a1 = assistant("回答1", group = "ag1", parentGroup = u1.id.toString(), at = 101)
        val u2 = user("提问2", group = "ug2", at = 102)
        val a2 = assistant("回答2", group = "ag2", parentGroup = u2.id.toString(), at = 103)
        val tree = ConversationTree.build(listOf(u1, a1, u2, a2))

        val removed = tree.removeMessage(u1.id)

        assertEquals(1, removed.userNodes.size)
        assertEquals(listOf("提问2", "回答2"), removed.displayMessages.map { it.content })
    }

    @Test
    fun removeMessage_removesLastUserNode_returnsEmptyTree() {
        val u = user("提问", group = "ug1")
        val a = assistant("回答", group = "ag1", parentGroup = u.id.toString(), at = u.createdAt + 1)
        val tree = ConversationTree.build(listOf(u, a))

        val removed = tree.removeMessage(u.id)

        assertTrue(removed.userNodes.isEmpty())
        assertTrue(removed.displayMessages.isEmpty())
    }

    @Test
    fun removeMessage_unknownId_returnsSameTree() {
        val u = user("提问", group = "ug1")
        val tree = ConversationTree.build(listOf(u))

        val removed = tree.removeMessage(Uuid.random())

        assertEquals(tree, removed)
    }
}
