package io.legado.app.model.ai.purify

import io.legado.app.model.ai.client.AiProvider
import io.legado.app.model.ai.client.DeepSeekReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelPurificationRuntimeConfigTest {

    @Test
    fun `reasoning effort preference uses low as safe default`() {
        assertEquals(
            DeepSeekReasoningEffort.LOW,
            DeepSeekReasoningEffort.fromPreference(null)
        )
        assertEquals(
            DeepSeekReasoningEffort.LOW,
            DeepSeekReasoningEffort.fromPreference("unsupported")
        )
        assertEquals(
            DeepSeekReasoningEffort.MAX,
            DeepSeekReasoningEffort.fromPreference("max")
        )
    }

    @Test
    fun `cache signature excludes API key`() {
        val first = config(apiKey = "key-a")
        val second = config(apiKey = "key-b")

        assertEquals(first.cacheSignature, second.cacheSignature)
    }

    @Test
    fun `cache signature changes with output-affecting settings`() {
        val base = config()

        assertNotEquals(base.cacheSignature, config(model = "deepseek-v4-flash").cacheSignature)
        assertNotEquals(base.cacheSignature, config(thinkingEnabled = false).cacheSignature)
        assertNotEquals(
            base.cacheSignature,
            config(reasoningEffort = DeepSeekReasoningEffort.MAX).cacheSignature
        )
        assertNotEquals(base.cacheSignature, config(systemPrompt = "新规则").cacheSignature)
        assertNotEquals(base.cacheSignature, config(userInstruction = "新要求").cacheSignature)
        assertNotEquals(base.cacheSignature, config(provider = AiProvider.MIMO).cacheSignature)
        assertNotEquals(base.cacheSignature, config(provider = AiProvider.QWEN).cacheSignature)
    }

    @Test
    fun `reasoning effort does not change cache when thinking is disabled`() {
        val low = config(
            thinkingEnabled = false,
            reasoningEffort = DeepSeekReasoningEffort.LOW
        )
        val max = config(
            thinkingEnabled = false,
            reasoningEffort = DeepSeekReasoningEffort.MAX
        )

        assertEquals(low.cacheSignature, max.cacheSignature)
    }

    @Test
    fun `cache signature normalizes user instruction like the request builder`() {
        val first = config(userInstruction = "保留章节标题")
        val second = config(userInstruction = "  保留章节标题\n")

        assertEquals(first.cacheSignature, second.cacheSignature)
    }

    @Test
    fun `request readiness requires URL key and model`() {
        assertTrue(config().isReady)
        assertTrue(!config(apiKey = "").isReady)
        assertTrue(!config(baseUrl = "").isReady)
        assertTrue(!config(model = "").isReady)
    }

    private fun config(
        baseUrl: String = "https://api.deepseek.com",
        apiKey: String = "test-key",
        model: String = "deepseek-v4-pro",
        thinkingEnabled: Boolean = true,
        reasoningEffort: DeepSeekReasoningEffort = DeepSeekReasoningEffort.LOW,
        systemPrompt: String = "净化规则",
        userInstruction: String = "",
        provider: AiProvider = AiProvider.DEEPSEEK
    ) = NovelPurificationRuntimeConfig(
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        thinkingEnabled = thinkingEnabled,
        reasoningEffort = reasoningEffort,
        systemPrompt = systemPrompt,
        userInstruction = userInstruction,
        provider = provider
    )
}
