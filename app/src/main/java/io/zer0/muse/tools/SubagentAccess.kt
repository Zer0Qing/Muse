package io.zer0.muse.tools

/**
 * 子代理的访问级别（对比清单 P1-08）。
 *
 * 此前 `Params.access` 只是"预留字段"，注释写着"当前未差异化" —— 也就是说
 * 调用方写了 `access=read` 也不会有任何区别，工具集合永远按同一套规则过滤。
 * 这里把它变成真的三档，并且**按风险等级定义**而不是维护一份工具名清单：
 * 名单会随工具增减而腐烂，而风险等级是仓库里已有单一真源（[ToolPermissionResolver]）。
 *
 * 三档的含义：
 *  - [READ_ONLY]：只有 SAFE（只读/查询）。审查、调研、总结这类任务用它；
 *  - [CODER]：SAFE + NORMAL（可写本地、可逆）。日常委派默认值；
 *  - [FULL]：连 HIGH 也放开，**但每次调用仍然过审批闸门**（[ToolCallGate]）——
 *    "放开"指的是模型能看到并请求它，不是"自动通过"。
 *
 * 三档共同保留的地板：递归类与显式禁止清单里的工具（见 SubagentRunner 的两级黑名单）。
 */
enum class SubagentAccess(val wire: String) {
    READ_ONLY("read"),
    CODER("write"),
    FULL("full"),
    ;

    /** 该级别是否允许某个风险等级的工具。 */
    fun allows(risk: ToolRiskLevel): Boolean = when (this) {
        READ_ONLY -> risk == ToolRiskLevel.SAFE
        CODER -> risk != ToolRiskLevel.HIGH
        FULL -> true
    }

    /** 英文说明，与 [description] 同义（提示词跟随应用 locale）。 */
    fun englishDescription(): String = when (this) {
        READ_ONLY -> "read-only: you may query information but change nothing"
        CODER -> "write: you may make local, reversible changes; high-risk tools are unavailable"
        FULL -> "full: high-risk tools can be requested, but each call still goes through approval"
    }

    /** 给模型看的说明（写进子代理提示词，避免它反复请求拿不到的工具）。 */
    fun description(): String = when (this) {
        READ_ONLY -> "只读：只能查询信息，不能修改任何内容"
        CODER -> "可写：可以修改本地内容（可逆操作），高风险操作不可用"
        FULL -> "完整：高风险工具也可请求，但每次都会走审批"
    }

    companion object {
        /**
         * 解析调用方给的字符串。
         *
         * 未知值一律降级到 [READ_ONLY] —— 写错一个单词就获得更大权限，
         * 是这类开关最不该有的行为。
         */
        fun parse(raw: String?): SubagentAccess = when (raw?.trim()?.lowercase()) {
            "write", "coder", "code", "edit" -> CODER
            "full", "all", "admin" -> FULL
            else -> READ_ONLY
        }
    }
}

/**
 * `agents/<name>.md` 里声明的一个角色。
 *
 * 文件格式（frontmatter + 正文）：
 * ```
 * ---
 * name: reviewer
 * description: 代码审查
 * access: read
 * ---
 * 你是一名严格的代码审查者……
 * ```
 */
data class AgentProfile(
    val name: String,
    val description: String,
    val access: SubagentAccess,
    val instructions: String,
)

/** 角色文件的解析与目录读取。全部纯函数，便于在没有文件系统的环境里验证。 */
object AgentProfileParser {

    /** 正文上限：角色文件是提示词，不是知识库。 */
    const val MAX_INSTRUCTIONS_CHARS = 8_000

    /** 一个目录里最多认多少个角色。 */
    const val MAX_PROFILES = 32

    /**
     * 解析一份角色文件。
     *
     * @param fileName 不带扩展名的文件名，用作 name 的兜底
     * @return 没有 frontmatter 时返回 null（不猜）
     */
    fun parse(fileName: String, content: String): AgentProfile? {
        val lines = content.replace("\r\n", "\n").split("\n")
        if (lines.firstOrNull()?.trim() != "---") return null
        val endIndex = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (endIndex < 0) return null
        val headerLines = lines.subList(1, endIndex + 1)
        val body = lines.drop(endIndex + 2).joinToString("\n").trim()
        val fields = headerLines.mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) null else line.substring(0, separator).trim().lowercase() to line.substring(separator + 1).trim()
        }.toMap()
        val name = fields["name"]?.takeIf { it.isNotBlank() } ?: fileName
        if (name.isBlank()) return null
        return AgentProfile(
            name = name,
            description = fields["description"].orEmpty(),
            access = SubagentAccess.parse(fields["access"]),
            instructions = body.take(MAX_INSTRUCTIONS_CHARS),
        )
    }

    /**
     * 从一组 (文件名, 内容) 里收集角色。
     *
     * 同名只保留第一个（按文件名排序），避免两个文件互相覆盖时行为随目录顺序变化。
     */
    fun collect(files: List<Pair<String, String>>): List<AgentProfile> {
        val seen = LinkedHashMap<String, AgentProfile>()
        files.sortedBy { it.first }.forEach { (fileName, content) ->
            val profile = parse(fileName, content) ?: return@forEach
            if (seen.size >= MAX_PROFILES) return@forEach
            seen.putIfAbsent(profile.name, profile)
        }
        return seen.values.toList()
    }
}
