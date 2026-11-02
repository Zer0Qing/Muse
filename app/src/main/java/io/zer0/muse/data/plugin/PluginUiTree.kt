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
 * HTML 没有被删掉 —— 它是要画图表、做自定义排版时的**逃生口**（`uiPanel`）。
 * 两者共存，但默认应该是这一份。
 *
 * ## 硬上限为什么必须有
 *
 * 这棵树是**外部输入**：不设深度上限，一个自嵌套的 JSON 就能把递归渲染打爆；
 * 不设节点上限，一张"界面"可以塞十万个文本把主线程卡死。
 * 超限一律整份拒收并说清是哪条上限 —— 部分渲染会让用户以为插件坏了。
 *
 * ## 只读
 *
 * v1 没有任何可交互节点。"点了要回调插件"需要一条会**再次执行插件代码**的链路，
 * 那涉及权限与循环调用深度，得单独设计；与其放一个点了没反应的按钮骗人，不如先不做。
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

    /** 支持的节点类型（拒绝信息里原样列出，作者不用再翻文档）。 */
    val SUPPORTED_TYPES: Set<String> = setOf(
        "column", "row", "divider", "spacer", "heading", "text", "caption",
        "badge", "stat", "kv", "list", "progress", "assetImage", "code",
    )

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

        /** 只允许引用插件包内的图片资源；路径由调用方再过一次安全校验。 */
        @Serializable
        @SerialName("assetImage")
        data class AssetImage(val path: String, val alt: String? = null) : Node

        @Serializable
        @SerialName("code")
        data class Code(val text: String, val language: String? = null) : Node
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
            // 只捕序列化异常：解析器的问题归解析器，别把 OOM/中断一类也咽下来。
            return Parsed(null, "界面文件无法解析：${readable(e.message)}；支持的类型：$SUPPORTED_TYPES", 0)
        }
        return validate(root)
    }

    private fun validate(root: Node): Parsed {
        val (count, problems) = measure(root)
        return when {
            problems.isEmpty() -> Parsed(root, null, count)
            else -> Parsed(null, problems.joinToString("；"), count)
        }
    }

    /** 迭代式遍历（不吃栈），把"结构上限"与"单节点内容上限"分开判。 */
    private fun measure(root: Node): Pair<Int, List<String>> {
        val problems = LinkedHashSet<String>()
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
                    when (node) {
                        is Node.Column -> node.children.forEach { stack.addLast(it to depth + 1) }
                        is Node.Row -> node.children.forEach { stack.addLast(it to depth + 1) }
                        else -> Unit
                    }
                    nodeViolation(node)?.let(problems::add)
                }
            }
        }
        return count to problems.toList()
    }

    /** 单节点违规：返回一句原因，没问题返回 null。 */
    private fun nodeViolation(node: Node): String? = when (node) {
        is Node.List_ -> if (node.items.size > MAX_NODES) "列表项超过 $MAX_NODES 条" else null
        is Node.Progress -> if (!node.value.isFinite()) "progress.value 必须是有限数" else null
        is Node.AssetImage -> if (node.path.isBlank()) "assetImage.path 不能为空" else null
        else -> {
            val text = textOf(node)
            if (text != null && text.length > MAX_TEXT_CHARS) "文本超过 $MAX_TEXT_CHARS 字" else null
        }
    }

    /** 把 kotlinx 的长报错压成一句：指出位置，去掉堆栈式描述。 */
    private fun readable(message: String?): String {
        val first = message?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val text = message.orEmpty()
        // kotlinx 的大小写与措辞不稳（"At path:" / "at path:" 都出现过），故不区分大小写；
        // 路径里含 `$`、`[]`，字符类必须带上，否则位置为空 —— 那正是上一版漏掉的东西。
        val path = pathPattern.find(text)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        val offset = offsetPattern.find(text)?.groupValues?.get(1)?.let { "偏移 $it" }
        val where = path ?: offset
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
        else -> null
    }

    // kotlinx 的报错里定位信息有两种写法，这里各备一条（object 本身就是单例，不能写 companion）。
    private val pathPattern = Regex("""(?i)at path:\s*([\w.$\[\]\-]+)""")
    private val offsetPattern = Regex("""(?i)offset (\d+)""")
}
