package io.legado.app.model.ai.purify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PurificationResultValidatorTest {

    @Test
    fun `accepts any nonblank text after a complete response`() {
        val original = "原始小说正文"
        val unusualOutput = "```text\n以下是一个很短的结果\n```"

        val result = PurificationResultValidator.validate(
            expectedOriginalHash = PurificationResultValidator.hashOriginal(original),
            currentOriginalText = original,
            responseContent = unusualOutput,
            finishReason = "stop"
        )

        assertTrue(result is PurificationResultValidator.Result.Valid)
        assertEquals(
            unusualOutput,
            (result as PurificationResultValidator.Result.Valid).content
        )
    }

    @Test
    fun `rejects a truncated response`() {
        assertInvalid(
            finishReason = "length",
            expectedReason = PurificationResultValidator.FailureReason.LENGTH
        )
    }

    @Test
    fun `rejects filtered and resource interrupted responses`() {
        assertInvalid(
            finishReason = "content_filter",
            expectedReason = PurificationResultValidator.FailureReason.CONTENT_FILTER
        )
        assertInvalid(
            finishReason = "insufficient_system_resource",
            expectedReason =
                PurificationResultValidator.FailureReason.INSUFFICIENT_SYSTEM_RESOURCE
        )
    }

    @Test
    fun `rejects an empty complete response`() {
        val original = "原文"
        val result = PurificationResultValidator.validate(
            expectedOriginalHash = PurificationResultValidator.hashOriginal(original),
            currentOriginalText = original,
            responseContent = " \n",
            finishReason = "stop"
        )

        assertTrue(result is PurificationResultValidator.Result.Invalid)
        assertEquals(
            PurificationResultValidator.FailureReason.EMPTY_CONTENT,
            (result as PurificationResultValidator.Result.Invalid).reason
        )
    }

    @Test
    fun `rejects a result when the original chapter changed`() {
        val result = PurificationResultValidator.validate(
            expectedOriginalHash = PurificationResultValidator.hashOriginal("请求时原文"),
            currentOriginalText = "当前已更新的原文",
            responseContent = "净化结果",
            finishReason = "stop"
        )

        assertTrue(result is PurificationResultValidator.Result.Invalid)
        assertEquals(
            PurificationResultValidator.FailureReason.ORIGINAL_CHANGED,
            (result as PurificationResultValidator.Result.Invalid).reason
        )
    }

    private fun assertInvalid(
        finishReason: String,
        expectedReason: PurificationResultValidator.FailureReason
    ) {
        val original = "原文"
        val result = PurificationResultValidator.validate(
            expectedOriginalHash = PurificationResultValidator.hashOriginal(original),
            currentOriginalText = original,
            responseContent = "部分结果",
            finishReason = finishReason
        )

        assertTrue(result is PurificationResultValidator.Result.Invalid)
        assertEquals(
            expectedReason,
            (result as PurificationResultValidator.Result.Invalid).reason
        )
    }
}
