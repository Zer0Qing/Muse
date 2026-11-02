package io.zer0.muse.data.plugin

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 定时任务的"上次执行时间"落盘（`schedule.background` 的前提）。
 *
 * 这份记录决定"要不要补跑"。所以两个方向都要守：
 *  - 记录必须**跨实例**保留（否则进程一死就等于从没跑过，下次启动集体触发）；
 *  - 文件坏掉时必须**当作空表**而不是抛异常 —— 读不到就让所有任务重跑，比不跑更糟。
 */
class PluginScheduleStoreTest {

    private lateinit var dir: File
    private lateinit var file: File

    @Before
    fun setUp() {
        dir = kotlin.io.path.createTempDirectory("muse-sched").toFile()
        file = File(dir, PluginScheduleStore.FILE_NAME)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `a recorded run survives a new instance`() {
        PluginScheduleStore(file).record("p1#digest", 1_000L)

        // 新实例 = 新进程
        assertEquals(1_000L, PluginScheduleStore(file).load()["p1#digest"])
    }

    @Test
    fun `an unknown key reads as null`() {
        assertNull(PluginScheduleStore(file).load()["nope"])
    }

    @Test
    fun `forgetting a key removes only that key`() {
        val store = PluginScheduleStore(file)
        store.record("a", 1L)
        store.record("b", 2L)

        store.forget("a")

        assertEquals(setOf("b"), store.load().keys)
    }

    @Test
    fun `a corrupt file reads as empty instead of throwing`() {
        file.writeText("{ 这不是 JSON")

        assertTrue("坏文件要当作空表", PluginScheduleStore(file).load().isEmpty())
    }

    @Test
    fun `a missing file reads as empty`() {
        assertTrue(PluginScheduleStore(File(dir, "nope.json")).load().isEmpty())
    }

    @Test
    fun `recording twice keeps the latest timestamp`() {
        val store = PluginScheduleStore(file)
        store.record("k", 1L)
        store.record("k", 9L)

        assertEquals(9L, store.load()["k"])
    }
}
