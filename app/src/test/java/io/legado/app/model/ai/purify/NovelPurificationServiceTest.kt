package io.legado.app.model.ai.purify

import io.legado.app.model.ai.client.DeepSeekChatChoice
import io.legado.app.model.ai.client.DeepSeekChatMessage
import io.legado.app.model.ai.client.DeepSeekChatRequest
import io.legado.app.model.ai.client.DeepSeekChatResponse
import io.legado.app.model.ai.client.DeepSeekReasoningEffort
import io.legado.app.model.ai.client.DeepSeekThinkingType
import io.legado.app.model.ai.client.AiProvider
import io.legado.app.model.ai.client.AiStreamEvent
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelPurificationServiceTest {

    @Test
    fun `streams one whole chapter request and publishes completed paragraphs`() = runBlocking {
        val capturedRequests = mutableListOf<DeepSeekChatRequest>()
        val updates = mutableListOf<StreamingParagraphAccumulator.Update>()
        val diagnostics = mutableListOf<PurificationStreamDiagnostic>()
        val service = NovelPurificationService(
            createChatCompletion = { error("non-streaming API must not be called") },
            createChatCompletionStream = { request ->
                capturedRequests += request
                flowOf(
                    AiStreamEvent.ReasoningDelta("不应展示的思考"),
                    AiStreamEvent.ContentDelta("第一"),
                    AiStreamEvent.ContentDelta("段。\n第"),
                    AiStreamEvent.ContentDelta("二段。"),
                    AiStreamEvent.Finished("stop")
                )
            }
        )

        val result = service.purifyStreaming(
            request = normalRequest(),
            onDiagnostic = { diagnostics += it },
            onContentAvailable = { updates += it }
        )

        assertEquals(1, capturedRequests.size)
        assertTrue(capturedRequests.single().stream)
        assertEquals(
            listOf("第一", "第一段。\n第", "第一段。\n第二段。"),
            updates.map { it.content }
        )
        assertEquals(
            listOf("第一段。"),
            updates.mapNotNull { it.completedLines }
        )
        assertEquals(
            "第一段。\n第二段。",
            (result as NovelPurificationService.Result.Success).content
        )
        assertTrue(diagnostics[0] is PurificationStreamDiagnostic.Collecting)
        assertEquals(
            PurificationStreamDiagnostic.FirstSseEvent("reasoning"),
            diagnostics[1]
        )
        assertTrue(diagnostics[2] is PurificationStreamDiagnostic.FirstReasoningDelta)
        assertTrue(diagnostics[3] is PurificationStreamDiagnostic.FirstContentDelta)
        assertEquals(
            PurificationStreamDiagnostic.Finished("stop"),
            diagnostics[4]
        )
        val completed = diagnostics[5] as PurificationStreamDiagnostic.Completed
        assertEquals(1, completed.reasoningDeltas)
        assertEquals(3, completed.contentDeltas)
        assertEquals("第一段。\n第二段。".length.toLong(), completed.contentChars)
    }

    @Test
    fun `does not publish an unfinished final paragraph after truncated stream`() = runBlocking {
        val updates = mutableListOf<StreamingParagraphAccumulator.Update>()
        val service = NovelPurificationService(
            createChatCompletion = { error("non-streaming API must not be called") },
            createChatCompletionStream = {
                flowOf(
                    AiStreamEvent.ContentDelta("完整段。\n未完整"),
                    AiStreamEvent.Finished("length")
                )
            }
        )

        val result = service.purifyStreaming(normalRequest()) { updates += it }

        assertEquals(listOf("完整段。\n未完整"), updates.map { it.content })
        assertEquals(listOf("完整段。"), updates.mapNotNull { it.completedLines })
        assertEquals(
            PurificationResultValidator.FailureReason.LENGTH,
            (result as NovelPurificationService.Result.InvalidResponse).validation.reason
        )
    }

    @Test
    fun `sends one whole chapter request with separated prompts`() = runBlocking {
        val capturedRequests = mutableListOf<DeepSeekChatRequest>()
        val service = NovelPurificationService { request ->
            capturedRequests += request
            completedResponse("净化后的完整正文")
        }

        val result = service.purify(normalRequest())

        assertEquals(1, capturedRequests.size)
        val request = capturedRequests.single()
        assertEquals("deepseek-v4-pro", request.model)
        assertEquals(false, request.stream)
        assertEquals(DeepSeekThinkingType.ENABLED, request.thinking?.type)
        assertEquals(DeepSeekReasoningEffort.LOW, request.reasoningEffort)
        assertNotNull(request.maxTokens)
        assertTrue(request.maxTokens!! >= 4_096)
        assertEquals(
            listOf("purification_rules", "purification_input"),
            request.messages.map { it.name }
        )
        assertEquals("固定净化规则", request.messages[0].content)
        val structuredInput = request.messages[1].content.orEmpty()
        assertTrue(structuredInput.contains("\n保留章节标题\n</ADDITIONAL_INSTRUCTION>"))
        assertTrue(structuredInput.contains("\n第一段。\n\n第二段。\n</NOVEL_TEXT>"))
        assertTrue(structuredInput.indexOf("<OUTPUT_COMMAND>") > structuredInput.indexOf("</NOVEL_TEXT>"))
        assertEquals(
            "净化后的完整正文",
            (result as NovelPurificationService.Result.Success).content
        )
    }

    @Test
    fun `does not call API for blank or oversized text`() = runBlocking {
        var callCount = 0
        val service = NovelPurificationService {
            callCount += 1
            completedResponse("不应返回")
        }

        val emptyResult = service.purify(normalRequest().copy(originalText = " \n"))
        val oversizedResult = service.purify(
            normalRequest().copy(originalText = "章".repeat(190_000))
        )

        assertTrue(emptyResult is NovelPurificationService.Result.EmptyInput)
        assertTrue(oversizedResult is NovelPurificationService.Result.ContextTooLarge)
        assertEquals(0, callCount)
    }

    @Test
    fun `uses MiMo completion budget field without DeepSeek reasoning effort`() = runBlocking {
        lateinit var capturedRequest: DeepSeekChatRequest
        val service = NovelPurificationService { request ->
            capturedRequest = request
            completedResponse("净化后的正文")
        }

        service.purify(
            normalRequest().copy(
                provider = AiProvider.MIMO,
                model = "mimo-v2.5-pro"
            )
        )

        assertNotNull(capturedRequest.maxCompletionTokens)
        assertEquals(null, capturedRequest.maxTokens)
        assertEquals(null, capturedRequest.reasoningEffort)
        assertEquals(DeepSeekThinkingType.ENABLED, capturedRequest.thinking?.type)
    }

    @Test
    fun `uses Qwen thinking and completion fields`() = runBlocking {
        lateinit var capturedRequest: DeepSeekChatRequest
        val service = NovelPurificationService { request ->
            capturedRequest = request
            completedResponse("净化后的正文")
        }

        service.purify(
            normalRequest().copy(
                provider = AiProvider.QWEN,
                model = "qwen3.8-flash"
            )
        )

        assertNotNull(capturedRequest.maxCompletionTokens)
        assertEquals(null, capturedRequest.maxTokens)
        assertEquals(true, capturedRequest.enableThinking)
        assertEquals(AiProvider.QWEN_THINKING_BUDGET, capturedRequest.thinkingBudget)
        assertEquals(null, capturedRequest.thinking)
        assertEquals(null, capturedRequest.reasoningEffort)
    }

    @Test
    fun `returns incomplete response for the user to retry without another call`() = runBlocking {
        var callCount = 0
        val service = NovelPurificationService {
            callCount += 1
            completedResponse("只有部分结果", finishReason = "length")
        }

        val result = service.purify(normalRequest())

        assertEquals(1, callCount)
        assertTrue(result is NovelPurificationService.Result.InvalidResponse)
        val invalid = result as NovelPurificationService.Result.InvalidResponse
        assertEquals(
            PurificationResultValidator.FailureReason.LENGTH,
            invalid.validation.reason
        )
    }

    @Test
    fun `discards result if original text changed while requesting`() = runBlocking {
        val service = NovelPurificationService {
            completedResponse("净化后的正文")
        }

        val result = service.purify(normalRequest()) { "用户已经修改了原文" }

        assertTrue(result is NovelPurificationService.Result.InvalidResponse)
        val invalid = result as NovelPurificationService.Result.InvalidResponse
        assertEquals(
            PurificationResultValidator.FailureReason.ORIGINAL_CHANGED,
            invalid.validation.reason
        )
    }

    @Test
    fun `does not make subjective quality judgments about complete output`() = runBlocking {
        val unusualOutput = "```text\n模型自行添加了代码块\n```"
        val service = NovelPurificationService {
            completedResponse(unusualOutput)
        }

        val result = service.purify(normalRequest())

        assertEquals(
            unusualOutput,
            (result as NovelPurificationService.Result.Success).content
        )
    }

    private fun normalRequest() = NovelPurificationService.Request(
        model = "deepseek-v4-pro",
        systemPrompt = "固定净化规则",
        userInstruction = "保留章节标题",
        originalText = "第一段。\n\n第二段。",
        thinkingEnabled = true
    )

    private fun completedResponse(
        content: String,
        finishReason: String = "stop"
    ) = DeepSeekChatResponse(
        choices = listOf(
            DeepSeekChatChoice(
                index = 0,
                message = DeepSeekChatMessage.assistant(content),
                finishReason = finishReason
            )
        )
    )
}
