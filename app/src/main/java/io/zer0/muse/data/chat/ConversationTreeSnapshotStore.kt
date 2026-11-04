package io.zer0.muse.data.chat

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.AtomicFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/**
 * P0 对话树选择快照。
 *
 * 只保存“用户组/助手组 + 选中变体索引”，不保存消息内容；
 * 重启后由 [ConversationTree.build(messages, previous)] 恢复上次分支选择。
 */
class ConversationTreeSnapshotStore(private val context: Context) {

    @Serializable
    data class SnapshotAssistant(
        val groupId: String,
        val selectIndex: Int,
    )

    @Serializable
    data class SnapshotVariant(
        val messageId: String,
        val variantIndex: Int,
        val assistants: List<SnapshotAssistant> = emptyList(),
    )

    @Serializable
    data class SnapshotUser(
        val groupId: String,
        val selectIndex: Int,
        val assistants: List<SnapshotAssistant> = emptyList(),
        val variants: List<SnapshotVariant> = emptyList(),
    )

    @Serializable
    data class TreeSnapshot(
        val selectedUserIndex: Int,
        val users: List<SnapshotUser> = emptyList(),
    )

    private val dir: File
        get() = File(context.filesDir, "conversation_tree_snapshots")

    suspend fun save(sessionId: String, tree: ConversationTree) = withContext(Dispatchers.IO) {
        runCatching {
            dir.mkdirs()
            val snapshot = TreeSnapshot(
                selectedUserIndex = tree.selectedUserIndex,
                users = tree.userNodes.map { user ->
                    SnapshotUser(
                        groupId = user.groupId,
                        selectIndex = user.selectIndex,
                        assistants = user.currentVariant?.assistantNodes?.map { assistant ->
                            SnapshotAssistant(assistant.groupId, assistant.selectIndex)
                        } ?: emptyList(),
                        variants = user.variants.map { variant ->
                            SnapshotVariant(
                                messageId = variant.message.id.toString(),
                                variantIndex = variant.message.variantIndex,
                                assistants = variant.assistantNodes.map { assistant ->
                                    SnapshotAssistant(assistant.groupId, assistant.selectIndex)
                                },
                            )
                        },
                    )
                },
            )
            AtomicFileStore.writeText(
                File(dir, fileName(sessionId)),
                AppJson.encodeToString(TreeSnapshot.serializer(), snapshot),
            )
        }.onFailure { e ->
            Logger.w("TreeSnapshotStore", "保存对话树选择快照失败: ${e.message}", e)
        }
    }

    suspend fun load(sessionId: String): ConversationTree? = withContext(Dispatchers.IO) {
        val file = File(dir, fileName(sessionId))
        if (!file.exists()) return@withContext null
        runCatching {
            val snapshot = AppJson.decodeFromString(TreeSnapshot.serializer(), file.readText())
            snapshot.toTree()
        }.onFailure { e ->
            Logger.w("TreeSnapshotStore", "读取对话树选择快照失败: ${e.message}", e)
            AtomicFileStore.quarantine(file, "snapshot_parse")
        }.getOrNull()
    }

    /**
     * 删除某个会话的对话树快照。
     *
     * 这条以前是 `File(...).delete()` 一句话：返回值没人接、失败也没痕迹。
     * 而它是**状态转换**（会话删了，快照就该跟着没）—— 删不掉的后果是
     * 同名会话重建时 `load` 会读回**上一个会话**的分支选择：用户看到的
     * "新会话怎么自带了一堆选中状态"完全无从解释。
     *
     * @return 快照现在确实不存在了（本来就没有也算，幂等）
     */
    suspend fun delete(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(dir, fileName(sessionId))
        if (!file.exists()) return@withContext true
        runCatching { file.delete() }
            .onFailure { e ->
                Logger.w("TreeSnapshotStore", "删除对话树快照异常 sessionId=$sessionId: ${e.message}", e)
            }
        val gone = !file.exists()
        if (!gone) {
            Logger.w(
                "TreeSnapshotStore",
                "对话树快照没被删掉 sessionId=$sessionId path=${file.absolutePath}（同名会话重建会读回旧选择）",
            )
        }
        gone
    }

    private fun TreeSnapshot.toTree(): ConversationTree {
        val userNodes = users.mapIndexed { index, user ->
            val savedVariants = user.variants
            val variants =
                if (savedVariants.isNotEmpty()) {
                    savedVariants.map { variant ->
                        ConversationTree.UserVariant(
                            message = io.zer0.ai.core.UIMessage(
                                id = runCatching { kotlin.uuid.Uuid.parse(variant.messageId) }
                                    .getOrElse { kotlin.uuid.Uuid.random() },
                                role = io.zer0.ai.core.MessageRole.USER,
                                content = "",
                                createdAt = 0,
                                variantGroupId = user.groupId,
                                variantIndex = variant.variantIndex,
                                variantCount = savedVariants.size,
                            ),
                            assistantNodes = variant.assistants.map { assistant ->
                                ConversationTree.AssistantNode(
                                    groupId = assistant.groupId,
                                    variants = emptyList(),
                                    selectIndex = assistant.selectIndex,
                                )
                            },
                        )
                    }
                } else {
                    listOf(
                        ConversationTree.UserVariant(
                            message = io.zer0.ai.core.UIMessage(
                                id = kotlin.uuid.Uuid.random(),
                                role = io.zer0.ai.core.MessageRole.USER,
                                content = "",
                                createdAt = 0,
                                variantGroupId = user.groupId,
                                variantIndex = 0,
                                variantCount = 1,
                            ),
                            assistantNodes = user.assistants.map { assistant ->
                                ConversationTree.AssistantNode(
                                    groupId = assistant.groupId,
                                    variants = emptyList(),
                                    selectIndex = assistant.selectIndex,
                                )
                            },
                        ),
                    )
                }
            ConversationTree.UserNode(
                userId = "snapshot-$index",
                groupId = user.groupId,
                variants = variants,
                selectIndex = user.selectIndex.coerceIn(0, (variants.size - 1).coerceAtLeast(0)),
            )
        }
        return ConversationTree(
            userNodes = userNodes,
            selectedUserIndex = selectedUserIndex.coerceIn(0, (userNodes.size - 1).coerceAtLeast(0)),
        )
    }

    private fun fileName(sessionId: String): String = sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json"
}
