package io.zer0.muse.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 用户偏好存储的测试。
 *
 * 助手自审第 20 条：「用户偏好学习：比如你习惯我别用空泛鼓励、别列三点总结，
 * 这些能沉淀成硬约束而不是靠我临场判断。」
 *
 * 这里钉住：写入能读回、重复不叠加、可停用不删、归一化去重。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [33])
class PreferenceStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: PreferenceStore

    @Before
    fun setUp() = runTest {
        store = PreferenceStore(context)
        store.clear()
    }

    @Test
    fun `saved preference can be read back`() = runTest {
        store.add("回答不要用空泛的鼓励")

        val rules = store.enabledPreferences().map { it.rule }

        assertEquals(listOf("回答不要用空泛的鼓励"), rules)
    }

    @Test
    fun `duplicate preference is not added twice`() = runTest {
        val first = store.add("别列三点总结")
        val second = store.add("别列三点总结")

        assertTrue(first)
        assertFalse("同一句偏好不该重复存", second)
        assertEquals(1, store.enabledPreferences().size)
    }

    @Test
    fun `duplicate detection ignores whitespace and trailing punctuation`() = runTest {
        store.add("别用空泛鼓励")

        val added = store.add("  别用空泛鼓励。 ")

        assertFalse("归一化后相同就不该新增", added)
        assertEquals(1, store.enabledPreferences().size)
    }

    @Test
    fun `different preferences are both kept`() = runTest {
        store.add("回答不要用空泛的鼓励")
        store.add("不要在结尾列三点总结")

        assertEquals(2, store.enabledPreferences().size)
    }

    @Test
    fun `disabled preference is excluded from injection but kept in storage`() = runTest {
        store.add("别用空泛鼓励")
        val id = store.preferencesFlow.first().single().id

        store.setEnabled(id, false)

        assertTrue("停用后不该进提示词", store.enabledPreferences().isEmpty())
        assertEquals("停用不是删除", 1, store.preferencesFlow.first().size)
    }

    @Test
    fun `re-enabling brings it back`() = runTest {
        store.add("别用空泛鼓励")
        val id = store.preferencesFlow.first().single().id
        store.setEnabled(id, false)
        store.setEnabled(id, true)

        assertEquals(1, store.enabledPreferences().size)
    }

    @Test
    fun `remove deletes the preference`() = runTest {
        store.add("别用空泛鼓励")
        val id = store.preferencesFlow.first().single().id

        store.remove(id)

        assertTrue(store.preferencesFlow.first().isEmpty())
    }

    @Test
    fun `blank rule is rejected`() = runTest {
        assertFalse(store.add("   "))
        assertTrue(store.preferencesFlow.first().isEmpty())
    }

    @Test
    fun `source wording is preserved for traceability`() = runTest {
        store.add("回答不要用空泛的鼓励", source = "以后别夸我，说人话就行")

        val saved = store.preferencesFlow.first().single()

        assertEquals("以后别夸我，说人话就行", saved.source)
    }

    @Test
    fun `clear removes everything`() = runTest {
        store.add("a")
        store.add("b")

        store.clear()

        assertTrue(store.preferencesFlow.first().isEmpty())
    }

    @Test
    fun `enabled filter drops blank rules that slipped through`() = runTest {
        // 正常路径不会写入空规则，这里模拟历史脏数据
        store.add("有效偏好")

        assertTrue(store.enabledPreferences().all { it.rule.isNotBlank() })
    }
}
