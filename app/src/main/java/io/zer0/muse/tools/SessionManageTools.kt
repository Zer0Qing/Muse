package io.zer0.muse.tools

import io.zer0.common.Logger
import io.zer0.muse.data.session.FolderRepository
import io.zer0.muse.data.session.SessionRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 会话管理工具集 —— 让助手能对用户的会话做日常整理。
 *
 * 此前删会话/改标题/移文件夹/归档只能用户在 UI 里长按操作，助手无法代劳。
 * 用户很自然的诉求："帮我把那些测试会话都删了" / "把这条会话改名成 XX"。
 *
 * 命名统一 `session_`，与 `scheduled_task_*`（定时任务）、`app_*`（应用控制）并列：
 *  - `session_list`        列出会话（SAFE，只读；先确认再删）
 *  - `session_rename`      改会话标题（NORMAL）
 *  - `session_move_folder` 移动会话到文件夹（NORMAL）
 *  - `session_archive`     归档/取消归档（NORMAL）
 *  - `session_delete`      删除会话（HIGH，走审批；不可撤销）
 *
 * 设计约束：
 *  - 不提供"批量删除"——破坏性操作逐条执行，让审批卡逐条出现，用户看得到删了什么；
 *  - 不提供"创建会话"——新会话由用户主动发起，助手不应自行创建对话；
 *  - 当前正在生成的会话不可删（由 SessionRepository 层判断）。
 */
class SessionManageTools(
    private val sessionRepository: SessionRepository,
    private val folderRepository: FolderRepository,
) {
    companion object {
        private const val TAG = "SessionManageTools"
    }

    fun register(registry: ToolRegistry) {
        registerList(registry)
        registerRename(registry)
        registerMoveToFolder(registry)
        registerArchive(registry)
        registerDelete(registry)
    }

    private fun registerList(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "session_list",
                description = "列出用户的会话（id、标题、最后更新时间）。" +
                    "用于删除/改名前确认会话 id；最多返回 50 条最近的。",
                parameters = mapOf(
                    "query" to "可选，按标题关键词过滤",
                ),
                required = emptySet(),
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            runBlocking {
                val query = args["query"]?.trim().orEmpty()
                val sessions = sessionRepository.observeAllSessions().first()
                    .filterNot { it.archived || it.deletedAt != null }
                    .sortedByDescending { it.updatedAt }
                    .take(50)
                val filtered = if (query.isBlank()) {
                    sessions
                } else {
                    sessions.filter { it.title.contains(query, ignoreCase = true) }
                }
                if (filtered.isEmpty()) {
                    "没有匹配的会话。"
                } else {
                    filtered.joinToString("\n") { s ->
                        "${s.id} | ${s.title}"
                    }
                }
            }
        }
    }

    private fun registerRename(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "session_rename",
                description = "修改一个会话的标题。",
                parameters = mapOf(
                    "session_id" to "必填，会话 id（可用 session_list 获取）",
                    "title" to "必填，新标题",
                ),
                required = setOf("session_id", "title"),
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args ->
            val id = args["session_id"]?.trim().orEmpty()
            val title = args["title"]?.trim().orEmpty()
            when {
                id.isBlank() -> errorJson("参数 session_id 缺失或为空")
                title.isBlank() -> errorJson("参数 title 缺失或为空")
                else -> runBlocking {
                    runCatching {
                        sessionRepository.renameSession(id, title)
                        Logger.i(TAG, "工具改会话标题: $id → $title")
                        okJson("已将会话标题改为「$title」。")
                    }.getOrElse { e ->
                        errorJson("改名失败: ${e.message}")
                    }
                }
            }
        }
    }

    private fun registerMoveToFolder(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "session_move_folder",
                description = "把一个会话移动到指定文件夹（folder_id 为空表示移出文件夹）。",
                parameters = mapOf(
                    "session_id" to "必填，会话 id",
                    "folder_id" to "目标文件夹 id；传空或省略表示移出到根层",
                ),
                required = setOf("session_id"),
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args ->
            val id = args["session_id"]?.trim().orEmpty()
            val folderId = args["folder_id"]?.trim().takeUnless { it.isNullOrBlank() }
            if (id.isBlank()) {
                errorJson("参数 session_id 缺失或为空")
            } else {
                runBlocking {
                    runCatching {
                        folderRepository.moveSessionToFolder(id, folderId)
                        Logger.i(TAG, "工具移会话: $id → folder=$folderId")
                        okJson("已移动会话 $id 到文件夹 ${folderId ?: "根层"}。")
                    }.getOrElse { e ->
                        errorJson("移动失败: ${e.message}")
                    }
                }
            }
        }
    }

    private fun registerArchive(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "session_archive",
                description = "归档或取消归档一个会话（归档后从主列表隐藏但不删除）。",
                parameters = mapOf(
                    "session_id" to "必填，会话 id",
                    "archive" to "true=归档，false=取消归档；默认 true",
                ),
                required = setOf("session_id"),
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args ->
            val id = args["session_id"]?.trim().orEmpty()
            val archive = args["archive"]?.trim()?.lowercase() != "false"
            if (id.isBlank()) {
                errorJson("参数 session_id 缺失或为空")
            } else {
                runBlocking {
                    runCatching {
                        sessionRepository.setArchived(id, archive)
                        Logger.i(TAG, "工具${if (archive) "归档" else "取消归档"}会话: $id")
                        okJson("已${if (archive) "归档" else "取消归档"}会话 $id。")
                    }.getOrElse { e ->
                        errorJson("归档失败: ${e.message}")
                    }
                }
            }
        }
    }

    private fun registerDelete(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "session_delete",
                description = "删除一个会话及其全部消息。不可撤销。" +
                    "执行前应先用 session_list 确认会话 id。",
                parameters = mapOf(
                    "session_id" to "必填，会话 id（可用 session_list 获取）",
                ),
                required = setOf("session_id"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val id = args["session_id"]?.trim().orEmpty()
            if (id.isBlank()) {
                errorJson("参数 session_id 缺失或为空")
            } else {
                runBlocking {
                    runCatching {
                        sessionRepository.deleteSession(id)
                        Logger.i(TAG, "工具删除会话: $id")
                        okJson("已删除会话 $id。")
                    }.getOrElse { e ->
                        errorJson("删除失败: ${e.message}")
                    }
                }
            }
        }
    }

    private fun okJson(message: String): String = buildJsonObject {
        put("ok", JsonPrimitive(true))
        put("message", JsonPrimitive(message))
    }.toString()

    private fun errorJson(message: String): String = buildJsonObject {
        put("ok", JsonPrimitive(false))
        put("error", JsonPrimitive(message))
    }.toString()
}

/** 注册器：走 Koin 注入，由 AutomationInitializer 统一引导。 */
class SessionManageToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val tools: SessionManageTools,
) {
    init {
        tools.register(toolRegistry)
    }
}
