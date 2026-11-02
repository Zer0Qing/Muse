package io.zer0.muse.data.plugin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * 插件声明式界面（D4：默认形态）。
 *
 * ## 为什么不让插件交 HTML
 *
 * HTML 面板要求每张卡都跑一个 WebView 沙盒：内存、隔离强度、主题一致性都得逐张付代价，
 * 插件作者还要自己处理深色模式与字号。声明式树由宿主渲染，
 * 颜色/字体/间距自动跟随用户的主题，插件拿不到也破坏不了宿主的界面。
 *
 * HTML 没有被删掉 —— 它是需要自定义排版时的**逃生口**（`uiPanel`）。两者共存，默认走这一份。
 *
 * ## 硬上限为什么必须有
 *
 * 这棵树是**外部输入**：不设深度上限，一个自嵌套 JSON 就能把递归渲染打爆；
 * 不设节点上限，一张"界面"可以塞十万个文本把主线程卡死。
 * 超限一律整份拒收并说清是哪条 —— 部分渲染会让用户以为插件坏了。
 *
 * ## 交互语义只有"提交-返回"（设计文档 §8）
 *
 * `submit` 调插件函数，函数返回**新的组件树**，宿主整棵替换视图。
 * 不做双向绑定：两处状态互写意味着出问题时没有任何一处是真相。
 */
object PluginUiTree {

    /** 树最大深度（超过即拒：渲染是递归的）。 */
    const val MAX_DEPTH = 12

    /** 整棵树节点数上限。 */
    const val MAX_NODES = 300

    /** 单个文本字段上限（字符）。 */
    const val MAX_TEXT_CHARS = 4 * 1024

    /** 清单文件本身字符上限。 */
    const val MAX_SOURCE_CHARS = 64 * 1024

    /** 一张表单里的输入项上限（再多就不是表单了）。 */
    const val MAX_INPUTS = 24

    /** 支持的节点类型（拒绝信息里原样列出，作者不用再翻文档）。 */
    val SUPPORTED_TYPES: Set<String> = setOf(
        "column", "row", "divider", "spacer", "heading", "text", "caption",
        "badge", "stat", "kv", "list", "progress", "assetImage", "code",
        "form", "input", "submit",
    )

    /** `input.kind` 白名单。未知 kind 直接拒：降级成文本框会让用户填的东西按错的类型上行。 */
    val INPUT_KINDS: Set<String> = setOf("text", "number", "select", "toggle")

    private val json = Json {
        // **故意严格**：未知键多半是作者拼错了类型名。静默忽略会让界面少一块
        // 而作者以为"就是这样"；这里宁可整份拒收并说清位置。
        ignoreUnknownKeys = false
        classDiscriminator = "type"
    }

    /** 一个界面节点，`type` 为判别字段。 */
    @Serializable
    sealed interface Node {

        @Serializable
        @SerialName("column")
        data class Column(val children: List<Node> = emptyList(), val gap: Int? = null) : Node

        @Serializable
        @SerialName("row")
        data class Row(val children: List<Node> = emptyList(), val gap: Int? = null) : Node

        @Serializable
        @SerialName("divider")
        data object Divider : Node

        @Serializable
        @SerialName("spacer")
        data class Spacer(val height: Int? = null) : Node

        @Serializable
        @SerialName("heading")
        data class Heading(val text: String, val level: Int? = null) : Node

        @Serializable
        @SerialName("text")
        data class Body(val text: String) : Node

        @Serializable
        @SerialName("caption")
        data class Caption(val text: String) : Node

        @Serializable
        @SerialName("badge")
        data class Badge(val text: String, val tone: String? = null) : Node

        @Serializable
        @SerialName("stat")
        data class Stat(val label: String, val value: String, val hint: String? = null) : Node

        @Serializable
        @SerialName("kv")
        data class KeyValue(val key: String, val value: String) : Node

        @Serializable
        @SerialName("list")
        data class List_(val items: List<String> = emptyList(), val ordered: Boolean? = null) : Node

        @Serializable
        @SerialName("progress")
        data class Progress(val value: Float, val max: Float? = null, val label: String? = null) : Node

        /** 只允许引用插件包内的图片资源；路径由渲染侧再过一次安全校验。 */
        @Serializable
        @SerialName("assetImage")
        data class AssetImage(val path: String, val alt: String? = null) : Node

        @Serializable
        @SerialName("code")
        data class Code(val text: String, val language: String? = null) : Node

        /** 表单容器：只有它内部的 `input` 随提交上行（装饰性文本不会被当成参数）。 */
        @Serializable
        @SerialName("form")
        data class Form(
            val children: List<Node> = emptyList(),
            /** 提交后是否清空输入。默认不清空：改一个字段要重填整张表是折磨。 */
            val resetOnSubmit: Boolean? = null,
        ) : Node

        /** 输入项。`key` 在整棵树内必须唯一。 */
        @Serializable
        @SerialName("input")
        data class Input(
            val key: String,
            val label: String,
            val kind: String? = null,
            val options: List<String> = emptyList(),
            val value: String? = null,
            val placeholder: String? = null,
        ) : Node

        /**
         * 提交按钮。`handler` 是插件函数名，**必须已在清单里声明**。
         *
         * 解析阶段不查清单：清单是另一份输入，真正的把关在执行入口
         * （`loadVerifiedFunction` 本来就拒绝未声明函数）。
         * 在解析器里再查一遍，等于给同一条规则建两个可能不同步的家。
         */
        @Serializable
        @SerialName("submit")
        data class Submit(val label: String, val handler: String) : Node
    }

    /** 解析结果。[reason] 可直接展示给插件作者。 */
    data class Parsed(val root: Node?, val reason: String?, val nodes: Int) {
        val success get() = root != null
    }

