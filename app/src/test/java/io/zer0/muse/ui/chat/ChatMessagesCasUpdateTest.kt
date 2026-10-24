package io.zer0.muse.ui.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.ui.ChatUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 并发写入不丢更新：`updateMessages` 的 CAS 语义回归。
 *
 * 背景：`ChatStateAccessor.updateMessages` 的实现是 `_messages.update(transform)`，
 * 冲突时用**最新值**重放 transform。CAS 只有在 transform 真正消费入参时才有意义——
 * `updateMessages { staleSnapshot }`（忽略入参）等于赋值，零保护。
 *
 * 本文件两层覆盖：
 *  1. 语义层：用真 CAS 的 StateFlow 夹具模拟「读快照后另一写者已写入」，锁住
 *     追加 / 按 id 改单条 / 过滤三类写入的合并语义，并反向锁住两种退化写法会丢数据。
 *  2. 接线层：断言两处生产文件确实以消费入参的形态写入（退回旧写法即失败）。
 *
 * 为什么不做端到端：这些 transform 是 ChatViewModel / ChatGenerationController
 * 内联在超长方法里的 lambda，依赖完整 Android 仓储与流式状态，无法在不引入
 * Robolectric 全量夹具的前提下直接调用。语义层用等价 transform 锁契约，
 * 接线层锁生产代码形态，两者合起来覆盖「改错就红」。
 */
class ChatMessagesCasUpdateTest {

    // ── 夹具：真 CAS 的 messages 容器 ───────────────────────────────

    private class CasAccessor(initial: List<UIMessage> = emptyList()) : ChatStateAccessor {
        val flow = MutableStateFlow(initial)

        override val snapshot: ChatUiState get() = ChatUiState()

        override fun update(transform: (ChatUiState) -> ChatUiState) = Unit

        override val messagesSnapshot: List<UIMessage> get() = flow.value

        override fun updateMessages(transform: (List<UIMessage>) -> List<UIMessage>) = flow.update(transform)

        override val coroutineScope: CoroutineScope get() = CoroutineScope(Dispatchers.Unconfined)
    }

    private fun msg(content: String, role: MessageRole = MessageRole.ASSISTANT): UIMessage = UIMessage(role = role, content = content)

    // ── 追加类（A1 / A2） ──────────────────────────────────────────

    @Test
    fun `append via CAS keeps writes that landed inside the window`() {
        val m1 = msg("base")
        val accessor = CasAccessor(listOf(m1))
        // 写者 A 读快照（模拟 ChatViewModel 读 _messages.value 之后的时间窗）
        val staleSnapshot = accessor.messagesSnapshot

        // 写者 B 在窗口内先追加（interlude 注入 / 断点恢复）
        accessor.updateMessages { it + msg("interlude-b") }

        // 写者 A 继续追加 —— 必须消费入参，否则 B 的追加被旧快照盖掉
        accessor.updateMessages { it + msg("interlude-a") }

        assertEquals(
            listOf("base", "interlude-b", "interlude-a"),
            accessor.messagesSnapshot.map { it.content },
        )
        // 快照确实陈旧，证明这不是「没有并发」的空跑
        assertEquals(listOf("base"), staleSnapshot.map { it.content })
    }

    @Test
    fun `ignoring the incoming list loses the concurrent append`() {
        val accessor = CasAccessor(listOf(msg("base")))
        val staleSnapshot = accessor.messagesSnapshot

        accessor.updateMessages { it + msg("interlude-b") }
        // 退化写法：忽略入参 = 赋值，CAS 形同虚设
        accessor.updateMessages { staleSnapshot + msg("interlude-a") }

        assertEquals(
            listOf("base", "interlude-a"),
            accessor.messagesSnapshot.map { it.content },
        )
        assertFalse(accessor.messagesSnapshot.any { it.content == "interlude-b" })
    }

    // ── 按 id 改单条（A3 / A6 / A8） ────────────────────────────────

