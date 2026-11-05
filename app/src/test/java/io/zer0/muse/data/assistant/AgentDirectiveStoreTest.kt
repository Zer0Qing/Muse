package io.zer0.muse.data.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.proactive.AgentDirectivesPrompt
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * 用户约定存储（整改计划 第 4 条 ③）。
 *
 * 这里锁的是"用户写完第二天还在不在"这一类事：落盘、按助手隔离、
 * 以及**坏文件不许让功能崩掉**（读不出就当没配置，比抛异常或清库都好）。
 */
@RunWith(RobolectricTestRunner::class)
class AgentDirectiveStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun reset() {
        AgentDirectiveStore.resetForTests()
    }

    private fun freshStore(): AgentDirectiveStore {
        AgentDirectiveStore.resetForTests()
        val dir: File = context.filesDir.also { it.mkdirs() }
        File(dir, AgentDirectiveStore.FILE_NAME).delete()
        return AgentDirectiveStore.of(context)
    }

    @Test
    fun `默认没有约定，提示词因此与未上线时逐字相同`() {
        val store = freshStore()
        runTest {
            assertEquals("新装应当是空表", emptyList<AgentDirective>(), store.directivesFor("a1"))
            val built = AgentDirectivesPrompt.build(store.directivesFor("a1"), 600)
            assertEquals("空表不得往提示词里加任何内容", "", built.block)
        }
    }

    @Test
    fun `新增后读回并按不希望在前排序`() {
        val store = freshStore()
        runTest {
            assertTrue(store.add("a1", DirectivePolarity.SHOULD, "早上提醒我吃早饭").isSuccess)
            assertTrue(store.add("a1", DirectivePolarity.MUST_NOT, "别叫我宝贝").isSuccess)
            val list = store.directivesFor("a1")
            assertEquals(2, list.size)
            assertEquals("禁止项要排在前面（装配层也按这个顺序写）", DirectivePolarity.MUST_NOT, list.first().polarity)
        }
    }

    @Test
    fun `按助手隔离，不给 B 立规矩串到 A`() {
        val store = freshStore()
        runTest {
            store.add("a1", DirectivePolarity.MUST_NOT, "别叫我宝贝")
            assertEquals(1, store.directivesFor("a1").size)
            assertEquals("别的助手不该看到这条", emptyList<AgentDirective>(), store.directivesFor("a2"))
        }
    }

    @Test
    fun `空内容与超长内容被拒且不写盘`() {
        val store = freshStore()
        runTest {
            assertTrue(store.add("a1", DirectivePolarity.SHOULD, "   ").isError)
            assertTrue(store.add("a1", DirectivePolarity.SHOULD, "字".repeat(201)).isError)
            assertEquals("被拒的条目不该留下", emptyList<AgentDirective>(), store.directivesFor("a1"))
        }
    }

    @Test
    fun `停用与删除都真的落盘`() {
        val store = freshStore()
        runTest {
            store.add("a1", DirectivePolarity.SHOULD, "早上提醒我")
            val id = store.directivesFor("a1").first().id
            assertNotNull(store.setEnabled(id, false).getOrNull())
            assertEquals(1, store.directivesFor("a1").count { !it.enabled })
            assertNotNull(store.remove(id).getOrNull())
            assertTrue(store.directivesFor("a1").isEmpty())
        }
    }

    @Test
    fun `文件坏了就当没配置，不清库也不崩`() {
        val store = freshStore()
        runTest {
            store.add("a1", DirectivePolarity.SHOULD, "早上提醒我")
            File(context.filesDir, AgentDirectiveStore.FILE_NAME).writeText("{ 这不是合法 JSON ")
            // 重置单例才算"重开一次进程"：不重置的话读到的还是内存里那份旧缓存，测的是空气
            AgentDirectiveStore.resetForTests()
            val reopened = AgentDirectiveStore.of(context)
            assertTrue("坏文件要回落空表", reopened.directivesFor("a1").isEmpty())
        }
    }

    @Test
    fun `移动越界时原样成功，不在界面上弹错`() {
        val store = freshStore()
        runTest {
            store.add("a1", DirectivePolarity.SHOULD, "第一条")
            store.add("a1", DirectivePolarity.SHOULD, "第二条")
            val second = store.directivesFor("a1").last()
            assertNotNull(store.move(second.id, 5).getOrNull())
            assertEquals(2, store.directivesFor("a1").size)
        }
    }
}
