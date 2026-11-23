package io.zer0.muse.runtime

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.Closeable
import java.util.concurrent.TimeUnit

internal class RuntimeDownloaderHttpFixture : Closeable {
    private val server = MockWebServer().apply { start() }

    fun url(path: String): String = server.url(path).toString()

    fun enqueue(response: MockResponse) {
        server.enqueue(response)
    }

    fun takeRequest(): RecordedRequest? = server.takeRequest(3, TimeUnit.SECONDS)

    override fun close() {
        server.shutdown()
    }
}