    @Test
    fun `targeted field update keeps fields written concurrently on the same message`() {
        val target = msg("原文").copy(imageUrls = emptyList())
        val other = msg("邻居", role = MessageRole.USER)
        val accessor = CasAccessor(listOf(target, other))
        // 旧快照（如 translateMessage 开头的 target / finalize 的 finalAssistant）
        val staleTarget = accessor.messagesSnapshot.first { it.id == target.id }

        // 窗口内另一写者给同一条消息补了媒体（生图 / 视频落盘）
        accessor.updateMessages { list ->
            list.map { if (it.id == target.id) it.copy(imageUrls = listOf("https://cdn/x.png")) else it }
        }

        // A3 形态：只 copy transformer / 翻译真正产出的字段
        accessor.updateMessages { list ->
            list.map { m ->
                if (m.id == staleTarget.id) {
                    m.copy(translations = m.translations + ("中文" to "译文"))
                } else {
                    m
                }
            }
        }
        // A8 形态：只 copy 元数据字段
        accessor.updateMessages { list ->
            list.map { m ->
                if (m.id == staleTarget.id) m.copy(durationMs = 1234L, promptTokens = 10) else m
            }
        }

        val merged = accessor.messagesSnapshot.first { it.id == target.id }
        assertEquals(listOf("https://cdn/x.png"), merged.imageUrls)
        assertEquals("译文", merged.translations["中文"])
        assertEquals(1234L, merged.durationMs)
        assertEquals(10, merged.promptTokens)
        // 邻居消息不受影响
        assertEquals("邻居", accessor.messagesSnapshot.first { it.id == other.id }.content)
    }

    @Test
    fun `replacing the whole message with a stale object loses concurrent fields`() {
        val target = msg("原文")
        val accessor = CasAccessor(listOf(target))
        val staleTarget = accessor.messagesSnapshot.first { it.id == target.id }

        accessor.updateMessages { list ->
            list.map { if (it.id == target.id) it.copy(imageUrls = listOf("https://cdn/x.png")) else it }
        }

        // A9 陷阱写法：用旧对象整体盖
        accessor.updateMessages { list ->
            list.map { if (it.id == target.id) staleTarget.copy(content = "改过") else it }
        }

        val merged = accessor.messagesSnapshot.first { it.id == target.id }
        assertEquals("改过", merged.content)
        assertTrue("整体替换旧对象必然丢窗口内媒体字段", merged.imageUrls.isEmpty())
    }

    @Test
    fun `transformer output merged by field keeps media and metadata`() {
        val target = msg("<mood>腹稿</mood>正文").copy(imageUrls = listOf("https://cdn/v.mp4"))
        val accessor = CasAccessor(listOf(target))
        val staleTarget = accessor.messagesSnapshot.first { it.id == target.id }

        accessor.updateMessages { list ->
            list.map { if (it.id == target.id) it.copy(durationMs = 999L) else it }
        }

        // A9 真实形态：transformer 产出 content/mood/reflection/moodSkin + 变体身份字段
        val transformed = staleTarget.copy(content = "正文", mood = "腹稿")
        accessor.updateMessages { list ->
            list.map {
                if (it.id == staleTarget.id) {
                    it.copy(
                        content = transformed.content,
                        mood = transformed.mood,
                        reflection = transformed.reflection,
                        moodSkin = transformed.moodSkin,
                        variantGroupId = transformed.variantGroupId,
                        variantIndex = transformed.variantIndex,
                        variantCount = transformed.variantCount,
                        parentGroupId = transformed.parentGroupId,
                    )
                } else {
                    it
                }
            }
        }

        val merged = accessor.messagesSnapshot.first { it.id == target.id }
        assertEquals("正文", merged.content)
        assertEquals("腹稿", merged.mood)
        assertEquals(listOf("https://cdn/v.mp4"), merged.imageUrls)
        assertEquals(999L, merged.durationMs)
    }

    // ── 过滤类（A7） ───────────────────────────────────────────────

    @Test
    fun `context overflow rollback keeps messages appended inside the window`() {
        val placeholder = msg("")
        val accessor = CasAccessor(listOf(msg("用户提问", MessageRole.USER), placeholder))
        val staleSnapshot = accessor.messagesSnapshot

        // 窗口内新追加（工具卡片 / 子 agent interlude）
        accessor.updateMessages { it + msg("窗口内追加") }

        // A7 形态：基于入参过滤，不是基于旧快照
        accessor.updateMessages { list ->
            list.filterNot { m -> m.id == placeholder.id && m.content.isBlank() }
        }

        val contents = accessor.messagesSnapshot.map { it.content }
        assertEquals(listOf("用户提问", "窗口内追加"), contents)
        // 旧快照确实不含窗口内追加的消息
        assertEquals(listOf("用户提问", ""), staleSnapshot.map { it.content })
    }

    @Test
    fun `filter over a stale snapshot drops the window append`() {
        val placeholder = msg("")
        val accessor = CasAccessor(listOf(msg("用户提问", MessageRole.USER), placeholder))
        val staleSnapshot = accessor.messagesSnapshot

        accessor.updateMessages { it + msg("窗口内追加") }
        // 退化写法：拿旧快照过滤后整体赋值
        accessor.updateMessages { staleSnapshot.filterNot { m -> m.id == placeholder.id && m.content.isBlank() } }

        assertFalse(accessor.messagesSnapshot.any { it.content == "窗口内追加" })
    }

    // ── 接线层：生产代码必须消费入参 ────────────────────────────────

