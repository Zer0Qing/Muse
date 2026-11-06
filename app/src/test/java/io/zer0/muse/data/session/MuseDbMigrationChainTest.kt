package io.zer0.muse.data.session

import android.content.Context
import androidx.room.migration.Migration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.7.x(S3): 迁移链的形态守卫 —— 「旧迁移冻结、新版本只追加」第一次成为可执行断言。
 *
 * 钉四条,每条都对应一种真实会发生的事故:
 *  1. **每步都是相邻版本**(to = from + 1):写错一个数字,Room 会跳过一段 schema,
 *     而跳过的部分不会有任何提示,只在很久之后表现为「某列不存在」;
 *  2. **链连续无空洞**:上一步的终点必须等于下一步的起点,否则某些存量库永远升不上来;
 *  3. **同一段区间只注册一次**:重复注册是历史事故之一(两条迁移抢同一段区间会冲突);
 *  4. **链终点等于 LATEST_SCHEMA_VERSION**:加了迁移忘了抬版本号,或抬了版本号没加迁移,
 *     两者都会让升级静默失效 —— 同 versionCode 覆盖安装本来就跳过迁移,这里再堵一道。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MuseDbMigrationChainTest {

    private val chain: List<Migration> by lazy {
        // 链里有一项需要 Context 来构造文件路径(75→76);其余都是纯版本号对象。
        MuseDb.migrations(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `每一步都只升一个版本号`() {
        val bad = chain.filter { it.endVersion != it.startVersion + 1 }
        assertTrue(
            "这些迁移不是相邻版本升级(会跳过 schema 步骤): " +
                bad.take(5).joinToString { "${it.startVersion}→${it.endVersion}" },
            bad.isEmpty(),
        )
    }

    @Test
    fun `链连续没有空洞且按版本升序`() {
        val sorted = chain.sortedBy { it.startVersion }
        assertEquals("注册顺序必须就是版本顺序", chain.map { it.startVersion }, sorted.map { it.startVersion })
        val gaps = chain.zipWithNext().filter { (prev, next) -> prev.endVersion != next.startVersion }
        assertTrue(
            "这些位置有版本空洞,存量库会升不过去: " +
                gaps.take(5).joinToString { "${it.first.endVersion} 之后直接到 ${it.second.startVersion}" },
            gaps.isEmpty(),
        )
        assertEquals("链必须从 1 开始(全新安装之外的最早存量库)", 1, chain.first().startVersion)
    }

    @Test
    fun `同一段区间不重复注册`() {
        val pairs = chain.map { it.startVersion to it.endVersion }
        assertEquals("出现重复的迁移区间", pairs.distinct().size, pairs.size)
    }

    @Test
    fun `链的终点就是声明的最新版本`() {
        assertEquals(
            "加了迁移没抬 LATEST_SCHEMA_VERSION,或抬了版本号没加迁移 —— 两种都会让升级静默不发生",
            MuseDb.LATEST_SCHEMA_VERSION,
            chain.maxOf { it.endVersion },
        )
    }

    @Test
    fun `包装层不改变链的版本区间与条数`() {
        val timed = MigrationRegistry(all = chain, clock = { 0L }, onRecord = {}).timed()

        assertEquals(chain.size, timed.size)
        assertEquals(chain.map { it.startVersion to it.endVersion }, timed.map { it.startVersion to it.endVersion })
    }

    @Test
    fun `重复构建同一条链不会改变区间也不建库文件`() {
        // 75→76 那一步需要 Context 来构造文件路径。这里要钉住的是「**读链**这件事没有副作用」:
        // 只构造对象不许顺手创建数据库或迁移记录文件 —— 预览功能一旦被调用就改磁盘是不可接受的。
        val context = ApplicationProvider.getApplicationContext<Context>()
        val listing = { files: Array<String>? -> files?.toList()?.sorted() ?: emptyList<String>() }
        val before = listing(context.filesDir.list())

        val again = MuseDb.migrations(context)

        val after = listing(context.filesDir.list())
        assertEquals(chain.size, again.size)
        assertEquals(chain.map { it.startVersion to it.endVersion }, again.map { it.startVersion to it.endVersion })
        assertEquals(
            "构造迁移链不该在 filesDir 里留下新文件: ${after - before}",
            before,
            after,
        )
    }
}
