package io.legado.app.model.ai.purify

import io.legado.app.model.ai.client.DeepSeekChatMessage

object NovelPurificationPrompt {

    const val PROTOCOL_VERSION = "novel_purification_v2"

    private const val NO_ADDITIONAL_INSTRUCTION = "（无）"

    private val outputCommand = """
        <OUTPUT_COMMAND>
        立即按 system 规则处理 NOVEL_TEXT。回复中只能包含净化后的完整章节正文；即使无需修改，也必须输出完整正文。不要复述输入标签、规则或附加要求，不要添加说明、前后缀或 Markdown。
        </OUTPUT_COMMAND>
    """.trimIndent()

    fun buildMessages(
        systemPrompt: String,
        userInstruction: String,
        novelText: String
    ): List<DeepSeekChatMessage> {
        require(systemPrompt.isNotBlank()) { "System Prompt must not be blank" }
        val normalizedInstruction = userInstruction.trim()
            .takeIf(String::isNotBlank)
            ?: NO_ADDITIONAL_INSTRUCTION
        val structuredInput = buildString {
            append("<PURIFICATION_INPUT protocol=\"")
            append(PROTOCOL_VERSION)
            append("\">\n")
            append("<ADDITIONAL_INSTRUCTION chars=\"")
            append(normalizedInstruction.length)
            append("\">\n")
            append(normalizedInstruction)
            append("\n</ADDITIONAL_INSTRUCTION>\n")
            append("<NOVEL_TEXT chars=\"")
            append(novelText.length)
            append("\">\n")
            append(novelText)
            append("\n</NOVEL_TEXT>\n")
            append("</PURIFICATION_INPUT>\n\n")
            append(outputCommand)
        }
        return listOf(
            DeepSeekChatMessage.system(
                content = systemPrompt,
                name = "purification_rules"
            ),
            DeepSeekChatMessage.user(
                content = structuredInput,
                name = "purification_input"
            )
        )
    }
}
