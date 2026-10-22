package io.legado.app.model.ai.purify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelPurificationContextGuardTest {

    @Test
    fun `allows a normal complete chapter and reserves full output`() {
        val result = NovelPurificationContextGuard.check(
            model = "deepseek-v4-pro",
            systemPrompt = "保守净化规则",
            userInstruction = "保留章节标题",
            novelText = "正文。".repeat(2_000),
            thinkingEnabled = true
        )

        assertTrue(result is NovelPurificationContextGuard.Result.Allowed)
        val budget = (result as NovelPurificationContextGuard.Result.Allowed).budget
        assertTrue(budget.reservedOutputTokens > 4_096)
        assertTrue(budget.estimatedTotalTokens < budget.safeContextTokens)
        assertTrue(budget.reservedOutputTokens <= budget.modelLimits.maxOutputTokens)
    }

    @Test
    fun `rejects an abnormal chapter before an API request`() {
        val result = NovelPurificationContextGuard.check(
            model = "deepseek-v4-pro",
            systemPrompt = "净化规则",
            userInstruction = "",
            novelText = "章".repeat(190_000),
            thinkingEnabled = true
        )

        assertTrue(result is NovelPurificationContextGuard.Result.Rejected)
    }

    @Test
    fun `thinking mode reserves more completion tokens`() {
        val thinking = allowedBudget(thinkingEnabled = true)
        val nonThinking = allowedBudget(thinkingEnabled = false)

        assertTrue(thinking.reservedOutputTokens > nonThinking.reservedOutputTokens)
    }

    @Test
    fun `uses current DeepSeek V4 model limits`() {
        val limits = NovelPurificationContextGuard.limitsFor("deepseek-v4-flash")

        assertEquals(1_000_000, limits.contextWindowTokens)
        assertEquals(384_000, limits.maxOutputTokens)
    }

    @Test
    fun `uses current MiMo V25 model limits`() {
        val limits = NovelPurificationContextGuard.limitsFor("mimo-v2.5-pro")

        assertEquals(1_000_000, limits.contextWindowTokens)
        assertEquals(128_000, limits.maxOutputTokens)
    }

    @Test
    fun `uses current Qwen 38 model limits`() {
        listOf(
            "qwen3.8-max",
            "qwen3.8-flash",
            "qwen3.8-2.4t-a95b",
            "qwen3.8-27b"
        ).forEach { model ->
            val limits = NovelPurificationContextGuard.limitsFor(model)

            assertEquals(1_000_000, limits.contextWindowTokens)
            assertEquals(131_072, limits.maxOutputTokens)
        }
    }

    @Test
    fun `uses UTF-8 bytes as a conservative multilingual estimate`() {
        assertEquals(4L, NovelPurificationContextGuard.estimateTokens("😀"))
        assertEquals(3L, NovelPurificationContextGuard.estimateTokens("章"))
    }

    private fun allowedBudget(
        thinkingEnabled: Boolean
    ): NovelPurificationContextGuard.RequestBudget {
        val result = NovelPurificationContextGuard.check(
            model = "deepseek-v4-pro",
            systemPrompt = "净化规则",
            userInstruction = "",
            novelText = "一段正常正文。".repeat(100),
            thinkingEnabled = thinkingEnabled
        )
        return (result as NovelPurificationContextGuard.Result.Allowed).budget
    }
}
