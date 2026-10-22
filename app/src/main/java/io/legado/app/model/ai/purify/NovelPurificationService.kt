package io.legado.app.model.ai.purify

import io.legado.app.model.ai.client.DeepSeekChatRequest
import io.legado.app.model.ai.client.DeepSeekChatResponse
import io.legado.app.model.ai.client.DeepSeekClient
import io.legado.app.model.ai.client.DeepSeekReasoningEffort
import io.legado.app.model.ai.client.DeepSeekUsage
import io.legado.app.model.ai.client.AiProvider
import io.legado.app.model.ai.client.AiStreamEvent
import io.legado.app.model.ai.client.OpenAiCompatibleClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

sealed interface PurificationStreamDiagnostic {
    data object Collecting : PurificationStreamDiagnostic

    data class FirstSseEvent(val kind: String) : PurificationStreamDiagnostic

    data class FirstReasoningDelta(val chars: Int) : PurificationStreamDiagnostic

    data class FirstContentDelta(val chars: Int) : PurificationStreamDiagnostic

    data class Usage(val value: DeepSeekUsage) : PurificationStreamDiagnostic

    data class Finished(val reason: String?) : PurificationStreamDiagnostic

    data class Completed(
        val reasoningDeltas: Int,
        val reasoningChars: Long,
        val contentDeltas: Int,
        val contentChars: Long
    ) : PurificationStreamDiagnostic
}

