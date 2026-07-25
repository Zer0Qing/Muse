package io.zer0.muse.tools

import io.zer0.muse.workspace.WorkspaceManager
import kotlinx.coroutines.runBlocking

/**
 * P2-7: 工作区文件管理工具集(AI 可调用)。
 *
 * 注册 6 个工具到 [ToolRegistry],让 AI 能在工作区内进行文件管理操作:
 *  1. workspace_list   — 列出指定目录下的子项
 *  2. workspace_read   — 读取文本文件(限制 1MB)
 *  3. workspace_write  — 写入文本文件(限制 10MB,覆盖写入)
 *  4. workspace_delete — 删除文件或目录(目录递归)
 *  5. workspace_mkdir  — 创建目录(支持多级)
 *  6. workspace_move   — 移动/重命名
 *
 * 所有路径均为相对工作区根目录的路径(`/data/data/io.zer0.muse/files/workspace/`),
 * 由 [WorkspaceManager.resolveSafe] 严格校验,禁止 `..` 越权访问工作区外文件。
 *
 * 桥接说明:
 *  - [WorkspaceManager] 的方法是 suspend(强制在 IO 协程执行)
 *  - [ToolRegistry.ToolFn] 是同步签名 `(Map<String,String>) -> String`
 *  - 因此 [execute] 用 [runBlocking] 桥接 — 调用方(GenerationHandler.executeTool)
 *    已在 IO 协程中,阻塞 IO 线程等待文件 IO 完成,无死锁风险
 */
object WorkspaceTool {

    /** workspace_list 工具名。 */
    const val NAME_LIST = "workspace_list"
    /** workspace_read 工具名。 */
    const val NAME_READ = "workspace_read"
    /** workspace_write 工具名。 */
    const val NAME_WRITE = "workspace_write"
    /** workspace_delete 工具名。 */
    const val NAME_DELETE = "workspace_delete"
    /** workspace_mkdir 工具名。 */
    const val NAME_MKDIR = "workspace_mkdir"
    /** workspace_move 工具名。 */
    const val NAME_MOVE = "workspace_move"

    /** 全部工具名(便于同步到 [ToolRegistry.BUILT_IN_TOOL_IDS])。 */
    val ALL_TOOL_NAMES: List<String> = listOf(
        NAME_LIST, NAME_READ, NAME_WRITE, NAME_DELETE, NAME_MKDIR, NAME_MOVE,
    )

    /**
     * 全部工具定义(注册到 ToolRegistry)。
     */
    fun toolDefs(): List<ToolRegistry.ToolDef> = listOf(
        ToolRegistry.ToolDef(
            name = NAME_LIST,
            description = "列出工作区指定目录下的所有文件与子目录(目录在前,文件在后)。" +
                "路径为相对工作区根目录,空串或 \"/\" 表示根目录。" +
                "返回每行一条:name | type(dir/file) | size | lastModified",
            parameters = mapOf(
                "path" to "可选,相对工作区根目录的路径,默认根目录",
            ),
            required = emptySet(),
            category = "built-in",
            riskLevel = ToolRiskLevel.SAFE,
        ),
        ToolRegistry.ToolDef(
            name = NAME_READ,
            description = "读取工作区内指定文本文件内容(UTF-8)。文件大小上限 1MB,超出返回错误。",
            parameters = mapOf(
                "path" to "必填,相对工作区根目录的文件路径",
            ),
            required = setOf("path"),
            category = "built-in",
            riskLevel = ToolRiskLevel.SAFE,
        ),
        ToolRegistry.ToolDef(
            name = NAME_WRITE,
            description = "写入文本文件到工作区(UTF-8,覆盖写入)。内容大小上限 10MB。" +
                "父目录不存在时自动创建。路径禁止包含 \"..\"。",
            parameters = mapOf(
                "path" to "必填,相对工作区根目录的文件路径",
                "content" to "必填,要写入的文本内容",
            ),
            required = setOf("path", "content"),
            category = "built-in",
            riskLevel = ToolRiskLevel.NORMAL,
        ),
        ToolRegistry.ToolDef(
            name = NAME_DELETE,
            description = "删除工作区内的文件或目录。目录会递归删除(包含所有子项),不可恢复。",
            parameters = mapOf(
                "path" to "必填,相对工作区根目录的路径",
            ),
            required = setOf("path"),
            category = "built-in",
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = NAME_MKDIR,
            description = "在工作区内创建目录(支持多级)。已存在视为成功。",
            parameters = mapOf(
                "path" to "必填,相对工作区根目录的目录路径",
            ),
            required = setOf("path"),
            category = "built-in",
            riskLevel = ToolRiskLevel.NORMAL,
        ),
        ToolRegistry.ToolDef(
            name = NAME_MOVE,
            description = "移动或重命名工作区内的文件/目录。目标已存在会失败。",
            parameters = mapOf(
                "from" to "必填,源路径(相对工作区根目录)",
                "to" to "必填,目标路径(相对工作区根目录)",
            ),
            required = setOf("from", "to"),
            category = "built-in",
            riskLevel = ToolRiskLevel.NORMAL,
        ),
    )

