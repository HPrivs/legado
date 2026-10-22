package io.legado.app.ui.ai.purify

import android.os.Bundle
import androidx.activity.viewModels
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.tabs.TabLayout
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivityNovelPurificationTestBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.ai.client.DeepSeekErrorKind
import io.legado.app.model.ai.client.DeepSeekException
import io.legado.app.model.ai.purify.PurificationResultValidator
import io.legado.app.utils.gone
import io.legado.app.utils.sendToClip
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible

class NovelPurificationTestActivity :
    VMBaseActivity<ActivityNovelPurificationTestBinding, NovelPurificationTestViewModel>() {

    override val binding by viewBinding(ActivityNovelPurificationTestBinding::inflate)
    override val viewModel by viewModels<NovelPurificationTestViewModel>()

    private var renderingText = false
    private var resultAvailable = false
    private var failureDialogShowing = false

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initTabs()
        initActions()
        viewModel.state.observe(this, ::render)
    }

    private fun initTabs() {
        binding.tabLayout.addOnTabSelectedListener(object :
            TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (tab.position == RESULT_TAB && !resultAvailable) {
                    binding.tabLayout.selectTab(binding.tabLayout.getTabAt(ORIGINAL_TAB))
                    return
                }
                showTab(tab.position)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        setResultAvailable(false)
        showTab(ORIGINAL_TAB)
    }

    private fun initActions() {
        binding.inputText.doAfterTextChanged {
            if (!renderingText) {
                viewModel.updateOriginalText(it?.toString().orEmpty())
            }
        }
        binding.btnPrimary.setOnClickListener {
            if (viewModel.state.value is NovelPurificationTestViewModel.State.Running) {
                viewModel.cancel()
            } else {
                viewModel.purify()
            }
        }
        binding.btnCopy.setOnClickListener {
            val state = viewModel.state.value
            if (state is NovelPurificationTestViewModel.State.Success) {
                sendToClip(state.resultText)
            }
        }
    }

    private fun render(state: NovelPurificationTestViewModel.State) {
        renderingText = true
        if (binding.inputText.text?.toString() != state.originalText) {
            binding.inputText.setText(state.originalText)
            binding.inputText.setSelection(binding.inputText.text?.length ?: 0)
        }
        renderingText = false

        val running = state is NovelPurificationTestViewModel.State.Running
        val runningResult = (state as? NovelPurificationTestViewModel.State.Running)
            ?.resultText
            .orEmpty()
        val success = state as? NovelPurificationTestViewModel.State.Success
        binding.inputText.isEnabled = !running
        binding.progressGroup.visible(running && runningResult.isBlank())
        binding.btnCopy.visible(success != null)
        binding.btnPrimary.setText(
            when {
                running -> R.string.cancel
                success != null -> R.string.ai_purification_retry
                else -> R.string.ai_purification_start
            }
        )

        setResultAvailable(success != null || runningResult.isNotBlank())
        if (success != null) {
            binding.resultText.text = success.resultText
            binding.tabLayout.selectTab(binding.tabLayout.getTabAt(RESULT_TAB))
        } else if (runningResult.isNotBlank()) {
            binding.resultText.text = runningResult
            binding.tabLayout.selectTab(binding.tabLayout.getTabAt(RESULT_TAB))
        } else {
            binding.resultText.text = ""
            binding.tabLayout.selectTab(binding.tabLayout.getTabAt(ORIGINAL_TAB))
        }

        if (state is NovelPurificationTestViewModel.State.Failed) {
            showFailure(state.error)
        }
    }

    private fun setResultAvailable(available: Boolean) {
        resultAvailable = available
        binding.tabLayout.getTabAt(RESULT_TAB)?.view?.apply {
            isEnabled = available
            alpha = if (available) 1f else 0.45f
        }
    }

    private fun showTab(position: Int) {
        if (position == RESULT_TAB && resultAvailable) {
            binding.inputContainer.gone()
            binding.resultContainer.visible()
        } else {
            binding.resultContainer.gone()
            binding.inputContainer.visible()
        }
    }

    private fun showFailure(error: NovelPurificationTestViewModel.UiError) {
        if (failureDialogShowing) return
        failureDialogShowing = true
        alert(
            title = getString(R.string.ai_purification_failed),
            message = errorMessage(error)
        ) {
            okButton()
            onDismiss {
                failureDialogShowing = false
                viewModel.consumeFailure()
            }
        }
    }

    private fun errorMessage(error: NovelPurificationTestViewModel.UiError): String {
        return when (error) {
            NovelPurificationTestViewModel.UiError.MissingUrl -> getString(
                R.string.ai_test_missing_url
            )
            NovelPurificationTestViewModel.UiError.MissingApiKey -> getString(
                R.string.ai_test_missing_key
            )
            NovelPurificationTestViewModel.UiError.EmptyInput -> getString(
                R.string.ai_purification_input_required
            )
            NovelPurificationTestViewModel.UiError.ContextTooLarge -> getString(
                R.string.ai_purification_context_too_large
            )
            is NovelPurificationTestViewModel.UiError.InvalidResponse -> {
                validationFailure(error.result.validation)
            }
            is NovelPurificationTestViewModel.UiError.Api -> apiFailure(error.exception)
            is NovelPurificationTestViewModel.UiError.Unexpected -> getString(
                R.string.ai_purification_unexpected_error,
                error.detail.ifBlank { getString(R.string.ai_test_invalid_response) }
            )
        }
    }

    private fun validationFailure(
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

    private fun apiFailure(error: DeepSeekException): String {
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
                R.string.ai_purification_unexpected_error,
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

    companion object {
        private const val ORIGINAL_TAB = 0
        private const val RESULT_TAB = 1
    }
}
