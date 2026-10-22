package io.legado.app.model.ai.purify

import kotlin.math.max

object NovelPurificationContextGuard {

    data class ModelLimits(
        val contextWindowTokens: Int,
        val maxOutputTokens: Int
    )

    data class RequestBudget(
        val estimatedInputTokens: Long,
        val reservedOutputTokens: Int,
        val estimatedTotalTokens: Long,
        val safeContextTokens: Long,
        val modelLimits: ModelLimits
    )

    sealed interface Result {
        data class Allowed(val budget: RequestBudget) : Result

        data class Rejected(
            val estimatedInputTokens: Long,
            val requiredOutputTokens: Long,
            val safeContextTokens: Long,
            val modelLimits: ModelLimits
        ) : Result
    }

    fun check(
        model: String,
        systemPrompt: String,
        userInstruction: String,
        novelText: String,
        thinkingEnabled: Boolean
    ): Result {
        val limits = limitsFor(model)
        val estimatedInputTokens = NovelPurificationPrompt.buildMessages(
            systemPrompt = systemPrompt,
            userInstruction = userInstruction,
            novelText = novelText
        ).sumOf { message ->
            estimateTokens(message.content.orEmpty()) + MESSAGE_OVERHEAD_TOKENS
        }
        val reasoningReserve = if (thinkingEnabled) {
            THINKING_RESERVE_TOKENS
        } else {
            NON_THINKING_RESERVE_TOKENS
        }
        val requiredOutputTokens = max(
            MIN_OUTPUT_TOKENS.toLong(),
            estimateTokens(novelText) + reasoningReserve
        )
        val safeContextTokens = limits.contextWindowTokens.toLong() *
            CONTEXT_SAFETY_PERCENT / 100
        val estimatedTotalTokens = estimatedInputTokens + requiredOutputTokens

        if (requiredOutputTokens > limits.maxOutputTokens ||
            estimatedTotalTokens > safeContextTokens
        ) {
            return Result.Rejected(
                estimatedInputTokens = estimatedInputTokens,
                requiredOutputTokens = requiredOutputTokens,
                safeContextTokens = safeContextTokens,
                modelLimits = limits
            )
        }

        return Result.Allowed(
            RequestBudget(
                estimatedInputTokens = estimatedInputTokens,
                reservedOutputTokens = requiredOutputTokens.toInt(),
                estimatedTotalTokens = estimatedTotalTokens,
                safeContextTokens = safeContextTokens,
                modelLimits = limits
            )
        )
    }

    fun limitsFor(model: String): ModelLimits {
        return when (model.trim().lowercase()) {
            "mimo-v2.5-pro",
            "mimo-v2.5" -> MIMO_V25_LIMITS
            "qwen3.8-max",
            "qwen3.8-flash",
            "qwen3.8-2.4t-a95b",
            "qwen3.8-27b" -> QWEN_38_LIMITS
            "deepseek-v4-pro",
            "deepseek-v4-flash",
            "deepseek-chat",
            "deepseek-reasoner" -> DEEPSEEK_V4_LIMITS
            else -> DEEPSEEK_V4_LIMITS
        }
    }

    internal fun estimateTokens(text: String): Long {
        if (text.isEmpty()) return 0
        // UTF-8 bytes are used as a deliberately high estimate for multilingual novel text.
        return text.toByteArray(Charsets.UTF_8).size.toLong()
    }

    private const val MESSAGE_OVERHEAD_TOKENS = 32L
    private const val MIN_OUTPUT_TOKENS = 4_096
    private const val THINKING_RESERVE_TOKENS = 16_384L
    private const val NON_THINKING_RESERVE_TOKENS = 1_024L
    private const val CONTEXT_SAFETY_PERCENT = 90L

    // Keep synchronized with DEEPSEEK_API_DEVELOPMENT.md and the official model page.
    private val DEEPSEEK_V4_LIMITS = ModelLimits(
        contextWindowTokens = 1_000_000,
        maxOutputTokens = 384_000
    )

    private val MIMO_V25_LIMITS = ModelLimits(
        contextWindowTokens = 1_000_000,
        maxOutputTokens = 128_000
    )

    private val QWEN_38_LIMITS = ModelLimits(
        contextWindowTokens = 1_000_000,
        maxOutputTokens = 131_072
    )
}
