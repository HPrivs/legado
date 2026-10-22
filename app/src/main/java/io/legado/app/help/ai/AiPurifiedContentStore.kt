package io.legado.app.help.ai

import android.content.Context
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.model.ai.purify.NovelPurificationRuntimeConfig
import io.legado.app.model.ai.purify.PurificationResultValidator
import java.io.File

class AiPurifiedContentStore(
    private val rootDir: File
) {

    class CacheKey internal constructor(
        val value: String,
        internal val legacyBookDirectory: String,
        internal val legacyChapterDirectory: String,
        internal val legacyValue: String
    )

    fun keyFor(
        bookIdentity: String,
        chapterIdentity: String,
        originalText: String,
        configSignature: String
    ): CacheKey {
        val originalHash = hash(originalText)
        val value = hash(
            listOf(
                CACHE_FORMAT_VERSION,
                originalHash,
                configSignature
            ).joinToString("\n")
        )
        val legacyValue = hash(
            listOf(
                bookIdentity,
                chapterIdentity,
                originalHash,
                configSignature
            ).joinToString("\n")
        )
        return CacheKey(
            value = value,
            legacyBookDirectory = hash(bookIdentity),
            legacyChapterDirectory = hash(chapterIdentity),
            legacyValue = legacyValue
        )
    }

    fun keyFor(
        book: Book,
        chapter: BookChapter,
        originalText: String,
        config: NovelPurificationRuntimeConfig
    ): CacheKey {
        return keyFor(
            bookIdentity = book.bookUrl,
            chapterIdentity = "${chapter.index}\n${chapter.url}",
            originalText = originalText,
            configSignature = config.cacheSignature
        )
    }

    @Synchronized
    fun read(key: CacheKey): String? {
        readFile(fileFor(key))?.let { return it }
        val legacyFile = legacyFileFor(key)
        val legacyContent = readFile(legacyFile) ?: return null
        // Migrate a cache entry that is still reachable through the original
        // book/chapter identity. The v2 key remains stable if a later TOC refresh
        // changes the book URL, chapter URL, or chapter index.
        runCatching { write(key, legacyContent) }
        return legacyContent
    }

    @Synchronized
    fun write(key: CacheKey, content: String) {
        require(content.isNotBlank()) { "AI purified content must not be blank" }
        val target = fileFor(key)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(content, Charsets.UTF_8)
        if (target.exists() && !target.delete()) {
            temporary.delete()
            error("Unable to replace AI purification cache")
        }
        if (!temporary.renameTo(target)) {
            target.writeText(content, Charsets.UTF_8)
            temporary.delete()
        }
        if (!target.isFile) {
            error("Unable to write AI purification cache")
        }
        deleteFileAndTemporary(legacyFileFor(key))
    }

    @Synchronized
    fun delete(key: CacheKey): Boolean {
        val currentDeleted = deleteFileAndTemporary(fileFor(key))
        val legacyDeleted = deleteFileAndTemporary(legacyFileFor(key))
        return currentDeleted && legacyDeleted
    }

    private fun fileFor(key: CacheKey): File {
        return File(
            File(File(rootDir, CACHE_FORMAT_VERSION), key.value.take(2)),
            "${key.value}.txt"
        )
    }

    private fun legacyFileFor(key: CacheKey): File {
        return File(
            File(File(rootDir, key.legacyBookDirectory), key.legacyChapterDirectory),
            "${key.legacyValue}.txt"
        )
    }

    private fun readFile(file: File): String? {
        if (!file.isFile) return null
        return runCatching { file.readText(Charsets.UTF_8) }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
    }

    private fun deleteFileAndTemporary(file: File): Boolean {
        File(file.parentFile, "${file.name}.tmp").delete()
        return !file.exists() || file.delete()
    }

    private fun hash(value: String): String {
        return PurificationResultValidator.hashOriginal(value)
    }

    companion object {
        private const val CACHE_FORMAT_VERSION = "v2"

        @Volatile
        private var instance: AiPurifiedContentStore? = null

        fun get(context: Context): AiPurifiedContentStore {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: AiPurifiedContentStore(
                    File(context.noBackupFilesDir, "ai_purification_cache")
                ).also { instance = it }
            }
        }
    }
}
