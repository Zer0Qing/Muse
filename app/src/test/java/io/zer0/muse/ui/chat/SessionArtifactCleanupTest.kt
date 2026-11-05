package io.zer0.muse.ui.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.common.Logger
import io.zer0.muse.data.chat.ConversationTreeSnapshotStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 会话残留清理器的契约：**每一项都得真做，做不成都要留痕**。
 *
 * 这个清单以前分叉在两处：单条删除做了 6 件，「清除全部聊天」一件都没做
 * （只清到 DB 层）。所以这里除了正向行为，还专门钉两条：
 *
 *  1. 依赖拿不到（比如快照仓库没注入）时**不能静默跳过** —— 必须算失败并写日志，
 *     否则"以为清了"比"没清"更糟：前者之后再没人会去查；
 *  2. 返回的失败数必须与日志条数一致 —— 数字与痕迹对不上，说明有一项被吞了。
 *
 * 注：本测试不启动 Koin，因此库里待办 / 附件索引 / 工具可见集这三项
 * 必然走"依赖不可用"分支 —— 这正好把上面第 1 条测到了，
 * 计数不做脆性断言（不断"恰好 3 项失败"，只断"计数 == 留痕条数"）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionArtifactCleanupTest {

    private val tag = "SessionArtifacts"
    private val logs = mutableListOf<String>()

    @After
    fun tearDown() {
        Logger.sink = null
        logs.clear()
    }

    private fun captureLogs() {
        Logger.sink = { level, t, msg, _ ->
            if (t == tag) logs += "$level|$msg"
        }
    }

    private fun snapshotFile(sessionId: String): File = File(snapshotDir(), "$sessionId.json")

    private fun snapshotDir(): File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return File(context.filesDir, "conversation_tree_snapshots").apply { mkdirs() }
    }

    private fun store(): ConversationTreeSnapshotStore = ConversationTreeSnapshotStore(ApplicationProvider.getApplicationContext())

    @Test
    fun `删会话要真的把对话树快照文件清掉`() {
        captureLogs()
        val file = snapshotFile("s-keep").apply { writeText("{}") }
        assertTrue("前置条件：快照文件应当存在", file.exists())

        runBlocking {
            SessionArtifactCleanup.purge(listOf("s-keep"), store(), "删除会话")
        }

        assertFalse("快照没被清掉：同名会话重建时（恢复备份/归档回滚）会读回上一个会话的选中状态", file.exists())
    }

    @Test
    fun `失败计数必须与留痕条数一致（不能有一项被吞）`() {
        captureLogs()

        val failures = runBlocking {
            SessionArtifactCleanup.purge(listOf("s-count"), store(), "删除会话")
        }

        val logged = logs.count { it.contains("会话残留清理失败") }
        assertEquals("计数与留痕不一致：有失败没被记下来", failures, logged)
        assertTrue("没有 Koin 时依赖项应当算失败：$logs", failures > 0)
    }

    @Test
    fun `快照仓库拿不到时算失败并写清楚，而不是静默跳过`() {
        captureLogs()
        snapshotFile("s-null").writeText("{}")

        val failures = runBlocking {
            SessionArtifactCleanup.purge(listOf("s-null"), null, "删除会话")
        }

        assertTrue(
            "依赖不可用必须被记为失败（静默跳过等于谎称清干净了）",
            logs.any { it.contains("对话树快照") && it.contains("快照仓库不可用") },
        )
        assertTrue("而且计数要算上它", failures >= 1)
        assertTrue("文件应当仍在原处（没被假装删过）", snapshotFile("s-null").exists())
    }

    @Test
    fun `空列表不做事也不写完成日志`() {
        captureLogs()

        val failures = runBlocking { SessionArtifactCleanup.purge(emptyList(), store(), "清除全部聊天") }

        assertEquals(0, failures)
        assertTrue("不该产生任何留痕：$logs", logs.isEmpty())
    }

    @Test
    fun `多个会话逐个清，一个都漏不掉`() {
        captureLogs()
        val ids = listOf("m-1", "m-2", "m-3")
        ids.forEach { snapshotFile(it).writeText("{}") }

        runBlocking { SessionArtifactCleanup.purge(ids, store(), "清除全部聊天") }

        ids.forEach { assertFalse("会话 $it 的快照残留还在", snapshotFile(it).exists()) }
    }
}
