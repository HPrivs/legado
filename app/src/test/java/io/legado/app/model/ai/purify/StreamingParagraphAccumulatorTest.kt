package io.legado.app.model.ai.purify

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingParagraphAccumulatorTest {

    @Test
    fun `publishes every SSE update and identifies completed line boundaries`() {
        val accumulator = StreamingParagraphAccumulator()

        assertEquals(
            StreamingParagraphAccumulator.Update("第一", null),
            accumulator.append("第一")
        )
        assertEquals(
            StreamingParagraphAccumulator.Update("第一段。\n第", "第一段。"),
            accumulator.append("段。\n第")
        )
        assertEquals(
            StreamingParagraphAccumulator.Update("第一段。\n第二段", null),
            accumulator.append("二段")
        )
        assertEquals(
            StreamingParagraphAccumulator.Update(
                "第一段。\n第二段。\n",
                "第一段。\n第二段。"
            ),
            accumulator.append("。\n")
        )
        assertEquals("第一段。\n第二段。\n", accumulator.complete())
    }

    @Test
    fun `keeps the unfinished final paragraph private until final validation`() {
        val accumulator = StreamingParagraphAccumulator()

        assertEquals(
            StreamingParagraphAccumulator.Update("唯一一段，没有末尾换行。", null),
            accumulator.append("唯一一段，没有末尾换行。")
        )
        assertEquals("唯一一段，没有末尾换行。", accumulator.complete())
    }
}
