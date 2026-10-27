package io.zer0.muse.ui

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ToolCallInfo
import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * v2.6.8 卡片交互链路回归（源码级 wiring 断言）。
 *
 * 真机反馈的两件事其实是同一条断链的两端：
 *  1. 卡片被收进任务卡的步骤里，得展开执行计划才看得见；
 *  2. 打开的那张卡里所有控件都没反应，长按弹系统选字菜单。
 *
 * 根因一处是「任务卡路径把桥硬编码成 null」，一处是「javaScriptEnabled = bridge != null」——
 * 没有回传回调时 JS 整个关闭，事件绑定从不执行。这些测试锁住三件事：
 *  - 桥必须能到产物卡（任一路径都不许再把它置空）；
 *  - JS 不许再依赖桥；
 *  - show_card 属于交付物，不被任务卡吸收。
 *
 * 纯源码/纯函数断言，不需要跑 Compose 渲染。
 */
class CardInteractionWiringTest {

    // ── 1. 任务卡路径必须传桥 ────────────────────────────────────────────

    @Test
    fun `inline direct output forwards the card bridge instead of nulling it`() {
        val source = readUi("MessageBubbleExtras.kt")
        val block = source.substringAfter("internal fun DirectToolOutputInline(")
            .substringBefore("@Composable\nprivate fun DirectToolOutput(")

        assertFalse(
            "DirectToolOutputInline 曾经硬编码 onCardAction = null，任务卡里的卡片因此永远无桥",
            block.contains("onCardAction = null"),
        )
        assertTrue("必须把调用方传进来的桥继续往下传", block.contains("onCardAction = onCardAction"))
        assertTrue("参数里必须有桥", block.contains("onCardAction: ((io.zer0.muse.ui.markdown.CardAction) -> Unit)? = null"))
    }

    @Test
    fun `chat screen passes one shared bridge into the task card path`() {
        val screen = readUi("ChatScreen.kt")
        val taskCardCall = screen.substringAfter("io.zer0.muse.ui.taskcard.TaskCard(")
            .substringBefore(") else if (")
        assertTrue(
            "任务卡必须拿到回传处理器，且复用 handleCardAction（不另造第二套）",
            taskCardCall.contains("onCardAction = handleCardAction"),
        )
    }

    @Test
    fun `message bubble forwards its task card bridge down to the task card`() {
        val bubble = readUi("MessageBubble.kt")
        val taskCardCall = bubble.substringAfter("io.zer0.muse.ui.taskcard.TaskCard(")
            .substringBefore(") else if (")
        assertTrue("任务卡必须收到 messageId 维度的回传处理器", taskCardCall.contains("onCardAction = taskCardCardAction"))
        assertTrue(
            "没传 messageId 维度的处理器时要回落到本条消息的处理器，不能静默无桥",
            bubble.contains("onTaskCardCardAction ?: onCardAction?.let"),
        )
    }

    // ── 2. 任务卡产物卡的 cardId 必须是消息 id，不是 toolCallId ──────────

    @Test
    fun `task card step output uses the message id as card identity`() {
        val source = readUi("taskcard/TaskCard.kt") + readUi("taskcard/TaskStepRow.kt")

        assertFalse(
            "toolCallId 与消息 id 是两个 id 空间：用它做 cardId 会让卡片读不到自己的数据、保存工件挂错消息",
            source.contains("cardId = step.toolCallId"),
        )
        val taskCardFn = source.substringAfter("fun TaskCard(")
            .substringBefore("/** 阶段徽章")
        assertTrue("步骤行必须拿到消息 id", taskCardFn.contains("cardId = data.id"))

        val stepRow = source.substringAfter("fun TaskStepRow(")
        assertTrue("步骤行的产物卡必须用传入的 cardId（消息 id）", stepRow.contains("cardId = cardId"))
    }

    // ── 3. javascriptEnabled 不许再依赖桥 ────────────────────────────────

    @Test
    fun `rich content card keeps javascript enabled regardless of the bridge`() {
        // v2.6.12：渲染已收敛到 markdown/card/CardRuntime，JS 开关与桥注入都在那里。
        // 负向断言对两个文件都跑：任一处重新绑上桥，都是同一个回归。
        val legacy = readUi("markdown/RichContentCard.kt").codeOnly()
        val runtime = readUi("markdown/card/CardRuntime.kt").codeOnly()
        for (source in listOf(legacy, runtime)) {
            assertFalse(
                "JS 依赖桥是「控件全死」的直接原因：无回传回调时事件绑定从不执行，" +
                    "长按还退化成 WebView 默认选字",
                source.contains("javaScriptEnabled = bridge != null"),
            )
            assertFalse(
                "高度首测也不该以桥为前提，否则无桥卡会被裁切",
                source.contains("onPageFinished = if (bridge != null)"),
            )
        }
        // 单一的渲染点是 CardRuntime：JS 无条件开启，桥仅在能回传时注入。
        assertTrue("卡片渲染要始终启用 JS", runtime.contains("javaScriptEnabled = true"))
        assertTrue(
            "桥仍然只在有回传时注入（安全边界不变）",
            runtime.contains("bridge?.let { webView.addJavascriptInterface"),
        )
    }