    /** 解析一份声明式界面 JSON。 */
    fun parse(source: String): Parsed {
        if (source.length > MAX_SOURCE_CHARS) {
            return Parsed(null, "界面文件 ${source.length} 字符，超过上限 $MAX_SOURCE_CHARS", 0)
        }
        val root = try {
            json.decodeFromString(Node.serializer(), source)
        } catch (e: SerializationException) {
            // 只捕序列化异常：解析器的问题归解析器，别把中断一类也咽下来。
            return Parsed(null, "界面文件无法解析：${readable(e.message)}；支持的类型：$SUPPORTED_TYPES", 0)
        }
        return validate(root)
    }

    private fun validate(root: Node): Parsed {
        val (count, problems) = measure(root)
        return if (problems.isEmpty()) Parsed(root, null, count) else Parsed(null, problems.joinToString("；"), count)
    }

    /** 迭代式遍历（不吃栈）：结构上限、单节点内容、跨节点 key 唯一性分开判。 */
    private fun measure(root: Node): Pair<Int, List<String>> {
        val problems = LinkedHashSet<String>()
        val seen = ArrayList<Node>()
        val stack = ArrayDeque<Pair<Node, Int>>()
        stack.addLast(root to 1)
        var count = 0
        var stopped = false
        while (stack.isNotEmpty() && !stopped) {
            val (node, depth) = stack.removeLast()
            count++
            when {
                count > MAX_NODES -> {
                    problems += "节点数超过 $MAX_NODES 个"
                    // 上限已破，剩下的不必再看：继续走只是多花时间和内存。
                    stopped = true
                }
                depth > MAX_DEPTH -> problems += "嵌套深度超过 $MAX_DEPTH 层"
                else -> {
                    seen += node
                    childrenOf(node).forEach { stack.addLast(it to depth + 1) }
                    nodeViolation(node)?.let(problems::add)
                }
            }
        }
        keyViolation(seen)?.let(problems::add)
        return count to problems.toList()
    }

    private fun childrenOf(node: Node): List<Node> = when (node) {
        is Node.Column -> node.children
        is Node.Row -> node.children
        is Node.Form -> node.children
        else -> emptyList()
    }

    /**
     * `input.key` 重复必须整份拒收：静默覆盖等于丢掉用户刚输入的那一格，
     * 而症状出现在"提交后数据不对"这种最难回查的地方。
     */
    private fun keyViolation(seen: List<Node>): String? {
        val keys = seen.filterIsInstance<Node.Input>().map { it.key }
        if (keys.size > MAX_INPUTS) return "输入项超过 $MAX_INPUTS 个上限"
        val duplicated = keys.groupBy { it }.filterValues { it.size > 1 }.keys
        return if (duplicated.isEmpty()) null else "input.key 重复：${duplicated.joinToString("/")}"
    }

    /** 单节点违规：返回一句原因，没问题返回 null。 */
    private fun nodeViolation(node: Node): String? = when (node) {
        is Node.Input -> inputViolation(node)
        is Node.Submit -> if (node.handler.isBlank()) "submit.handler 必须指向一个已声明的插件函数" else null
        is Node.List_ -> if (node.items.size > MAX_NODES) "列表项超过 $MAX_NODES 条" else null
        is Node.Progress -> if (!node.value.isFinite()) "progress.value 必须是有限数" else null
        is Node.AssetImage -> if (node.path.isBlank()) "assetImage.path 不能为空" else null
        else -> {
            val text = textOf(node)
            if (text != null && text.length > MAX_TEXT_CHARS) "文本超过 $MAX_TEXT_CHARS 字" else null
        }
    }

    private fun inputViolation(node: Node.Input): String? {
        // 省略 kind 等于 text：判定必须建立在"补默认值之后"的字符串上，
        // 否则 `null !in 集合` 会把最普通的写法判成违规。
        val kind = node.kind?.lowercase() ?: "text"
        return when {
            node.key.isBlank() -> "input.key 不能为空"
            node.label.isBlank() -> "input ${node.key} 缺 label（用户得知道这格填什么）"
            kind !in INPUT_KINDS -> "input.kind=${node.kind} 不支持（可用：${INPUT_KINDS.joinToString("/")}）"
            kind == "select" && node.options.isEmpty() -> "input ${node.key} 是 select 但没有 options"
            else -> null
        }
    }

    /** 把 kotlinx 的长报错压成一句：指出位置，去掉堆栈式描述。 */
    private fun readable(message: String?): String {
        val first = message?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val text = message.orEmpty()
        // kotlinx 的大小写与措辞不稳（"At path:" / "at path:" 都出现过），故不区分大小写；
        // 路径里含 `$`、`[]`，字符类必须带上，否则位置为空 —— 那正是上一版漏掉的东西。
        val where = pathPattern.find(text)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            ?: offsetPattern.find(text)?.groupValues?.get(1)?.let { "偏移 $it" }
        return if (where == null) first.take(200) else "${first.take(180)}（位置：$where）"
    }

    private fun textOf(node: Node): String? = when (node) {
        is Node.Heading -> node.text
        is Node.Body -> node.text
        is Node.Caption -> node.text
        is Node.Badge -> node.text
        is Node.Stat -> node.label + node.value + (node.hint ?: "")
        is Node.KeyValue -> node.key + node.value
        is Node.Code -> node.text
        is Node.Input -> node.label + node.key + (node.value ?: "")
        is Node.Submit -> node.label + node.handler
        else -> null
    }

    // kotlinx 的报错里定位信息有两种写法，这里各备一条（object 本身是单例，不能写 companion）。
    private val pathPattern = Regex("""(?i)at path:\s*([\w.$\[\]\-]+)""")
    private val offsetPattern = Regex("""(?i)offset (\d+)""")
}
