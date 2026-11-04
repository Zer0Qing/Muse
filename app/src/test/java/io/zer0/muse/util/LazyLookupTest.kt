package io.zer0.muse.util

import io.zer0.common.Logger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `lookupOrNull` 的契约：**降级可以沉默，故障不能沉默**。
 *
 * 这个助手是从 `AppKoinModule` 里那句 `runCatching { get<GroupChatScheduler>() }.getOrNull()`
 * 抽出来的。原写法的问题不是"会返回 null"（测试环境就该降级），而是
 * **生产上装配出错时它也一声不响**：`agent_phone` 只回一句「未配置」，
 * 日志里没有原因，用户看到的是"悄悄发了但没人回"。
 *
 * 所以这里断言两面：
 *  - 行为不变（失败返回 null、绝不抛，调用方照旧降级）；
 *  - **必须留下带原因的一条日志**（用 `Logger.sink` 抓，不靠读代码确认）。
 *
 * 注意 `Logger.sink` 是**进程级全局**的，而同一 JVM 里其它测试类遗留的后台协程
 * 可能正好在窗口里打日志（实测撞到过一条 RAG 的 DEBUG）。
 * 所以所有断言都先按自己的 tag 过滤，不写"一条日志都没有"这种会被环境噪声打红的断言。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LazyLookupTest {

    private val seen = mutableListOf<Triple<String, String, Throwable?>>()

    @After
    fun tearDown() {
        Logger.sink = null
        seen.clear()
    }

    private fun captureSink() {
        seen.clear()
        Logger.sink = { level, tag, msg, throwable ->
            seen += Triple("$level|$tag", msg, throwable)
        }
    }

    /** 只取本助手发出的日志：环境里可能有别的模块在并发写日志。 */
    private fun ourLogs() = seen.filter { it.first.contains(TAG_UNDER_TEST) }

    @Test
    fun `取到值时原样返回且不多打日志`() {
        captureSink()

        val value = lookupOrNull("GroupChatScheduler") { "scheduler-instance" }

        assertEquals("scheduler-instance", value)
        assertTrue("成功路径不该由本助手产生日志：${ourLogs()}", ourLogs().isEmpty())
    }

    @Test
    fun `解析失败时返回 null 并记下原因与可用于定位的堆栈`() {
        captureSink()
        val boom = IllegalStateException("No definition found for class GroupChatScheduler")

        val value: String? = lookupOrNull("GroupChatScheduler") { throw boom }

        assertNull("失败必须降级为 null，不能把异常抛给工具调用方", value)
        val record = ourLogs().singleOrNull()
        assertNotNull("解析失败却没留下日志，正是这次要修的静默：$seen", record)
        assertTrue("日志要带上依赖名，便于定位是哪条注入：${record!!.second}", record.second.contains("GroupChatScheduler"))
        assertTrue("日志要带上原始原因文本：${record.second}", record.second.contains("No definition found"))

        // 出口拿到的是 `sanitizedThrowable` 的包装件（文本含类名与因果链、栈指向原抛出点），
        // 不是异常本体 —— 挂回原对象等于让 Android 原样打印那条未脱敏的消息。
        // 所以这里断言"能不能定位"，而不是"是不是同一个对象"。
        val thrown = record.third
        assertNotNull("要带上异常信息，光一行文字不足以定位：$record", thrown)
        val text = thrown!!.toString()
        assertTrue("文本要能看出原始类型：$text", text.contains("IllegalStateException"))
        assertTrue("文本要带原因：$text", text.contains("No definition found"))
        assertTrue(
            "栈必须指向真正的抛出点，而不是日志调用处：${thrown.stackTrace.joinToString()}",
            thrown.stackTrace.any { it.className.contains("LazyLookupTest") },
        )
    }

    @Test
    fun `原因文字为空时也不能写出空日志`() {
        captureSink()
        val noMessage = NullPointerException()

        val value: String? = lookupOrNull("PluginMarket") { throw noMessage }

        assertNull(value)
        val record = ourLogs().singleOrNull()
        assertNotNull(record)
        assertTrue(
            "message 为 null 时要退回类名，否则日志只剩「原因: null」等于没写：${record!!.second}",
            record.second.contains("NullPointerException"),
        )
    }

    private companion object {
        /** 与 `LazyLookup` 内部使用的 tag 保持一致。 */
        private const val TAG_UNDER_TEST = "LazyLookup"
    }
}
