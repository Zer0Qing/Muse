package io.zer0.muse.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 启动期写盘的**触发形状**回归。
 *
 * 这族断言守的不是业务结果，而是"谁负责启动它"：
 * 一旦有人把它挪回 `SettingsRepository.init`，构造仓库就会附带一个并发后台写手，
 * 于是「跑全量偶发挂一条、单跑必过、每轮换受害者」的 flaky 会原样回来 ——
 * 而且下次没人会怀疑到构造函数上。所以这里把它钉死。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = SettingsRepositoryTestApplication::class)
class SettingsStartupWorkTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun newRepository(): SettingsRepository = SettingsRepository(context, mockk(relaxed = true))

    @Test
    fun `constructing the repository starts no background writes`() {
        val repo = newRepository()

        assertNull(
            "构造即写盘会让任何比对配置快照的测试和后台协程赛跑，flaky 就是这么来的",
            repo.startupJob,
        )
    }

    @Test
    fun `explicit startup work is idempotent and returns the same job`() {
        val repo = newRepository()

        val first: Job = repo.startStartupWork()
        val second: Job = repo.startStartupWork()

        assertSame("重复触发不得再起一个写手（迁移各自幂等，但并发快照仍会被打扰）", first, second)
        assertNotNull(repo.startupJob)
    }

    @Test
    fun `awaiting startup work returns immediately when it was never started`() {
        // 测试没触发启动写盘时，awaitStartupWork() 不该卡住或抛异常。
        val repo = newRepository()

        kotlinx.coroutines.runBlocking { repo.awaitStartupWork() }

        assertNull(repo.startupJob)
    }

    @Test
    fun `a fresh repository instance does not inherit the previous instance job`() {
        val a = newRepository()
        a.startStartupWork()
        val b = newRepository()

        assertNull("每个实例自己决定是否启动，别把状态藏进静态字段", b.startupJob)
        assertEquals(1, if (a.startupJob === a.startStartupWork()) 1 else 0)
    }
}
