package io.zer0.memory.fact

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 按助手分库的提供者（`:memory` 体检第十一处）。
 *
 * 装配层刚修掉一个问题：默认助手的 FactStore 之前被 new 了**两份**
 * （Koin 里手工一份、这里缓存一份），于是各自的写后对账节流与去重锁互不相干 ——
 * **锁只在实例内有效，双实例等于把它废掉**。那个修法成立的前提就是这里的实例语义：
 * 同一个 key 永远拿到同一个实例、不同 key 各一份。这里把它钉住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FactDbProviderTest {

    private lateinit var provider: FactDbProvider
    private lateinit var dbDir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        provider = FactDbProvider(context)
        dbDir = context.getDatabasePath("facts.db").parentFile!!
    }

    @After
    fun tearDown() {
        provider.releaseAll()
    }

    @Test
    fun `同一个助手 id 永远拿到同一个实例`() {
        assertSame(provider.getFactStore("writer"), provider.getFactStore("writer"))
        assertSame(provider.getFactDb("writer"), provider.getFactDb("writer"))
    }

    @Test
    fun `空白助手 id 归一到 default 而不是造出一个空名分库`() {
        val def = provider.getFactStore("default")

        assertSame("空串必须落到 default 那一份", def, provider.getFactStore(""))
        assertSame("空白串也一样", def, provider.getFactStore("   "))
        assertSame("库句柄同样归一", provider.getFactDb("default"), provider.getFactDb(""))
    }

    @Test
    fun `不同助手是两份互不共享的库`() {
        val a = provider.getFactStore("alpha")
        val b = provider.getFactStore("beta")

        assertNotSame(a, b)
        assertNotSame(provider.getFactDb("alpha"), provider.getFactDb("beta"))
    }

    @Test
    fun `release 之后再取是新实例`() {
        val first = provider.getFactDb("gamma")
        provider.release("gamma")

        assertNotSame(first, provider.getFactDb("gamma"))
    }

    @Test
    fun `列分库只列磁盘上真存在的 不凭 id 猜出空库`() {
        // Room 的库文件是懒创建的：只 getFactDb 不落盘，必须真开一次库才生成文件
        provider.getFactDb("realone").openHelper.writableDatabase.close()

        val listed = provider.listAssistantDbIds()

        assertTrue("刚建出来的库该被列到: $listed", "realone" in listed)
        listed.forEach { id ->
            assertTrue("列出的每个 id 都必须真有文件: $id", File(dbDir, "facts_$id.db").exists())
        }
        // 凭不存在的 id 去 getFactDb 会真的建库，所以清单只能是"已经存在的"：
        // 遍历清单本身不许再让磁盘多出一个文件
        val before = dbDir.listFiles().orEmpty().map { it.name }.toSet()
        provider.listAssistantDbIds()
        val after = dbDir.listFiles().orEmpty().map { it.name }.toSet()
        assertEquals("列目录不该创建任何新库", before, after)
    }

    @Test
    fun `主库不进分库清单`() {
        provider.getFactDb("default").openHelper.writableDatabase.close()

        assertTrue("default 走 facts.db，不是 facts_default.db",
            "default" !in provider.listAssistantDbIds())
    }

    @Test
    fun `releaseAll 之后仍能取回可用的实例`() {
        val before = provider.getFactStore("delta")

        provider.releaseAll()

        val after = provider.getFactStore("delta")
        assertNotSame("旧实例已随句柄一起释放", before, after)
        assertSame("新实例此后同样被缓存", after, provider.getFactStore("delta"))
    }
}
