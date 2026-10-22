package io.legado.app.ui.ai

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ai.client.DeepSeekConnectionFailure
import io.legado.app.model.ai.client.DeepSeekConnectionResult
import io.legado.app.model.ai.client.AiApiConfigProvider
import io.legado.app.model.ai.client.AiConnectionTester
import io.legado.app.model.ai.client.AiProvider
import io.legado.app.model.ai.client.DeepSeekChatMessage
import io.legado.app.model.ai.client.DeepSeekErrorKind
import io.legado.app.model.ai.client.DeepSeekException
import io.legado.app.model.ai.client.DeepSeekReasoningEffort
import io.legado.app.model.ai.purify.NovelPurificationPromptProvider
import io.legado.app.model.ai.purify.NovelPurificationPromptSmokeTest
import io.legado.app.model.ai.purify.NovelPurificationService
import io.legado.app.model.ai.purify.PurificationResultValidator
import io.legado.app.ui.ai.purify.NovelPurificationTestActivity
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.startActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class AiConfigFragment : PreferenceFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_ai)
        findPreference<EditTextPreference>(PreferKey.aiApiKey)?.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            it.setSelection(it.text.length)
        }
        findPreference<EditTextPreference>(PreferKey.aiMimoApiKey)?.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            it.setSelection(it.text.length)
        }
        findPreference<EditTextPreference>(PreferKey.aiQwenApiKey)?.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            it.setSelection(it.text.length)
        }
        findPreference<EditTextPreference>(PreferKey.aiNovelUserPrompt)
            ?.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                it.setSingleLine(false)
                it.minLines = 5
                it.maxLines = 12
                it.setHorizontallyScrolling(false)
                it.setSelection(it.text.length)
            }
        updateProviderPreferences()
        updateSummary(PreferKey.aiProvider)
        updateSummary(PreferKey.aiApiBaseUrl)
        updateSummary(PreferKey.aiApiKey)
        updateSummary(PreferKey.aiModel)
        updateSummary(PreferKey.aiMimoApiBaseUrl)
        updateSummary(PreferKey.aiMimoApiKey)
        updateSummary(PreferKey.aiMimoModel)
        updateSummary(PreferKey.aiQwenApiBaseUrl)
        updateSummary(PreferKey.aiQwenApiKey)
        updateSummary(PreferKey.aiQwenModel)
        updateSummary(PreferKey.aiReasoningEffort)
        updateSummary(PreferKey.aiNovelSystemPrompt)
        updateSummary(PreferKey.aiNovelUserPrompt)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView.setEdgeEffectColor(primaryColor)
        preferenceManager.sharedPreferences
            ?.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroyView() {
        preferenceManager.sharedPreferences
            ?.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroyView()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == PreferKey.aiProvider || key == PreferKey.aiThinking) {
            updateProviderPreferences()
        }
        key?.let(::updateSummary)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            PreferKey.aiTestConnection -> {
                testConnection(preference)
                return true
            }

            PreferKey.aiTestChat -> {
                testChat(preference)
                return true
            }

            PreferKey.aiNovelSystemPrompt -> {
                showSystemPrompt()
                return true
            }

            PreferKey.aiTestPurificationPrompt -> {
                testPurificationPrompt(preference)
                return true
            }

            PreferKey.aiNovelPurificationTest -> {
                startActivity<NovelPurificationTestActivity>()
                return true
            }
        }
        return super.onPreferenceTreeClick(preference)
    }

    private fun showSystemPrompt() {
        val customPrompt = getPrefString(PreferKey.aiNovelSystemPrompt).orEmpty()
        val prompt = NovelPurificationPromptProvider.resolveSystemPrompt(
            requireContext(),
            customPrompt
        )
        alert(
            title = getString(R.string.ai_system_prompt_view_title),
            message = prompt
        ) {
            okButton()
            neutralButton(R.string.edit) {
                showSystemPromptEditor(prompt)
            }
            if (customPrompt.isNotBlank()) {
                negativeButton(R.string.ai_system_prompt_restore) {
                    removePref(PreferKey.aiNovelSystemPrompt)
                    updateSummary(PreferKey.aiNovelSystemPrompt)
                    alert(
                        title = getString(R.string.ai_system_prompt_restore),
                        message = getString(R.string.ai_system_prompt_restored)
                    ) {
                        okButton()
                    }
                }
            }
        }
    }

    @SuppressLint("InflateParams")
    private fun showSystemPromptEditor(prompt: String) {
        val binding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            editView.setSingleLine(false)
            editView.minLines = 12
            editView.maxLines = 20
            editView.setHorizontallyScrolling(false)
            editView.setText(prompt)
            editView.setSelection(editView.text.length)
        }
        alert(getString(R.string.ai_system_prompt_edit_title)) {
            customView { binding.root }
            okButton {
                val editedPrompt = binding.editView.text?.toString().orEmpty()
                if (editedPrompt.isBlank()) {
                    removePref(PreferKey.aiNovelSystemPrompt)
                } else {
                    putPrefString(PreferKey.aiNovelSystemPrompt, editedPrompt)
                }
                updateSummary(PreferKey.aiNovelSystemPrompt)
            }
            cancelButton()
        }
    }

    private fun testConnection(preference: Preference) {
        val apiConfig = AiApiConfigProvider.current(requireContext())
        val baseUrl = apiConfig.baseUrl
        val apiKey = apiConfig.apiKey
        val model = apiConfig.model
        when {
            baseUrl.isEmpty() -> {
                showTestFailure(getString(R.string.ai_test_missing_url))
                return
            }
            apiKey.isEmpty() -> {
                showTestFailure(getString(R.string.ai_test_missing_key))
                return
            }
        }

        preference.isEnabled = false
        preference.summary = getString(R.string.ai_testing_connection)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = AiConnectionTester.test(apiConfig)
            preference.isEnabled = true
            preference.summary = getString(R.string.ai_test_connection_desc)
            if (!isAdded) return@launch
            when (result) {
                is DeepSeekConnectionResult.Success -> alert(
                    title = getString(R.string.ai_test_success),
                    message = getString(R.string.ai_test_success_message, model)
                ) {
                    okButton()
                }

                is DeepSeekConnectionResult.Failure -> showTestFailure(
                    failureMessage(result, model)
                )
            }
        }
    }

    private fun testChat(preference: Preference) {
        val apiConfig = AiApiConfigProvider.current(requireContext())
        val baseUrl = apiConfig.baseUrl
        val apiKey = apiConfig.apiKey
        val model = apiConfig.model
        when {
            baseUrl.isEmpty() -> {
                showTestFailure(getString(R.string.ai_test_missing_url))
                return
            }
            apiKey.isEmpty() -> {
                showTestFailure(getString(R.string.ai_test_missing_key))
                return
            }
        }

        val thinkingEnabled = getPrefBoolean(PreferKey.aiThinking, true)
        val reasoningEffort = DeepSeekReasoningEffort.fromPreference(
            getPrefString(PreferKey.aiReasoningEffort, "low")
        )
        val request = apiConfig.provider.createChatRequest(
            model = model,
            messages = listOf(
                DeepSeekChatMessage.system("You are a helpful assistant."),
                DeepSeekChatMessage.user(getString(R.string.ai_chat_test_prompt))
            ),
            thinkingEnabled = thinkingEnabled,
            reasoningEffort = reasoningEffort,
            maxCompletionTokens = if (
                apiConfig.provider == AiProvider.QWEN && thinkingEnabled
            ) {
                8_192
            } else {
                512
            }
        )

        preference.isEnabled = false
        preference.summary = getString(R.string.ai_chat_testing)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = apiConfig.createClient().createChatCompletion(request)
                if (!isAdded) return@launch
                val choice = response.choices.orEmpty().first()
                val finishReason = choice.finishReason ?: "unknown"
                if (finishReason != "stop") {
                    showTestFailure(
                        getString(R.string.ai_chat_test_incomplete, finishReason)
                    )
                    return@launch
                }
                val answer = choice.message?.content?.trim().orEmpty()
                if (answer.isEmpty()) {
                    showTestFailure(getString(R.string.ai_chat_test_empty_response))
                    return@launch
                }
                alert(
                    title = getString(R.string.ai_chat_test_succeeded),
                    message = getString(
                        R.string.ai_chat_test_success_message,
                        response.model?.takeIf(String::isNotBlank) ?: model,
                        finishReason,
                        response.usage?.totalTokens?.toString() ?: "-",
                        answer
                    )
                ) {
                    okButton()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: DeepSeekException) {
                if (isAdded) showTestFailure(chatFailureMessage(e))
            } catch (e: Exception) {
                if (isAdded) {
                    showTestFailure(
                        getString(
                            R.string.ai_chat_test_unexpected_error,
                            e.localizedMessage ?: getString(R.string.ai_test_invalid_response)
                        )
                    )
                }
            } finally {
                if (isAdded) {
                    preference.isEnabled = true
                    preference.summary = getString(R.string.ai_chat_test_desc)
                }
            }
        }
    }

    private fun testPurificationPrompt(preference: Preference) {
        val apiConfig = AiApiConfigProvider.current(requireContext())
        val baseUrl = apiConfig.baseUrl
        val apiKey = apiConfig.apiKey
        val model = apiConfig.model
        when {
            baseUrl.isEmpty() -> {
                showTestFailure(getString(R.string.ai_test_missing_url))
                return
            }
            apiKey.isEmpty() -> {
                showTestFailure(getString(R.string.ai_test_missing_key))
                return
            }
        }

        val customSystemPrompt = getPrefString(PreferKey.aiNovelSystemPrompt).orEmpty()
        val userPrompt = getPrefString(PreferKey.aiNovelUserPrompt).orEmpty()
        val systemPrompt = NovelPurificationPromptProvider.resolveSystemPrompt(
            requireContext(),
            customSystemPrompt
        )
        val thinkingEnabled = getPrefBoolean(PreferKey.aiThinking, true)
        val reasoningEffort = DeepSeekReasoningEffort.fromPreference(
            getPrefString(PreferKey.aiReasoningEffort, "low")
        )
        val service = NovelPurificationService(apiConfig.createClient())
        val request = NovelPurificationService.Request(
            model = model,
            systemPrompt = systemPrompt,
            userInstruction = userPrompt,
            originalText = NovelPurificationPromptSmokeTest.SAMPLE_INPUT,
            thinkingEnabled = thinkingEnabled,
            reasoningEffort = reasoningEffort,
            provider = apiConfig.provider
        )

        preference.isEnabled = false
        preference.summary = getString(R.string.ai_purification_testing)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = service.purify(request)
                if (!isAdded) return@launch
                val output = when (result) {
                    is NovelPurificationService.Result.Success -> result.content
                    is NovelPurificationService.Result.EmptyInput -> {
                        showTestFailure(getString(R.string.ai_purification_input_required))
                        return@launch
                    }
                    is NovelPurificationService.Result.ContextTooLarge -> {
                        showTestFailure(getString(R.string.ai_purification_context_too_large))
                        return@launch
                    }
                    is NovelPurificationService.Result.InvalidResponse -> {
                        showTestFailure(purificationValidationFailure(result.validation))
                        return@launch
                    }
                }

                val check = NovelPurificationPromptSmokeTest.evaluate(output)
                val checkMessage = if (check.passed) {
                    getString(R.string.ai_purification_test_check_passed)
                } else {
                    getString(
                        R.string.ai_purification_test_check_failed,
                        check.issues.joinToString("\n") { "• $it" }
                    )
                }
                alert(
                    title = getString(
                        if (check.passed) {
                            R.string.ai_purification_test_passed
                        } else {
                            R.string.ai_purification_test_failed
                        }
                    ),
                    message = getString(
                        R.string.ai_purification_test_report,
                        checkMessage,
                        NovelPurificationPromptSmokeTest.SAMPLE_INPUT,
                        output,
                        NovelPurificationPromptSmokeTest.REFERENCE_OUTPUT
                    )
                ) {
                    okButton()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: DeepSeekException) {
                if (isAdded) showTestFailure(chatFailureMessage(e))
            } catch (e: Exception) {
                if (isAdded) {
                    showTestFailure(
                        getString(
                            R.string.ai_chat_test_unexpected_error,
                            e.localizedMessage ?: getString(R.string.ai_test_invalid_response)
                        )
                    )
                }
            } finally {
                if (isAdded) {
                    preference.isEnabled = true
                    preference.summary = getString(R.string.ai_purification_test_desc)
                }
            }
        }
    }

    private fun purificationValidationFailure(
        result: PurificationResultValidator.Result.Invalid
    ): String {
        return when (result.reason) {
            PurificationResultValidator.FailureReason.ORIGINAL_CHANGED -> getString(
                R.string.ai_purification_original_changed
            )
            PurificationResultValidator.FailureReason.LENGTH -> getString(
                R.string.ai_purification_response_length
            )
            PurificationResultValidator.FailureReason.CONTENT_FILTER -> getString(
                R.string.ai_purification_response_filtered
            )
            PurificationResultValidator.FailureReason.INSUFFICIENT_SYSTEM_RESOURCE -> getString(
                R.string.ai_purification_response_resource_interrupted
            )
            PurificationResultValidator.FailureReason.EMPTY_CONTENT -> getString(
                R.string.ai_chat_test_empty_response
            )
            PurificationResultValidator.FailureReason.INCOMPLETE -> getString(
                R.string.ai_chat_test_incomplete,
                result.finishReason ?: "unknown"
            )
        }
    }

    private fun chatFailureMessage(error: DeepSeekException): String {
        val detail = error.detail ?: getString(R.string.ai_test_invalid_response)
        return when (error.kind) {
            DeepSeekErrorKind.INVALID_URL -> getString(R.string.ai_test_invalid_url)
            DeepSeekErrorKind.HTTPS_REQUIRED -> getString(R.string.ai_test_https_required)
            DeepSeekErrorKind.MISSING_API_KEY -> getString(R.string.ai_test_missing_key)
            DeepSeekErrorKind.INVALID_REQUEST -> getString(
                R.string.ai_chat_test_invalid_request,
                detail
            )
            DeepSeekErrorKind.AUTHENTICATION -> getString(R.string.ai_test_auth_failed)
            DeepSeekErrorKind.BALANCE -> getString(R.string.ai_test_balance_failed)
            DeepSeekErrorKind.RATE_LIMIT -> getString(R.string.ai_test_rate_limited)
            DeepSeekErrorKind.SERVER -> getString(R.string.ai_test_server_error)
            DeepSeekErrorKind.INVALID_RESPONSE -> getString(
                R.string.ai_chat_test_unexpected_error,
                detail
            )
            DeepSeekErrorKind.TIMEOUT -> getString(R.string.ai_chat_test_timeout)
            DeepSeekErrorKind.NETWORK -> getString(R.string.ai_test_network_error, detail)
            DeepSeekErrorKind.HTTP -> getString(
                R.string.ai_test_http_error,
                error.httpCode ?: 0,
                error.detail.orEmpty()
            )
        }
    }

    private fun failureMessage(
        failure: DeepSeekConnectionResult.Failure,
        model: String
    ): String {
        return when (failure.kind) {
            DeepSeekConnectionFailure.INVALID_URL -> getString(R.string.ai_test_invalid_url)
            DeepSeekConnectionFailure.HTTPS_REQUIRED -> getString(R.string.ai_test_https_required)
            DeepSeekConnectionFailure.AUTHENTICATION -> getString(R.string.ai_test_auth_failed)
            DeepSeekConnectionFailure.BALANCE -> getString(R.string.ai_test_balance_failed)
            DeepSeekConnectionFailure.RATE_LIMIT -> getString(R.string.ai_test_rate_limited)
            DeepSeekConnectionFailure.SERVER -> getString(R.string.ai_test_server_error)
            DeepSeekConnectionFailure.MODEL_UNAVAILABLE -> getString(
                R.string.ai_test_model_unavailable,
                model,
                failure.availableModels.joinToString()
            )
            DeepSeekConnectionFailure.INVALID_RESPONSE -> failure.detail
                ?.let { "${getString(R.string.ai_test_invalid_response)}\n$it" }
                ?: getString(R.string.ai_test_invalid_response)
            DeepSeekConnectionFailure.NETWORK -> getString(
                R.string.ai_test_network_error,
                failure.detail ?: getString(R.string.ai_test_invalid_response)
            )
            DeepSeekConnectionFailure.HTTP -> getString(
                R.string.ai_test_http_error,
                failure.httpCode ?: 0,
                failure.detail.orEmpty()
            )
        }
    }

    private fun showTestFailure(message: String) {
        alert(
            title = getString(R.string.ai_test_failed),
            message = message
        ) {
            okButton()
        }
    }

    private fun updateProviderPreferences() {
        val provider = AiProvider.from(
            getPrefString(PreferKey.aiProvider, AiProvider.DEEPSEEK.value)
        )
        val providerPreferences = mapOf(
            AiProvider.DEEPSEEK to listOf(
                PreferKey.aiApiBaseUrl,
                PreferKey.aiApiKey,
                PreferKey.aiModel
            ),
            AiProvider.MIMO to listOf(
                PreferKey.aiMimoApiBaseUrl,
                PreferKey.aiMimoApiKey,
                PreferKey.aiMimoModel
            ),
            AiProvider.QWEN to listOf(
                PreferKey.aiQwenApiBaseUrl,
                PreferKey.aiQwenApiKey,
                PreferKey.aiQwenModel
            )
        )
        providerPreferences.forEach { (preferenceProvider, keys) ->
            keys.forEach { key ->
                findPreference<Preference>(key)?.isVisible = provider == preferenceProvider
            }
        }
        findPreference<Preference>(PreferKey.aiReasoningEffort)?.apply {
            isVisible = provider == AiProvider.DEEPSEEK
            isEnabled = getPrefBoolean(PreferKey.aiThinking, true)
        }
    }

    private fun updateSummary(key: String) {
        val preference = findPreference<Preference>(key) ?: return
        val value = when (key) {
            PreferKey.aiProvider,
            PreferKey.aiApiBaseUrl,
            PreferKey.aiApiKey,
            PreferKey.aiModel,
            PreferKey.aiMimoApiBaseUrl,
            PreferKey.aiMimoApiKey,
            PreferKey.aiMimoModel,
            PreferKey.aiQwenApiBaseUrl,
            PreferKey.aiQwenApiKey,
            PreferKey.aiQwenModel,
            PreferKey.aiReasoningEffort,
            PreferKey.aiNovelSystemPrompt,
            PreferKey.aiNovelUserPrompt -> requireContext().getPrefString(key)
            else -> return
        }
        preference.summary = when {
            key == PreferKey.aiNovelSystemPrompt && value.isNullOrBlank() -> getString(
                R.string.ai_system_prompt_default_desc
            )
            key == PreferKey.aiNovelSystemPrompt -> getString(
                R.string.ai_system_prompt_custom_desc
            )
            key == PreferKey.aiNovelUserPrompt && value.isNullOrBlank() -> getString(
                R.string.ai_user_prompt_desc
            )
            key == PreferKey.aiNovelUserPrompt -> getString(
                R.string.ai_user_prompt_custom_desc,
                value.orEmpty().replace('\n', ' ').take(80)
            )
            key in setOf(
                PreferKey.aiApiKey,
                PreferKey.aiMimoApiKey,
                PreferKey.aiQwenApiKey
            ) &&
                value.isNullOrEmpty() -> getString(R.string.ai_api_key_desc)
            key in setOf(
                PreferKey.aiApiKey,
                PreferKey.aiMimoApiKey,
                PreferKey.aiQwenApiKey
            ) -> "•".repeat(8)
            key in setOf(
                PreferKey.aiApiBaseUrl,
                PreferKey.aiMimoApiBaseUrl,
                PreferKey.aiQwenApiBaseUrl
            ) &&
                value.isNullOrBlank() -> getString(R.string.ai_api_base_url_desc)
            preference is ListPreference -> {
                val index = preference.findIndexOfValue(value)
                if (index >= 0) preference.entries[index] else value
            }
            else -> value
        }
    }
}
