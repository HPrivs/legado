package io.legado.app.model.ai.client

import com.google.gson.JsonParser
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.SocketTimeoutException
import kotlin.reflect.KClass

class DeepSeekClientTest {

    @Test
    fun `sends non-streaming request and parses response`() = runBlocking {
        lateinit var capturedRequest: Request
        val responseJson = """
            {
              "id": "chat-1",
              "model": "deepseek-v4-pro",
              "choices": [
                {
                  "index": 0,
                  "message": {
                    "role": "assistant",
                    "reasoning_content": "test reasoning",
                    "content": "test reply"
                  },
                  "finish_reason": "stop"
                }
              ],
              "usage": {
                "prompt_tokens": 10,
                "completion_tokens": 6,
                "total_tokens": 16,
                "completion_tokens_details": {"reasoning_tokens": 4}
              }
            }
        """.trimIndent()
        val client = DeepSeekClient(
            baseUrl = "https://api.deepseek.com/",
            apiKey = "test-key",
            callFactory = fakeClient(responseBody = responseJson) {
                capturedRequest = it
            }
        )

        val response = client.createChatCompletion(
            DeepSeekChatRequest(
                model = "deepseek-v4-pro",
                messages = listOf(DeepSeekChatMessage.user("hello", name = "novel_text")),
                thinking = DeepSeekThinking.fromEnabled(true),
                reasoningEffort = DeepSeekReasoningEffort.HIGH,
                maxTokens = 512
            )
        )

        assertEquals("/chat/completions", capturedRequest.url.encodedPath)
        assertEquals("Bearer test-key", capturedRequest.header("Authorization"))
        val requestBuffer = Buffer()
        assertNotNull(capturedRequest.body)
        capturedRequest.body!!.writeTo(requestBuffer)
        val requestJson = JsonParser.parseString(requestBuffer.readUtf8()).asJsonObject
        assertFalse(requestJson["stream"].asBoolean)
        assertEquals("enabled", requestJson["thinking"].asJsonObject["type"].asString)
        assertEquals("high", requestJson["reasoning_effort"].asString)
        assertEquals(512, requestJson["max_tokens"].asInt)
        assertEquals(
            "novel_text",
            requestJson["messages"].asJsonArray[0].asJsonObject["name"].asString
        )
        assertEquals("test reply", response.choices?.first()?.message?.content)
        assertEquals("test reasoning", response.choices?.first()?.message?.reasoningContent)
        assertEquals("stop", response.choices?.first()?.finishReason)
        assertEquals(16, response.usage?.totalTokens)
        assertEquals(4, response.usage?.completionTokensDetails?.reasoningTokens)
    }

    @Test
    fun `serializes MiMo compatible completion fields`() = runBlocking {
        lateinit var capturedRequest: Request
        val responseJson = """
            {
              "model": "mimo-v2.5-pro",
              "choices": [{
                "index": 0,
                "message": {"role": "assistant", "content": "OK"},
                "finish_reason": "stop"
              }]
            }
        """.trimIndent()
        val client = OpenAiCompatibleClient(
            baseUrl = "https://api.xiaomimimo.com/v1/",
            apiKey = "mimo-key",
            callFactory = fakeClient(responseBody = responseJson) {
                capturedRequest = it
            }
        )

        client.createChatCompletion(
            AiProvider.MIMO.createChatRequest(
                model = "mimo-v2.5-pro",
                messages = listOf(DeepSeekChatMessage.user("hello")),
                thinkingEnabled = true,
                maxCompletionTokens = 8_192
            )
        )

        assertEquals("/v1/chat/completions", capturedRequest.url.encodedPath)
        assertEquals("Bearer mimo-key", capturedRequest.header("Authorization"))
        val requestBuffer = Buffer()
        capturedRequest.body!!.writeTo(requestBuffer)
        val requestJson = JsonParser.parseString(requestBuffer.readUtf8()).asJsonObject
        assertEquals(8_192, requestJson["max_completion_tokens"].asInt)
        assertFalse(requestJson.has("max_tokens"))
        assertFalse(requestJson.has("reasoning_effort"))
        assertEquals("enabled", requestJson["thinking"].asJsonObject["type"].asString)
    }

