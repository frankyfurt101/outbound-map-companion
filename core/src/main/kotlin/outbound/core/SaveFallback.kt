package outbound.core

/** Pure candidate-ordering and fallback selection. [decode] returns null when a file is unreadable. */
object SaveFallback {
    data class Pick<T>(val candidate: Candidate, val value: T, val skippedUnreadable: Boolean)

    /** Save files only, newest lastModified first (missing/0 last), ties broken by name descending. */
    fun order(all: List<Candidate>): List<Candidate> =
        all.filter { NewestSelector.isSaveName(it.name) }
            .sortedWith(
                compareByDescending<Candidate> { it.lastModified?.takeIf { m -> m > 0 } ?: Long.MIN_VALUE }
                    .thenByDescending { it.name },
            )

    /** First decodable candidate in [order]; [Pick.skippedUnreadable] is true when a newer one failed. */
    fun <T : Any> newestReadable(all: List<Candidate>, decode: (Candidate) -> T?): Pick<T>? {
        var skipped = false
        for (c in order(all)) {
            val v = decode(c)
            if (v != null) return Pick(c, v, skipped)
            skipped = true
        }
        return null
    }

    /** Highest play time among decodable candidates (ties keep the newer-ordered one); null if all fail. */
    fun <T : Any> highestTimePlayed(all: List<Candidate>, timePlayed: (T) -> Long, decode: (Candidate) -> T?): Pick<T>? {
        var skipped = false
        var best: Pick<T>? = null
        for (c in order(all)) {
            val v = decode(c)
            if (v == null) { skipped = true; continue }
            val b = best
            if (b == null || timePlayed(v) > timePlayed(b.value)) best = Pick(c, v, false)
        }
        return best?.copy(skippedUnreadable = skipped)
    }
}
