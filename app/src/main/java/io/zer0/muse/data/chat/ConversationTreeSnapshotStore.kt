package io.zer0.muse.data.chat

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
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
    data class SnapshotUser(
        val groupId: String,
        val selectIndex: Int,
        val assistants: List<SnapshotAssistant> = emptyList(),
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
                        groupId = user.currentVariant?.message?.variantGroupId ?: user.groupId,
                        selectIndex = user.selectIndex,
                        assistants = user.currentVariant?.assistantNodes?.map { assistant ->
                            SnapshotAssistant(assistant.groupId, assistant.selectIndex)
                        } ?: emptyList(),
                    )
                },
            )
            File(dir, fileName(sessionId)).writeText(AppJson.encodeToString(TreeSnapshot.serializer(), snapshot))
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
        }.getOrNull()
    }

    suspend fun delete(sessionId: String) = withContext(Dispatchers.IO) {
        File(dir, fileName(sessionId)).delete()
    }

    private fun TreeSnapshot.toTree(): ConversationTree {
        val userNodes = users.mapIndexed { index, user ->
            ConversationTree.UserNode(
                userId = "snapshot-$index",
                groupId = user.groupId,
                variants = listOf(
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
                ),
                selectIndex = user.selectIndex.coerceIn(0, 0),
            )
        }
        return ConversationTree(
            userNodes = userNodes,
            selectedUserIndex = selectedUserIndex.coerceIn(0, (userNodes.size - 1).coerceAtLeast(0)),
        )
    }

    private fun fileName(sessionId: String): String =
        sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json"
}
