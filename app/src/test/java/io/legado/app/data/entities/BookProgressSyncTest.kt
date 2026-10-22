package io.legado.app.data.entities

import org.junit.Assert.assertEquals
import org.junit.Test

class BookProgressSyncTest {

    @Test
    fun `newer local device wins even when it is reading an earlier chapter`() {
        val local = progress(chapter = 3, position = 20, time = 200)
        val remote = progress(chapter = 12, position = 80, time = 100)

        assertEquals(
            BookProgressSyncDecision.KEEP_LOCAL,
            BookProgressSync.decide(local, remote)
        )
    }

    @Test
    fun `newer remote device wins even when it is reading an earlier chapter`() {
        val local = progress(chapter = 12, position = 80, time = 100)
        val remote = progress(chapter = 3, position = 20, time = 200)

        assertEquals(
            BookProgressSyncDecision.USE_REMOTE,
            BookProgressSync.decide(local, remote)
        )
    }

    @Test
    fun `same position does not trigger a location conflict`() {
        val local = progress(chapter = 5, position = 60, time = 100)
        val remote = progress(chapter = 5, position = 60, time = 200)

        assertEquals(
            BookProgressSyncDecision.ALREADY_SYNCED,
            BookProgressSync.decide(local, remote)
        )
    }

    @Test
    fun `missing remote progress keeps local progress`() {
        assertEquals(
            BookProgressSyncDecision.KEEP_LOCAL,
            BookProgressSync.decide(progress(chapter = 5, position = 60, time = 100), null)
        )
    }

    @Test
    fun `remote wins an exact timestamp tie to avoid overwriting observed cloud state`() {
        val local = progress(chapter = 5, position = 60, time = 100)
        val remote = progress(chapter = 2, position = 10, time = 100)

        assertEquals(
            BookProgressSyncDecision.USE_REMOTE,
            BookProgressSync.decide(local, remote)
        )
    }

    private fun progress(chapter: Int, position: Int, time: Long) = BookProgress(
        name = "book",
        author = "author",
        durChapterIndex = chapter,
        durChapterPos = position,
        durChapterTime = time,
        durChapterTitle = "chapter-$chapter"
    )
}