    // ── 4. show_card 是交付物，不被任务卡吸收 ────────────────────────────

    @Test
    fun `show card is classified as an inline deliverable`() {
        assertTrue(isInlineDeliverableTool(SHOW_CARD_TOOL_NAME))
        assertFalse(isInlineDeliverableTool("render_data"))
        assertFalse(isInlineDeliverableTool("generate_image"))
        assertFalse(isInlineDeliverableTool(null))
    }

    @Test
    fun `inline deliverable ids collect only show card tool messages`() {
        val owner = UIMessage(role = MessageRole.ASSISTANT, content = "")
        val card = toolMessage(owner, "show_card")
        val image = toolMessage(owner, "generate_image")
        val plain = UIMessage(role = MessageRole.ASSISTANT, content = "回复")

        val ids = inlineDeliverableMessageIds(listOf(owner, card, image, plain))

        assertEquals(setOf(card.id.toString()), ids)
    }

    @Test
    fun `chat screen keeps show card out of the task card dedupe set`() {
        val screen = readUi("ChatScreen.kt")
        val dedupe = screen.substringAfter("val cardRenderedByTaskCard by remember(")
            .substringBefore("val groupedRuns by remember(")
        assertTrue(
            "交付物必须从「已由任务卡渲染」集合里摘出去，否则卡片会被隐藏，" +
                "用户只能展开执行计划才看得到",
            dedupe.contains("inlineDeliverableMessageIds(visibleMessages)"),
        )
        assertTrue(dedupe.contains("covered - "))
    }

    @Test
    fun `show card is never folded into a grouped tool run card`() {
        val screen = readUi("ChatScreen.kt")
        val grouping = screen.substringAfter("ChatDisplayGrouper.group(visibleMessages)")
            .substringBefore(".filterIsInstance")
        assertTrue(
            "组卡默认折叠，把交付物收进去等于又让卡片藏起来",
            grouping.contains("!io.zer0.muse.ui.isInlineDeliverableTool(msg.toolCallInfo?.toolName)"),
        )
    }

    @Test
    fun `task card renders only a reference line for a generated card`() {
        // v2.6.8 拆分：步骤行一族已搬到 TaskStepRow.kt（TaskCard.kt 超了函数数上限）。
        // 断言内容不变，只是指针跟着代码走。
        val source = readUi("taskcard/TaskStepRow.kt")
        val stepRow = source.substringAfter("fun TaskStepRow(")
        assertTrue(
            "任务卡里只留一行「已生成卡片」引用",
            source.contains("isInlineDeliverableTool(step.toolName)"),
        )
        assertTrue(
            "引用行文案走资源，不是硬编码中文",
            source.contains("stringResource(R.string.task_card_card_generated)"),
        )
        assertTrue("这个判断必须在步骤结果区里", stepRow.isNotEmpty())
    }

    @Test
    fun `generated card reference string exists in all shipped locales`() {
        val locales = listOf(
            "values",
            "values-en",
            "values-ja",
            "values-ko",
            "values-ru",
            "values-es",
            "values-pt-rBR",
        )
        locales.forEach { locale ->
            val path = Path.of("app/src/main/res/$locale/strings_features.xml")
            val candidates = listOf(path, Path.of("src/main/res/$locale/strings_features.xml"))
            val file = candidates.firstOrNull(Files::exists)
                ?: error("missing resource file for $locale")
            val text = Files.newBufferedReader(file).use { it.readText() }
            assertTrue(
                "$locale 缺 task_card_card_generated（七语言必须同步）",
                text.contains("name=\"task_card_card_generated\""),
            )
        }
    }

    // ── 辅助 ────────────────────────────────────────────────────────────

    private fun toolMessage(owner: UIMessage, toolName: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        content = "",
        parentMessageId = owner.id.toString(),
        toolCallInfo = ToolCallInfo(
            toolName = toolName,
            arguments = """{"title":"t","code":"<div/>"}""",
            result = "ok",
            isSuccess = true,
        ),
    )

    private fun readUi(relative: String): String {
        val candidates = listOf(
            Path.of("src/main/java/io/zer0/muse/ui/$relative"),
            Path.of("app/src/main/java/io/zer0/muse/ui/$relative"),
        )
        val path = candidates.firstOrNull(Files::exists)
            ?: error("Unable to locate ui/$relative from ${System.getProperty("user.dir")}")
        return Files.newBufferedReader(path).use { it.readText() }
    }

    /** 丢掉 `//` 行注释，避免注释里提到旧实现时被误判为命中。 */
    private fun String.codeOnly(): String = lineSequence()
        .filterNot { it.trimStart().startsWith("//") }
        .joinToString("\n")
}
