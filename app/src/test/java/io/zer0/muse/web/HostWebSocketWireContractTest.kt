package io.zer0.muse.web

import kotlinx.serialization.descriptors.SerialDescriptor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Host WebSocket 的**跨语言线契约**：Kotlin 吐出的键，必须就是网页客户端读的键。
 *
 * 这条测试存在的理由是一个已经发生的事故：回执载荷的键名两侧不一致
 * （Kotlin 发 `memory` / `memoryHits` / `knowledgeBases` / `systemStatus`，
 * 而客户端只读 `items` / `bases` / `system`），于是记忆页、知识库页、数据隐私页
 * 拿到的是空值 —— **而两侧测试全绿**：
 *  - Kotlin 侧只测"同一个类型 encode→decode 回来相等"（自己跟自己一致，当然通过）；
 *  - 客户端侧的 `check-*.mjs` 只做源码字符串断言。
 * 一个跨语言契约被两份"自己闭环"的测试保护，等于没有保护。
 *
 * 所以这里两头都钉：
 *  1. 序列化器实际吐出的元素名必须是 `items`/`bases`/`system`，且旧的键不得复现；
 *  2. 客户端源码必须真的在读这些名字（改客户端契约而不改这里，同样会红）。
 */
class HostWebSocketWireContractTest {

    /** 只用稳定 API：按元素下标取名（`serialNames` 那个扩展属性依赖版本，不用）。 */
    private fun SerialDescriptor.wireNames(): Set<String> =
        (0 until elementsCount).map { getElementName(it) }.toSet()

    private val eventNames: Set<String> by lazy {
        HostEvent.serializer().descriptor.wireNames()
    }

    @Test
    fun `回执载荷的线上键必须是客户端读的那一套`() {
        assertTrue(
            "记忆回执的线上键应是 items（客户端 HostEvent.items），实际=$eventNames",
            eventNames.contains("items"),
        )
        assertTrue("知识库回执应是 bases，实际=$eventNames", eventNames.contains("bases"))
        assertTrue("状态回执应是 system，实际=$eventNames", eventNames.contains("system"))
    }

    @Test
    fun `旧的 Kotlin 属性名不得再泄漏到线上`() {
        listOf("memory", "memoryHits", "knowledgeBases", "systemStatus").forEach { dead ->
            assertFalse(
                "客户端不读 `$dead`，它出现在线上就意味着键名又漂回去了",
                eventNames.contains(dead),
            )
        }
    }

    @Test
    fun `搜索命中是扁平条目，不再嵌套一层`() {
        val entryNames = HostMemoryEntry.serializer().descriptor.wireNames()
        assertTrue("命中片段应挂在条目自己身上：$entryNames", entryNames.contains("snippet"))

        // `memoryHits` 这类"再套一层"的形状会让客户端 item.content 取到 undefined
        assertFalse(eventNames.contains("memoryHits"))
    }

    @Test
    fun `客户端确实声明了这些键（反向防漂移）`() {
        val api = readClientSource("api.ts")
        listOf("readonly items?:", "readonly bases?:", "readonly system?:").forEach { needle ->
            assertTrue("客户端 api.ts 里找不到 $needle —— 契约变了就要同步这里", api.contains(needle))
        }
        val hook = readClientSource("host/useHostConnection.ts")
        assertTrue("客户端读的是 event.items", hook.contains("event.items"))
        assertTrue("客户端读的是 event.bases", hook.contains("event.bases"))
        assertTrue("客户端读的是 event.system", hook.contains("event.system"))
    }

    private fun readClientSource(relative: String): String {
        val candidates = listOf(
            File("../../web/apps/client/src/$relative"),
            File("web/apps/client/src/$relative"),
            File("../web/apps/client/src/$relative"),
        )
        return requireNotNull(candidates.firstOrNull { it.exists() }) {
            "找不到客户端源码 $relative（工作目录=${File(".").absolutePath}）"
        }.readText()
    }
}
