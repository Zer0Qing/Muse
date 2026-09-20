package io.zer0.muse.data.plugin.market

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.KeyPair

class PluginCatalogRepositoryTest {

    private val fixtures = PluginMarketTestFixtures
    private val pair: KeyPair = fixtures.newKeyPair()
    private val keyId = "root-1"
    private val trustRoot = mapOf(keyId to fixtures.publicKeyBase64(pair))
    private val tempDirs = mutableListOf<File>()

    @After
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun refreshPersistsCatalogAndSurvivesRecreation() = runBlocking {
        val dir = tempDir()
        val signed = fixtures.sign(fixtures.payload(sequence = 5L), pair, keyId)
        val repository = repository(dir, transport = fixedTransport(signed))

        val result = repository.refresh("https://example.com/catalog.json")

        assertTrue(result is CatalogRefreshResult.Updated)
        assertEquals(5L, repository.lastAcceptedSequence(CATALOG_ID))
        assertEquals(listOf("safe-plugin"), repository.currentCatalog()?.payload?.entries?.map { it.id })

        // 新实例（模拟重启）只读缓存也能恢复条目与已接受 sequence。
        val recreated = repository(dir, transport = { _, _ -> throw IOException("不该发起请求") })
        val snapshot = recreated.loadCache()

        assertNull(snapshot.error)
        assertEquals(listOf("safe-plugin"), snapshot.entries.map { it.id })
        assertEquals(5L, recreated.lastAcceptedSequence(CATALOG_ID))
    }

    @Test
    fun rollbackIsRejectedAndKeepsNewerCache() = runBlocking {
        val dir = tempDir()
        val newer = fixtures.sign(fixtures.payload(sequence = 5L), pair, keyId)
        val older = fixtures.sign(fixtures.payload(sequence = 4L), pair, keyId)
        val repository = repository(dir, transport = sequenced(newer, older))

        assertTrue(repository.refresh("https://example.com/catalog.json") is CatalogRefreshResult.Updated)

        val rejected = repository.refresh("https://example.com/catalog.json")

        assertTrue(rejected is CatalogRefreshResult.Rejected)
        assertTrue((rejected as CatalogRefreshResult.Rejected).reason.contains("回退"))
        assertEquals(5L, repository.currentCatalog()?.payload?.sequence)
        assertEquals(listOf("safe-plugin"), repository.loadCache().entries.map { it.id })
    }

    @Test
    fun expiredCatalogAndUntrustedKeyAreRejected() = runBlocking {
        val dir = tempDir()
        val expired = fixtures.sign(fixtures.payload(sequence = 1L, expiresAtEpochMs = 500L), pair, keyId)
        val foreign = fixtures.newKeyPair()
        val foreignSigned = fixtures.sign(fixtures.payload(sequence = 1L), foreign, keyId)

        val expiredResult = repository(dir, transport = fixedTransport(expired), nowEpochMs = 1_000L)
            .refresh("https://example.com/catalog.json")
        val untrustedResult = repository(dir, transport = fixedTransport(foreignSigned))
            .refresh("https://example.com/catalog.json")

        assertTrue(expiredResult is CatalogRefreshResult.Rejected)
        assertTrue((expiredResult as CatalogRefreshResult.Rejected).reason.contains("过期"))
        assertTrue(untrustedResult is CatalogRefreshResult.Rejected)
        assertTrue((untrustedResult as CatalogRefreshResult.Rejected).reason.contains("签名"))
    }

    @Test
    fun refreshWithoutTrustRootFailsClosedWithoutRequest() = runBlocking {
        var calls = 0
        val repository = PluginCatalogRepository(
            client = PluginCatalogClient(transport = { _, _ ->
                calls++
                CatalogHttpResponse(200, ByteArray(0))
            }),
            cacheDir = tempDir(),
            trustRootKeys = { emptyMap() },
        )

        val result = repository.refresh("https://example.com/catalog.json")

        assertTrue(result is CatalogRefreshResult.Rejected)
        assertEquals(PluginCatalogRepository.REASON_NO_TRUST_ROOT, (result as CatalogRefreshResult.Rejected).reason)
        assertEquals(0, calls)
        assertNull(repository.currentCatalog())
        assertNull(repository.lastAcceptedSequence(CATALOG_ID))
    }

    @Test
    fun refreshFailureKeepsPreviousCache() = runBlocking {
        val dir = tempDir()
        val signed = fixtures.sign(fixtures.payload(sequence = 7L), pair, keyId)
        var failNext = false
        val repository = repository(
            dir,
            transport = { _, _ ->
                if (failNext) throw IOException("网络不可达")
                CatalogHttpResponse(200, fixtures.catalogJson(signed).toByteArray())
            },
        )
        assertTrue(repository.refresh("https://example.com/catalog.json") is CatalogRefreshResult.Updated)

        failNext = true
        val failed = repository.refresh("https://example.com/catalog.json")

        assertTrue(failed is CatalogRefreshResult.Rejected)
        assertTrue((failed as CatalogRefreshResult.Rejected).reason.contains("网络不可达"))
        assertEquals(7L, repository.currentCatalog()?.payload?.sequence)
        assertEquals(7L, repository.lastAcceptedSequence(CATALOG_ID))
    }

