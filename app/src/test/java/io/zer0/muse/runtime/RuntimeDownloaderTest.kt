package io.zer0.muse.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class RuntimeDownloaderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `failed source falls through to the next source`() = runBlocking {
        RuntimeDownloaderHttpFixture().use { server ->
            val body = "rootfs bytes".toByteArray()
            server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
            server.enqueue(MockResponse().setBody(body.decodeToString()))
            val request = request(server, body, listOf("/mirror-a", "/mirror-b"))

            val result = RuntimeDownloader().download(request)

            assertTrue(result.exceptionOrNull()?.message.orEmpty(), result.isSuccess)
            assertEquals(body.toList(), result.getOrThrow().archiveFile.readBytes().toList())
            assertEquals("/mirror-a", server.takeRequest()?.path)
            assertEquals("/mirror-b", server.takeRequest()?.path)
        }
    }

    @Test
    fun `checksum failure deletes partial content before retrying another source`() = runBlocking {
        RuntimeDownloaderHttpFixture().use { server ->
            val body = "verified archive".toByteArray()
            server.enqueue(MockResponse().setBody("wrong archive"))
            server.enqueue(MockResponse().setBody(body.decodeToString()))
            val request = request(server, body, listOf("/bad", "/good"))

            val result = RuntimeDownloader().download(request)

            assertTrue(result.exceptionOrNull()?.message.orEmpty(), result.isSuccess)
            assertEquals(body.toList(), result.getOrThrow().archiveFile.readBytes().toList())
            assertEquals("/bad", server.takeRequest()?.path)
            assertEquals("/good", server.takeRequest()?.path)
        }
    }

    @Test
    fun `valid partial response resumes from requested offset`() = runBlocking {
        RuntimeDownloaderHttpFixture().use { server ->
            val body = "abcdef".toByteArray()
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 3-5/6")
                    .setBody("def"),
            )
            val request = request(server, body, listOf("/resume"))
            request.destination.resolveSibling(request.destination.name + ".part").writeBytes("abc".toByteArray())

            val result = RuntimeDownloader().download(request)

            assertTrue(result.exceptionOrNull()?.message.orEmpty(), result.isSuccess)
            assertTrue(result.getOrThrow().resumed)
            assertEquals("abcdef", result.getOrThrow().archiveFile.readText())
            assertEquals("bytes=3-", server.takeRequest()?.getHeader("Range"))
        }
    }

    @Test
    fun `incorrect range start retries and valid full response succeeds`() = runBlocking {
        RuntimeDownloaderHttpFixture().use { server ->
            val body = "abcdef".toByteArray()
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 2-5/6")
                    .setBody("cdef"),
            )
            server.enqueue(MockResponse().setBody(body.decodeToString()))
            val request = request(server, body, listOf("/bad-range", "/full"))
            request.destination.resolveSibling(request.destination.name + ".part").writeBytes("abc".toByteArray())

            val result = RuntimeDownloader().download(request)

            assertTrue(result.exceptionOrNull()?.message.orEmpty(), result.isSuccess)
            assertEquals("abcdef", result.getOrThrow().archiveFile.readText())
            assertEquals("bytes=3-", server.takeRequest()?.getHeader("Range"))
            assertEquals("/full", server.takeRequest()?.path)
        }
    }

    @Test
    fun `server ignoring range replaces old partial file`() = runBlocking {
        RuntimeDownloaderHttpFixture().use { server ->
            val body = "abcdef".toByteArray()
            server.enqueue(MockResponse().setResponseCode(200).setBody(body.decodeToString()))
            val request = request(server, body, listOf("/ignore-range"))
            request.destination.resolveSibling(request.destination.name + ".part").writeBytes("abc".toByteArray())

            val result = RuntimeDownloader().download(request)

            assertTrue(result.exceptionOrNull()?.message.orEmpty(), result.isSuccess)
            assertEquals("abcdef", result.getOrThrow().archiveFile.readText())
            assertEquals("bytes=3-", server.takeRequest()?.getHeader("Range"))
        }
    }

    @Test
    fun `cancellation closes the connection and preserves the received part`() = runBlocking {
        RuntimeDownloaderHttpFixture().use { server ->
            server.enqueue(
                MockResponse()
                    .setBody("abcdefghij")
                    .throttleBody(1, 100, TimeUnit.MILLISECONDS),
            )
            val body = "abcdefghij".toByteArray()
            val request = request(server, body, listOf("/slow"))
            val part = request.destination.resolveSibling(request.destination.name + ".part")
            val job = launch(Dispatchers.IO) { RuntimeDownloader(readTimeoutMs = 2_000).download(request) }
            val deadline = System.currentTimeMillis() + 2_000
            while (part.length() == 0L && System.currentTimeMillis() < deadline) delay(20)
            job.cancelAndJoin()

            assertTrue("预期保留已下载数据", part.isFile && part.length() in 1L until body.size.toLong())
            assertEquals(0L, request.destination.length())
        }
    }

    @Test
    fun `stalled drip stream switches source after no progress deadline`() = runBlocking {
        RuntimeDownloaderHttpFixture().use { server ->
            val body = "abcdef".toByteArray()
            server.enqueue(MockResponse().setBody(body.decodeToString()).throttleBody(1, 500, TimeUnit.MILLISECONDS))
            server.enqueue(MockResponse().setBody(body.decodeToString()))
            val request = request(server, body, listOf("/drip", "/healthy"))
            val started = System.currentTimeMillis()

            val result = RuntimeDownloader(
                connectTimeoutMs = 1_000,
                readTimeoutMs = 2_000,
                stallTimeoutMs = 200,
                sourceDeadlineMs = 3_000,
            ).download(request)

            assertTrue(result.exceptionOrNull()?.message.orEmpty(), result.isSuccess)
            assertTrue("停滞源需在阈值附近退出", System.currentTimeMillis() - started < 2_000)
            assertEquals("/drip", server.takeRequest()?.path)
            assertEquals("/healthy", server.takeRequest()?.path)
        }
    }

    @Test
    fun `all source errors are retained in final failure`() = runBlocking {
        RuntimeDownloaderHttpFixture().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(503))
            val request = request(server, "bytes".toByteArray(), listOf("/missing", "/unavailable"))

            val failure = RuntimeDownloader().download(request).exceptionOrNull()

            assertNotNull(failure)
            assertTrue(failure?.message.orEmpty(), failure?.message?.contains("HTTP 404") == true)
            assertTrue(failure?.message.orEmpty(), failure?.message?.contains("HTTP 503") == true)
            assertTrue(failure?.message.orEmpty(), failure?.message?.contains("/missing") == true)
            assertTrue(failure?.message.orEmpty(), failure?.message?.contains("/unavailable") == true)
        }
    }

    private fun request(
        server: RuntimeDownloaderHttpFixture,
        body: ByteArray,
        paths: List<String>,
    ): RuntimeDownloadRequest {
        val sha = sha256(body)
        val urls = paths.map(server::url)
        val version = RuntimeVersion(
            revision = "r-test",
            archive = RuntimeManifest.Archive("rootfs.tar.gz", body.size.toLong(), sha, urls),
            installedBytes = body.size.toLong(),
        )
        return RuntimeDownloadRequest(
            version = version,
            destination = temporaryFolder.newFile("runtime.tar.gz"),
            expectedSha256 = sha,
            expectedSize = body.size.toLong(),
            sourceUrls = urls,
            operationId = "operation-test",
            targetAbi = "x86_64",
        )
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
