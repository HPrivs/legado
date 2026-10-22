package io.legado.app.model.ai.purify

/** Accumulates SSE content deltas while also tracking text safe for reader re-layout. */
class StreamingParagraphAccumulator {

    data class Update(
        /** Exact cumulative content, including the line currently being generated. */
        val content: String,
        /** Cumulative content through the latest closed line, when that boundary advances. */
        val completedLines: String?
    )

    private val content = StringBuilder()
    private var publishedBoundary = 0

    fun append(delta: String): Update? {
        if (delta.isEmpty()) return null
        content.append(delta)
        val boundary = content.lastIndexOf("\n") + 1
        val completedLines = if (boundary > publishedBoundary) {
            publishedBoundary = boundary
            content.substring(0, boundary).trimEnd('\r', '\n').takeIf { it.isNotBlank() }
        } else {
            null
        }
        return Update(
            content = content.toString(),
            completedLines = completedLines
        )
    }

    fun complete(): String {
        return content.toString()
    }

}