    @Test
    fun `serializes Qwen 38 compatible completion fields`() = runBlocking {
        lateinit var capturedRequest: Request
        val client = OpenAiCompatibleClient(
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/",
            apiKey = "qwen-key",
            callFactory = fakeClient(
                responseBody = """{
                    "model":"qwen3.8-max",
                    "choices":[{
                        "message":{"role":"assistant","content":"OK"},
                        "finish_reason":"stop"
                    }]
                }""".trimIndent()
            ) { capturedRequest = it }
        )

        client.createChatCompletion(
            AiProvider.QWEN.createChatRequest(
                model = "qwen3.8-max",
                messages = listOf(DeepSeekChatMessage.user("hello")),
                thinkingEnabled = true,
                maxCompletionTokens = 8_192
            )
        )

        assertEquals(
            "/compatible-mode/v1/chat/completions",
            capturedRequest.url.encodedPath
        )
        assertEquals("Bearer qwen-key", capturedRequest.header("Authorization"))
        val requestBuffer = Buffer()
        capturedRequest.body!!.writeTo(requestBuffer)
        val requestJson = JsonParser.parseString(requestBuffer.readUtf8()).asJsonObject
        assertTrue(requestJson["enable_thinking"].asBoolean)
        assertEquals(AiProvider.QWEN_THINKING_BUDGET, requestJson["thinking_budget"].asInt)
        assertEquals(8_192, requestJson["max_completion_tokens"].asInt)
        assertFalse(requestJson.has("thinking"))
        assertFalse(requestJson.has("reasoning_effort"))
        assertFalse(requestJson.has("max_tokens"))
    }

    @Test
    fun `disables Qwen thinking without sending a thinking budget`() {
        val request = AiProvider.QWEN.createChatRequest(
            model = "qwen3.8-flash",
            messages = listOf(DeepSeekChatMessage.user("hello")),
            thinkingEnabled = false,
            maxCompletionTokens = 512
        )

        assertEquals(false, request.enableThinking)
        assertEquals(null, request.thinkingBudget)
        assertEquals(null, request.thinking)
    }

    @Test
    fun `parses one streaming request into typed SSE events`() = runBlocking {
        lateinit var capturedRequest: Request
        val sse = """
            : keep-alive

            data: {"choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"thinking"},"finish_reason":null}]}

            data: {"choices":[{"index":0,"delta":{"content":"第一段。\n"},"finish_reason":null}]}

            data: {"choices":[{"index":0,"delta":{"content":"第二段。"},"finish_reason":"stop"}]}

            data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":6,"total_tokens":16}}

            data: [DONE]

        """.trimIndent()
        val client = OpenAiCompatibleClient(
            baseUrl = "https://api.deepseek.com",
            apiKey = "test-key",
            callFactory = fakeClient(responseBody = sse) { capturedRequest = it }
        )

        val events = client.createChatCompletionStream(
            minimalRequest().copy(stream = true)
        ).toList()

        assertEquals("text/event-stream", capturedRequest.header("Accept"))
        assertEquals("identity", capturedRequest.header("Accept-Encoding"))
        assertEquals("no-cache", capturedRequest.header("Cache-Control"))
        val requestBuffer = Buffer()
        capturedRequest.body!!.writeTo(requestBuffer)
        val requestJson = JsonParser.parseString(requestBuffer.readUtf8()).asJsonObject
        assertTrue(requestJson["stream"].asBoolean)
        assertEquals(AiStreamEvent.ReasoningDelta("thinking"), events[0])
        assertEquals(AiStreamEvent.ContentDelta("第一段。\n"), events[1])
        assertEquals(AiStreamEvent.ContentDelta("第二段。"), events[2])
        assertEquals(16, (events[3] as AiStreamEvent.UsageUpdate).usage.totalTokens)
        assertEquals(AiStreamEvent.Finished("stop"), events[4])
    }

