package io.zer0.muse.tools

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 浏览器设置存储。
 *
 * 要钉住的是**单一实例**这件事：注册表里可能同时存在多个 `BrowserManager`
 * （`sharedManager` + 每会话各一个），如果每个各建一份 store，就会各存一份内存态 StateFlow ——
 * 设置页改了引擎，正在用的 manager 还读着旧值，表现就是"保存了但没生效"。
 * 这一类推导过太多次（顺序表、能力表、上限都因为抄第二份漂移过），所以直接写成断言。
 */
@RunWith(RobolectricTestRunner::class)
class BrowserSettingsStoreTest {

    private val context by lazy { ApplicationProvider.getApplicationContext<android.content.Context>() }

    @Before
    fun setUp() {
        BrowserSettingsStore.resetForTests()
    }

    @After
    fun tearDown() {
        BrowserSettingsStore.resetForTests()
    }

    @Test
    fun `全应用共用同一个实例`() {
        val a = BrowserSettingsStore.of(context)
        val b = BrowserSettingsStore.of(context)
        assertSame("两个调用点必须拿到同一个对象，否则状态各存一份", a, b)
    }

    @Test
    fun `默认值与改动前的浏览器行为一致`() {
        val store = BrowserSettingsStore.of(context)
        assertEquals("默认引擎必应（与参照平台的中文环境一致）", BrowserSearchEngine.BING, store.engine.value)
        assertTrue("默认接受 Cookies：不顺手改变用户已有的登录态习惯", store.acceptCookies.value)
    }

    @Test
    fun `设置引擎后另一个引用点立即可见`() = runTest {
        val store = BrowserSettingsStore.of(context)
        store.setEngine(BrowserSearchEngine.DUCKDUCKGO)
        // 重新 of() 拿到的必须是同一个已更新的状态（而不是重新读盘或各存一份）
        assertEquals(BrowserSearchEngine.DUCKDUCKGO, BrowserSettingsStore.of(context).engine.value)
    }

    @Test
    fun `关闭 Cookies 开关不改变引擎选择`() = runTest {
        val store = BrowserSettingsStore.of(context)
        store.setEngine(BrowserSearchEngine.BAIDU)
        store.setAcceptCookies(false)
        assertEquals(BrowserSearchEngine.BAIDU, store.engine.value)
        assertEquals(false, store.acceptCookies.value)
    }

    @Test
    fun `未知引擎 id 回落默认`() {
        assertEquals(BrowserSearchEngine.BING, BrowserSearchEngine.of("不存在的 id"))
        assertEquals(BrowserSearchEngine.BING, BrowserSearchEngine.of(null))
    }

    @Test
    fun `每个引擎的模板都能填进查询词`() {
        BrowserSearchEngine.entries.forEach { engine ->
            assertTrue(
                "${engine.id} 的模板缺了占位符：%s".format(engine.searchTemplate),
                engine.searchTemplate.contains("%s"),
            )
            assertTrue("必须是 https", engine.searchTemplate.startsWith("https://"))
        }
    }
}
