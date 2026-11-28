package io.zer0.muse.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BLK-8 结论的代码锚点。

 * 评估结论是「不改 `ChatUiState` 的值语义」。这个结论建立在两个事实上：

 *  1. `ChatUiState` 不是 `data class`（没有 `equals`）；
 *  2. 五个嵌套状态类**是** `data class`（有 `equals`）。

 * 两条一起意味着：一旦给顶层加 `equals`，`StateFlow` 的去重会走完整深比较 ——
 * 消息列表逐元素比，而 80 处 `_state.update` 每次都会触发。原来靠引用不等直接
 * 发射是 O(1)，改完变成 O(n)。**收益为负。**

 * 这个类钉住前提。如果哪天有人把 `ChatUiState` 改成 `data class`，或者把某个
 * 嵌套类改成普通 class，测试会失败并指向这段说明 —— 那时需要重新评估，
 * 而不是让一份过时的结论静静躺在计划里。
 */
class ChatUiStateValueSemanticsTest {

    private val source: String by lazy {
        File("src/main/java/io/zer0/muse/ui/chat/ChatViewModel.kt").readText()
    }

    @Test
    fun `ChatUiState stays a plain class`() {
        assertFalse(
            "ChatUiState 一旦变成 data class，BLK-8 的结论就失效了：" +
                "StateFlow 去重会走深比较，80 处 update 每次多一次 O(n) 开销。" +
                "若确实要改，先重新评估并更新 §16.85。",
            source.contains("data class ChatUiState("),
        )
        assertTrue(
            "ChatUiState 必须存在",
            source.contains("class ChatUiState("),
        )
    }

    @Test
    fun `nested state classes stay data classes`() {
        // 它们是深比较代价的来源。改成普通 class 会消除这个代价，
        // 但也意味着嵌套层的值变更不再能触发重组 —— 那是另一个方向的问题。
        listOf(
            "ChatStreamState",
            "ChatToolsState",
            "ChatInputState",
            "ChatSessionState",
            "ChatAgentState",
        ).forEach { name ->
            assertTrue(
                name + " 应当保持 data class；若被改掉，请重新评估 BLK-8 的代价分析",
                source.contains("data class " + name + "("),
            )
        }
    }

    @Test
    fun `copy overload still has the documented arity`() {
        // 状态参数数量变化时需要重新核对字段结构与兼容访问器。
        val at = source.indexOf("fun copy(")
        assertTrue("copy() 必须存在", at > 0)
        var depth = 0
        var end = -1
        var i = at + "fun copy".length
        while (i < source.length) {
            val ch = source[i]
            if (ch == '(') {
                depth++
            } else if (ch == ')') {
                depth--
                if (depth == 0) {
                    end = i
                    break
                }
            }
            i++
        }
        assertTrue("找不到 copy() 的结束括号", end > at)
        val params = Regex("(?m)^\\s+\\w+:").findAll(source.substring(at, end + 1)).count()
        assertTrue(
            "copy() 的参数数应与当前显式状态字段数量一致，实际为 $params",
            params == 125,
        )
    }
}
