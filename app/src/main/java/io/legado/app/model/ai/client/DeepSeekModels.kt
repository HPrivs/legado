package io.legado.app.model.ai.client

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class DeepSeekChatRequest(
    val model: String,
    val messages: List<DeepSeekChatMessage>,
    val thinking: DeepSeekThinking? = null,
    @SerializedName("enable_thinking")
    val enableThinking: Boolean? = null,
    @SerializedName("thinking_budget")
    val thinkingBudget: Int? = null,
    @SerializedName("reasoning_effort")
    val reasoningEffort: DeepSeekReasoningEffort? = null,
    @SerializedName("max_tokens")
    val maxTokens: Int? = null,
    @SerializedName("max_completion_tokens")
    val maxCompletionTokens: Int? = null,
    val stream: Boolean = false
)

@Keep
data class DeepSeekChatMessage(
    val role: String,
    val content: String?,
    val name: String? = null,
    @SerializedName("reasoning_content")
    val reasoningContent: String? = null
) {

    companion object {
        fun system(content: String, name: String? = null) =
            DeepSeekChatMessage(role = "system", content = content, name = name)

        fun user(content: String, name: String? = null) =
            DeepSeekChatMessage(role = "user", content = content, name = name)

        fun assistant(content: String, reasoningContent: String? = null) =
            DeepSeekChatMessage(
                role = "assistant",
                content = content,
                reasoningContent = reasoningContent
            )
    }
}

@Keep
data class DeepSeekThinking(
    val type: DeepSeekThinkingType
) {

    companion object {
        fun fromEnabled(enabled: Boolean) = DeepSeekThinking(
            if (enabled) DeepSeekThinkingType.ENABLED else DeepSeekThinkingType.DISABLED
        )
    }
}

@Keep
enum class DeepSeekThinkingType {
    @SerializedName("enabled")
    ENABLED,

    @SerializedName("disabled")
    DISABLED
}

@Keep
enum class DeepSeekReasoningEffort(val preferenceValue: String) {
    @SerializedName("low")
    LOW("low"),

    @SerializedName("high")
    HIGH("high"),

    @SerializedName("max")
    MAX("max");

    companion object {
        fun fromPreference(value: String?): DeepSeekReasoningEffort {
            return entries.firstOrNull { it.preferenceValue == value } ?: LOW
        }
    }
}

@Keep
data class DeepSeekChatResponse(
    val id: String? = null,
    val choices: List<DeepSeekChatChoice>? = null,
    val created: Long? = null,
    val model: String? = null,
    @SerializedName("system_fingerprint")
    val systemFingerprint: String? = null,
    val usage: DeepSeekUsage? = null
)

@Keep
data class DeepSeekChatChoice(
    val index: Int? = null,
    val message: DeepSeekChatMessage? = null,
    @SerializedName("finish_reason")
    val finishReason: String? = null
)

@Keep
data class DeepSeekChatChunk(
    val id: String? = null,
    val choices: List<DeepSeekChatChunkChoice>? = null,
    val created: Long? = null,
    val model: String? = null,
    val usage: DeepSeekUsage? = null
)

@Keep
data class DeepSeekChatChunkChoice(
    val index: Int? = null,
    val delta: DeepSeekChatDelta? = null,
    @SerializedName("finish_reason")
    val finishReason: String? = null
)

@Keep
data class DeepSeekChatDelta(
    val role: String? = null,
    val content: String? = null,
    @SerializedName("reasoning_content")
    val reasoningContent: String? = null
)

sealed interface AiStreamEvent {
    data class ReasoningDelta(val text: String) : AiStreamEvent

    data class ContentDelta(val text: String) : AiStreamEvent

    data class UsageUpdate(val usage: DeepSeekUsage) : AiStreamEvent

    data class Finished(val reason: String?) : AiStreamEvent
}

@Keep
data class DeepSeekUsage(
    @SerializedName("prompt_tokens")
    val promptTokens: Int? = null,
    @SerializedName("completion_tokens")
    val completionTokens: Int? = null,
    @SerializedName("total_tokens")
    val totalTokens: Int? = null,
    @SerializedName("prompt_cache_hit_tokens")
    val promptCacheHitTokens: Int? = null,
    @SerializedName("prompt_cache_miss_tokens")
    val promptCacheMissTokens: Int? = null,
    @SerializedName("completion_tokens_details")
    val completionTokensDetails: DeepSeekCompletionTokensDetails? = null
)

@Keep
data class DeepSeekCompletionTokensDetails(
    @SerializedName("reasoning_tokens")
    val reasoningTokens: Int? = null
)