    @Test
    fun `rejects a stream that ends without done marker`() = runBlocking {
        val client = OpenAiCompatibleClient(
            "https://api.deepseek.com",
            "test-key",
            fakeClient(
                responseBody = """data: {"choices":[{"delta":{"content":"partial"}}]}""" +
                        "\n\n"
            )
        )

        val error = expectDeepSeekException {
            client.createChatCompletionStream(minimalRequest().copy(stream = true)).collect()
        }

        assertEquals(DeepSeekErrorKind.INVALID_RESPONSE, error.kind)
    }

    @Test
    fun `accepts a compatible stream closed after finish reason`() = runBlocking {
        val client = OpenAiCompatibleClient(
            "https://api.deepseek.com",
            "test-key",
            fakeClient(
                responseBody = """
                    data: {"choices":[{"delta":{"content":"完整正文"},"finish_reason":"stop"}]}

                """.trimIndent()
            )
        )

        val events = client.createChatCompletionStream(
            minimalRequest().copy(stream = true)
        ).toList()

        assertEquals(
            listOf(
                AiStreamEvent.ContentDelta("完整正文"),
                AiStreamEvent.Finished("stop")
            ),
            events
        )
    }

    @Test
    fun `done marker without explicit finish reason is a normal stop`() = runBlocking {
        val client = OpenAiCompatibleClient(
            "https://api.deepseek.com",
            "test-key",
            fakeClient(
                responseBody = """
                    data: {"choices":[{"delta":{"content":"完整正文"}}]}

                    data: [DONE]

                """.trimIndent()
            )
        )

        val events = client.createChatCompletionStream(
            minimalRequest().copy(stream = true)
        ).toList()

        assertEquals(AiStreamEvent.Finished("stop"), events.last())
    }

    @Test
    fun `parses consecutive data-only SSE lines without blank separators`() = runBlocking {
        val client = OpenAiCompatibleClient(
            "https://api.deepseek.com",
            "test-key",
            fakeClient(
                responseBody = listOf(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"第一\"}}]}",
                    "data: {\"choices\":[{\"delta\":{\"content\":\"段\"},\"finish_reason\":\"stop\"}]}",
                    "data: [DONE]"
                ).joinToString("\n")
            )
        )

        val events = client.createChatCompletionStream(
            minimalRequest().copy(stream = true)
        ).toList()

        assertEquals(
            listOf(
                AiStreamEvent.ContentDelta("第一"),
                AiStreamEvent.ContentDelta("段"),
                AiStreamEvent.Finished("stop")
            ),
            events
        )
    }

    @Test
    fun `maps DeepSeek parameter error`() = runBlocking {
        val client = DeepSeekClient(
            "https://api.deepseek.com",
            "test-key",
            fakeClient(
                responseCode = 422,
                responseBody = """{"error":{"message":"invalid model"}}"""
            )
        )

        val error = expectDeepSeekException {
            client.createChatCompletion(minimalRequest())
        }

        assertEquals(DeepSeekErrorKind.INVALID_REQUEST, error.kind)
        assertEquals(422, error.httpCode)
        assertEquals("invalid model", error.detail)
    }

    @Test
    fun `maps all documented HTTP status codes`() {
        val expectedKinds = mapOf(
            400 to DeepSeekErrorKind.INVALID_REQUEST,
            401 to DeepSeekErrorKind.AUTHENTICATION,
            402 to DeepSeekErrorKind.BALANCE,
            422 to DeepSeekErrorKind.INVALID_REQUEST,
            429 to DeepSeekErrorKind.RATE_LIMIT,
            500 to DeepSeekErrorKind.SERVER,
            503 to DeepSeekErrorKind.SERVER,
            418 to DeepSeekErrorKind.HTTP
        )

        expectedKinds.forEach { (code, expectedKind) ->
            assertEquals(expectedKind, deepSeekHttpException(code, "{}").kind)
        }
    }

