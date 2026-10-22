package io.legado.app.model.ai.client

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefString

enum class AiProvider(
    val value: String,
    val defaultBaseUrl: String,
    val defaultModel: String
) {
    DEEPSEEK(
        value = "deepseek",
        defaultBaseUrl = "https://api.deepseek.com",
        defaultModel = "deepseek-v4-pro"
    ),
    MIMO(
        value = "mimo",
        defaultBaseUrl = "https://api.xiaomimimo.com/v1",
        defaultModel = "mimo-v2.5-pro"
    ),
    QWEN(
        value = "qwen",
        defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        defaultModel = "qwen3.8-max"
    );

    fun createChatRequest(
        model: String,
        messages: List<DeepSeekChatMessage>,
        thinkingEnabled: Boolean,
        reasoningEffort: DeepSeekReasoningEffort = DeepSeekReasoningEffort.HIGH,
        maxCompletionTokens: Int,
        stream: Boolean = false
    ): DeepSeekChatRequest {
        return DeepSeekChatRequest(
            model = model,
            messages = messages,
            thinking = DeepSeekThinking.fromEnabled(thinkingEnabled)
                .takeIf { this != QWEN },
            enableThinking = thinkingEnabled.takeIf { this == QWEN },
            thinkingBudget = QWEN_THINKING_BUDGET.takeIf {
                this == QWEN && thinkingEnabled
            },
            reasoningEffort = if (this == DEEPSEEK && thinkingEnabled) {
                reasoningEffort
            } else {
                null
            },
            maxTokens = maxCompletionTokens.takeIf { this == DEEPSEEK },
            maxCompletionTokens = maxCompletionTokens.takeIf { this != DEEPSEEK },
            stream = stream
        )
    }

    companion object {
        const val QWEN_THINKING_BUDGET = 4_096

        fun from(value: String?): AiProvider {
            return entries.firstOrNull { it.value == value } ?: DEEPSEEK
        }
    }
}

data class AiApiConfig(
    val provider: AiProvider,
    val baseUrl: String,
    val apiKey: String,
    val model: String
) {
    val isReady: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    fun createClient() = OpenAiCompatibleClient(baseUrl, apiKey)
}

object AiApiConfigProvider {

    fun current(context: Context): AiApiConfig {
        val provider = AiProvider.from(
            context.getPrefString(PreferKey.aiProvider, AiProvider.DEEPSEEK.value)
        )
        val keys = when (provider) {
            AiProvider.DEEPSEEK -> ProviderPreferenceKeys(
                baseUrl = PreferKey.aiApiBaseUrl,
                apiKey = PreferKey.aiApiKey,
                model = PreferKey.aiModel
            )

            AiProvider.MIMO -> ProviderPreferenceKeys(
                baseUrl = PreferKey.aiMimoApiBaseUrl,
                apiKey = PreferKey.aiMimoApiKey,
                model = PreferKey.aiMimoModel
            )

            AiProvider.QWEN -> ProviderPreferenceKeys(
                baseUrl = PreferKey.aiQwenApiBaseUrl,
                apiKey = PreferKey.aiQwenApiKey,
                model = PreferKey.aiQwenModel
            )
        }
        return AiApiConfig(
            provider = provider,
            baseUrl = context.getPrefString(
                keys.baseUrl,
                provider.defaultBaseUrl
            ).orEmpty().trim(),
            apiKey = context.getPrefString(keys.apiKey).orEmpty().trim(),
            model = context.getPrefString(
                keys.model,
                provider.defaultModel
            ).orEmpty().trim()
        )
    }

    private data class ProviderPreferenceKeys(
        val baseUrl: String,
        val apiKey: String,
        val model: String
    )
}
