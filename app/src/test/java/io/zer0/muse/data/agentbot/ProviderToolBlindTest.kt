package io.zer0.muse.data.agentbot

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F4：Provider 不支持工具调用时必须明确告知，不能静默退化。

 * 清单原话：

 * > 若报错 → 可发现，用户能换 Provider，问题不大。
 * > 若返回纯文本 → **静默退化**，用户会看到主 Bot 只会聊天不会做事，且不知道为什么。
 *
 * > 若是后者，需要给 `toolChoice="required"` 加能力探测：
 * > Provider 不支持时明确告知用户，而不是假装能用。

 * 这个类验的是那条"明确告知"的链路是否接通。
 */
class ProviderToolBlindTest {

    private val coordinator: String by lazy {
        File("src/main/java/io/zer0/muse/data/agentbot/ForegroundCoordinator.kt").readText()
    }

    private val vm: String by lazy {
        File("src/main/java/io/zer0/muse/ui/agentbot/AgentBotViewModel.kt").readText()
    }

    private val screen: String by lazy {
        File("src/main/java/io/zer0/muse/ui/agentbot/AgentBotScreen.kt").readText()
    }

    @Test
    fun `turn carries a tool-blind flag`() {
        assertTrue(
            "Turn 必须能表达「这个 Provider 不发 tool_calls」",
            coordinator.contains("val providerToolBlind: Boolean = false"),
        )
    }

    @Test
    fun `detection requires two text-only responses`() {
        // 一次没配合是"模型这次偷懒"，两次都没有才是"Provider 不支持"。
        val at = coordinator.indexOf("var providerLooksToolBlind = false")
        assertTrue("必须有探测变量", at > 0)
        // 窗口要够宽：探测块里有 finishReason 判据 + 补救调用 + 赋值。
        // 用 1200 会在"补救也失败才判定"那行之前截断。
        val end = coordinator.indexOf("providerToolBlind = true)", at)
        val body = coordinator.substring(at, if (end > at) end + 30 else coordinator.length)
        assertTrue("要看首轮有没有正文", body.contains("completion.text.isNotBlank()"))
        assertTrue("要做一次补救", body.contains("retryWithBotSay("))
        assertTrue("补救也失败才判定", body.contains("providerLooksToolBlind = true"))
        // 判据不止"有正文"：还要确认 Provider 把这次请求当成普通补全处理了。
        assertTrue("需要 finishReason 门控", body.contains("askedForTools"))
    }

    @Test
    fun `the model text is NOT used as the reply`() {
        // 我第一版写的是"正文要留在 say 里，让用户至少看到点什么"。
        // 那条断言错了 —— 计划 V15 把"模型正文只有经 bot_say 提交才可见"
        // 定为固定前提，把正文塞进 say 直接违反它。
        //
        // 端到端测试当场抓住了：`no bot_say means no message and the turn says so`。
        //
        // 用户该看到的是"这个 Provider 不支持工具调用"这条明确错误，
        // 而不是一段没经审核的模型正文。
        val at = coordinator.indexOf("providerLooksToolBlind = true")
        assertTrue(at > 0)
        val body = coordinator.substring(at, minOf(at + 700, coordinator.length))
        assertTrue(
            "不能把正文放进 say —— 那违反 V15 的「正文不经 bot_say 不可见」",
            !body.contains("say = completion.text"),
        )
    }

    @Test
    fun `the flag reaches the ui as a specific error`() {
        assertTrue("VM 必须处理这个标记", vm.contains("turn.providerToolBlind"))
        assertTrue("要有专门的错误类型", vm.contains("AgentBotError.PROVIDER_TOOL_BLIND"))
        assertTrue("界面要有文案", screen.contains("AgentBotError.PROVIDER_TOOL_BLIND"))
    }

    @Test
    fun `the message tells the user what to do`() {
        // 只说"不支持"没用，要告诉用户"换个供应商"。
        val at = screen.indexOf("AgentBotError.PROVIDER_TOOL_BLIND")
        assertTrue(at > 0)
        val around = screen.substring(at, minOf(at + 200, screen.length))
        assertTrue("要指向具体文案资源", around.contains("agent_bot_error_tool_blind"))
    }

    @Test
    fun `detection also requires the provider to have answered with stop`() {
        // 上一版只看"两次都返回纯文本"，会把"模型这次不肯用工具"
        // 也判成"Provider 不支持" —— 那是把责任推给 Provider。
        //
        // 补充判据：finishReason 为 stop 或缺失，说明这次请求被当成
        // 普通补全处理了。支持工具的 Provider 在 required 下不会这样。
        val at = coordinator.indexOf("val askedForTools =")
        assertTrue("必须有 finishReason 判据", at > 0)
        val body = coordinator.substring(at, minOf(at + 400, coordinator.length))
        assertTrue("要检查 finishReason", body.contains("finishReason"))
        assertTrue("并且用于门控探测", coordinator.contains("gotText && !askedForTools"))
    }

    @Test
    fun `the wording is hedged not absolute`() {
        // 判据是启发式的：两次纯文本 + finishReason 为 stop。
        // 它区分不了"Provider 不支持"与"模型这次没配合"。
        //
        // 所以文案不能说"不支持"，只能说"可能不支持" ——
        // 否则错误信息会把责任推给 Provider，用户换一圈供应商也解决不了。
        val text = File("src/main/res/values/strings_ui_misc.xml").readText()
        val m = Regex("<string name=\"agent_bot_error_tool_blind\">([^<]*)</string>").find(text)
        assertTrue("文案必须存在", m != null)
        val wording = m!!.groupValues[1]
        assertTrue(
            "必须用不确定语气（可能/或许），不能断言「不支持」：" + wording,
            wording.contains("可能"),
        )
        assertTrue(
            "要给出下一步（换供应商 或 直接说怎么做）：" + wording,
            wording.contains("供应商") || wording.contains("告诉我"),
        )
    }
}