    private fun source(relative: String): String {
        val candidates = listOf(Path.of(relative), Path.of("app/$relative"))
        val path = candidates.firstOrNull(Files::exists) ?: error("$relative not found")
        return Files.newBufferedReader(path).use { it.readText() }
    }

    private fun viewModel(): String = source("src/main/java/io/zer0/muse/ui/ChatViewModel.kt")

    private fun generationController(): String = source("src/main/java/io/zer0/muse/ui/chat/ChatGenerationController.kt")

    @Test
    fun `interlude injections append through CAS instead of overwriting the snapshot`() {
        val src = viewModel()
        assertTrue(src.contains("updateMessages { it + interludes }"))
        assertTrue(src.contains("updateMessages { it + unownedInterludes }"))
        assertFalse(src.contains("_messages.value = _messages.value + interludes"))
        assertFalse(src.contains("_messages.value = _messages.value + unownedInterludes"))
    }

    @Test
    fun `translation writes update the source message by id through CAS`() {
        val src = viewModel()
        val applyBody = src.substringAfter("fun applyTranslation(text: String) {").substringBefore("applyTranslation(\"\")")
        assertTrue(applyBody.contains("updateMessages { list ->"))
        assertTrue(applyBody.contains("m.translations + (targetLanguage to text)"))
        assertFalse(applyBody.contains("_messages.value"))

        val partialBody = src.substringAfter("val partial = sb.toString().trim()").substringBefore("_state.update {")
        assertTrue(partialBody.contains("updateMessages { list ->"))
        assertTrue(partialBody.contains("m.translations - targetLanguage"))
        assertFalse(partialBody.contains("_messages.value"))

        val emptyBody = src.substringAfter("// 清掉空占位,改为错误提示").substringBefore("_state.update {")
        assertTrue(emptyBody.contains("updateMessages { list ->"))
        assertFalse(emptyBody.contains("_messages.value"))
    }

    @Test
    fun `reaction optimistic update goes through CAS`() {
        val src = viewModel()
        val body = src.substringAfter("fun setReaction(messageId: Uuid, reaction: String?) {")
            .substringBefore("v1.104 U7")
        assertTrue(body.contains("updateMessages { list ->"))
        assertTrue(body.contains("msg.copy(reaction ="))
        assertFalse(body.contains("_messages.value ="))
    }

    @Test
    fun `context overflow rollback filters the incoming list`() {
        val src = generationController()
        val body = src.substringAfter("if (state.contextOverflowBlocked) {")
            .substringBefore("deps.messageController.rebuildConversationTree()")
        assertTrue(body.contains("accessor.updateMessages { list ->"))
        assertTrue(body.contains("filterNot { msg -> msg.id == state.currentAssistantId && msg.content.isBlank() }"))
        assertFalse(body.contains("deps.stateStore.messages.value ="))
    }

    @Test
    fun `metadata writeback goes through CAS`() {
        val src = generationController()
        val body = src.substringAfter("// A5: 生成元数据持久化").substringBefore("// 流式结束后刷新上下文")
        assertTrue(body.contains("accessor.updateMessages { list ->"))
        assertTrue(body.contains("durationMs = durationMs"))
        assertFalse(body.contains("deps.stateStore.messages.value ="))
    }

    @Test
    fun `finalize writeback merges only the fields the transformer produces`() {
        val src = generationController()
        val body = src.substringAfter("val preservedAssistant =")
            .substringBefore("deps.sessionRepository.upsertMessage(sessionId, preservedAssistant)")
        // 禁止用旧对象整体覆盖
        assertFalse(body.contains("preservedAssistant else it"))
        assertFalse(body.contains("deps.stateStore.messages.value ="))
        // 只覆盖 transformer 真正产出的四个字段
        listOf(
            "content = preservedAssistant.content",
            "mood = preservedAssistant.mood",
            "reflection = preservedAssistant.reflection",
            "moodSkin = preservedAssistant.moodSkin",
        ).forEach { assertTrue("缺少字段合并: $it", body.contains(it)) }
        // 变体身份字段也必须写回（否则 transformer 重建对象后变体结构丢失）
        listOf(
            "variantGroupId = preservedAssistant.variantGroupId",
            "variantIndex = preservedAssistant.variantIndex",
            "variantCount = preservedAssistant.variantCount",
            "parentGroupId = preservedAssistant.parentGroupId",
        ).forEach { assertTrue("缺少变体字段: $it", body.contains(it)) }
        // 媒体字段不允许出现在合并列表里（它们来自入参最新值，不能被旧快照覆盖）
        listOf("imageUrls =", "imageBase64List =", "videoFileUri =").forEach {
            assertFalse("不应覆盖媒体字段: $it", body.contains(it))
        }
    }
}
