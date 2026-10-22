package io.legado.app.ui.ai.purify

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.PreferKey
import io.legado.app.model.ai.client.AiApiConfigProvider
import io.legado.app.model.ai.client.DeepSeekException
import io.legado.app.model.ai.client.DeepSeekReasoningEffort
import io.legado.app.model.ai.purify.NovelPurificationPromptProvider
import io.legado.app.model.ai.purify.NovelPurificationService
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NovelPurificationTestViewModel(application: Application) : BaseViewModel(application) {

    sealed interface State {
        val originalText: String

        data class Editing(override val originalText: String) : State

        data class Running(
            override val originalText: String,
            val resultText: String = ""
        ) : State

        data class Success(
            override val originalText: String,
            val resultText: String
        ) : State

        data class Failed(
            override val originalText: String,
            val error: UiError
        ) : State
    }

    sealed interface UiError {
        data object MissingUrl : UiError
        data object MissingApiKey : UiError
        data object EmptyInput : UiError
        data object ContextTooLarge : UiError
        data class InvalidResponse(
            val result: NovelPurificationService.Result.InvalidResponse
        ) : UiError
        data class Api(val exception: DeepSeekException) : UiError
        data class Unexpected(val detail: String) : UiError
    }

    private val _state = MutableLiveData<State>(State.Editing(""))
    val state: LiveData<State> = _state

    private var purificationJob: Job? = null

    fun updateOriginalText(text: String) {
        val current = _state.value ?: State.Editing("")
        if (current is State.Running || current.originalText == text) return
        _state.value = State.Editing(text)
    }

    fun purify() {
        val current = _state.value ?: State.Editing("")
        if (current is State.Running) return
        val originalText = current.originalText
        if (originalText.isBlank()) {
            _state.value = State.Failed(originalText, UiError.EmptyInput)
            return
        }

        val apiConfig = AiApiConfigProvider.current(context)
        val baseUrl = apiConfig.baseUrl
        val apiKey = apiConfig.apiKey
        val model = apiConfig.model
        when {
            baseUrl.isEmpty() -> {
                _state.value = State.Failed(originalText, UiError.MissingUrl)
                return
            }
            apiKey.isEmpty() -> {
                _state.value = State.Failed(originalText, UiError.MissingApiKey)
                return
            }
        }

        val systemPrompt = NovelPurificationPromptProvider.resolveSystemPrompt(
            context,
            context.getPrefString(PreferKey.aiNovelSystemPrompt).orEmpty()
        )
        val request = NovelPurificationService.Request(
            model = model,
            systemPrompt = systemPrompt,
            userInstruction = context.getPrefString(PreferKey.aiNovelUserPrompt).orEmpty(),
            originalText = originalText,
            thinkingEnabled = context.getPrefBoolean(PreferKey.aiThinking, true),
            reasoningEffort = DeepSeekReasoningEffort.fromPreference(
                context.getPrefString(PreferKey.aiReasoningEffort, "low")
            ),
            provider = apiConfig.provider
        )
        val service = NovelPurificationService(apiConfig.createClient())

        _state.value = State.Running(originalText)
        purificationJob = viewModelScope.launch {
            try {
                when (val result = service.purifyStreaming(
                    request = request,
                    currentOriginalText = {
                        _state.value?.originalText ?: originalText
                    },
                    onContentAvailable = { update ->
                        withContext(Dispatchers.Main.immediate) {
                            if (_state.value is State.Running) {
                                _state.value = State.Running(originalText, update.content)
                            }
                        }
                    }
                )) {
                    is NovelPurificationService.Result.Success -> {
                        _state.value = State.Success(originalText, result.content)
                    }
                    is NovelPurificationService.Result.EmptyInput -> {
                        _state.value = State.Failed(originalText, UiError.EmptyInput)
                    }
                    is NovelPurificationService.Result.ContextTooLarge -> {
                        _state.value = State.Failed(originalText, UiError.ContextTooLarge)
                    }
                    is NovelPurificationService.Result.InvalidResponse -> {
                        _state.value = State.Failed(
                            originalText,
                            UiError.InvalidResponse(result)
                        )
                    }
                }
            } catch (error: CancellationException) {
                _state.value = State.Editing(originalText)
                throw error
            } catch (error: DeepSeekException) {
                _state.value = State.Failed(originalText, UiError.Api(error))
            } catch (error: Exception) {
                _state.value = State.Failed(
                    originalText,
                    UiError.Unexpected(error.localizedMessage.orEmpty())
                )
            } finally {
                purificationJob = null
            }
        }
    }

    fun cancel() {
        purificationJob?.cancel()
    }

    fun consumeFailure() {
        val current = _state.value
        if (current is State.Failed) {
            _state.value = State.Editing(current.originalText)
        }
    }
}
