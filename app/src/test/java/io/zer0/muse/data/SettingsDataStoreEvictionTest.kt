package io.zer0.muse.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * `museSettingsDataStore` 的**僵尸实例回收**契约。
 *
 * 背景是全仓最烦的一族偶发红：Robolectric 每个用例给一个新的 `filesDir`，用例结束后目录被删，
 * 但缓存里的 DataStore 实例还活着、写盘协程仍挂在已删除的路径上 —— 于是随机某个用到设置的用例
 * 撞上 `muse_settings.preferences_pb (另一个程序正在使用此文件)`。
 *
 * 两条规矩都得钉住，因为它们各自对应一种"顺手改坏"：
 *
 *  - **目录还在的实例绝不取消**：历史上栽过，取消顺着 flow 打到别用例的收集协程上
 *    （`DiagnosticExporterZipTest` 的 `JobCancellationException`）；
 *  - **目录已消失的必须回收**：否则实例只增不减、僵尸句柄越攒越多。
 *
 * 断言只看"我这个目录在不在缓存里"，不看缓存总数 ——
 * 同一个 JVM 里别的测试类也往这个缓存放东西，拿总数断言必偶然翻脸。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsDataStoreEvictionTest {

    private val probeKey = stringPreferencesKey("eviction_probe")

    /**
     * 只改 `filesDir` 的上下文。两处覆写都不可少：
     * `Context` 是带构造器的抽象类不能 `by` 委托；而缓存读的是
     * `applicationContext.filesDir` —— 不覆写 `getApplicationContext()` 会被绕回真实目录
     * （这个坑我上一轮刚踩过，测试会"绿着测错的东西"）。
     */
    private class FilesDirContext(
        base: Context,
        private val dir: File,
    ) : android.content.ContextWrapper(base) {
        override fun getFilesDir(): File = dir
        override fun getApplicationContext(): Context = this
    }

    private fun freshDir(label: String): File =
        File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "store-$label-${System.nanoTime()}")
            .apply { mkdirs() }

    @Test
    fun `目录已消失的旧实例会被回收`() {
        val dirA = freshDir("a")
        // 先让 A 进入缓存
        FilesDirContext(ApplicationProvider.getApplicationContext(), dirA).museSettingsDataStore
        assertTrue("前置条件：A 的实例应已在缓存里", museSettingsStoreCachedDirsForTests().contains(dirA))

        // 模拟那个用例结束：Robolectric 就是这么删掉临时 filesDir 的
        dirA.deleteRecursively()

        // 取一个新目录的实例，触发回收扫描
        val dirB = freshDir("b")
        FilesDirContext(ApplicationProvider.getApplicationContext(), dirB).museSettingsDataStore

        assertFalse(
            "A 的目录已经不存在，实例必须被摘掉，否则僵尸句柄会一直攒着",
            museSettingsStoreCachedDirsForTests().contains(dirA),
        )
        assertTrue("B 还在，说明回收没顺手清全场", museSettingsStoreCachedDirsForTests().contains(dirB))
    }

    @Test
    fun `目录还在的实例不许被回收也不许被取消`() {
        val dirLive = freshDir("live")
        val live = FilesDirContext(ApplicationProvider.getApplicationContext(), dirLive).museSettingsDataStore

        // 再开一个新目录，触发一次回收扫描
        val dirOther = freshDir("other")
        FilesDirContext(ApplicationProvider.getApplicationContext(), dirOther).museSettingsDataStore

        assertTrue(
            "活目录的实例必须留着（它可能正被别处的读流骑着）",
            museSettingsStoreCachedDirsForTests().contains(dirLive),
        )
        runBlocking {
            live.edit { it[probeKey] = "ok" }
            assertEquals(
                "如果它被取消了，写读流会当场报错 —— 这正是历史上那类「无关用例翻红」的成因",
                "ok",
                live.data.first()[probeKey],
            )
        }
    }

    @Test
    fun `同一目录仍然复用同一个实例`() {
        val dir = freshDir("same")
        val context = FilesDirContext(ApplicationProvider.getApplicationContext(), dir)

        val first = context.museSettingsDataStore
        val second = context.applicationContext.museSettingsDataStore

        assertTrue("回收逻辑不能破坏 DataStore「同一文件只能有一个实例」的硬约束", first === second)
    }
}
