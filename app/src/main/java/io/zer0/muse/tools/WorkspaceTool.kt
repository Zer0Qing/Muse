package io.zer0.muse.tools

import io.zer0.muse.workspace.WorkspaceManager
import io.zer0.muse.workspace.WorkspaceMount
import kotlinx.coroutines.runBlocking

/**
 * 工作区文件管理工具集（AI 可调用）。
 *
 * 注册 10 个工具到 [ToolRegistry]：
 *  1. workspace_list   — 列出目录下的子项
 *  2. workspace_read   — 读取文本文件，大文件按字符游标分页
 *  3. workspace_write  — 写入文本文件（覆盖或追加）
 *  4. workspace_edit   — 把文件里的某段原文替换成另一段，回执带行级 diff
 *  5. workspace_delete — 删除文件或目录
 *  6. workspace_mkdir  — 创建目录
 *  7. workspace_move   — 移动或重命名
 *  8. workspace_copy   — 复制
 *  9. workspace_grep   — 按文本或正则搜索内容
 * 10. workspace_mounts — 列出可用挂载及其能力
 *
 * 路径写法：`挂载标识:/相对路径`。不写前缀时落到默认挂载，因此老调用照常可用。
 * 前缀只认已注册的挂载标识，普通文件名里出现冒号不会被误判。
 *
 * 安全：路径由 [WorkspaceManager] 解析，能力位（能否读/写）与挂载边界都在那里校验；
 * 工具层不自行拼路径，也不提供绕过校验的入口。
 */
@Suppress("TooManyFunctions")
object WorkspaceTool {

    /** workspace_list 工具名。 */
    const val NAME_LIST = "workspace_list"

    /** workspace_read 工具名。 */
    const val NAME_READ = "workspace_read"

    /** workspace_write 工具名。 */
    const val NAME_WRITE = "workspace_write"

    /** workspace_edit 工具名。 */
    const val NAME_EDIT = "workspace_edit"

    /** workspace_delete 工具名。 */
    const val NAME_DELETE = "workspace_delete"

    /** workspace_mkdir 工具名。 */
    const val NAME_MKDIR = "workspace_mkdir"

    /** workspace_move 工具名。 */
    const val NAME_MOVE = "workspace_move"

    /** workspace_copy 工具名。 */
    const val NAME_COPY = "workspace_copy"

    /** workspace_grep 工具名。 */
    const val NAME_GREP = "workspace_grep"

    /** workspace_mounts 工具名。 */
    const val NAME_MOUNTS = "workspace_mounts"

    /** 全部工具名（便于同步到 [ToolRegistry.BUILT_IN_TOOL_IDS]）。 */
    val ALL_TOOL_NAMES: List<String> = listOf(
        NAME_LIST,
        NAME_READ,
        NAME_WRITE,
        NAME_EDIT,
        NAME_DELETE,
        NAME_MKDIR,
        NAME_MOVE,
        NAME_COPY,
        NAME_GREP,
        NAME_MOUNTS,
    )

    /** 全部工具定义（注册到 ToolRegistry）。 */
    fun toolDefs(): List<ToolRegistry.ToolDef> = listOf(
        listToolDef(),
        readToolDef(),
        writeToolDef(),
        editToolDef(),
        deleteToolDef(),
        mkdirToolDef(),
        moveToolDef(),
        copyToolDef(),
        grepToolDef(),
        mountsToolDef(),
    )

    /** 路径参数说明，多个工具共用同一句话。 */
    private const val PATH_HINT = "路径可写 挂载标识:/相对路径；不写前缀则落到默认工作区。先用 workspace_mounts 查可用的挂载标识。"

    /** diff 回执里改动块前后各带几行上下文。 */
    private const val DIFF_CONTEXT_LINES = 3