    @Test
    fun tamperedOrExpiredCacheIsNotServed() = runBlocking {
        val tamperedDir = tempDir()
        val expiredDir = tempDir()
        val foreign = fixtures.sign(fixtures.payload(sequence = 3L), fixtures.newKeyPair(), keyId)
        val expired = fixtures.sign(fixtures.payload(sequence = 3L, expiresAtEpochMs = 500L), pair, keyId)
        writeCache(tamperedDir, fixtures.catalogJson(foreign), 3L)
        writeCache(expiredDir, fixtures.catalogJson(expired), 3L)

        val tamperedRepository = repository(tamperedDir, transport = unreachable(), nowEpochMs = 1_000L)
        val tamperedSnapshot = tamperedRepository.loadCache()
        val expiredRepository = repository(expiredDir, transport = unreachable(), nowEpochMs = 1_000L)
        val expiredSnapshot = expiredRepository.loadCache()

        assertTrue(tamperedSnapshot.entries.isEmpty())
        assertNotNull(tamperedSnapshot.error)
        assertNull(tamperedRepository.currentCatalog())
        assertTrue(expiredSnapshot.entries.isEmpty())
        assertTrue(expiredSnapshot.error.orEmpty().contains("过期"))
        // 即使缓存不可用，回退保护仍保留已接受 sequence。
        assertEquals(3L, expiredRepository.lastAcceptedSequence(CATALOG_ID))
    }

    @Test
    fun sequencesAreTrackedPerCatalogIdSoSwitchingCatalogsIsNotARollback() = runBlocking {
        val dir = tempDir()
        val official = fixtures.sign(fixtures.payload(catalogId = "official", sequence = 5L), pair, keyId)
        val custom = fixtures.sign(fixtures.payload(catalogId = "custom-catalog", sequence = 1L), pair, keyId)
        val repository = repository(dir, transport = sequenced(official, custom))

        assertTrue(repository.refresh("https://example.com/catalog.json") is CatalogRefreshResult.Updated)
        assertEquals(5L, repository.lastAcceptedSequence("official"))

        // 自定义目录序号更小，但属于另一个目录 id，不能被判成回退。
        val switched = repository.refresh("https://mirror.example.com/catalog.json")
        assertTrue("切换目录不应被当作回退: $switched", switched is CatalogRefreshResult.Updated)
        assertEquals(1L, repository.lastAcceptedSequence("custom-catalog"))
        assertEquals("已接受记录按目录分别保留", 5L, repository.lastAcceptedSequence("official"))
    }

    @Test
    fun legacyGlobalSequenceIsAttributedToTheCachedCatalog() = runBlocking {
        val dir = tempDir()
        val signed = fixtures.sign(fixtures.payload(catalogId = "official", sequence = 9L), pair, keyId)
        writeCache(dir, fixtures.catalogJson(signed), 9L)

        val repository = repository(dir, transport = unreachable(), nowEpochMs = 1_000L)
        repository.loadCache()

        assertEquals(9L, repository.lastAcceptedSequence("official"))
        assertNull(repository.lastAcceptedSequence("some-other-catalog"))
    }

    @Test
    fun unconfiguredUrlReportsNotConfigured() = runBlocking {
        val repository = repository(tempDir(), transport = unreachable())

        assertEquals(CatalogRefreshResult.NotConfigured, repository.refresh("  "))
    }

    private companion object {
        /** fixtures 默认的目录 id。 */
        private const val CATALOG_ID = "official"
    }

    private fun repository(
        dir: File,
        transport: CatalogHttpTransport,
        nowEpochMs: Long = 1_000L,
    ) = PluginCatalogRepository(
        client = PluginCatalogClient(transport = transport),
        cacheDir = dir,
        trustRootKeys = { trustRoot },
        nowEpochMs = { nowEpochMs },
    )

    private fun fixedTransport(signed: SignedPluginCatalog): CatalogHttpTransport = { _, _ ->
        CatalogHttpResponse(200, fixtures.catalogJson(signed).toByteArray())
    }

    private fun sequenced(vararg signed: SignedPluginCatalog): CatalogHttpTransport {
        val remaining = ArrayDeque(signed.toList())
        return { _, _ ->
            val next = remaining.removeFirstOrNull() ?: throw IOException("没有更多目录")
            CatalogHttpResponse(200, fixtures.catalogJson(next).toByteArray())
        }
    }

    private fun unreachable(): CatalogHttpTransport = { _, _ -> throw IOException("不该发起请求") }

    private fun tempDir(): File =
        Files.createTempDirectory("plugin-market-test").toFile().also { tempDirs += it }

    private fun writeCache(dir: File, catalogJson: String, sequence: Long) {
        dir.mkdirs()
        File(dir, PluginCatalogRepository.CATALOG_FILE_NAME).writeText(catalogJson)
        File(dir, PluginCatalogRepository.SEQUENCE_FILE_NAME).writeText(sequence.toString())
    }
}
