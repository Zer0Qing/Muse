package io.zer0.ai.openai

import io.zer0.ai.core.AbortSignal
import io.zer0.ai.core.ChatRequest
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.SocketHandler
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okio.buffer

/**
 * Regression tests for cancelling a long-lived OpenAI-compatible SSE response.
 *
 * The response is streamed by a real MockWebServer socket handler. The server-side write loop only
 * finishes when the client closes its socket, so a successful abort is proven by an IOException
 * observed on the server, rather than merely by a completed Flow or a cancelled coroutine.
 */
class OpenAIProviderAbortTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        Logger.enabled = false
    }

    @After
    fun tearDown() {
        server.shutdown()
        Logger.enabled = true
    }

    @Test
    fun `aborting long Chat Completions SSE closes server socket and unregisters listener`() = runBlocking {
        assertAbortClosesLongSse(useResponsesApi = false)
    }

    @Test
    fun `aborting long Responses SSE closes server socket and unregisters listener`() = runBlocking {
        assertAbortClosesLongSse(useResponsesApi = true)
    }

    @Test
    fun `cancelling SSE collector closes server socket and removes listener`() = runBlocking {
        assertAbortClosesLongSse(useResponsesApi = false, cancelCollector = true)
    }

    private suspend fun assertAbortClosesLongSse(
        useResponsesApi: Boolean,
        cancelCollector: Boolean = false,
    ) = kotlinx.coroutines.coroutineScope {
        val firstEventWritten = CountDownLatch(1)
        val clientDisconnected = CountDownLatch(1)
        installLongSseDispatcher(
            useResponsesApi = useResponsesApi,
            firstEventWritten = firstEventWritten,
            clientDisconnected = clientDisconnected,
        )

        val signal = AbortSignal()
        val request = ChatRequest(
            messages = listOf(UIMessage(role = MessageRole.USER, content = "keep streaming")),
            model = Model(id = "test-model", providerId = "openai-test"),
            abortSignal = signal,
        )
        val provider = OpenAIProvider(
            ProviderConfig(
                id = "openai-test",
                displayName = "OpenAI Test",
                type = ProviderType.OPENAI,
                baseUrl = server.url("/v1").toString(),
                apiKey = "sk-test",
                specific = io.zer0.ai.core.ProviderSpecificConfig.OpenAI(
                    useResponseApi = useResponsesApi,
                ),
            ),
        )
        val events = CopyOnWriteArrayList<ChatStreamEvent>()
        val collector = launch(Dispatchers.IO) {
            provider.streamChat(request).collect { events += it }
        }

        assertTrue("server should write the first SSE event", firstEventWritten.await(5, TimeUnit.SECONDS))
        if (cancelCollector) {
            // Exercise Flow cancellation independently from AbortSignal.abort(). The provider's
            // awaitClose must still cancel the underlying EventSource and unregister its listener.
            collector.cancel()
        } else {
            signal.abort()
        }

        withTimeout(5_000) { collector.join() }
        assertEquals("collector cancellation must not mutate the caller's signal", !cancelCollector, signal.aborted)
        assertTrue(
            "server must observe the client closing the long SSE socket",
            clientDisconnected.await(5, TimeUnit.SECONDS),
        )
        assertEquals("abort listener must be removed when the flow closes", 0, signal.listenerCount)
        assertEquals("abort must not trigger a retry request", 1, server.requestCount)
        if (!cancelCollector) {
            assertTrue(
                "aborted stream should report interruption to its collector",
                events.any { it is ChatStreamEvent.StreamInterrupted },
            )
        }
    }

    private fun installLongSseDispatcher(
        useResponsesApi: Boolean,
        firstEventWritten: CountDownLatch,
        clientDisconnected: CountDownLatch,
    ) {
        server.delegate.dispatcher = object : Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                assertTrue("unexpected request path", request.target.endsWith(if (useResponsesApi) "/responses" else "/chat/completions"))
                val responseBody = if (useResponsesApi) {
                    "data: {\"type\":\"response.output_text.delta\",\"delta\":\"first token\"}\n\n"
                } else {
                    "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"first token\"},\"finish_reason\":null}]}\n\n"
                }
                return MockResponse.Builder()
                    .clearHeaders()
                    .addHeader("Content-Type", "text/event-stream")
                    .socketHandler(
                        LongSseSocketHandler(
                            firstEvent = responseBody,
                            firstEventWritten = firstEventWritten,
                            clientDisconnected = clientDisconnected,
                        ),
                    )
                    .build()
            }
        }
    }

    private class LongSseSocketHandler(
        private val firstEvent: String,
        private val firstEventWritten: CountDownLatch,
        private val clientDisconnected: CountDownLatch,
    ) : SocketHandler {
        override fun handle(socket: okio.Socket) {
            val sink = socket.sink.buffer()
            try {
                sink.writeUtf8(firstEvent)
                sink.flush()
                firstEventWritten.countDown()
                while (true) {
                    // A comment is valid SSE and keeps the real response open without producing
                    // additional provider events or allowing a read timeout to hide cancellation.
                    sink.writeUtf8(": heartbeat\n\n")
                    sink.flush()
                    Thread.sleep(25)
                }
            } catch (_: IOException) {
                // A client-side OkHttp Call.cancel() closes this sink. This is the regression proof.
                clientDisconnected.countDown()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                runCatching { sink.close() }
                socket.cancel()
            }
        }
    }
}