    private fun listToolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME_LIST,
        description = "列出工作区某个目录下的文件与子目录（目录在前，文件在后）。" +
            "返回每行一条：name | type(dir/file) | size | lastModified。" + PATH_HINT,
        parameters = mapOf(
            "path" to "可选,目录路径,空串或 \"/\" 表示默认工作区根目录",
        ),
        required = emptySet(),
        category = "built-in",
        riskLevel = ToolRiskLevel.SAFE,
    )

    private fun readToolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME_READ,
        description = "读取工作区内指定文本文件内容(UTF-8)。大文件自动分段,用 offset_chars/length_chars 继续读。" +
            PATH_HINT,
        parameters = mapOf(
            "path" to "必填,文件路径",
            "offset_chars" to "可选,字符起始游标;继续读取时使用上次返回的 offset_chars。",
            "length_chars" to "可选,单页字符数,最大 ${io.zer0.muse.tools.TOOL_OUTPUT_READ_PAGE_CHARS}。",
        ),
        required = setOf("path"),
        category = "built-in",
        riskLevel = ToolRiskLevel.SAFE,
    )

    private fun writeToolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME_WRITE,
        description = "写入文本文件到工作区(UTF-8)。默认覆盖;append=true 时追加。单次内容上限 10MB。" +
            "父目录不存在会自动创建。写长文件请分批:先写首段,后续用 append=true 逐段追加。" +
            "只读挂载会被拒绝。" + PATH_HINT,
        parameters = mapOf(
            "path" to "必填,文件路径",
            "content" to "必填,要写入的文本内容",
            "append" to "可选,是否追加写入(默认 false 覆盖)。写长文件建议分批追加",
        ),
        required = setOf("path", "content"),
        category = "built-in",
        // 必须声明类型，否则发给模型的 schema 会把 append 说成 string，
        // 而执行侧用 String.toBoolean() 解释，"1"/"yes"/"是" 会静默回落成覆盖写。
        parameterTypes = mapOf("append" to "boolean"),
        riskLevel = ToolRiskLevel.HIGH,
    )

    private fun editToolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME_EDIT,
        description =
        "局部编辑文件(修改工作区里的文本)：把某段原文替换成另一段(UTF-8)。" +
            "old_string 必须与文件内容逐字一致,且在整份文件里只出现一次 —— 出现 0 次或多次都会被拒绝," +
            "本工具不会替你猜改哪一处。要改多处就带着足够上下文分多次调用。new_string 传空串表示删除该片段。" +
            "成功返回行级 diff(首个改动行 + @@ 块),可直接核对改了哪几行。" +
            "改文件先用 workspace_read 或 workspace_grep 定位原文,不要整份重写。只读挂载会被拒绝。" + PATH_HINT,
        parameters =
        mapOf(
            "path" to "必填,文件路径",
            "old_string" to "必填,要被替换的原文片段;必须逐字匹配且在整个文件中唯一",
            "new_string" to "必填,替换后的新片段;空串表示删除该片段",
        ),
        required = setOf("path", "old_string", "new_string"),
        category = "built-in",
        riskLevel = ToolRiskLevel.HIGH,
    )

    private fun deleteToolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME_DELETE,
        description = "删除工作区内的文件或目录。目录会递归删除(包含所有子项),不可恢复。只读挂载会被拒绝。" + PATH_HINT,
        parameters = mapOf(
            "path" to "必填,要删除的路径",
        ),
        required = setOf("path"),
        category = "built-in",
        riskLevel = ToolRiskLevel.HIGH,
    )

    private fun mkdirToolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME_MKDIR,
        description = "在工作区内创建目录(支持多级)。已存在视为成功。只读挂载会被拒绝。" + PATH_HINT,
        parameters = mapOf(
            "path" to "必填,目录路径",
        ),
        required = setOf("path"),
        category = "built-in",
        riskLevel = ToolRiskLevel.HIGH,
    )

    private fun moveToolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME_MOVE,
        description = "移动或重命名工作区内的文件/目录。目标已存在会失败;不能跨挂载移动,跨挂载请用 workspace_copy。" +
            PATH_HINT,
        parameters = mapOf(
            "from" to "必填,源路径",
            "to" to "必填,目标路径",
        ),
        required = setOf("from", "to"),
        category = "built-in",
        riskLevel = ToolRiskLevel.HIGH,
    )

    private fun copyToolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME_COPY,
        description = "复制工作区内的文件或目录。目标已存在会失败。" +
            "同一挂载内可直接复制目录;跨挂载只能复制单个文件。目标挂载需要写权限。" + PATH_HINT,
        parameters = mapOf(
            "from" to "必填,源路径(需要读权限)",
            "to" to "必填,目标路径(需要写权限)",
        ),
        required = setOf("from", "to"),
        category = "built-in",
        riskLevel = ToolRiskLevel.HIGH,
    )

    private fun grepToolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME_GREP,
        description =
        "在工作区文件里搜索内容,返回 路径:行号:内容。改文件前先用它定位,不要把整份文件读进上下文。" +
            "递归搜索,自动跳过二进制与超 2MB 的文件。is_regex=true 时 pattern 按正则解释。" +
            "命中 100 条即停并标注 truncated。path 为空时搜全部可用挂载。",
        parameters =
        mapOf(
            "pattern" to "必填,要查找的文本;is_regex=true 时为正则表达式",
            "path" to "可选,搜索起点的路径,默认全部可用挂载",
            "is_regex" to "可选,是否按正则解释 pattern(默认 false)",
            "max_results" to "可选,命中数上限,默认 100",
        ),
        required = setOf("pattern"),
        category = "built-in",
        riskLevel = ToolRiskLevel.SAFE,
    )

    private fun mountsToolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME_MOUNTS,
        description =
        "列出当前可用的工作区挂载:标识 | 名称 | 能力 | 来源。" +
            "路径前缀要写这里的标识,例如 `album:/DCIM/a.jpg`;" +
            "标识为 main 的是默认挂载,可以不写前缀。只读挂载会标出来。",
        parameters = emptyMap(),
        required = emptySet(),
        category = "built-in",
        riskLevel = ToolRiskLevel.SAFE,
    )

    /**
     * 执行工具。
     *
     * @param name 工具名（必须为 [ALL_TOOL_NAMES] 之一）
     * @param args 参数 map
     * @param manager 注入的 [WorkspaceManager] 实例
     * @param sessionId 当前会话 id，用于取会话可见的挂载范围；为空表示不限制
     * @return 执行结果字符串（失败返回 "Error: ..." 前缀的消息）
     */
    fun execute(name: String, args: Map<String, String>, manager: WorkspaceManager, sessionId: String? = null): String = runBlocking {
        val scope = manager.sessionMountIds(sessionId)
        when (name) {
            NAME_MOUNTS -> renderMounts(manager.mounts())
            NAME_LIST -> executeList(args, manager, scope)
            NAME_READ -> executeRead(args, manager, scope)
            NAME_WRITE -> executeWrite(args, manager, scope)
            NAME_EDIT -> executeEdit(args, manager, scope)
            NAME_DELETE -> executeDelete(args, manager, scope)
            NAME_MKDIR -> executeMkdir(args, manager, scope)
            NAME_MOVE -> executeMove(args, manager, scope)
            NAME_COPY -> executeCopy(args, manager, scope)
            NAME_GREP -> executeGrep(args, manager, scope)
            else -> "Error: 未知工具名 $name"
        }
    }

    /** 把挂载表渲染成给模型看的一行一条。 */
    private fun renderMounts(mounts: List<WorkspaceMount>): String {
        if (mounts.isEmpty()) return "(没有任何挂载)"
        return mounts.joinToString("\n") { mount ->
            val caps = buildList {
                if (mount.canList) add("list")
                if (mount.canRead) add("read")
                if (mount.canWrite) add("write")
            }.joinToString("+").ifEmpty { "none" }
            val source = when (mount.provider) {
                io.zer0.muse.workspace.MountProvider.LOCAL -> "本地目录"
                io.zer0.muse.workspace.MountProvider.SAF -> "系统授权目录"
            }
            "${mount.id} | ${mount.label} | $caps | $source"
        }
    }

    /** workspace_list 的执行体。 */
    private suspend fun executeList(args: Map<String, String>, manager: WorkspaceManager, scope: Set<String>?): String {
        val path = args["path"]?.trim() ?: ""
        return when (val r = manager.listDir(path, scope)) {
            is WorkspaceManager.ListResult.Success -> {
                if (r.entries.isEmpty()) {
                    "(empty)"
                } else {
                    r.entries.joinToString("\n") { e ->
                        val type = if (e.isDirectory) "dir" else "file"
                        "${e.relativePath} | $type | ${e.size} | ${e.lastModified}"
                    }
                }
            }
            is WorkspaceManager.ListResult.Error -> "Error: ${r.message}"
        }
    }

    /** workspace_read 的执行体。 */
    private suspend fun executeRead(args: Map<String, String>, manager: WorkspaceManager, scope: Set<String>?): String {
        val path = args["path"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return "Error: 参数 path 必填"
        val offsetChars = args["offset_chars"]?.toIntOrNull()
        val lengthChars = args["length_chars"]?.toIntOrNull()
        return when (val r = manager.readFilePage(path, offsetChars, lengthChars, scope)) {
            is WorkspaceManager.ReadResult.Success -> r.content
            is WorkspaceManager.ReadResult.Error -> "Error: ${r.message}"
        }
    }

    /** workspace_write 的执行体。 */
    private suspend fun executeWrite(args: Map<String, String>, manager: WorkspaceManager, scope: Set<String>?): String {
        val path = args["path"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return "Error: 参数 path 必填"
        val content = args["content"] ?: return "Error: 参数 content 必填"
        val append = args["append"]?.toBoolean() ?: false
        val r = if (append) {
            manager.appendFile(path, content, scope)
        } else {
            manager.writeFile(path, content, scope)
        }
        return when (r) {
            is WorkspaceManager.OpResult.Success ->
                "OK: 已${if (append) "追加" else "写入"} $path(${content.length} 字符)"
            is WorkspaceManager.OpResult.Error -> "Error: ${r.message}"
        }
    }

    /** workspace_edit 的执行体：在全文快照上判唯一片段 → 替换 → 落盘 → 回行级 diff。 */
    private suspend fun executeEdit(
        args: Map<String, String>,
        manager: WorkspaceManager,
        scope: Set<String>?,
    ): String {
        val path = args["path"]?.trim()?.takeIf { it.isNotEmpty() }
        val oldString = args["old_string"]
        val newString = args["new_string"]
        if (path == null || oldString == null || newString == null) {
            val missing = listOfNotNull(
                "path".takeIf { path == null },
                "old_string".takeIf { oldString == null },
                "new_string".takeIf { newString == null },
            )
            return "Error: 参数 ${missing.joinToString("/")} 必填"
        }
        // 必须整份读：片段匹配要跑在对全文的一份快照上，分页读到的一半内容匹配不上。
        // 读与写被包在同一把路径锁里完成（[WorkspaceManager.editFile]）：
        // 只锁写等于没锁——两个并发调用会各自读到旧内容再各自写回，丢一次更新。
        return when (val r = manager.editFile(path, scope) { original ->
            when (val plan = planEdit(original, oldString, newString, path)) {
                is EditPlan.Rejected -> WorkspaceManager.EditDecision.Abort(plan.message)
                is EditPlan.Applied -> WorkspaceManager.EditDecision.Commit(plan.updated)
            }
        }) {
            is WorkspaceManager.EditResult.Applied -> renderEdited(path, r)
            is WorkspaceManager.EditResult.Rejected -> "Error: ${r.message}"
            is WorkspaceManager.EditResult.Failed -> "Error: ${r.message}"
        }
    }

    /** 已写回的改动回执：首个改动行 + 行级 hunk。 */
    private fun renderEdited(path: String, applied: WorkspaceManager.EditResult.Applied): String {
        val diff = diffLines(applied.original, applied.updated)
        return buildString {
            appendLine("OK: workspace_edit 已更新 $path")
            appendLine(
                "改动: 首个改动行 ${diff.firstChangedLine};" +
                    "原文 ${diff.oldLineCount} 行 → 新内容 ${diff.newLineCount} 行",
            )
            append(diff.hunk)
        }
    }

    /**
     * 在完整原文上做一次唯一的片段替换。
     *
     * 三类前置拒绝全部在这里判完：片段找不到、片段不唯一、新旧内容相同。
     * 判定阶段不产生写入动作，所以拒绝时文件必然保持原样。
     *
     * @param original 文件完整内容
     * @param oldString 待替换片段
     * @param newString 替换后片段
     * @param path 展示用路径（只用于错误文案）
     */
    internal fun planEdit(original: String, oldString: String, newString: String, path: String): EditPlan {
        val occurrences = countOccurrences(original, oldString)
        val rejection = when {
            oldString.isEmpty() -> "old_string 不能为空：空片段在任何位置都能匹配,无法确定要改哪里"
            oldString == newString -> "新旧内容相同，无需修改：new_string 与 old_string 逐字一致"
            occurrences > 1 -> "old_string 在 $path 中出现 $occurrences 次，请提供更多上下文使其唯一" +
                "(本工具不会只替换第一处)"
            occurrences == 0 -> editMissingMessage(original, oldString, path)
            else -> null
        }
        if (rejection != null) return EditPlan.Rejected("$rejection(文件未改动)")
        val at = original.indexOf(oldString)
        val updated = original.replaceRange(at, at + oldString.length, newString)
        return EditPlan.Applied(updated, diffLines(original, updated))
    }

    /** [needle] 在 [haystack] 里的出现次数；重叠出现也各算一次（宁可多报重复，不少报唯一）。 */
    internal fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var from = haystack.indexOf(needle)
        while (from >= 0) {
            count++
            from = haystack.indexOf(needle, from + 1)
        }
        return count
    }

    /** 片段找不到时的提示；文件是 CRLF 换行而 old_string 用 LF 时点明原因。 */
    private fun editMissingMessage(original: String, oldString: String, path: String): String {
        val crlfHint = if (original.contains("\r\n") && original.replace("\r\n", "\n").contains(oldString)) {
            "；文件使用 CRLF 换行,old_string 里的换行也要写成 CRLF 才能逐字匹配"
        } else {
            ""
        }
        return "找不到该片段：old_string 在 $path 中不存在,请先用 workspace_read 或 workspace_grep 核对原文" +
            "(缩进与换行都要逐字一致)$crlfHint"
    }

    /**
     * 行级 diff。
     *
     * 单点替换保证改动是一段连续块，所以用公共前后缀定出这块即可，不需要通用 LCS；
     * 再按 [DIFF_CONTEXT_LINES] 行上下文渲染成 @@ hunk。
     *
     * @param oldText 替换前全文
     * @param newText 替换后全文
     */
    internal fun diffLines(oldText: String, newText: String): EditDiff {
        val oldLines = oldText.split('\n')
        val newLines = newText.split('\n')
        var prefix = 0
        while (prefix < oldLines.size && prefix < newLines.size && oldLines[prefix] == newLines[prefix]) {
            prefix++
        }
        val shared = minOf(oldLines.size - prefix, newLines.size - prefix)
        var suffix = 0
        while (suffix < shared && oldLines[oldLines.size - 1 - suffix] == newLines[newLines.size - 1 - suffix]) {
            suffix++
        }
        val removed = oldLines.subList(prefix, oldLines.size - suffix)
        val added = newLines.subList(prefix, newLines.size - suffix)
        val before = minOf(DIFF_CONTEXT_LINES, prefix)
        val after = minOf(DIFF_CONTEXT_LINES, suffix)
        val hunkStart = prefix - before + 1
        val body = buildString {
            appendLine("@@ -$hunkStart,${before + removed.size + after} +$hunkStart,${before + added.size + after} @@")
            for (i in prefix - before until prefix) appendLine(" ${oldLines[i].trimEnd('\r')}")
            removed.forEach { appendLine("-${it.trimEnd('\r')}") }
            added.forEach { appendLine("+${it.trimEnd('\r')}") }
            for (i in 0 until after) appendLine(" ${oldLines[oldLines.size - suffix + i].trimEnd('\r')}")
        }.trimEnd()
        return EditDiff(
            firstChangedLine = prefix + 1,
            oldLineCount = removed.size,
            newLineCount = added.size,
            hunk = body,
        )
    }

    /** workspace_delete 的执行体。 */
    private suspend fun executeDelete(args: Map<String, String>, manager: WorkspaceManager, scope: Set<String>?): String {
        val path = args["path"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return "Error: 参数 path 必填"
        return when (val r = manager.delete(path, scope)) {
            is WorkspaceManager.OpResult.Success -> "OK: 已删除 $path"
            is WorkspaceManager.OpResult.Error -> "Error: ${r.message}"
        }
    }

    /** workspace_mkdir 的执行体。 */
    private suspend fun executeMkdir(args: Map<String, String>, manager: WorkspaceManager, scope: Set<String>?): String {
        val path = args["path"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return "Error: 参数 path 必填"
        return when (val r = manager.mkdir(path, scope)) {
            is WorkspaceManager.OpResult.Success -> "OK: 目录已创建 $path"
            is WorkspaceManager.OpResult.Error -> "Error: ${r.message}"
        }
    }

    /** workspace_move 的执行体。 */
    private suspend fun executeMove(args: Map<String, String>, manager: WorkspaceManager, scope: Set<String>?): String {
        val from = args["from"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return "Error: 参数 from 必填"
        val to = args["to"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return "Error: 参数 to 必填"
        return when (val r = manager.move(from, to, scope)) {
            is WorkspaceManager.OpResult.Success -> "OK: 已移动 $from -> $to"
            is WorkspaceManager.OpResult.Error -> "Error: ${r.message}"
        }
    }

    /** workspace_copy 的执行体。 */
    private suspend fun executeCopy(args: Map<String, String>, manager: WorkspaceManager, scope: Set<String>?): String {
        val from = args["from"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return "Error: 参数 from 必填"
        val to = args["to"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return "Error: 参数 to 必填"
        return when (val r = manager.copy(from, to, scope)) {
            is WorkspaceManager.OpResult.Success -> "OK: 已复制 $from -> $to"
            is WorkspaceManager.OpResult.Error -> "Error: ${r.message}"
        }
    }

    /** workspace_grep 的执行体。 */
    private suspend fun executeGrep(args: Map<String, String>, manager: WorkspaceManager, scope: Set<String>?): String {
        val pattern = args["pattern"]?.takeIf { it.isNotEmpty() }
            ?: return "Error: 参数 pattern 必填"
        val path = args["path"]?.trim() ?: ""
        val isRegex = args["is_regex"]?.toBoolean() ?: false
        val maxResults = args["max_results"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
        return when (val r = manager.grep(pattern, path, isRegex, maxResults, scope)) {
            is WorkspaceManager.GrepResult.Success -> renderGrepMatches(r)
            is WorkspaceManager.GrepResult.Error -> "Error: ${r.message}"
        }
    }

    /** 把命中渲染成 路径:行号:内容。 */
    private fun renderGrepMatches(result: WorkspaceManager.GrepResult.Success): String {
        if (result.matches.isEmpty()) return "无命中(已扫描 ${result.scannedFiles} 个文件)"
        return buildString {
            result.matches.forEach { m ->
                appendLine("${m.path}:${m.lineNumber}:${m.line}")
            }
            if (result.truncated) {
                append("... 命中数达到上限,结果已截断;请缩小 path 或让 pattern 更精确")
            }
        }.trimEnd()
    }
}

/**
 * 工作区工具注册器。
 *
 * 用带上下文的注册方式：会话可见的挂载范围要靠 [ToolExecutionContext.sessionId] 取，
 * 普通注册拿不到会话信息。
 *
 * @param toolRegistry 工具注册表
 * @param workspaceManager 工作区管理器
 */
class WorkspaceToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val workspaceManager: WorkspaceManager,
) {
    init {
        registerAll()
    }

    /** 注册全部工作区工具。 */
    fun registerAll() {
        WorkspaceTool.toolDefs().forEach { def ->
            toolRegistry.registerWithContext(def) { args, context ->
                WorkspaceTool.execute(def.name, args, workspaceManager, context.sessionId)
            }
        }
    }
}

/** 一次片段替换的行级 diff。 */
internal data class EditDiff(
    /** 首个改动行的行号（1 起）。 */
    val firstChangedLine: Int,
    /** 被替换掉的原文行数。 */
    val oldLineCount: Int,
    /** 替换后的新内容行数。 */
    val newLineCount: Int,
    /** 带上下文的 @@ hunk 文本。 */
    val hunk: String,
)

/** 片段替换的判定结果。 */
internal sealed class EditPlan {
    /** 拒绝：带原因，文件未改动。 */
    data class Rejected(val message: String) : EditPlan()

    /** 可落盘：换好的全文 + 回执要用的 diff。 */
    data class Applied(val updated: String, val diff: EditDiff) : EditPlan()
}
