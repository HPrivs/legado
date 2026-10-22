package io.legado.app.model.ai.client

import io.legado.app.help.http.await
import io.legado.app.utils.GSON
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.concurrent.TimeUnit

object DeepSeekConnectionTester {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    suspend fun test(
        baseUrl: String,
        apiKey: String,
        selectedModel: String
    ): DeepSeekConnectionResult {
        val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
        val parsedBaseUrl = normalizedBaseUrl.toHttpUrlOrNull()
            ?: return DeepSeekConnectionResult.Failure(
                DeepSeekConnectionFailure.INVALID_URL
            )
        if (!parsedBaseUrl.isHttps) {
            return DeepSeekConnectionResult.Failure(
                DeepSeekConnectionFailure.HTTPS_REQUIRED
            )
        }
        val endpoint = "$normalizedBaseUrl/models".toHttpUrlOrNull()
            ?: return DeepSeekConnectionResult.Failure(
                DeepSeekConnectionFailure.INVALID_URL
            )
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .get()
            .build()

        try {
            client.newCall(request).await().use { response ->
                val responseText = response.body.string()
                if (!response.isSuccessful) {
                    return httpFailure(response.code, responseText)
                }
                val modelResponse = runCatching {
                    GSON.fromJson(responseText, ModelListResponse::class.java)
                }.getOrNull()
                val models = modelResponse?.data
                    ?.mapNotNull { it.id?.takeIf(String::isNotBlank) }
                    ?.distinct()
                    .orEmpty()
                if (models.isEmpty()) {
                    return DeepSeekConnectionResult.Failure(
                        DeepSeekConnectionFailure.INVALID_RESPONSE
                    )
                }
                if (selectedModel !in models) {
                    return DeepSeekConnectionResult.Failure(
                        kind = DeepSeekConnectionFailure.MODEL_UNAVAILABLE,
                        availableModels = models
                    )
                }
                return DeepSeekConnectionResult.Success(models)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return DeepSeekConnectionResult.Failure(
                kind = DeepSeekConnectionFailure.NETWORK,
                detail = e.localizedMessage
            )
        } catch (e: Exception) {
            return DeepSeekConnectionResult.Failure(
                kind = DeepSeekConnectionFailure.INVALID_RESPONSE,
                detail = e.localizedMessage
            )
        }
    }

    private fun httpFailure(code: Int, responseText: String): DeepSeekConnectionResult.Failure {
        val detail = runCatching {
            GSON.fromJson(responseText, ErrorResponse::class.java).error?.message
        }.getOrNull()?.takeIf(String::isNotBlank)
        val kind = when (code) {
            401 -> DeepSeekConnectionFailure.AUTHENTICATION
            402 -> DeepSeekConnectionFailure.BALANCE
            429 -> DeepSeekConnectionFailure.RATE_LIMIT
            500, 503 -> DeepSeekConnectionFailure.SERVER
            else -> DeepSeekConnectionFailure.HTTP
        }
        return DeepSeekConnectionResult.Failure(kind, code, detail)
    }

    private data class ModelListResponse(
        val data: List<ModelInfo>? = null
    )

    private data class ModelInfo(
        val id: String? = null
    )

    private data class ErrorResponse(
        val error: ErrorDetail? = null
    )

    private data class ErrorDetail(
        val message: String? = null
    )
}

sealed interface DeepSeekConnectionResult {

    data class Success(
        val availableModels: List<String>
    ) : DeepSeekConnectionResult

    data class Failure(
        val kind: DeepSeekConnectionFailure,
        val httpCode: Int? = null,
        val detail: String? = null,
        val availableModels: List<String> = emptyList()
    ) : DeepSeekConnectionResult
}

enum class DeepSeekConnectionFailure {
    INVALID_URL,
    HTTPS_REQUIRED,
    AUTHENTICATION,
    BALANCE,
    RATE_LIMIT,
    SERVER,
    MODEL_UNAVAILABLE,
    INVALID_RESPONSE,
    NETWORK,
    HTTP
}