    @Test
    fun `maps socket timeout`() = runBlocking {
        val timeoutClient = OkHttpClient.Builder()
            .addInterceptor { throw SocketTimeoutException("test timeout") }
            .build()
        val client = DeepSeekClient(
            "https://api.deepseek.com",
            "test-key",
            timeoutClient
        )

        val error = expectDeepSeekException {
            client.createChatCompletion(minimalRequest())
        }

        assertEquals(DeepSeekErrorKind.TIMEOUT, error.kind)
    }

    @Test
    fun `rejects malformed success response`() = runBlocking {
        val client = DeepSeekClient(
            "https://api.deepseek.com",
            "test-key",
            fakeClient(responseBody = """{"id":"chat-1","choices":[]}""")
        )

        val error = expectDeepSeekException {
            client.createChatCompletion(minimalRequest())
        }

        assertEquals(DeepSeekErrorKind.INVALID_RESPONSE, error.kind)
    }

    @Test
    fun `cancels OkHttp call when coroutine is cancelled`() = runBlocking {
        val callFactory = WaitingCallFactory()
        val client = DeepSeekClient(
            "https://api.deepseek.com",
            "test-key",
            callFactory
        )

        val job = launch {
            client.createChatCompletion(minimalRequest())
        }
        yield()
        job.cancelAndJoin()

        assertTrue(callFactory.latestCall.isCanceled())
    }

    @Test
    fun `cancels streaming OkHttp call when collector is cancelled`() = runBlocking {
        val callFactory = WaitingCallFactory()
        val client = OpenAiCompatibleClient(
            "https://api.deepseek.com",
            "test-key",
            callFactory
        )

        val job = launch {
            client.createChatCompletionStream(minimalRequest().copy(stream = true)).collect()
        }
        yield()
        job.cancelAndJoin()

        assertTrue(callFactory.latestCall.isCanceled())
    }

    private fun minimalRequest() = DeepSeekChatRequest(
        model = "deepseek-v4-pro",
        messages = listOf(DeepSeekChatMessage.user("hello"))
    )

    private suspend fun expectDeepSeekException(block: suspend () -> Unit): DeepSeekException {
        try {
            block()
            fail("Expected DeepSeekException")
        } catch (error: DeepSeekException) {
            return error
        }
        error("unreachable")
    }

    private fun fakeClient(
        responseCode: Int = 200,
        responseBody: String,
        onRequest: (Request) -> Unit = {}
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                onRequest(request)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(responseCode)
                    .message("Test response")
                    .body(responseBody.toResponseBody(jsonMediaType))
                    .build()
            }
            .build()
    }

    private class WaitingCallFactory : Call.Factory {
        lateinit var latestCall: WaitingCall

        override fun newCall(request: Request): Call {
            return WaitingCall(request).also { latestCall = it }
        }
    }

    private class WaitingCall(
        private val originalRequest: Request
    ) : Call {

        @Volatile
        private var executed = false

        @Volatile
        private var canceled = false

        override fun request(): Request = originalRequest

        override fun execute(): Response {
            executed = true
            throw UnsupportedOperationException("Synchronous execution is not used in this test")
        }

        override fun enqueue(responseCallback: Callback) {
            executed = true
        }

        override fun cancel() {
            canceled = true
        }

        override fun isExecuted(): Boolean = executed

        override fun isCanceled(): Boolean = canceled

        override fun timeout(): Timeout = Timeout()

        override fun <T : Any> tag(type: KClass<T>): T? = null

        override fun <T> tag(type: Class<out T>): T? = null

        override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T {
            return computeIfAbsent()
        }

        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T {
            return computeIfAbsent()
        }

        override fun clone(): Call = WaitingCall(originalRequest)
    }

    companion object {
        private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    }
}
