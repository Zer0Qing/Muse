package io.zer0.muse.computenode

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * @@MUSE_BOOT 行解析契约。
 *
 * 解析结果直接驱动安装进度条，因此这里覆盖三类边界：合法四阶段事件、带 data 的
 * DAEMON 事件（Token 只在 data 里，不得出现在任何字符串化输出中）、以及脚本
 * stderr/普通日志混入 stdout 时必须安全跳过。
 */
class BootstrapEventParserTest {

    @Test
    fun runningEventParsesCoreFields() {
        val event = BootstrapEventParser.parseLine(
            """@@MUSE_BOOT {"phase":"PROBE","step":"os_check","percent":15,"status":"running","msg":"detecting os"}""",
        )

        assertNotNull(event)
        event ?: return
        assertEquals(BootstrapPhase.PROBE, event.phase)
        assertEquals("os_check", event.step)
        assertEquals(15, event.percent)
        assertEquals(BootstrapStatus.RUNNING, event.status)
        assertEquals("detecting os", event.message)
        assertTrue(event.data.isEmpty())
    }

    @Test
    fun okEventParsesNestedData() {
        val event = BootstrapEventParser.parseLine(
            """@@MUSE_BOOT {"phase":"PROBE","step":"done","percent":25,"status":"ok","msg":"probe done","data":{"os":"ubuntu-24.04","arch":"x86_64","memMb":2048,"hasSwap":false}}""",
        )

        assertNotNull(event)
        event ?: return
        assertEquals(BootstrapPhase.PROBE, event.phase)
        assertEquals(BootstrapStatus.OK, event.status)
        assertEquals("ubuntu-24.04", event.stringValue("os"))
        assertEquals("x86_64", event.stringValue("arch"))
        assertEquals(2048, event.data["memMb"]?.jsonPrimitive?.int)
    }

    @Test
    fun daemonTokenStayOutOfToString() {
        val token = "test-token-placeholder"
        val event = BootstrapEventParser.parseLine(
            """@@MUSE_BOOT {"phase":"DAEMON","step":"systemd","percent":60,"status":"ok","msg":"daemon ready","data":{"port":18765,"bind":"127.0.0.1","token":"$token","fp":"SHA256:placeholder"}}""",
        )

        assertNotNull(event)
        event ?: return
        assertEquals(token, event.stringValue("token"))
        assertFalse("BootstrapEvent.toString 不得泄露 Token", event.toString().contains(token))
    }

    @Test
    fun humanReadableLinesAreIgnored() {
        assertNull(BootstrapEventParser.parseLine("[muse-bootstrap] installing core pack"))
        assertNull(BootstrapEventParser.parseLine(""))
        assertNull(BootstrapEventParser.parseLine("""@@MUSE_BOOTISH {"phase":"PROBE"}"""))
    }

    @Test
    fun malformedJsonIsIgnored() {
        assertNull(BootstrapEventParser.parseLine("@@MUSE_BOOT {not-json"))
        assertNull(BootstrapEventParser.parseLine("@@MUSE_BOOT [1,2,3]"))
        assertNull(BootstrapEventParser.parseLine("""@@MUSE_BOOT {"phase":"PROBE"}"""))
    }

    @Test
    fun unknownPhaseOrStatusIsRejected() {
        assertNull(
            BootstrapEventParser.parseLine(
                """@@MUSE_BOOT {"phase":"UNKNOWN","step":"x","percent":1,"status":"running","msg":"x"}""",
            ),
        )
        assertNull(
            BootstrapEventParser.parseLine(
                """@@MUSE_BOOT {"phase":"PROBE","step":"x","percent":1,"status":"weird","msg":"x"}""",
            ),
        )
    }

    @Test
    fun missingDataDefaultsToEmptyObject() {
        val event = BootstrapEventParser.parseLine(
            """@@MUSE_BOOT {"phase":"VERIFY","step":"health","percent":100,"status":"ok","msg":"ready"}""",
        )

        assertNotNull(event)
        event ?: return
        assertEquals(BootstrapPhase.VERIFY, event.phase)
        assertEquals(BootstrapStatus.OK, event.status)
        assertTrue(event.data.isEmpty())
        assertNull(event.stringValue("missing"))
    }

    @Test
    fun surroundingWhitespaceIsTolerated() {
        val event = BootstrapEventParser.parseLine(
            """  @@MUSE_BOOT {"phase":"PACKS","step":"core","percent":85,"status":"ok","msg":"core ready","data":{"packs":["core"]}}  """,
        )

        assertNotNull(event)
        event ?: return
        assertEquals(BootstrapPhase.PACKS, event.phase)
        assertEquals(85, event.percent)
        assertEquals(BootstrapStatus.OK, event.status)
    }
}
