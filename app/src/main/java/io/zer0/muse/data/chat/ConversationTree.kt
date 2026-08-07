package io.zer0.muse.data.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import kotlin.uuid.Uuid

/**
 * P0 对话树模型（v2：每个用户提问版本独立挂助手回复）。
 *
 * 结构：
 * ```
 * 提问组 UserNode（variantGroupId 相同）
 *   ├─ 用户版本 1/N（UserVariant）
 *   │    └─ 助手回复组（重试产生的多版本回答）
 *   ├─ 用户版本 2/N（UserVariant，编辑/重试产生）
 *   │    └─ 各自独立的助手回复组
 *   └─ ...
 * ```
 *
 * 不变式：
 * - 切换用户版本时，助手子树一起切换，互不污染。
 * - 助手重试 = 当前用户版本下最后一个助手组新增助手变体。
 * - 编辑用户消息 = 保留旧版本，新建用户版本并放一个空助手占位组。
 * - 持久化：用户版本由 [UIMessage.variantGroupId/variantIndex/variantCount] 表达；
 *   助手消息 [UIMessage.parentGroupId] 指向所属用户版本的消息 ID（兼容旧数据按组 ID 挂载）。
 */
data class ConversationTree(
    val userNodes: List<UserNode> = emptyList(),
    val selectedUserIndex: Int = 0,
) {

    val selectedUserNode: UserNode?
        get() = userNodes.getOrNull(selectedUserIndex)

    /** 当前选中的用户消息。 */
    val selectedUserVariant: UIMessage?
        get() = selectedUserNode?.currentVariant?.message

    val lastAssistantNode: AssistantNode?
        get() = selectedUserNode?.currentVariant?.assistantNodes?.lastOrNull()

    val displayMessages: List<UIMessage>
        get() {
            // 多轮对话按顺序展示每一轮;同一提问组内的多版本只展示当前选中的版本。
            return buildList {
                userNodes.forEach { user ->
                    val variant = user.currentVariant ?: return@forEach
                    add(variant.message)
                    variant.assistantNodes.forEach { node -> node.currentVariant?.let { add(it) } }
                }
            }
        }

    /** 当前选中用户版本的全部扁平消息（含所有助手重试变体），用于树重建时保留分支。 */
    val selectedVariantFlatMessages: List<UIMessage>
        get() {
            val variant = selectedUserNode?.currentVariant ?: return emptyList()
            return buildList {
                add(variant.message)
                variant.assistantNodes.forEach { node -> node.variants.forEach { add(it) } }
            }
        }

    /** 全部用户版本及助手重试变体的扁平消息，用于树重建时完整保留所有分支。 */
    val allFlatMessages: List<UIMessage>
        get() = buildList {
            userNodes.forEach { user ->
                user.variants.forEach { variant ->
                    add(variant.message)
                    variant.assistantNodes.forEach { node -> node.variants.forEach { add(it) } }
                }
            }
        }

    /** 某条消息所属分支组信息，供 UI 渲染变体切换器。 */
    fun branchInfoFor(messageId: Uuid): BranchInfo? {
        userNodes.forEach { user ->
            user.variants.forEach { variant ->
                if (variant.message.id == messageId) {
                    return BranchInfo(
                        groupId = user.currentVariant?.message?.variantGroupId ?: user.groupId,
                        parentGroupId = null,
                        selectIndex = user.selectIndex,
                        branchCount = user.variants.size,
                    )
                }
                variant.assistantNodes.forEach { assistant ->
                    if (assistant.variants.any { it.id == messageId }) {
                        return BranchInfo(
                            groupId = assistant.groupId,
                            parentGroupId = user.currentVariant?.message?.id?.toString()
                                ?: user.currentVariant?.message?.variantGroupId
                                ?: user.groupId,
                            selectIndex = assistant.selectIndex,
                            branchCount = assistant.variants.size,
                        )
                    }
                }
            }
        }
        return null
    }

    fun selectUserVariant(userId: String, variantIndex: Int): ConversationTree {
        val index = userNodes.indexOfFirst { it.userId == userId }
        if (index < 0) return this
        val node = userNodes[index]
        val target = node.variants.getOrNull(variantIndex) ?: node.variants.lastOrNull() ?: return this
        return copy(
            userNodes = userNodes.toMutableList().apply {
                this[index] = node.copy(
                    selectIndex = node.variants.indexOfFirst { it.message.id == target.message.id }.coerceAtLeast(0),
                )
            },
            selectedUserIndex = index,
        )
    }

    fun selectAssistantVariant(userGroupId: String, assistantGroupId: String, index: Int): ConversationTree {
        val nodeIndex = userNodes.indexOfFirst { user ->
            val current = user.currentVariant ?: return@indexOfFirst false
            current.message.id.toString() == userGroupId ||
                (current.message.variantGroupId ?: user.groupId) == userGroupId
        }
        if (nodeIndex < 0) return this
        val node = userNodes[nodeIndex]
        val current = node.currentVariant ?: return this

        val updatedVariant = current.copy(
            assistantNodes = current.assistantNodes.map { assistant ->
                if (assistant.groupId == assistantGroupId) assistant.selectVariant(index) else assistant
            },
        )
        return copy(
            userNodes = userNodes.toMutableList().apply {
                this[nodeIndex] = node.copy(
                    variants = node.variants.toMutableList().apply {
                        this[node.selectIndex] = updatedVariant
                    },
                )
            },
        )
    }

    /** 重试当前用户版本下的最后一条助手回复，返回新树 + 需持久化的新消息。 */
    fun retryLastAssistant(): TreeUpdate {
        val user = selectedUserNode ?: return TreeUpdate(this, null, null)
        val variant = user.currentVariant ?: return TreeUpdate(this, null, null)
        val userMsg = variant.message
        val userGroupId = userMsg.variantGroupId ?: user.groupId

        if (variant.assistantNodes.isEmpty()) {
            val groupId = "asg_" + Uuid.random()
            val newMsg = UIMessage(
                role = MessageRole.ASSISTANT,
                content = "",
                createdAt = System.currentTimeMillis(),
                variantGroupId = groupId,
                variantIndex = 0,
                variantCount = 1,
                parentGroupId = userMsg.id.toString(),
            )
            val updatedVariant = variant.copy(
                assistantNodes = listOf(AssistantNode(groupId, listOf(newMsg), 0)),
            )
            return TreeUpdate(
                tree = replaceCurrentVariant(updatedVariant),
                newMessage = newMsg,
                changedGroupId = groupId,
            )
        }

        val last = variant.assistantNodes.last()
        val newIndex = last.variants.size
        val newCount = newIndex + 1
        val newMsg = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "",
            createdAt = System.currentTimeMillis(),
            variantGroupId = last.groupId,
            variantIndex = newIndex,
            variantCount = newCount,
            parentGroupId = userMsg.id.toString(),
        )
        val updatedAssistant = last.copy(
            variants = last.variants.map { it.copy(variantCount = newCount) } + newMsg,
            selectIndex = newIndex,
        )
        val updatedVariant = variant.copy(
            assistantNodes = variant.assistantNodes.dropLast(1) + updatedAssistant,
        )
        return TreeUpdate(
            tree = replaceCurrentVariant(updatedVariant),
            newMessage = newMsg,
            changedGroupId = last.groupId,
        )
    }

    /** 编辑用户消息：保留旧版本，新建用户版本并放一个空助手占位组。 */
    fun editUserMessage(messageId: Uuid, newContent: String): EditUpdate? {
        val nodeIndex = userNodes.indexOfFirst { user ->
            user.variants.any { it.message.id == messageId && it.message.role == MessageRole.USER }
        }
        if (nodeIndex < 0) return null
        val user = userNodes[nodeIndex]

        val userGroupId = user.currentVariant?.message?.variantGroupId ?: user.groupId
        val newIndex = user.variants.size
        val newCount = newIndex + 1
        val now = System.currentTimeMillis()
        val newUserMsg = UIMessage(
            role = MessageRole.USER,
            content = newContent,
            createdAt = now,
            variantGroupId = userGroupId,
            variantIndex = newIndex,
            variantCount = newCount,
        )
        val placeholderGroupId = "asg_" + Uuid.random()
        val placeholder = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "",
            createdAt = now + 1,
            variantGroupId = placeholderGroupId,
            variantIndex = 0,
            variantCount = 1,
            parentGroupId = newUserMsg.id.toString(),
        )
        val newVariant = UserVariant(
            message = newUserMsg,
            assistantNodes = listOf(AssistantNode(placeholderGroupId, listOf(placeholder), 0)),
        )
        val updatedUser = user.copy(
            variants = user.variants.map { it.copy(message = it.message.copy(variantCount = newCount)) } + newVariant,
            selectIndex = newIndex,
        )
        val newNodes = userNodes.toMutableList().apply { this[nodeIndex] = updatedUser }
        return EditUpdate(
            tree = copy(userNodes = newNodes, selectedUserIndex = nodeIndex),
            newUserMessage = newUserMsg,
            newAssistantPlaceholder = placeholder,
        )
    }

    private fun replaceCurrentVariant(updated: UserVariant): ConversationTree {
        val user = selectedUserNode ?: return this
        return copy(
            userNodes = userNodes.toMutableList().apply {
                val idx = indexOfFirst { it.userId == user.userId }
                if (idx >= 0) {
                    this[idx] = user.copy(
                        variants = user.variants.toMutableList().apply {
                            this[user.selectIndex] = updated
                        },
                    )
                }
            },
        )
    }

    data class BranchInfo(
        val groupId: String,
        val parentGroupId: String?,
        val selectIndex: Int,
        val branchCount: Int,
    )

    data class TreeUpdate(
        val tree: ConversationTree,
        val newMessage: UIMessage?,
        val changedGroupId: String?,
    )

    data class EditUpdate(
        val tree: ConversationTree,
        val newUserMessage: UIMessage?,
        val newAssistantPlaceholder: UIMessage?,
    )

    /** 用户提问组：同一 variantGroupId 的多个用户版本共享“提问位置”。 */
    data class UserNode(
        val userId: String,
        val groupId: String,
        val variants: List<UserVariant>,
        val selectIndex: Int = variants.lastIndex.coerceAtLeast(0),
    ) {
        val currentVariant: UserVariant?
            get() = variants.getOrNull(selectIndex)

        fun selectVariant(index: Int): UserNode =
            copy(selectIndex = index.coerceIn(0, variants.lastIndex))
    }

    /** 用户版本：一条用户消息 + 它自己的助手回复组。 */
    data class UserVariant(
        val message: UIMessage,
        val assistantNodes: List<AssistantNode> = emptyList(),
    )

    /** 助手回复组：同一回复位置的多个重试变体。 */
    data class AssistantNode(
        val groupId: String,
        val variants: List<UIMessage>,
        val selectIndex: Int = variants.lastIndex.coerceAtLeast(0),
    ) {
        val currentVariant: UIMessage?
            get() = variants.getOrNull(selectIndex)

        fun selectVariant(index: Int): AssistantNode =
            copy(selectIndex = index.coerceIn(0, variants.lastIndex))
    }

    companion object {
        /**
         * 从扁平消息列表重建对话树。
         *
         * 兼容两种数据：
         * 1. 新数据：assistant 消息带 [UIMessage.parentGroupId] = 用户版本消息 ID，精确挂载。
         * 2. 旧数据：parentGroupId 为用户组 ID，挂到该组最后一个用户版本。
         */
        fun build(messages: List<UIMessage>, previous: ConversationTree? = null): ConversationTree {
            if (messages.isEmpty()) return ConversationTree()

            val groups = LinkedHashMap<String, MutableList<UIMessage>>()
            val groupOrder = LinkedHashSet<String>()
            messages.forEach { msg ->
                val gid = msg.variantGroupId ?: msg.id.toString()
                groups.getOrPut(gid) { mutableListOf() }.add(msg)
                groupOrder.add(gid)
            }

            // v1.0.63: 归一化分支索引/计数，修复历史数据中同一组重复 variantIndex。
            groups.forEach { (_, msgs) ->
                val sorted = msgs.sortedWith(compareBy({ it.variantIndex }, { it.createdAt }))
                val normalized = sorted.mapIndexed { idx, msg ->
                    msg.copy(variantIndex = idx, variantCount = sorted.size)
                }
                msgs.clear()
                msgs.addAll(normalized)
            }

            val userNodes = mutableListOf<UserNode>()
            val userNodeByGroup = HashMap<String, Int>()
            val userVariantById = HashMap<String, UserVariant>()
            var lastUserGroupId: String? = null

            groupOrder.forEach { gid ->
                val groupMsgs = groups.getValue(gid)
                val first = groupMsgs.first()
                when (first.role) {
                    MessageRole.USER -> {
                        val variants = groupMsgs.map { UserVariant(message = it) }
                        if (gid in userNodeByGroup) {
                            val idx = userNodeByGroup.getValue(gid)
                            val existing = userNodes[idx]
                            val merged = mergeUserVariants(existing.variants, variants)
                            userNodes[idx] = existing.copy(variants = merged)
                        } else {
                            userNodes += UserNode(
                                userId = "usn_" + Uuid.random(),
                                groupId = gid,
                                variants = variants,
                                selectIndex = variants.lastIndex.coerceAtLeast(0),
                            )
                            userNodeByGroup[gid] = userNodes.lastIndex
                        }
                        userNodes[userNodeByGroup.getValue(gid)].variants.forEach { variant ->
                            userVariantById[variant.message.id.toString()] = variant
                        }
                        lastUserGroupId = gid
                    }
                    MessageRole.ASSISTANT -> {
                        val parentRef = groupMsgs.firstNotNullOfOrNull { it.parentGroupId }
                        val targetVariant = parentRef?.let { ref ->
                            userVariantById[ref] ?: userNodeByGroup[ref]?.let { idx -> userNodes[idx].variants.lastOrNull() }
                        } ?: lastUserGroupId?.let { gidRef ->
                            userNodeByGroup[gidRef]?.let { idx ->
                                val node = userNodes[idx]
                                // v1.x 修复: 无 parentGroupId 的旧数据助手消息按创建时间归属 ——
                                // 挂在"最后一个早于该助手消息创建的用户版本"上。
                                // 此前直接取最后一个版本,用户编辑消息(新建版本 V1)后,
                                // 旧助手消息被挂到 V1,与占位/新回复并列,表现为"助手消息分裂成多条"。
                                val groupCreatedAt = groupMsgs.first().createdAt
                                node.variants.lastOrNull { it.message.createdAt <= groupCreatedAt }
                                    ?: node.variants.lastOrNull()
                            }
                        }
                        if (targetVariant == null) return@forEach
                        val messageId = targetVariant.message.id.toString()
                        replaceUserVariant(userNodes, userVariantById, messageId) { variant ->
                            val existingIdx = variant.assistantNodes.indexOfFirst { it.groupId == gid }
                            if (existingIdx >= 0) {
                                variant.copy(
                                    assistantNodes = variant.assistantNodes.toMutableList().apply {
                                        this[existingIdx] = this[existingIdx].copy(
                                            variants = mergeVariants(this[existingIdx].variants, groupMsgs),
                                        )
                                    },
                                )
                            } else {
                                variant.copy(
                                    assistantNodes = variant.assistantNodes + AssistantNode(
                                        groupId = gid,
                                        variants = groupMsgs,
                                        selectIndex = groupMsgs.lastIndex.coerceAtLeast(0),
                                    ),
                                )
                            }
                        }
                    }
                    else -> {
                        val targetVariant = lastUserGroupId?.let { gidRef ->
                            userNodeByGroup[gidRef]?.let { idx -> userNodes[idx].variants.lastOrNull() }
                        } ?: return@forEach
                        val messageId = targetVariant.message.id.toString()
                        replaceUserVariant(userNodes, userVariantById, messageId) { variant ->
                            variant.copy(
                                assistantNodes = variant.assistantNodes + AssistantNode(
                                    groupId = gid,
                                    variants = groupMsgs,
                                    selectIndex = groupMsgs.lastIndex.coerceAtLeast(0),
                                ),
                            )
                        }
                    }
                }
            }

            return restoreSelection(
                ConversationTree(userNodes = userNodes, selectedUserIndex = userNodes.lastIndex.coerceAtLeast(0)),
                previous,
            )
        }

        private fun replaceUserVariant(
            userNodes: MutableList<UserNode>,
            userVariantById: MutableMap<String, UserVariant>,
            messageId: String,
            transform: (UserVariant) -> UserVariant,
        ) {
            for (i in userNodes.indices) {
                val node = userNodes[i]
                val idx = node.variants.indexOfFirst { it.message.id.toString() == messageId }
                if (idx >= 0) {
                    val newVariant = transform(node.variants[idx])
                    userNodes[i] = node.copy(
                        variants = node.variants.toMutableList().apply { this[idx] = newVariant },
                    )
                    userVariantById[messageId] = newVariant
                    return
                }
            }
        }

        private fun mergeUserVariants(
            existing: List<UserVariant>,
            incoming: List<UserVariant>,
        ): List<UserVariant> {
            val merged = LinkedHashMap<String, UserVariant>()
            (existing + incoming).forEach { variant ->
                merged[variant.message.id.toString()] = variant
            }
            return merged.values.sortedWith(
                compareBy({ it.message.variantIndex }, { it.message.createdAt }),
            )
        }

        private fun mergeVariants(existing: List<UIMessage>, incoming: List<UIMessage>): List<UIMessage> {
            val merged = LinkedHashMap<String, UIMessage>()
            (existing + incoming).forEach { msg -> merged[msg.id.toString()] = msg }
            return merged.values.sortedWith(compareBy({ it.variantIndex }, { it.createdAt }))
        }
    }
}

