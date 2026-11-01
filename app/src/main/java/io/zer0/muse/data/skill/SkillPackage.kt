package io.zer0.muse.data.skill

import io.zer0.common.resultOf

/**
 * `SKILL.md` 能力包：让用户**不写代码**也能给 Muse 加能力。
 *
 * 一个包就是一个目录（或 ZIP），里面必须有 `SKILL.md`：
 *
 * ```markdown
 * ---
 * name: 周报整理
 * description: 把本周的会话要点整理成三段式周报
 * ---
 *
 * 正文就是交给模型的指令，可以写多长都行（受 [MAX_BODY_CHARS] 约束）。
 * ```
 *
 * ## 为什么强调"渐进披露"
 *
 * 技能一旦多了，把每份正文都塞进系统提示会**直接把上下文吃光** —— 那是用户能直接感知的退化
 * （答非所问、开头就被截断）。所以这里的分工是硬性的：
 *
 *  - **进列表的只有 `name` + `description`**，因此 [MAX_DESCRIPTION_CHARS] 是上下文预算，
 *    不是排版偏好：20 个技能 × 500 字就是 1 万字；
 *  - **正文只在模型真正调用这个技能时才载入**（见 `prompt_file:` 编码，[io.zer0.muse.tools.SkillImporter.PROMPT_FILE_PREFIX]）。
 *
 * ## 为什么不要求能力授权
 *
 * 技能默认**不可执行**：它给的是指令文本，不是代码。`scripts/` 里的东西要跑必须走既有的
 * 工具/插件链路并各自受审。把权限账本套到纯文本技能上，只会制造无谓的安装摩擦，
 * 让人以为"点了允许就有了权限"其实什么也没发生。
 */
object SkillPackage {

    /** 包内清单文件名（大小写固定，避免同一包里出现两个入口）。 */
    const val FILE_NAME = "SKILL.md"

    /** description 进列表 → 这是上下文预算。 */
    const val MAX_DESCRIPTION_CHARS = 500

    /** name 会出现在每次列表里，压得更紧。 */
    const val MAX_NAME_CHARS = 60

    /** 正文上限：单次调用注入的指令文本，不是"能存多大"。 */
    const val MAX_BODY_CHARS = 32 * 1024

    /** 解析结果。[body] 已去掉 frontmatter，是准备交给模型的指令文本。 */
    data class Parsed(
        val name: String,
        val description: String,
        val body: String,
        val metadata: Map<String, String>,
    )

    /**
     * 解析 `SKILL.md`。失败一律给可读中文原因 —— 用户是手写的这份文件，
     * 报错必须告诉他**第几行、缺什么**，否则只能靠猜。
     */
    fun parse(markdown: String) = resultOf {
        validate(markdown)
    }

    private fun validate(markdown: String): Parsed {
        // BOM 用 Char(0xFEFF) 显式构造：写不可见字符迟早有人"看不见地"改坏它
        val normalized = markdown.replace("\r\n", "\n").removePrefix(Char(0xFEFF).toString())
        val lines = normalized.split("\n")
        require(lines.firstOrNull()?.trim() == "---") {
            "SKILL.md 必须以 YAML 头部（单独一行 ---）开始"
        }
        val end = lines.indices.drop(1).firstOrNull { lines[it].trim() == "---" }
            ?: error("SKILL.md 的 YAML 头部没有闭合（缺少结尾的 --- 行）")
        val fields = parseFields(lines.subList(1, end))
        val name = fields["name"].orEmpty().trim()
        val description = fields["description"].orEmpty().trim()
        require(name.isNotEmpty()) { "SKILL.md 头部缺少 name" }
        require(name.length <= MAX_NAME_CHARS) {
            "name 过长（${name.length} 字，上限 $MAX_NAME_CHARS）：它会出现在每次技能列表里"
        }
        require(description.isNotEmpty()) { "SKILL.md 头部缺少 description（模型就是靠这句话决定要不要用这个技能）" }
        require(description.length <= MAX_DESCRIPTION_CHARS) {
            "description 过长（${description.length} 字，上限 $MAX_DESCRIPTION_CHARS）：" +
                "它会被注入技能列表，太长会挤占上下文；正文放在 --- 以下，不受这个限制"
        }
        val body = lines.drop(end + 1).joinToString("\n").trim()
        require(body.isNotEmpty()) { "SKILL.md 头部之后没有正文（正文就是这个技能要交给模型的指令）" }
        require(body.length <= MAX_BODY_CHARS) {
            "正文过长（${body.length} 字，上限 $MAX_BODY_CHARS）：请把细节拆进 references/ 文件，正文只留主流程"
        }
        return Parsed(
            name = name,
            description = description,
            body = body,
            metadata = fields.filterKeys { it != "name" && it != "description" },
        )
    }

    /**
     * 只支持技能清单真正需要的那点 YAML 子集：`key: value`、引号包裹、`|` 与 `>` 块。
     *
     * 不实现完整 YAML（锚点/流式集合/多文档）是**有意的**：解析器越接近完整 YAML，
     * 越容易出现"用户写的和系统理解的不一样"，而这里宁可拒收也不要猜。
     */
    private fun parseFields(block: List<String>): Map<String, String> {
        val fields = LinkedHashMap<String, String>()
        var index = 0
        while (index < block.size) {
            val line = block[index].trim()
            val sep = line.indexOf(':')
            if (line.isEmpty() || line.startsWith("#") || sep <= 0) {
                index++
            } else {
                val key = line.substring(0, sep).trim().lowercase()
                val raw = line.substring(sep + 1).trim()
                if (raw == "|" || raw == "|-" || raw == ">" || raw == ">-") {
                    val (value, next) = readBlockScalar(block, index + 1, folded = raw.startsWith(">"))
                    fields[key] = value
                    index = next
                } else {
                    fields[key] = unquote(raw)
                    index++
                }
            }
        }
        return fields
    }

    /** 读 `|` / `>` 块标量：属于本键的行都以缩进或空行为界，返回解析值与下一条的起始下标。 */
    private fun readBlockScalar(block: List<String>, from: Int, folded: Boolean): Pair<String, Int> {
        val collected = mutableListOf<String>()
        var index = from
        while (index < block.size && (block[index].startsWith(" ") || block[index].isBlank())) {
            collected += block[index].trim()
            index++
        }
        return (if (folded) collected.joinToString(" ") else collected.joinToString("\n")) to index
    }

    private fun unquote(value: String): String {
        if (value.length >= 2 && ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith('\'') && value.endsWith('\'')))) {
            return value.substring(1, value.length - 1)
        }
        return value.substringBefore(" #").trim()
    }
}
