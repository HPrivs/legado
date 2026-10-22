package io.legado.app.model.ai.purify

import android.content.Context
import androidx.annotation.VisibleForTesting
import io.legado.app.R
import java.io.InputStream

object NovelPurificationPromptProvider {

    @Volatile
    private var cachedDefaultSystemPrompt: String? = null

    fun defaultSystemPrompt(context: Context): String {
        cachedDefaultSystemPrompt?.let { return it }
        return synchronized(this) {
            cachedDefaultSystemPrompt ?: decode(
                context.resources.openRawResource(
                    R.raw.novel_purification_system_prompt
                )
            ).also { cachedDefaultSystemPrompt = it }
        }
    }

    fun resolveSystemPrompt(context: Context, customPrompt: String): String {
        return customPrompt.takeIf(String::isNotBlank) ?: defaultSystemPrompt(context)
    }

    @VisibleForTesting
    internal fun decode(input: InputStream): String {
        val prompt = input.bufferedReader(Charsets.UTF_8).use { it.readText() }
            .removePrefix("\uFEFF")
            .trimEnd('\r', '\n')
        require(prompt.isNotBlank()) { "Built-in System Prompt must not be blank" }
        return prompt
    }
}
