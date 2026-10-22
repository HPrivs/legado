package io.legado.app.model.ai.client

import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object AiConnectionTester {

    private val testClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    suspend fun test(config: AiApiConfig): DeepSeekConnectionResult {
        if (config.provider == AiProvider.DEEPSEEK) {
            return DeepSeekConnectionTester.test(
                config.baseUrl,
                config.apiKey,
                config.model
            )
        }
        return try {
            val request = config.provider.createChatRequest(
                model = config.model,
                messages = listOf(
                    DeepSeekChatMessage.user("Reply with OK only.")
                ),
                thinkingEnabled = false,
                maxCompletionTokens = 32
            )
            val response = OpenAiCompatibleClient(
                config.baseUrl,
                config.apiKey,
                testClient
            ).createChatCompletion(request)
            if (response.choices.orEmpty().firstOrNull()?.message?.content.isNullOrBlank()) {
                DeepSeekConnectionResult.Failure(
                    DeepSeekConnectionFailure.INVALID_RESPONSE
                )
            } else {
                DeepSeekConnectionResult.Success(listOf(config.model))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: DeepSeekException) {
            DeepSeekConnectionResult.Failure(
                kind = when (error.kind) {
                    DeepSeekErrorKind.INVALID_URL -> DeepSeekConnectionFailure.INVALID_URL
                    DeepSeekErrorKind.HTTPS_REQUIRED -> DeepSeekConnectionFailure.HTTPS_REQUIRED
                    DeepSeekErrorKind.AUTHENTICATION,
                    DeepSeekErrorKind.MISSING_API_KEY -> DeepSeekConnectionFailure.AUTHENTICATION
                    DeepSeekErrorKind.BALANCE -> DeepSeekConnectionFailure.BALANCE
                    DeepSeekErrorKind.RATE_LIMIT -> DeepSeekConnectionFailure.RATE_LIMIT
                    DeepSeekErrorKind.SERVER -> DeepSeekConnectionFailure.SERVER
                    DeepSeekErrorKind.NETWORK,
                    DeepSeekErrorKind.TIMEOUT -> DeepSeekConnectionFailure.NETWORK
                    DeepSeekErrorKind.INVALID_RESPONSE -> {
                        DeepSeekConnectionFailure.INVALID_RESPONSE
                    }
                    DeepSeekErrorKind.INVALID_REQUEST,
                    DeepSeekErrorKind.HTTP -> DeepSeekConnectionFailure.HTTP
                },
                httpCode = error.httpCode,
                detail = error.detail
            )
        }
    }
}
