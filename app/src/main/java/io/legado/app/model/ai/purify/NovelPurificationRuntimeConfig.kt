package io.legado.app.model.ai.purify

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.model.ai.client.AiApiConfigProvider
import io.legado.app.model.ai.client.AiProvider
import io.legado.app.model.ai.client.DeepSeekReasoningEffort
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString

data class NovelPurificationRuntimeConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val thinkingEnabled: Boolean,
    val reasoningEffort: DeepSeekReasoningEffort,
    val systemPrompt: String,
    val userInstruction: String,
    val provider: AiProvider = AiProvider.DEEPSEEK
) {

    val isReady: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    val cacheSignature: String by lazy {
        PurificationResultValidator.hashOriginal(
            buildString {
                append("promptProtocol=")
                    .append(NovelPurificationPrompt.PROTOCOL_VERSION).append('\n')
                append("provider=").append(provider.value).append('\n')
                append("baseUrl=").append(baseUrl.trim().trimEnd('/')).append('\n')
                append("model=").append(model.trim()).append('\n')
                append("thinking=").append(thinkingEnabled).append('\n')
                append("reasoningEffort=").append(
                    if (thinkingEnabled) reasoningEffort.preferenceValue else "disabled"
                ).append('\n')
                append("systemHash=").append(
                    PurificationResultValidator.hashOriginal(systemPrompt)
                ).append('\n')
                append("instructionHash=").append(
                    PurificationResultValidator.hashOriginal(userInstruction.trim())
                )
            }
        )
    }

    fun createRequest(originalText: String) = NovelPurificationService.Request(
        model = model,
        systemPrompt = systemPrompt,
        userInstruction = userInstruction,
        originalText = originalText,
        thinkingEnabled = thinkingEnabled,
        reasoningEffort = reasoningEffort,
        provider = provider
    )

}

object NovelPurificationRuntimeConfigProvider {

    fun current(context: Context): NovelPurificationRuntimeConfig {
        val apiConfig = AiApiConfigProvider.current(context)
        val customSystemPrompt = context.getPrefString(
            PreferKey.aiNovelSystemPrompt
        ).orEmpty()
        return NovelPurificationRuntimeConfig(
            baseUrl = apiConfig.baseUrl,
            apiKey = apiConfig.apiKey,
            model = apiConfig.model,
            thinkingEnabled = context.getPrefBoolean(PreferKey.aiThinking, true),
            reasoningEffort = DeepSeekReasoningEffort.fromPreference(
                context.getPrefString(PreferKey.aiReasoningEffort, "low")
            ),
            systemPrompt = NovelPurificationPromptProvider.resolveSystemPrompt(
                context,
                customSystemPrompt
            ),
            userInstruction = context.getPrefString(
                PreferKey.aiNovelUserPrompt
            ).orEmpty(),
            provider = apiConfig.provider
        )
    }
}
