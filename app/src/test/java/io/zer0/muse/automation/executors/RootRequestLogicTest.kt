package io.zer0.muse.automation.executors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Root 授权请求纯逻辑测试 —— 不启动任何子进程、不执行 su。
 *
 * 覆盖:命令构造、`uid=0` 判定、退出码/输出到失败原因的映射、超时与异常映射。
 * root 管理器弹窗无法在测试环境复现,进程编排由 [RootExecutor.requestRootAccess] 负责;
 * 这里锁定的正是 UI 依赖的结果语义。
 */
class RootRequestLogicTest {

    // ── 命令构造 ─────────────────────────────────────────────────────────

    @Test
    fun `probe args reuse su prefix with fixed id command`() {
        assertEquals(listOf("su", "-c", "id"), rootProbeArgs(listOf("su")))
    }

    @Test
    fun `probe args keep whatever prefix the executor declares`() {
        assertEquals(listOf("su", "--mount-master", "-c", "id"), rootProbeArgs(listOf("su", "--mount-master")))
    }

    // ── 输出判定 ─────────────────────────────────────────────────────────

    @Test
    fun `uid 0 output counts as granted`() {
        assertTrue(isRootGranted("uid=0(root) gid=0(root) groups=0(root)"))
        assertTrue(isRootGranted("uid=0(root) gid=0(root) context=u:r:su:s0"))
    }

    @Test
    fun `non root uid does not count as granted`() {
        assertFalse(isRootGranted("uid=2000(shell) gid=2000(shell)"))
        assertFalse(isRootGranted(""))
        assertFalse(isRootGranted(null))
    }

    // ── 终态判定 ─────────────────────────────────────────────────────────

    @Test
    fun `exit code 0 with uid 0 is granted`() {
        val result = evaluateRootProbe(0, "uid=0(root) gid=0(root)")

        assertTrue(result.granted)
        assertNull(result.failure)
    }

    @Test
    fun `exit code 0 with shell uid is denied`() {
        val result = evaluateRootProbe(0, "uid=2000(shell) gid=2000(shell)")

        assertFalse(result.granted)
        assertEquals(RootRequestFailure.DENIED, result.failure)
    }

    @Test
    fun `nonzero exit code is denied even when output mentions uid 0`() {
        val result = evaluateRootProbe(1, "uid=0(root) gid=0(root)")

        assertFalse(result.granted)
        assertEquals(RootRequestFailure.DENIED, result.failure)
    }

    @Test
    fun `missing su binary output maps to no su failure`() {
        val result = evaluateRootProbe(127, "/system/bin/sh: su: not found")

        assertFalse(result.granted)
        assertEquals(RootRequestFailure.NO_SU_BINARY, result.failure)
    }

    // ── 超时 / 异常映射 ──────────────────────────────────────────────────

    @Test
    fun `timeout maps to timeout failure`() {
        val result = rootProbeTimeout()

        assertFalse(result.granted)
        assertEquals(RootRequestFailure.TIMEOUT, result.failure)
    }

    @Test
    fun `security exception maps to denied`() {
        assertEquals(
            RootRequestFailure.DENIED,
            classifyRootProbeException(SecurityException("permission denied")),
        )
    }

    @Test
    fun `other exceptions map to error`() {
        assertEquals(RootRequestFailure.ERROR, classifyRootProbeException(java.io.IOException("broken pipe")))
        assertEquals(RootRequestFailure.ERROR, classifyRootProbeException(IllegalStateException("no process")))
    }

    @Test
    fun `failed result always carries a readable reason`() {
        RootRequestFailure.entries.forEach { failure ->
            val result = RootRequestResult.failed(failure)

            assertFalse(result.granted)
            assertEquals(failure, result.failure)
        }
    }
}
