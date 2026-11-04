package io.zer0.muse.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 设置 DataStore 的实例契约与**命名唯一性**守卫。
 *
 * 两件事都只有运行时会炸，测试不写就没人拦：
 *
 *  1. `muse_settings` 走的是"按 `filesDir` 缓存、一个目录一个实例"（见
 *     [io.zer0.muse.data.museSettingsDataStore] 的注释）。DataStore 明确要求
 *     **同一文件只能有一个实例**，同时开两个会直接抛
 *     `IllegalStateException: There are multiple DataStores active for the same file`。
 *     这条缓存要是有被人改回"每次新建"或者改成 `by preferencesDataStore(...)` 的那天，
 *     测试要立刻红 —— 而不是等某台设备上两个仓库同时读写才发现。
 *  2. 全仓各处 `preferencesDataStore(name = "…")` 是**静态委托**：两个不同文件
 *     一旦起了同一个名字，也是同一个"多实例打同一文件"的错，而且**只有第一次并发读写才炸**，
 *     单看代码完全看不出来。这里把所有名字收进一个集合断言互不相同。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsDataStoreContractTest {

    private val probeKey = stringPreferencesKey("contract_probe")

    private fun sourceFiles(): List<File> {
        val roots = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File("../app/src/main/java"),
        )
        val root = requireNotNull(roots.firstOrNull { it.exists() }) {
            "找不到 app 源码目录，工作目录=${File(".").absolutePath}"
        }
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun `同一个 filesDir 必须复用同一个 muse_settings 实例`() {
        val context = RuntimeEnvironment.getApplication()

        val first: DataStore<Preferences> = context.museSettingsDataStore
        val second: DataStore<Preferences> = context.applicationContext.museSettingsDataStore

        assertSame(
            "「按 filesDir 缓存、目录不变就复用」是 DataStore 的硬要求：两个实例打同一个文件会抛 " +
                "multiple DataStores active for the same file",
            first,
            second,
        )
    }

    @Test
    fun `写进去的设置读得回来（缓存实例没被换掉）`() {
        val context = RuntimeEnvironment.getApplication()
        val store = context.museSettingsDataStore

        runBlocking {
            store.edit { it[probeKey] = "ok" }
            assertEquals("ok", store.data.first()[probeKey])
            // 经由另一条取用路径也必须看见同一条数据（不同实例 = 各自缓存视图）
            assertEquals("ok", context.applicationContext.museSettingsDataStore.data.first()[probeKey])
        }
    }

    @Test
    fun `全仓 preferencesDataStore 名字互不相同`() {
        val pattern = Regex("""preferencesDataStore\(\s*name\s*=\s*"([^"]+)"""")
        val found = linkedMapOf<String, MutableList<String>>()
        for (file in sourceFiles()) {
            val text = file.readText()
            pattern.findAll(text).forEach { match ->
                found.getOrPut(match.groupValues[1]) { mutableListOf() } += file.name
            }
        }

        assertTrue("没扫到任何 DataStore 名字，说明这个守卫已经抓不到东西了（正则或路径要修）", found.isNotEmpty())
        val duplicated = found.filter { (_, files) -> files.distinct().size > 1 }
        assertTrue(
            "两处不同文件用了同一个 DataStore 名字 = 运行期 multiple DataStores active：" +
                duplicated.entries.joinToString { "${it.key} → ${it.value}" },
            duplicated.isEmpty(),
        )
    }

    @Test
    fun `静态委托的名字不许撞上 muse_settings`() {
        val pattern = Regex("""preferencesDataStore\(\s*name\s*=\s*"([^"]+)"""")
        val names = sourceFiles().flatMap { pattern.findAll(it.readText()).map { m -> m.groupValues[1] } }.toSet()

        // muse_settings 走手工工厂 + filesDir 缓存；若再有静态委托用同名，就是第二个实例
        assertTrue("muse_settings 由按目录缓存的工厂独占，静态委托不许重名", "muse_settings" !in names)
        assertTrue("至少应有若干静态委托 store 被扫到", names.size >= 4)
    }
}
