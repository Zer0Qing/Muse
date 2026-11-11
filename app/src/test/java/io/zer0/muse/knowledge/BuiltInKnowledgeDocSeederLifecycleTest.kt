package io.zer0.muse.knowledge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.coVerify
import io.zer0.muse.data.knowledge.BuiltInKnowledgeDocSeeder
import io.zer0.muse.data.knowledge.KnowledgeDocDao
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R9 步骤 7(计划 R9:「seed 保持 APK assets/devdocs/ 位置和稳定 ID；恢复/配置改变能重新检查
 * 版本/空间，不被单实例 Boolean 永久短路」)。
 *
 * 本测试验证 [BuiltInKnowledgeDocSeeder.invalidateForRestore] 清掉单实例短路:
 * 首次 ensureSeeded 后再次调用被跳过;失效后可再次执行 seed。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BuiltInKnowledgeDocSeederLifecycleTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var dao: KnowledgeDocDao

    @Before
    fun setUp() {
        dao = mockk<KnowledgeDocDao>(relaxed = true)
        coEvery { dao.getInternalDocs() } returns emptyList()
    }

    @Test
    fun `invalidate allows reseed after restore`() = runBlocking {
        val seeder = BuiltInKnowledgeDocSeeder(context = context, dao = dao)
        seeder.ensureSeeded()
        coVerify(atLeast = 1) { dao.upsert(any()) }

        // 未失效:单实例布尔短路,不会再次 upsert。
        io.mockk.clearMocks(dao, answers = false)
        seeder.ensureSeeded()
        coVerify(exactly = 0) { dao.upsert(any()) }

        // 失效后:允许重新检查并再次 seed(模拟恢复/配置变更)。
        io.mockk.clearMocks(dao, answers = false)
        seeder.invalidateForRestore()
        seeder.ensureSeeded()
        coVerify(atLeast = 1) { dao.upsert(any()) }
    }
}
