package io.zer0.muse.web.a2a

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUD-20261011-A2A-PROTOCOL（.audit REPORT_DOCS_TESTS F2 / REPORT_SECURITY F2 / REPORT_AGENT F5）：
 *
 * A2A 是 v2.5.8 的旗舰外部入口，但此前协议与任务簿完全无测试，且有三处实现缺陷：
 *  1) GetTask / CancelTask 把 JSON-RPC **信封 id** 当 task id 读（规范要求 params.id）——
 *     正常 A2A 客户端永远取不回任务；
 *  2) CancelTask 无视 taskId 一律 chatViewModel.stop()，可打断用户前台正在跑的生成；
 *  3) 任务状态不区分"本 handler 未知的任务"与"已知任务"，无法做归属校验。
 *
 * 本测试锁定任务簿的归属与状态语义（纯逻辑，不依赖 Android/ViewModel）。
 */
class A2aTaskRegistryTest {

    private fun task(id: String, state: String) = A2aTask(id = id, state = state)

    @Test
    fun `registry returns the recorded task by id`() {
        val registry = A2aTaskRegistry()
        registry.record(task("t1", A2aTaskState.WORKING))
        assertEquals(A2aTaskState.WORKING, registry.get("t1")?.state)
    }

    @Test
    fun `unknown task is not owned and returns null`() {
        val registry = A2aTaskRegistry()
        assertNull(registry.get("does-not-exist"))
        assertFalse(
            "未登记的任务不得被认领,否则 CancelTask 会误停用户自己的生成",
            registry.isOwned("does-not-exist"),
        )
    }

    @Test
    fun `recorded task is owned`() {
        val registry = A2aTaskRegistry()
        registry.record(task("t1", A2aTaskState.WORKING))
        assertTrue(registry.isOwned("t1"))
    }

    @Test
    fun `registry is bounded and evicts the oldest`() {
        val registry = A2aTaskRegistry(maxEntries = 3)
        registry.record(task("t1", A2aTaskState.COMPLETED))
        registry.record(task("t2", A2aTaskState.COMPLETED))
        registry.record(task("t3", A2aTaskState.COMPLETED))
        registry.record(task("t4", A2aTaskState.COMPLETED))
        assertNull("最旧的任务应被逐出,避免任务簿无界增长", registry.get("t1"))
        assertTrue(registry.isOwned("t4"))
        assertEquals(3, registry.size)
    }

    @Test
    fun `recording the same id twice updates in place`() {
        val registry = A2aTaskRegistry()
        registry.record(task("t1", A2aTaskState.WORKING))
        registry.record(task("t1", A2aTaskState.COMPLETED))
        assertEquals(A2aTaskState.COMPLETED, registry.get("t1")?.state)
        assertEquals(1, registry.size)
    }

    @Test
    fun `only working tasks are cancelable`() {
        val registry = A2aTaskRegistry()
        registry.record(task("done", A2aTaskState.COMPLETED))
        registry.record(task("running", A2aTaskState.WORKING))
        assertFalse("已完成的任务不该再触发停止", registry.isCancelable("done"))
        assertTrue(registry.isCancelable("running"))
        assertFalse("未知任务不可取消", registry.isCancelable("unknown"))
    }
}
