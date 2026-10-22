package io.legado.app.data.entities

enum class BookProgressSyncDecision {
    KEEP_LOCAL,
    USE_REMOTE,
    ALREADY_SYNCED
}

/**
 * Resolves progress conflicts by the time at which a device last saved its reading position.
 * Chapter index and offset describe the position only; they do not indicate which device is newer.
 */
object BookProgressSync {

    fun decide(
        local: BookProgress,
        remote: BookProgress?
    ): BookProgressSyncDecision {
        remote ?: return BookProgressSyncDecision.KEEP_LOCAL
        if (local.hasSamePosition(remote)) {
            return BookProgressSyncDecision.ALREADY_SYNCED
        }
        return if (remote.durChapterTime >= local.durChapterTime) {
            BookProgressSyncDecision.USE_REMOTE
        } else {
            BookProgressSyncDecision.KEEP_LOCAL
        }
    }

    private fun BookProgress.hasSamePosition(other: BookProgress): Boolean {
        return durChapterIndex == other.durChapterIndex &&
                durChapterPos == other.durChapterPos
    }
}
