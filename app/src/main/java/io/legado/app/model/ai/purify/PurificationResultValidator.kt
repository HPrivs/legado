package io.legado.app.model.ai.purify

import java.security.MessageDigest

object PurificationResultValidator {

    enum class FailureReason {
        ORIGINAL_CHANGED,
        LENGTH,
        CONTENT_FILTER,
        INSUFFICIENT_SYSTEM_RESOURCE,
        INCOMPLETE,
        EMPTY_CONTENT
    }

    sealed interface Result {
        data class Valid(val content: String) : Result

        data class Invalid(
            val reason: FailureReason,
            val finishReason: String? = null
        ) : Result
    }

    fun validate(
        expectedOriginalHash: String,
        currentOriginalText: String,
        responseContent: String?,
        finishReason: String?
    ): Result {
        if (hashOriginal(currentOriginalText) != expectedOriginalHash) {
            return Result.Invalid(FailureReason.ORIGINAL_CHANGED, finishReason)
        }
        if (finishReason != "stop") {
            val reason = when (finishReason) {
                "length" -> FailureReason.LENGTH
                "content_filter" -> FailureReason.CONTENT_FILTER
                "insufficient_system_resource" -> FailureReason.INSUFFICIENT_SYSTEM_RESOURCE
                else -> FailureReason.INCOMPLETE
            }
            return Result.Invalid(reason, finishReason)
        }
        if (responseContent.isNullOrBlank()) {
            return Result.Invalid(FailureReason.EMPTY_CONTENT, finishReason)
        }
        return Result.Valid(responseContent)
    }

    fun hashOriginal(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 0x0f])
            }
        }
    }
}
