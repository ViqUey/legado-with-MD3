package io.legado.app.model.cache

import java.util.concurrent.ConcurrentHashMap

/** Recovery-only observations. Model removal, pause and FIFO never act on these counters. */
internal object CacheRecoveryWork {
    private val counts = ConcurrentHashMap<Pair<String, CacheDownloadActivity>, Int>()

    fun begin(bookUrl: String, activity: CacheDownloadActivity) {
        counts.merge(bookUrl to activity, 1, Int::plus)
    }

    fun end(bookUrl: String, activity: CacheDownloadActivity) {
        counts.computeIfPresent(bookUrl to activity) { _, count -> (count - 1).takeIf { it > 0 } }
    }

    fun activity(bookUrl: String): CacheDownloadActivity? = when {
        counts.containsKey(bookUrl to CacheDownloadActivity.Running) -> CacheDownloadActivity.Running
        counts.containsKey(bookUrl to CacheDownloadActivity.Retrying) -> CacheDownloadActivity.Retrying
        else -> null
    }

    inline fun <T> observe(bookUrl: String, activity: CacheDownloadActivity, block: () -> T): T {
        begin(bookUrl, activity)
        try {
            return block()
        } finally {
            end(bookUrl, activity)
        }
    }
}
