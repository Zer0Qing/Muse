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

    /**
     * v1.0.92: 全量 branchInfo 索引 — 供 UI 高频渲染路径 O(1) 查询。
     *
     * [branchInfoFor] 单次查询是整树遍历;聊天列表每条消息每帧调用一次时
     * 总开销 O(n²),长会话流式期间每 50ms 重算,是卡顿的主要来源之一。
     *
     * v2.4.6 重构:助手的变体切换器改为「挂在整轮回复的最后一条消息上」。
     * 旧实现把同一个 [BranchInfo] 打到回复组内**每一条**变体消息上,而一条
     * 助手变体可能包含多条消息(思考/工具/正文),导致每条消息各自渲染一个
     * 切换器 —— 多步回复时箭头出现在第一条下、且重试后内容叠加错位。
     * 现在只给"这一轮回复中最后一条被渲染的助手消息"挂助手 [BranchInfo];
     * 用户版本切换器仍挂在用户消息上(语义不变)。
     */
    fun buildBranchInfoIndex(): Map<String, BranchInfo> {
        val map = HashMap<String, BranchInfo>()
        userNodes.forEach { user ->
            val userGroupId = user.currentVariant?.message?.variantGroupId ?: user.groupId
            user.variants.forEach { variant ->
                val userParentId = variant.message.id.toString()
                // 用户版本切换器:挂在用户消息上(编辑/重试产生的提问版本)
                map.putIfAbsent(
                    userParentId,
                    BranchInfo(
                        groupId = userGroupId,
                        parentGroupId = null,
                        selectIndex = user.selectIndex,
                        branchCount = user.variants.size,
                    ),
                )
                // 助手变体切换器:只挂在"本轮回复的最后一条消息"上。
                // 该轮回复 = 此 user variant 下全部 assistantNodes;
                // 变体数取"回复节点"(含非工具消息的节点)的变体数 —— 纯工具节点不是可重试的"回复"。
                // 末条优先取回复节点的当前变体(它一定会被渲染;工具节点可能被聚合为组卡),
                // 无回复节点时退回最后一个节点的当前变体。
                val replyNode = variant.assistantNodes.lastOrNull { node ->
                    node.variants.any { it.toolCallInfo == null }
                }
                val lastNode = variant.assistantNodes.lastOrNull()
                val anchorNode = replyNode ?: lastNode ?: return@forEach
                val lastMsg = anchorNode.currentVariant ?: return@forEach
                map.putIfAbsent(
                    lastMsg.id.toString(),
                    BranchInfo(
                        groupId = anchorNode.groupId,
                        parentGroupId = userParentId,
                        selectIndex = anchorNode.selectIndex,
                        branchCount = anchorNode.variants.size,
                    ),
                )
            }
        }
        return map
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

    /**
     * v1.0.80 (T-4): 从树中移除单条消息(删除消息时同步树)。
     *
     * 删除消息后若树不更新,切回会话时 rebuildConversationTree 会通过
     * mergeRebuildMessages 把旧树里的被删消息合并回来,导致已删消息"复活"
     * (用户反馈:删了重进还在,旧消息突然变最新)。
     * 此方法把该消息从所有用户变体/助手变体中剔除,并重排 variantIndex/variantCount。
     */
    fun removeMessage(messageId: Uuid): ConversationTree {
        var changed = false
        val newNodes = userNodes.mapNotNull { user ->
            val remainingVariants = user.variants.mapNotNull { variant ->
                if (variant.message.id == messageId) {
                    changed = true
                    null
                } else {
                    val newAssistantNodes = variant.assistantNodes.mapNotNull { node ->
                        val remaining = node.variants.filterNot { it.id == messageId }
                        if (remaining.size != node.variants.size) changed = true
                        if (remaining.isEmpty()) {
                            null
                        } else {
                            val reindexedAssistants = remaining.mapIndexed { idx, msg ->
                                if (msg.variantIndex != idx || msg.variantCount != remaining.size) {
                                    changed = true
                                    msg.copy(variantIndex = idx, variantCount = remaining.size)
                                } else {
                                    msg
                                }
                            }
                            node.copy(
                                variants = reindexedAssistants,
                                selectIndex = node.selectIndex.coerceIn(0, reindexedAssistants.lastIndex),
                            )
                        }
                    }
                    if (newAssistantNodes.size != variant.assistantNodes.size) changed = true
                    variant.copy(assistantNodes = newAssistantNodes)
                }
            }
            if (remainingVariants.isEmpty()) {
                changed = true
                null
            } else {
                val reindexed = remainingVariants.mapIndexed { idx, variant ->
                    if (variant.message.variantIndex != idx || variant.message.variantCount != remainingVariants.size) {
                        changed = true
                        variant.copy(
                            message = variant.message.copy(
                                variantIndex = idx,
                                variantCount = remainingVariants.size,
                            ),
                        )
                    } else {
                        variant
                    }
                }
                user.copy(
                    variants = reindexed,
                    selectIndex = user.selectIndex.coerceIn(0, reindexed.lastIndex),
                )
            }
        }
        if (!changed) return this
        if (newNodes.isEmpty()) return ConversationTree()
        val selected = selectedUserIndex.coerceIn(0, newNodes.lastIndex)
        return copy(userNodes = newNodes, selectedUserIndex = selected)
    }

    /** 重试当前用户版本下的最后一条助手回复，返回新树 + 需持久化的新消息。 */
    fun retryLastAssistant(): TreeUpdate {
        val user = selectedUserNode ?: return TreeUpdate(this, null, null)
        val variant = user.currentVariant ?: return TreeUpdate(this, null, null)
        val userMsg = variant.message
        val userGroupId = userMsg.variantGroupId ?: user.groupId

        if (variant.assistantNodes.isEmpty()) {
            val groupId = "asg_" + Uuid.random()
            // v1.0.85 (T-1): 重发新消息的 createdAt 用"用户消息+1ms"而非当前时间 —
            // 此前用 System.currentTimeMillis(),重发后消息时序跳到末尾,
            // ConversationTree 把它挂到最后一个用户消息下(第一句回复变最后一句)。
            val newMsg = UIMessage(
                role = MessageRole.ASSISTANT,
                content = "",
                createdAt = userMsg.createdAt + 1,
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

        // v1.0.92: 只认「回复节点」— 存在非工具消息变体的节点。工具展示消息
        // (toolCallInfo != null) 是独立节点,不是可重试的「回复」;旧实现直接取 last(),
        // 有工具调用时会把新变体挂进「工具组」,工具卡变成「工具/回复」的 1/2 变体。
        val replyIndex = variant.assistantNodes.indexOfLast { node ->
            node.variants.any { it.toolCallInfo == null }
        }
        if (replyIndex < 0) {
            // 本轮只有工具消息(生成中断/被停止):还没有可重试的回复,
            // 在末尾新增一个全新回复组,重试从「空回复」重新开始。
            val groupId = "asg_" + Uuid.random()
            val anchorAt = variant.assistantNodes.last().currentVariant?.createdAt
                ?: userMsg.createdAt
            val newMsg = UIMessage(
                role = MessageRole.ASSISTANT,
                content = "",
                createdAt = anchorAt + 1,
                variantGroupId = groupId,
                variantIndex = 0,
                variantCount = 1,
                parentGroupId = userMsg.id.toString(),
            )
            val updatedVariant = variant.copy(
                assistantNodes = variant.assistantNodes + AssistantNode(groupId, listOf(newMsg), 0),
            )
            return TreeUpdate(
                tree = replaceCurrentVariant(updatedVariant),
                newMessage = newMsg,
                changedGroupId = groupId,
            )
        }

        val last = variant.assistantNodes[replyIndex]
        val newIndex = last.variants.size
        val newCount = newIndex + 1
        // v1.0.85 (T-1): 新变体继承原组第一条消息的 createdAt(保持位置稳定),
        // 而非当前时间 — 否则重发的回复时序跳到末尾、显示位置错乱。
        val baseCreatedAt = last.variants.firstOrNull()?.createdAt ?: last.currentVariant?.createdAt ?: userMsg.createdAt
        val newMsg = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "",
            createdAt = baseCreatedAt + newIndex,
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
            // v1.0.92: 按下标替换被重试的回复节点。修复前用 dropLast(1) + 副本,
            // 仅在 last 恰好是列表末项时等价;工具节点排在回复节点之后时,
            // 会把更新副本追加到末尾、原回复组保持旧状态(变体丢失)。
            assistantNodes = variant.assistantNodes.toMutableList().apply {
                this[replyIndex] = updatedAssistant
            },
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

    /**
     * v2.5.3 (P3-1): 编辑助手消息 —— 一律新增版本节点，旧版本永久保留在分支里。
     *
     * 与 [retryLastAssistant] 的差别：重试是“重新生成一个空回复变体”，编辑是“用用户写好的内容
     * 作为新回复变体”，两者都遵循“不覆盖、只追加”的多分支原则，旧回复永远可回溯。
     *
     * @return null 表示未找到目标助手消息；否则返回新树 + 新消息（需持久化）。
     */
    fun editAssistantMessage(messageId: Uuid, newContent: String): TreeUpdate? {
        val nodeIndex =
            userNodes.indexOfFirst { user ->
                user.variants.any { v -> v.assistantNodes.any { node -> node.variants.any { it.id == messageId } } }
            }
        val user = userNodes.getOrNull(nodeIndex)
        val variant = user?.currentVariant
        val replyIndex = variant?.assistantNodes?.indexOfFirst { node -> node.variants.any { it.id == messageId } } ?: -1
        val node = variant?.assistantNodes?.getOrNull(replyIndex)
        val original = node?.variants?.firstOrNull { it.id == messageId }
        if (variant == null || node == null || original == null) return null
        val newIndex = node.variants.size
        val newCount = newIndex + 1
        // 新版本继承原版 createdAt 基准 + index，保持位置稳定（与重生成同口径）。
        val baseCreatedAt = node.variants.firstOrNull()?.createdAt ?: original.createdAt
        val edited =
            original.copy(
                id = Uuid.random(),
                content = newContent,
                reasoning = null,
                createdAt = baseCreatedAt + newIndex,
                variantGroupId = node.groupId,
                variantIndex = newIndex,
                variantCount = newCount,
            )
        val updatedNode =
            node.copy(
                variants = node.variants.map { it.copy(variantCount = newCount) } + edited,
                selectIndex = newIndex,
            )
        val updatedVariant =
            variant.copy(
                assistantNodes = variant.assistantNodes.toMutableList().apply { this[replyIndex] = updatedNode },
            )
        return TreeUpdate(
            tree = replaceCurrentVariant(updatedVariant),
            newMessage = edited,
            changedGroupId = node.groupId,
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

        fun selectVariant(index: Int): UserNode = copy(selectIndex = index.coerceIn(0, variants.lastIndex))
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

        fun selectVariant(index: Int): AssistantNode = copy(selectIndex = index.coerceIn(0, variants.lastIndex))
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
            // 树重建不能信任调用方当前 List 的插入顺序：它可能来自旧快照合并、
            // 分页回灌或流式异步更新。统一按持久化顺序先排一次，防止旧消息被挂到最前。
            val orderedMessages = orderConversationMessages(messages)

            val groups = LinkedHashMap<String, MutableList<UIMessage>>()
            val groupOrder = LinkedHashSet<String>()
            orderedMessages.forEach { msg ->
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

        private fun mergeUserVariants(existing: List<UserVariant>, incoming: List<UserVariant>): List<UserVariant> {
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

/**
 * 重建树时合并旧树全部分支与当前扁平显示，保证新消息不丢、旧重试/编辑分支保留。
 *
 * @param summarizedIds v2.3.2 (D): 已被上下文摘要覆盖的消息 id —— 这些原文不再从旧树合并回来，
 *        否则"摘要 + 原文"会同时留在上下文里(token 双计)，压缩等于白做。
 *        (调用方从 `io.zer0.muse.transformer.CompressionSummaryStore` 取;缺省空集 = 旧行为。)
 */
fun mergeRebuildMessages(tree: ConversationTree, current: List<UIMessage>, summarizedIds: Set<String> = emptySet()): List<UIMessage> {
    // Snapshot 只保存分支选择。SnapshotStore 为了还原树形结构会构造 createdAt=0、
    // content 为空、随机 id 的虚拟用户节点；这些节点绝不能混入真实消息，否则会被
    // 排到列表最前面，表现为“旧消息跑到最前面”。
    val persistedTreeMessages =
        tree.allFlatMessages.filter { it.createdAt > 0L && it.id.toString() !in summarizedIds }
    if (persistedTreeMessages.isEmpty()) return orderConversationMessages(current)
    val merged = linkedMapOf<String, UIMessage>()
    // 旧树只负责补充当前列表缺失的分支；同一 id 的内容必须由当前列表胜出。
    // 否则流式最终回复会被较早的树快照覆盖，表现为正文闪现后消失。
    val source = persistedTreeMessages + current
    source.forEach { message -> merged[message.id.toString()] = message }
    return orderConversationMessages(merged.values.toList())
}

/** 会话消息唯一的内存排序规则，必须与 MessageDao/MessageProjector 保持一致。 */
fun orderConversationMessages(messages: List<UIMessage>): List<UIMessage> {
    fun UIMessage.stableOrder(): Long? = when {
        commitSeq > 0L -> commitSeq
        seq > 0L -> seq
        else -> null
    }

    // 旧导入/旧快照可能完全没有 seq。此时 createdAt 也可能不可信，
    // 不能无条件重排，数据库传入顺序才是唯一可用顺序。
    if (messages.none { it.stableOrder() != null }) return messages

    // v2.3.2: 改成"先给每条消息算一个键,再单键排序" —— 原实现逐对比较,对"一边有序一边无序"
    // 的一对退回 createdAt,三个消息就能成环(实测 A(seq=10,createdAt=3000)、B(seq=0,createdAt=1000)、
    // C(seq=20,createdAt=100) 会同时得出 A>C、A<B、B<C),TimSort 下可能抛
    // "Comparison method violates its general contract!" 或给出不稳定顺序。
    // 标尺:有序消息按 seq 排名占偶数位(seq 优先,与既有一致);
    //       无序消息按"createdAt 早于它的有序消息条数"占奇数位(仍按时间插回正确时间段)。
    // 键在排序前一次算好,因此比较器是严格全序(传递、反对称、自反),不再依赖比较顺序。
    val ordered = messages.filter { it.stableOrder() != null }.sortedBy { it.stableOrder() }
    val unordered = messages.filter { it.stableOrder() == null }
    val keys = HashMap<String, Long>(messages.size)
    ordered.forEachIndexed { index, message -> keys[message.id.toString()] = index * 2L }
    val orderedTimes = ordered.map { it.createdAt }.sorted()
    unordered.forEach { message ->
        val found = orderedTimes.binarySearch { it.compareTo(message.createdAt) }
        val earlierCount = if (found >= 0) found else -(found + 1)
        keys[message.id.toString()] = earlierCount * 2L - 1
    }
    return messages.sortedWith(
        compareBy({ keys[it.id.toString()] ?: Long.MAX_VALUE }, { it.createdAt }, { it.id.toString() }),
    )
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