    /**
     * 执行工具(由 [WorkspaceToolsRegistrar] 注册的 ToolFn 调用)。
     *
     * @param name 工具名(必须为 [ALL_TOOL_NAMES] 之一)
     * @param args 参数 map
     * @param manager 注入的 [WorkspaceManager] 实例
     * @return 执行结果字符串(成功返回操作详情,失败返回 "Error: ..." 前缀的消息)
     */
    fun execute(name: String, args: Map<String, String>, manager: WorkspaceManager): String {
        return runBlocking {
            when (name) {
                NAME_LIST -> {
                    val path = args["path"]?.trim() ?: ""
                    when (val r = manager.listDir(path)) {
                        is WorkspaceManager.ListResult.Success -> {
                            if (r.entries.isEmpty()) "(empty)" else r.entries.joinToString("\n") { e ->
                                val type = if (e.isDirectory) "dir" else "file"
                                "${e.name} | $type | ${e.size} | ${e.lastModified}"
                            }
                        }
                        is WorkspaceManager.ListResult.Error -> "Error: ${r.message}"
                    }
                }
                NAME_READ -> {
                    val path = args["path"]?.trim()?.takeIf { it.isNotEmpty() }
                        ?: return@runBlocking "Error: 参数 path 必填"
                    when (val r = manager.readFile(path)) {
                        is WorkspaceManager.ReadResult.Success -> r.content
                        is WorkspaceManager.ReadResult.Error -> "Error: ${r.message}"
                    }
                }
                NAME_WRITE -> {
                    val path = args["path"]?.trim()?.takeIf { it.isNotEmpty() }
                        ?: return@runBlocking "Error: 参数 path 必填"
                    val content = args["content"] ?: return@runBlocking "Error: 参数 content 必填"
                    when (val r = manager.writeFile(path, content)) {
                        is WorkspaceManager.OpResult.Success -> "OK: 已写入 $path(${content.length} 字符)"
                        is WorkspaceManager.OpResult.Error -> "Error: ${r.message}"
                    }
                }
                NAME_DELETE -> {
                    val path = args["path"]?.trim()?.takeIf { it.isNotEmpty() }
                        ?: return@runBlocking "Error: 参数 path 必填"
                    when (val r = manager.delete(path)) {
                        is WorkspaceManager.OpResult.Success -> "OK: 已删除 $path"
                        is WorkspaceManager.OpResult.Error -> "Error: ${r.message}"
                    }
                }
                NAME_MKDIR -> {
                    val path = args["path"]?.trim()?.takeIf { it.isNotEmpty() }
                        ?: return@runBlocking "Error: 参数 path 必填"
                    when (val r = manager.mkdir(path)) {
                        is WorkspaceManager.OpResult.Success -> "OK: 目录已创建 $path"
                        is WorkspaceManager.OpResult.Error -> "Error: ${r.message}"
                    }
                }
                NAME_MOVE -> {
                    val from = args["from"]?.trim()?.takeIf { it.isNotEmpty() }
                        ?: return@runBlocking "Error: 参数 from 必填"
                    val to = args["to"]?.trim()?.takeIf { it.isNotEmpty() }
                        ?: return@runBlocking "Error: 参数 to 必填"
                    when (val r = manager.move(from, to)) {
                        is WorkspaceManager.OpResult.Success -> "OK: 已移动 $from -> $to"
                        is WorkspaceManager.OpResult.Error -> "Error: ${r.message}"
                    }
                }
                else -> "Error: 未知工具名 $name"
            }
        }
    }
}

/**
 * 工作区工具注册器 — 把 [WorkspaceTool] 的 6 个工具注册到 [ToolRegistry]。
 *
 * 在 [io.zer0.muse.AppKoinModule] 中以 `single` 注册,init 块自动完成注册。
 *
 * @param toolRegistry 工具注册表
 * @param workspaceManager 工作区管理器
 */
class WorkspaceToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val workspaceManager: WorkspaceManager,
) {
    init { registerAll() }

    /** 注册全部 6 个工作区工具。 */
    fun registerAll() {
        WorkspaceTool.toolDefs().forEach { def ->
            toolRegistry.register(def) { args ->
                WorkspaceTool.execute(def.name, args, workspaceManager)
            }
        }
    }
}
