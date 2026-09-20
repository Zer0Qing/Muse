package io.zer0.memory.fact

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 3 (可靠性 P1): 事实写入后的增量对账触发测试。
 *
 * 背景: FACTS 编译产物原先只由每日流水线(12–24h)对账一次,用户在记忆页新增/编辑事实后
 * system prompt 仍注入旧表述。修复后 add/addBatch/update 成功即触发一次对账 hook,
 * 并有节流/合并与递归防护:
 *  - 单条 add/update 触发一次 hook,携带写入后的最终文本与 scope/space;
 *  - 窗口内多次(批量)写入合并为一次 hook 调用;
 *  - hook 内部再次写库不会递归触发;
 *  - 未注入 hook(单测/无 DI)时零调度开销,行为与旧版一致。
 *
 * 说明: 采用 runBlocking 而非 runTest —— hook 在独立 IO 协程中执行,runTest 的虚拟时钟
 * 会在真实后台任务完成前耗尽 withTimeout 预算。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FactStoreReconcileTriggerTest {

    private lateinit var db: FactDb
    private lateinit var dao: FactDao

    /** hook 调用记录(scope/space/facts)。 */
    private data class HookCall(
        val facts: List<String>,
        val scope: String,
        val spaceId: String,
    )

    private class RecordingHook : FactStore.FactReconcileHook {
        val calls = CopyOnWriteArrayList<HookCall>()

        override suspend fun onFactsChanged(facts: List<FactStore.Fact>, scope: String, spaceId: String) {
            calls.add(HookCall(facts.map { it.fact }, scope, spaceId))
        }

        /** 轮询等待第 [count] 次调用(真实时间,最多 [timeoutMs])。 */
        suspend fun awaitCalls(count: Int, timeoutMs: Long = 5_000) {
            withTimeout(timeoutMs) {
                while (calls.size < count) delay(20)
            }
        }
    }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, FactDb::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.factDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun storeWithHook(hook: FactStore.FactReconcileHook): FactStore =
        FactStore(dao, db, reconcileHook = hook).also { it.reconcileDebounceMs = 120 }

    @Test
    fun `add triggers reconcile with written fact and scope`() = runBlocking {
        val hook = RecordingHook()
        val store = storeWithHook(hook)

        store.add(FactStore.Fact(fact = "喜欢喝美式咖啡"), scope = "main", spaceId = "default")
        hook.awaitCalls(1)
        delay(300) // 等待可能的额外窗口,验证未重复触发

        assertEquals("add 应触发一次对账", 1, hook.calls.size)
        assertEquals(listOf("喜欢喝美式咖啡"), hook.calls[0].facts)
        assertEquals("main", hook.calls[0].scope)
        assertEquals("default", hook.calls[0].spaceId)
    }

    @Test
    fun `addBatch coalesces into a single reconcile call`() = runBlocking {
        val hook = RecordingHook()
        val store = storeWithHook(hook)

        store.addBatch(
            listOf(
                FactStore.Fact(fact = "喜欢喝美式咖啡"),
                FactStore.Fact(fact = "周末常去公园跑步"),
                FactStore.Fact(fact = "正在学习吉他"),
            ),
            scope = "main",
            spaceId = "default",
        )
        hook.awaitCalls(1)
        delay(300)

        assertEquals("批量写入应节流合并为一次对账", 1, hook.calls.size)
        assertEquals(
            setOf("喜欢喝美式咖啡", "周末常去公园跑步", "正在学习吉他"),
            hook.calls[0].facts.toSet(),
        )
    }

    @Test
    fun `rapid adds within debounce window coalesce`() = runBlocking {
        val hook = RecordingHook()
        val store = storeWithHook(hook)

        store.add(FactStore.Fact(fact = "喜欢喝美式咖啡"))
        store.add(FactStore.Fact(fact = "周末常去公园跑步"))

        hook.awaitCalls(1)
        delay(300)
        assertEquals("窗口内连续写入应合并为一次对账", 1, hook.calls.size)
        assertEquals(2, hook.calls[0].facts.size)
    }

    @Test
    fun `update triggers reconcile with new content`() = runBlocking {
        val hook = RecordingHook()
        val store = storeWithHook(hook)
        val id = store.add(FactStore.Fact(fact = "喜欢喝美式咖啡"))
        hook.awaitCalls(1)
        delay(250)

        store.update(id, "喜欢喝手冲咖啡")
        hook.awaitCalls(2)
        delay(250)

        assertEquals(2, hook.calls.size)
        assertEquals(listOf("喜欢喝手冲咖啡"), hook.calls[1].facts)
    }

    @Test
    fun `hook writing back into store does not recurse`() = runBlocking {
        val calls = AtomicInteger(0)
        var store: FactStore? = null
        val hook = FactStore.FactReconcileHook { _, _, _ ->
            calls.incrementAndGet()
            // hook 内部再次写库:不得再次触发对账(防递归),由每日流水线兜底
            store?.update(1L, "hook 内部改写的文本")
        }
        store = storeWithHook(hook)

        store.add(FactStore.Fact(fact = "喜欢喝美式咖啡"))
        withTimeout(5_000) {
            while (calls.get() < 1) delay(20)
        }
        delay(400)

        assertEquals("hook 内的写入不得递归触发对账", 1, calls.get())
    }

    @Test
    fun `no hook configured keeps writes working without scheduling`() = runBlocking {
        // 默认构造(hook=null,无 DI 解析)时行为与旧版一致:写入成功且不抛异常
        val store = FactStore(dao, db)
        val id = store.add(FactStore.Fact(fact = "喜欢喝美式咖啡"))
        assertTrue(id > 0)
        assertEquals("喜欢喝美式咖啡", store.getById(id)?.fact)
        assertTrue(store.update(id, "喜欢喝手冲咖啡"))
        assertEquals("喜欢喝手冲咖啡", store.getById(id)?.fact)
    }
}
