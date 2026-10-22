package io.legado.app.help.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AiPurifiedContentStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `stores complete result without changing the original text`() {
        val store = AiPurifiedContentStore(temporaryFolder.newFolder("ai-cache"))
        val original = "原始章节正文"
        val key = store.keyFor(
            bookIdentity = "book-1",
            chapterIdentity = "chapter-1",
            originalText = original,
            configSignature = "config-1"
        )

        store.write(key, "　　净化后的章节正文")

        assertEquals("原始章节正文", original)
        assertEquals("　　净化后的章节正文", store.read(key))
    }

    @Test
    fun `different original or configuration cannot reuse stale result`() {
        val store = AiPurifiedContentStore(temporaryFolder.newFolder("ai-cache"))
        val originalKey = store.keyFor("book", "chapter", "原文", "config-a")
        store.write(originalKey, "净化结果")

        val updatedOriginalKey = store.keyFor("book", "chapter", "新原文", "config-a")
        val updatedConfigKey = store.keyFor("book", "chapter", "原文", "config-b")

        assertNull(store.read(updatedOriginalKey))
        assertNull(store.read(updatedConfigKey))
    }

    @Test
    fun `book and chapter identity changes keep the same content addressed entry`() {
        val store = AiPurifiedContentStore(temporaryFolder.newFolder("ai-cache"))
        val first = store.keyFor("book-a", "chapter-1", "相同原文", "config")
        val second = store.keyFor("book-b", "chapter-1", "相同原文", "config")
        val third = store.keyFor("book-a", "chapter-2", "相同原文", "config")

        store.write(first, "净化结果")

        assertEquals(first.value, second.value)
        assertEquals(first.value, third.value)
        assertEquals("净化结果", store.read(second))
        assertEquals("净化结果", store.read(third))
    }

    @Test
    fun `reads and migrates a legacy identity based cache entry`() {
        val root = temporaryFolder.newFolder("ai-cache")
        val store = AiPurifiedContentStore(root)
        val legacyKey = store.keyFor("old-book", "old-chapter", "原文", "config")
        val legacyFile = File(
            File(File(root, legacyKey.legacyBookDirectory), legacyKey.legacyChapterDirectory),
            "${legacyKey.legacyValue}.txt"
        )
        legacyFile.parentFile!!.mkdirs()
        legacyFile.writeText("旧格式净化结果", Charsets.UTF_8)

        assertEquals("旧格式净化结果", store.read(legacyKey))
        assertTrue(!legacyFile.exists())

        val refreshedIdentity = store.keyFor(
            "new-book",
            "new-chapter",
            "原文",
            "config"
        )
        assertEquals("旧格式净化结果", store.read(refreshedIdentity))
    }

    @Test
    fun `deleting one result makes the chapter use original content again`() {
        val store = AiPurifiedContentStore(temporaryFolder.newFolder("ai-cache"))
        val key = store.keyFor("book", "chapter", "原文", "config")
        store.write(key, "净化结果")

        store.delete(key)

        assertNull(store.read(key))
    }
}
