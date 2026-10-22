package io.legado.app.model.ai.client

import io.legado.app.help.http.await
import io.legado.app.utils.GSON
import io.legado.app.utils.GSONStrict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class OpenAiCompatibleClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val callFactory: Call.Factory = defaultClient
) {

    suspend fun createChatCompletion(
        request: DeepSeekChatRequest
    ): DeepSeekChatResponse = withContext(Dispatchers.IO) {
        validateRequest(request)
        if (request.stream) {
            throw DeepSeekException(
                DeepSeekErrorKind.INVALID_REQUEST,
                detail = "use createChatCompletionStream for stream=true"
            )
        }
        val endpoint = chatCompletionsEndpoint()
        val requestBody = GSON.toJson(request).toRequestBody(jsonMediaType)
        val httpRequest = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .post(requestBody)
            .build()

        try {
            callFactory.newCall(httpRequest).await().use { response ->
                val responseText = response.body.string()
                if (!response.isSuccessful) {
                    throw deepSeekHttpException(response.code, responseText)
                }
                parseResponse(responseText)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DeepSeekException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw DeepSeekException(DeepSeekErrorKind.TIMEOUT, cause = e)
        } catch (e: InterruptedIOException) {
            throw DeepSeekException(DeepSeekErrorKind.TIMEOUT, cause = e)
        } catch (e: IOException) {
            throw DeepSeekException(
                DeepSeekErrorKind.NETWORK,
                detail = e.localizedMessage,
                cause = e
            )
        } catch (e: Exception) {
            throw DeepSeekException(
                DeepSeekErrorKind.INVALID_RESPONSE,
                detail = e.localizedMessage,
                cause = e
            )
        }
    }

    fun createChatCompletionStream(
        request: DeepSeekChatRequest
    ): Flow<AiStreamEvent> = callbackFlow {
        validateRequest(request)
        if (!request.stream) {
            throw DeepSeekException(
                DeepSeekErrorKind.INVALID_REQUEST,
                detail = "streaming requests must set stream=true"
            )
        }
        val endpoint = chatCompletionsEndpoint()
        val requestBody = GSON.toJson(request).toRequestBody(jsonMediaType)
        val httpRequest = Request.Builder()
            .url(endpoint)
            .header("Accept", "text/event-stream")
            .header("Accept-Encoding", "identity")
            .header("Cache-Control", "no-cache")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .post(requestBody)
            .build()
        val call = callFactory.newCall(httpRequest)
        val readerJob = launch(Dispatchers.IO) {
            try {
                call.await().use { response ->
                    if (!response.isSuccessful) {
                        throw deepSeekHttpException(response.code, response.body.string())
                    }
                    val source = response.body.source()
                    var finishReason: String? = null
                    var done = false

                    suspend fun processData(data: String): Boolean {
                        if (data.isBlank()) return false
                        if (data == "[DONE]") {
                            send(AiStreamEvent.Finished(finishReason ?: "stop"))
                            return true
                        }
                        val chunk = parseStreamChunk(data)
                        chunk.choices.orEmpty().firstOrNull()?.let { choice ->
                            choice.delta?.reasoningContent
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { send(AiStreamEvent.ReasoningDelta(it)) }
                            choice.delta?.content
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { send(AiStreamEvent.ContentDelta(it)) }
                            if (choice.finishReason != null) {
                                finishReason = choice.finishReason
                            }
                        }
                        chunk.usage?.let { send(AiStreamEvent.UsageUpdate(it)) }
                        return false
                    }

                    while (!done) {
                        val line = source.readUtf8Line() ?: break
                        when {
                            line.isEmpty() -> Unit
                            line.startsWith(":") -> Unit
                            line.startsWith("data:") -> {
                                done = processData(line.removePrefix("data:").trimStart())
                            }
                        }
                    }
                    if (!done && finishReason != null) {
                        send(AiStreamEvent.Finished(finishReason))
                        done = true
                    }
                    if (!done) {
                        throw DeepSeekException(
                            DeepSeekErrorKind.INVALID_RESPONSE,
                            detail = "stream ended without [DONE]"
                        )
                    }
                }
                close()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                close(mapException(error))
            }
        }
        awaitClose {
            call.cancel()
            readerJob.cancel()
        }
    }

    private fun validateRequest(request: DeepSeekChatRequest) {
        if (apiKey.isBlank()) {
            throw DeepSeekException(DeepSeekErrorKind.MISSING_API_KEY)
        }
        if (request.model.isBlank()) {
            throw DeepSeekException(
                DeepSeekErrorKind.INVALID_REQUEST,
                detail = "model must not be blank"
            )
        }
        if (request.messages.isEmpty()) {
            throw DeepSeekException(
                DeepSeekErrorKind.INVALID_REQUEST,
                detail = "messages must not be empty"
            )
        }
        if (request.messages.any { it.role.isBlank() || it.content == null }) {
            throw DeepSeekException(
                DeepSeekErrorKind.INVALID_REQUEST,
                detail = "each message must contain role and content"
            )
        }
        if (request.maxTokens != null && request.maxTokens <= 0) {
            throw DeepSeekException(
                DeepSeekErrorKind.INVALID_REQUEST,
                detail = "max_tokens must be greater than zero"
            )
        }
        if (request.maxCompletionTokens != null && request.maxCompletionTokens <= 0) {
            throw DeepSeekException(
                DeepSeekErrorKind.INVALID_REQUEST,
                detail = "max_completion_tokens must be greater than zero"
            )
        }
        if (request.thinkingBudget != null && request.thinkingBudget <= 0) {
            throw DeepSeekException(
                DeepSeekErrorKind.INVALID_REQUEST,
                detail = "thinking_budget must be greater than zero"
            )
        }
        if (request.maxTokens != null && request.maxCompletionTokens != null) {
            throw DeepSeekException(
                DeepSeekErrorKind.INVALID_REQUEST,
                detail = "max_tokens and max_completion_tokens cannot both be set"
            )
        }
    }

    private fun chatCompletionsEndpoint(): String {
        val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
        val parsedBaseUrl = normalizedBaseUrl.toHttpUrlOrNull()
            ?: throw DeepSeekException(DeepSeekErrorKind.INVALID_URL)
        if (!parsedBaseUrl.isHttps) {
            throw DeepSeekException(DeepSeekErrorKind.HTTPS_REQUIRED)
        }
        if (parsedBaseUrl.query != null || parsedBaseUrl.fragment != null) {
            throw DeepSeekException(DeepSeekErrorKind.INVALID_URL)
        }
        return "$normalizedBaseUrl/chat/completions".toHttpUrlOrNull()?.toString()
            ?: throw DeepSeekException(DeepSeekErrorKind.INVALID_URL)
    }

    private fun parseResponse(responseText: String): DeepSeekChatResponse {
        val chatResponse = runCatching {
            GSONStrict.fromJson(responseText, DeepSeekChatResponse::class.java)
        }.getOrElse { error ->
            throw DeepSeekException(
                DeepSeekErrorKind.INVALID_RESPONSE,
                detail = error.localizedMessage,
                cause = error
            )
        }
        if (chatResponse?.choices?.firstOrNull()?.message == null) {
            throw DeepSeekException(
                DeepSeekErrorKind.INVALID_RESPONSE,
                detail = "choices[0].message is missing"
            )
        }
        return chatResponse
    }

    private fun parseStreamChunk(data: String): DeepSeekChatChunk {
        return runCatching {
            GSONStrict.fromJson(data, DeepSeekChatChunk::class.java)
        }.getOrElse { error ->
            throw DeepSeekException(
                DeepSeekErrorKind.INVALID_RESPONSE,
                detail = error.localizedMessage,
                cause = error
            )
        }
    }

    private fun mapException(error: Exception): DeepSeekException {
        return when (error) {
            is DeepSeekException -> error
            is SocketTimeoutException, is InterruptedIOException -> {
                DeepSeekException(DeepSeekErrorKind.TIMEOUT, cause = error)
            }

            is IOException -> DeepSeekException(
                DeepSeekErrorKind.NETWORK,
                detail = error.localizedMessage,
                cause = error
            )

            else -> DeepSeekException(
                DeepSeekErrorKind.INVALID_RESPONSE,
                detail = error.localizedMessage,
                cause = error
            )
        }
    }

    companion object {
        private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

        private val defaultClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                // A chapter stream may keep producing reasoning/content for more than five minutes.
                // The read timeout still detects a connection that becomes completely silent.
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}

/** Kept for source compatibility with the original single-provider integration. */
class DeepSeekClient {
    private val delegate: OpenAiCompatibleClient

    constructor(baseUrl: String, apiKey: String) {
        delegate = OpenAiCompatibleClient(baseUrl, apiKey)
    }

    constructor(baseUrl: String, apiKey: String, callFactory: Call.Factory) {
        delegate = OpenAiCompatibleClient(baseUrl, apiKey, callFactory)
    }

    suspend fun createChatCompletion(
        request: DeepSeekChatRequest
    ): DeepSeekChatResponse = delegate.createChatCompletion(request)

    fun createChatCompletionStream(
        request: DeepSeekChatRequest
    ): Flow<AiStreamEvent> = delegate.createChatCompletionStream(request)
}