/** 重建树时合并旧树全部分支与当前扁平显示，保证新消息不丢、旧重试/编辑分支保留。 */
fun mergeRebuildMessages(tree: ConversationTree, current: List<UIMessage>): List<UIMessage> {
    val flat = tree.allFlatMessages
    if (flat.isEmpty()) return current
    val merged = linkedMapOf<String, UIMessage>()
    flat.forEach { merged[it.id.toString()] = it }
    current.forEach { merged[it.id.toString()] = it }
    return merged.values.toList()
}

private fun restoreSelection(tree: ConversationTree, previous: ConversationTree?): ConversationTree {
    if (previous == null || previous.userNodes.isEmpty()) return tree
    val previousUserGroup = previous.selectedUserNode?.let {
        it.currentVariant?.message?.variantGroupId ?: it.groupId
    }
    // 新追加了一轮用户消息时,把选中层切到最新一轮,保证 retry/续聊都指向新消息;
    // 同一提问组内切换版本时仍保留之前的选中索引。
    val selectedUserIndex = if (tree.userNodes.size > previous.userNodes.size) {
        tree.userNodes.lastIndex
    } else {
        tree.userNodes.indexOfFirst { node ->
            (node.currentVariant?.message?.variantGroupId ?: node.groupId) == previousUserGroup
        }.coerceAtLeast(0)
    }
    val restoredNodes = tree.userNodes.map { user ->
        val prevUser = previous.userNodes.firstOrNull { prev ->
            (prev.currentVariant?.message?.variantGroupId ?: prev.groupId) ==
                (user.currentVariant?.message?.variantGroupId ?: user.groupId)
        }
        val userSelect = prevUser?.selectIndex?.let { index ->
            if (user.variants.isEmpty()) 0 else index.coerceIn(0, user.variants.lastIndex)
        } ?: user.selectIndex
        val selectedVariant = user.variants.getOrNull(userSelect)
        val prevVariant = prevUser?.variants?.getOrNull(prevUser.selectIndex)
        val assistants = selectedVariant?.assistantNodes?.map { assistant ->
            val prevAssistant = prevVariant?.assistantNodes?.firstOrNull { it.groupId == assistant.groupId }
            val assistantSelect = prevAssistant?.selectIndex?.let { index ->
                if (assistant.variants.isEmpty()) 0 else index.coerceIn(0, assistant.variants.lastIndex)
            } ?: assistant.selectIndex
            assistant.copy(selectIndex = assistantSelect)
        } ?: selectedVariant?.assistantNodes ?: emptyList()
        val newVariants = user.variants.toMutableList().apply {
            if (selectedVariant != null && userSelect in indices) {
                this[userSelect] = selectedVariant.copy(assistantNodes = assistants)
            }
        }
        user.copy(selectIndex = userSelect, variants = newVariants)
    }
    return tree.copy(userNodes = restoredNodes, selectedUserIndex = selectedUserIndex)
}
