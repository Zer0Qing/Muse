package io.zer0.memory.fact

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P0-4 回归:记忆中心 UI 与生产写入同库路由原语。
 *
 * 生产侧(DeepMemoryProcessor / MemoryAutoSaveScheduler / SystemPromptAssembler)
 * 按助手分库写事实([FactDbProvider.getFactStore]),记忆中心 UI 现按同一 provider
 * 解析 store。本测试验证核心约束:
 *  - UI 对子助手 scope 写入 → 该分库能读回(此前落在默认库,对 AI 无效)
 *  - 其他 scope 的分库不受影响(写入隔离)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FactDbProviderScopeIsolationTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `write to sub-assistant scope is read back from its own db and isolated from others`() = runTest {
        val provider = FactDbProvider(context)
        try {
            val storeA = provider.getFactStore("assistant_a")
            val storeB = provider.getFactStore("assistant_b")
            val defaultStore = provider.getFactStore("default")

            val addedId = storeA.add(
                FactStore.Fact(fact = "用户喜欢蓝色主题"),
                scope = "assistant_a",
                spaceId = "default",
            )
            assertTrue("写入子助手分库必须成功", addedId > 0)

            // 目标 scope 能读回
            val readBack = storeA.getByScopeAndSpace("assistant_a", "default")
            assertTrue("目标分库必须能读回 UI 写入的事实", readBack.any { it.id == addedId && it.fact.contains("蓝色主题") })

            // 其他 scope 分库不受影响
            assertEquals("其他子助手分库不得读到该事实", 0, storeB.getByScopeAndSpace("assistant_b", "default").size)
            assertEquals("主助手默认库不得读到该事实", 0, defaultStore.getByScopeAndSpace("main", "default").size)
        } finally {
            provider.releaseAll()
        }
    }

    @Test
    fun `main scope write stays in default db`() = runTest {
        val provider = FactDbProvider(context)
        try {
            val defaultStore = provider.getFactStore("default")

            defaultStore.add(FactStore.Fact(fact = "主助手事实示例"), scope = "main", spaceId = "default")

            assertTrue(defaultStore.getByScopeAndSpace("main", "default").isNotEmpty())
        } finally {
            provider.releaseAll()
        }
    }

    /**
     * SEC-04 前提：各分库的自增 id **会撞号**。
     *
     * 这正是「按裸 id 跨库查找」必然出错的原因——记忆中心的编辑/删除/置顶必须带 scope 精确定位
     * （见 `MemoryViewModel.storeForFact`），不能靠 id 猜归属。若哪天分库改成全局唯一 id，
     * 这条会失败，届时才可以放心简化路由逻辑。
     */
    @Test
    fun `fact ids collide across per-assistant databases`() = runTest {
        val provider = FactDbProvider(context)
        try {
            val storeA = provider.getFactStore("assistant_a")
            val storeB = provider.getFactStore("assistant_b")

            val idA = storeA.add(
                FactStore.Fact(fact = "A 助手的事实"),
                scope = "assistant_a",
                spaceId = "default",
            )
            val idB = storeB.add(
                FactStore.Fact(fact = "B 助手的事实"),
                scope = "assistant_b",
                spaceId = "default",
            )

            assertEquals("两个分库各自自增，首条事实 id 相同", idA, idB)
            assertTrue("A 库按自己的 id 读到 A 的记录", storeA.getById(idA)?.fact?.contains("A 助手") == true)
            assertTrue("B 库按同一 id 读到的是 B 的记录", storeB.getById(idB)?.fact?.contains("B 助手") == true)
        } finally {
            provider.releaseAll()
        }
    }
}
