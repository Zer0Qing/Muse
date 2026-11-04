package io.zer0.muse.conversation.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 草稿投影的拒绝通道（`:conversation-core` 体检第一处）。
 *
 * `DraftStore.apply` 对外承诺的是**类型化结果**：非法输入返回 `Rejected(reason)`，
 * 不抛。两处原来不守这个承诺：
 *
 *  - `setMetadataPart` 用 `require(...)` 往外抛 `IllegalArgumentException` ——
 *    流式投影里没有 catch，接上生产路径就是一次崩溃；
 *  - 同一个 run 重复 `Start` 被报成 `IdentityMismatch`，而此刻身份**完全相等** ——
 *    把排查的人指向完全错误的方向。
 */
class DraftStoreRejectionTest {

    private val identity = RunIdentity(
        sessionId = "session-1",
        incarnation = 1,
        turnId = "turn-1",
        generationId = "generation-1",
        streamId = "stream-1",
        selectionSetId = "selection-1",
    )

    private fun start(sequence: Long, id: RunIdentity = identity) =
        DraftEvent.Start(sequence, id, startedAt = 1L)

    private fun metadata(sequence: Long, partId: String, kind: PartKind, text: String = "") =
        DraftEvent.SetMetadataPart(sequence, identity, partId, kind, text)

    @Test
    fun `不支持的 metadata 种类走拒绝而不是抛异常`() {
        val store = DraftStore()
        store.apply(start(1))

        val result = store.apply(metadata(2, "part-1", PartKind.Text))

        assertTrue("该返回拒绝而不是抛出: $result", result is DraftApplyResult.Rejected)
        assertEquals(RejectionReason.InvalidPart, (result as DraftApplyResult.Rejected).reason)
    }

    @Test
    fun `不能把已有正文段就地改成 metadata 段`() {
        val store = DraftStore()
        store.apply(start(1))
        val applied = store.apply(DraftEvent.AppendText(2, identity, "p1", "打了一半"))
        assertTrue(applied is DraftApplyResult.Applied)

        val result = store.apply(metadata(3, "p1", PartKind.Image))

        assertEquals(RejectionReason.InvalidPart, (result as DraftApplyResult.Rejected).reason)
        val part = store.snapshot().parts.single()
        assertEquals("正文必须还是正文", PartKind.Text, part.kind)
        assertEquals("打了一半", part.text)
    }

    @Test
    fun `同身份重复开始要说AlreadyStarted而不是身份不匹配`() {
        val store = DraftStore()
        store.apply(start(1))

        val result = store.apply(start(2))

        assertTrue(result is DraftApplyResult.Rejected)
        assertEquals(
            "身份明明相等，报身份不匹配会把排查指向错方向",
            RejectionReason.AlreadyStarted,
            (result as DraftApplyResult.Rejected).reason,
        )
    }

    @Test
    fun `不同身份才叫身份不匹配`() {
        val store = DraftStore()
        store.apply(start(1))

        val other = identity.copy(turnId = "turn-2")
        val result = store.apply(start(2, other))

        assertEquals(RejectionReason.IdentityMismatch, (result as DraftApplyResult.Rejected).reason)
    }

    @Test
    fun `metadata 段的正常路径仍然可用`() {
        val store = DraftStore()
        store.apply(start(1))

        val added = store.apply(metadata(2, "img-1", PartKind.Image, "content://a"))
            as DraftApplyResult.Applied
        assertEquals(PartKind.Image, added.snapshot.parts.single().kind)

        val updated = store.apply(
            DraftEvent.SetMetadataPart(3, identity, "img-1", PartKind.Image, "content://b", mapOf("w" to "2")),
        ) as DraftApplyResult.Applied
        val part = updated.snapshot.parts.single()
        assertEquals("content://b", part.text)
        assertEquals(mapOf("w" to "2"), part.metadata)
    }

    @Test
    fun `未开始的草稿不认任何内容事件`() {
        val store = DraftStore()

        val result = store.apply(DraftEvent.AppendText(1, identity, "p1", "x"))

        assertEquals(RejectionReason.NotStarted, (result as DraftApplyResult.Rejected).reason)
    }

    @Test
    fun `非正序列与重复序列各归其类`() {
        val store = DraftStore()
        store.apply(start(5))
        store.apply(DraftEvent.AppendText(6, identity, "p1", "a"))

        val stale = store.apply(DraftEvent.AppendText(3, identity, "p1", "b"))
        val dup = store.apply(DraftEvent.AppendText(6, identity, "p1", "c"))

        assertTrue("回退的序号按忽略处理", stale is DraftApplyResult.IgnoredStale)
        assertTrue("重放的序号按重复处理", dup is DraftApplyResult.Duplicate)
        assertEquals("两次都不该改正文", "a", store.snapshot().parts.single().text)
    }

    @Test
    fun `空增量被拒绝且不吃掉序号`() {
        val store = DraftStore()
        store.apply(start(1))

        val empty = store.apply(DraftEvent.AppendText(2, identity, "p1", ""))

        assertEquals(RejectionReason.EmptyAppend, (empty as DraftApplyResult.Rejected).reason)
        // 被拒的事件不该占用序号：否则后续同样序号会被当成重放丢掉
        val retry = store.apply(DraftEvent.AppendText(2, identity, "p1", "有内容了"))
        assertTrue("同一序号重试要能生效: $retry", retry is DraftApplyResult.Applied)
    }
}
