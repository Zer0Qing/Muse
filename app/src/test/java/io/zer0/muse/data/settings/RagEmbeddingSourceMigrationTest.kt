package io.zer0.muse.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.SettingsRepositoryTestApplication
import io.zer0.muse.data.museSettingsDataStore
import io.zer0.muse.rag.RagConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.0.53 embedding 来源迁移回归测试。
 *
 * 历史 bug:迁移逻辑写在 [SettingsRepository.ragConfigFlow] 里,每次读取都会把
 * 「LOCAL_KEYWORD + 云端字段非空」改写成 CLOUD。用户点「本地关键词」后写进去的是 LOCAL,
 * 读出来永远是 CLOUD,界面显示已选本地、实际跑云端,embedding 404 导致语义检索永久失效。
 *
 * 现在迁移收敛为启动时一次性调用 [SettingsRepository.migrateRagEmbeddingSourceIfNeeded],
 * 读路径只解码不改写。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = SettingsRepositoryTestApplication::class)
class RagEmbeddingSourceMigrationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** 与 SettingsRepository 内 KEY_RAG_EMBEDDING_SOURCE_MIGRATED 对应的持久化 key(私有常量,测试按名取)。 */
    private val migratedKey = booleanPreferencesKey("rag_embedding_source_migrated")

    @Before
    fun resetSettings() {
        // 同一 JVM 内所有测试共用一个 museSettingsDataStore,必须清干净才能断言"未迁移"场景。
        runBlocking { context.museSettingsDataStore.edit { it.clear() } }
    }

    private fun newRepository(): SettingsRepository = SettingsRepository(context, mockk(relaxed = true))

    private fun isMigrated(): Boolean = runBlocking { context.museSettingsDataStore.data.first()[migratedKey] == true }

    @Test
    fun unmigratedLegacyCloudConfig_isLiftedToCloudAndFlagSet() = runBlocking {
        val repo = newRepository()
        // v1.0.53 之前存下的配置:JSON 里没有 embeddingSource 字段,反序列化默认 LOCAL_KEYWORD,
        // 但云端字段是用户配过的。旧版本靠读路径改写掩盖问题,现在读路径必须原样返回。
        repo.saveRagConfig(RagConfig().copy(cloudModel = "text-embedding-3-small"))
        assertEquals(
            "读路径不得再改写配置(写入什么就读出什么)",
            RagConfig.EmbeddingSource.LOCAL_KEYWORD,
            repo.getRagConfig().embeddingSource,
        )

        repo.migrateRagEmbeddingSourceIfNeeded()

        assertEquals(
            "老用户的云端配置必须被一次性迁到 CLOUD,不能静默降级成关键词检索",
            RagConfig.EmbeddingSource.CLOUD,
            repo.getRagConfig().embeddingSource,
        )
        assertTrue("迁移完成后必须置位标志位", isMigrated())
    }

    @Test
    fun afterMigration_explicitLocalChoiceStaysLocal() = runBlocking {
        val repo = newRepository()
        repo.saveRagConfig(RagConfig().copy(cloudModel = "text-embedding-3-small"))
        repo.migrateRagEmbeddingSourceIfNeeded()
        assertTrue(isMigrated())

        // 用户之后显式选「本地关键词」:cloudModel 还是旧值时的中间态(UI 清理前)。
        repo.saveRagConfig(
            RagConfig().copy(
                embeddingSource = RagConfig.EmbeddingSource.LOCAL_KEYWORD,
                cloudModel = "text-embedding-3-small",
            ),
        )

        assertEquals(
            "本次 bug 的直接钉子:迁移已完成后,用户选本地必须读出本地",
            RagConfig.EmbeddingSource.LOCAL_KEYWORD,
            repo.getRagConfig().embeddingSource,
        )
    }

    @Test
    fun unmigratedEmptyCloudFields_keepsLocalKeyword() = runBlocking {
        val repo = newRepository()
        // 没配过云端的新用户/默认配置:不得被误抬成 CLOUD(否则又回到 embedding 404)。
        repo.saveRagConfig(RagConfig())

        repo.migrateRagEmbeddingSourceIfNeeded()

        assertEquals(
            "云端字段全空时不得抬成 CLOUD",
            RagConfig.EmbeddingSource.LOCAL_KEYWORD,
            repo.getRagConfig().embeddingSource,
        )
        assertTrue(isMigrated())
    }

    @Test
    fun migrationIsIdempotent_secondCallDoesNotWrite() = runBlocking {
        val repo = newRepository()
        repo.saveRagConfig(RagConfig().copy(cloudModel = "text-embedding-3-small"))

        repo.migrateRagEmbeddingSourceIfNeeded()
        val afterFirst = repo.getRagConfig()
        val prefsAfterFirst = context.museSettingsDataStore.data.first().asMap()

        // 直接盯 DataStore 的发射次数:标志位已置时第二次调用不应再 edit(每次 edit 都会触发一次发射)。
        // 不用落盘文件 mtime 判定,因为 Robolectric 下 DataStore 单例可能绑定在首个测试的 filesDir 上。
        val emissions = java.util.concurrent.atomic.AtomicInteger(0)
        val job = launch(Dispatchers.Default) {
            context.museSettingsDataStore.data.collect { emissions.incrementAndGet() }
        }
        withTimeout(5_000) { while (emissions.get() == 0) delay(10) }
        val baseline = emissions.get()

        repo.migrateRagEmbeddingSourceIfNeeded()
        delay(1_000)
        val afterSecond = emissions.get()
        job.cancel()

        assertEquals("第二次调用结果必须与第一次一致", afterFirst, repo.getRagConfig())
        assertEquals(
            "第二次调用不得改配置",
            prefsAfterFirst,
            context.museSettingsDataStore.data.first().asMap(),
        )
        assertEquals("第二次调用不得再写 DataStore(不应产生新的发射)", baseline, afterSecond)
    }
}
