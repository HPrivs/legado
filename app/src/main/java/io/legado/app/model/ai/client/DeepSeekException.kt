package io.legado.app.model.ai.client

import androidx.annotation.Keep
import io.legado.app.utils.GSON
import java.io.IOException

class DeepSeekException(
    val kind: DeepSeekErrorKind,
    val httpCode: Int? = null,
    val detail: String? = null,
    cause: Throwable? = null
) : IOException(detail ?: kind.name, cause)

enum class DeepSeekErrorKind {
    INVALID_URL,
    HTTPS_REQUIRED,
    MISSING_API_KEY,
    INVALID_REQUEST,
    AUTHENTICATION,
    BALANCE,
    RATE_LIMIT,
    SERVER,
    INVALID_RESPONSE,
    TIMEOUT,
    NETWORK,
    HTTP
}

internal fun deepSeekHttpException(code: Int, responseText: String): DeepSeekException {
    val detail = runCatching {
        GSON.fromJson(responseText, DeepSeekErrorResponse::class.java)
            ?.error
            ?.message
            ?.takeIf(String::isNotBlank)
    }.getOrNull()
    val kind = when (code) {
        400, 422 -> DeepSeekErrorKind.INVALID_REQUEST
        401 -> DeepSeekErrorKind.AUTHENTICATION
        402 -> DeepSeekErrorKind.BALANCE
        429 -> DeepSeekErrorKind.RATE_LIMIT
        500, 503 -> DeepSeekErrorKind.SERVER
        else -> DeepSeekErrorKind.HTTP
    }
    return DeepSeekException(kind, code, detail)
}

@Keep
private data class DeepSeekErrorResponse(
    val error: DeepSeekErrorBody? = null
)

@Keep
private data class DeepSeekErrorBody(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null,
    val param: String? = null
)