class NovelPurificationService(
    private val createChatCompletionStream: ((DeepSeekChatRequest) -> Flow<AiStreamEvent>)? = null,
    private val createChatCompletion: suspend (DeepSeekChatRequest) -> DeepSeekChatResponse
) {

    constructor(client: DeepSeekClient) : this(
        client::createChatCompletionStream,
        client::createChatCompletion
    )

    constructor(client: OpenAiCompatibleClient) : this(
        client::createChatCompletionStream,
        client::createChatCompletion
    )

    data class Request(
        val model: String,
        val systemPrompt: String,
        val userInstruction: String,
        val originalText: String,
        val thinkingEnabled: Boolean,
        val reasoningEffort: DeepSeekReasoningEffort = DeepSeekReasoningEffort.LOW,
        val provider: AiProvider = AiProvider.DEEPSEEK
    )

    sealed interface Result {
        data class Success(val content: String) : Result

        data object EmptyInput : Result

        data class ContextTooLarge(
            val contextResult: NovelPurificationContextGuard.Result.Rejected
        ) : Result

        data class InvalidResponse(
            val validation: PurificationResultValidator.Result.Invalid
        ) : Result
    }

    suspend fun purify(
        request: Request,
        currentOriginalText: () -> String = { request.originalText }
    ): Result = withContext(Dispatchers.Default) {
        if (request.originalText.isBlank()) return@withContext Result.EmptyInput

        val contextResult = NovelPurificationContextGuard.check(
            model = request.model,
            systemPrompt = request.systemPrompt,
            userInstruction = request.userInstruction,
            novelText = request.originalText,
            thinkingEnabled = request.thinkingEnabled
        )
        val budget = when (contextResult) {
            is NovelPurificationContextGuard.Result.Allowed -> contextResult.budget
            is NovelPurificationContextGuard.Result.Rejected -> {
                return@withContext Result.ContextTooLarge(contextResult)
            }
        }

        val expectedOriginalHash = PurificationResultValidator.hashOriginal(
            request.originalText
        )
        val response = createChatCompletion(
            request.provider.createChatRequest(
                model = request.model,
                messages = NovelPurificationPrompt.buildMessages(
                    systemPrompt = request.systemPrompt,
                    userInstruction = request.userInstruction,
                    novelText = request.originalText
                ),
                thinkingEnabled = request.thinkingEnabled,
                reasoningEffort = request.reasoningEffort,
                maxCompletionTokens = budget.reservedOutputTokens
            )
        )
        val choice = response.choices.orEmpty().firstOrNull()
        when (val validation = PurificationResultValidator.validate(
            expectedOriginalHash = expectedOriginalHash,
            currentOriginalText = currentOriginalText(),
            responseContent = choice?.message?.content,
            finishReason = choice?.finishReason
        )) {
            is PurificationResultValidator.Result.Valid -> Result.Success(validation.content)
            is PurificationResultValidator.Result.Invalid -> Result.InvalidResponse(validation)
        }
    }

    /**
     * Sends the whole chapter once and publishes every cumulative SSE content update.
     * Partial content is a preview; callers must persist only [Result.Success].
     */
    suspend fun purifyStreaming(
        request: Request,
        currentOriginalText: () -> String = { request.originalText },
        onDiagnostic: suspend (PurificationStreamDiagnostic) -> Unit = {},
        onContentAvailable: suspend (StreamingParagraphAccumulator.Update) -> Unit
    ): Result = withContext(Dispatchers.Default) {
        if (request.originalText.isBlank()) return@withContext Result.EmptyInput

        val contextResult = NovelPurificationContextGuard.check(
            model = request.model,
            systemPrompt = request.systemPrompt,
            userInstruction = request.userInstruction,
            novelText = request.originalText,
            thinkingEnabled = request.thinkingEnabled
        )
        val budget = when (contextResult) {
            is NovelPurificationContextGuard.Result.Allowed -> contextResult.budget
            is NovelPurificationContextGuard.Result.Rejected -> {
                return@withContext Result.ContextTooLarge(contextResult)
            }
        }

        val streamRequest = request.provider.createChatRequest(
            model = request.model,
            messages = NovelPurificationPrompt.buildMessages(
                systemPrompt = request.systemPrompt,
                userInstruction = request.userInstruction,
                novelText = request.originalText
            ),
            thinkingEnabled = request.thinkingEnabled,
            reasoningEffort = request.reasoningEffort,
            maxCompletionTokens = budget.reservedOutputTokens,
            stream = true
        )
        val stream = createChatCompletionStream
        if (stream == null) {
            return@withContext when (val result = purify(request, currentOriginalText)) {
                is Result.Success -> {
                    onContentAvailable(
                        StreamingParagraphAccumulator.Update(
                            content = result.content,
                            completedLines = result.content
                        )
                    )
                    result
                }

                else -> result
            }
        }

        val accumulator = StreamingParagraphAccumulator()
        var finishReason: String? = null
        var firstSseEventSeen = false
        var firstReasoningSeen = false
        var firstContentSeen = false
        var usageSeen = false
        var reasoningDeltas = 0
        var reasoningChars = 0L
        var contentDeltas = 0
        var contentChars = 0L
        onDiagnostic(PurificationStreamDiagnostic.Collecting)
        stream(streamRequest).collect { event ->
            if (!firstSseEventSeen) {
                firstSseEventSeen = true
                val kind = when (event) {
                    is AiStreamEvent.ReasoningDelta -> "reasoning"
                    is AiStreamEvent.ContentDelta -> "content"
                    is AiStreamEvent.UsageUpdate -> "usage"
                    is AiStreamEvent.Finished -> "finished"
                }
                onDiagnostic(PurificationStreamDiagnostic.FirstSseEvent(kind))
            }
            when (event) {
                is AiStreamEvent.ContentDelta -> {
                    contentDeltas++
                    contentChars += event.text.length
                    if (!firstContentSeen) {
                        firstContentSeen = true
                        onDiagnostic(
                            PurificationStreamDiagnostic.FirstContentDelta(event.text.length)
                        )
                    }
                    accumulator.append(event.text)?.let { onContentAvailable(it) }
                }

                is AiStreamEvent.ReasoningDelta -> {
                    reasoningDeltas++
                    reasoningChars += event.text.length
                    if (!firstReasoningSeen) {
                        firstReasoningSeen = true
                        onDiagnostic(
                            PurificationStreamDiagnostic.FirstReasoningDelta(event.text.length)
                        )
                    }
                }

                is AiStreamEvent.UsageUpdate -> {
                    if (!usageSeen) {
                        usageSeen = true
                        onDiagnostic(PurificationStreamDiagnostic.Usage(event.usage))
                    }
                }

                is AiStreamEvent.Finished -> {
                    finishReason = event.reason
                    onDiagnostic(PurificationStreamDiagnostic.Finished(event.reason))
                }
            }
        }
        onDiagnostic(
            PurificationStreamDiagnostic.Completed(
                reasoningDeltas = reasoningDeltas,
                reasoningChars = reasoningChars,
                contentDeltas = contentDeltas,
                contentChars = contentChars
            )
        )
        val expectedOriginalHash = PurificationResultValidator.hashOriginal(request.originalText)
        when (val validation = PurificationResultValidator.validate(
            expectedOriginalHash = expectedOriginalHash,
            currentOriginalText = currentOriginalText(),
            responseContent = accumulator.complete(),
            finishReason = finishReason
        )) {
            is PurificationResultValidator.Result.Valid -> Result.Success(validation.content)
            is PurificationResultValidator.Result.Invalid -> Result.InvalidResponse(validation)
        }
    }
}
